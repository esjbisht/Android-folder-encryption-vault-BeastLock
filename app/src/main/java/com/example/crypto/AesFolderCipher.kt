package com.example.crypto

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class EncryptedFileMetadata(
  val originalName: String,
  val mimeType: String,
  val originalSize: Long,
  val encryptedAt: Long,
)

/**
 * Real streaming AES-256-GCM chunked file cipher.
 *
 * File binary layout (VLK1):
 * - Magic: 4 bytes ("VLK1")
 * - Version: 1 byte (0x01)
 * - Base Nonce: 12 bytes (random per file)
 * - Chunk Size: 4 bytes (default 64 KiB = 65536)
 * - Metadata Cipher Length: 4 bytes (big-endian int)
 * - Metadata Ciphertext: N bytes (AES-256-GCM encrypted JSON using chunkNonce(0xFFFFFFFF))
 * - Data Chunks: repeated [4-byte ciphertextLen][ciphertext + 16-byte GCM tag],
 *   with chunkNonce(index) = baseNonce XOR bigEndian64(index), AAD = "VLK1-DATA-" + index
 * - End Sentinel: 4 bytes (0x00000000) followed by 16-byte EOF tag check or terminal length 0.
 *   To prevent truncation attacks, the final sentinel is an authenticated 0-byte final chunk
 *   tagged with AAD = "VLK1-EOF-" + totalChunks!
 */
object AesFolderCipher {
  private val MAGIC = byteArrayOf('V'.code.toByte(), 'L'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())
  private const val FORMAT_VERSION: Byte = 1
  private const val GCM_TAG_BITS = 128
  private const val NONCE_BYTES = 12
  private const val DEFAULT_CHUNK_SIZE = 64 * 1024 // 64 KiB
  private const val PBKDF2_ITERATIONS = 120_000
  private const val AES_KEY_BITS = 256

  private val secureRandom = SecureRandom()
  private val json = Json { ignoreUnknownKeys = true }

  const val ENCRYPTED_EXTENSION = ".vlock"

  fun generateAes256Key(): SecretKey {
    val keyGenerator = KeyGenerator.getInstance("AES")
    keyGenerator.init(AES_KEY_BITS, secureRandom)
    return keyGenerator.generateKey()
  }

  fun secretKeyFromBytes(raw: ByteArray): SecretKey {
    require(raw.size == 32) { "AES-256 key must be 32 bytes, got ${raw.size}" }
    return SecretKeySpec(raw, "AES")
  }

  fun generateSalt(bytes: Int = 16): ByteArray {
    val salt = ByteArray(bytes)
    secureRandom.nextBytes(salt)
    return salt
  }

  fun derivePinKey(pin: String, salt: ByteArray): SecretKey {
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS)
    val derived = factory.generateSecret(spec).encoded
    spec.clearPassword()
    return SecretKeySpec(derived, "AES")
  }

  fun computePinVerifier(pin: String, salt: ByteArray): String {
    val derived = derivePinKey(pin, salt).encoded
    val digest = MessageDigest.getInstance("SHA-256").digest(derived + "VERIFIER".toByteArray())
    derived.fill(0)
    return Base64.encodeToString(digest, Base64.NO_WRAP)
  }

  fun wrapDekWithKey(dek: SecretKey, wrappingKey: SecretKey): String {
    val iv = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
    cipher.updateAAD("VLK1-KEYWRAP".toByteArray(StandardCharsets.UTF_8))
    val encrypted = cipher.doFinal(dek.encoded)
    return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
  }

  fun unwrapDekWithKey(wrappedBase64: String, wrappingKey: SecretKey): SecretKey {
    val payload = Base64.decode(wrappedBase64, Base64.NO_WRAP)
    require(payload.size > NONCE_BYTES + 16) { "Corrupted wrapped key payload" }
    val iv = payload.copyOfRange(0, NONCE_BYTES)
    val ciphertext = payload.copyOfRange(NONCE_BYTES, payload.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
    cipher.updateAAD("VLK1-KEYWRAP".toByteArray(StandardCharsets.UTF_8))
    val rawKey = cipher.doFinal(ciphertext)
    return secretKeyFromBytes(rawKey)
  }

  fun computeKeyFingerprint(key: SecretKey): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
    return digest.take(8).joinToString(":") { "%02X".format(it) }
  }

  fun encryptStream(
    input: InputStream,
    output: OutputStream,
    key: SecretKey,
    metadata: EncryptedFileMetadata,
    chunkSize: Int = DEFAULT_CHUNK_SIZE,
  ): Long {
    val baseNonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
    val metaBytes = json.encodeToString(metadata).toByteArray(StandardCharsets.UTF_8)

    val metaCipher = Cipher.getInstance("AES/GCM/NoPadding")
    metaCipher.init(
      Cipher.ENCRYPT_MODE,
      key,
      GCMParameterSpec(GCM_TAG_BITS, deriveNonce(baseNonce, -1L)),
    )
    metaCipher.updateAAD("VLK1-META".toByteArray(StandardCharsets.UTF_8))
    val encryptedMeta = metaCipher.doFinal(metaBytes)

    val dos = DataOutputStream(output)
    dos.write(MAGIC)
    dos.writeByte(FORMAT_VERSION.toInt())
    dos.write(baseNonce)
    dos.writeInt(chunkSize)
    dos.writeInt(encryptedMeta.size)
    dos.write(encryptedMeta)

    val buffer = ByteArray(chunkSize)
    var chunkIndex = 0L
    var totalPlainBytes = 0L

    while (true) {
      val read = readFullyUpTo(input, buffer)
      if (read <= 0) break
      totalPlainBytes += read
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(
        Cipher.ENCRYPT_MODE,
        key,
        GCMParameterSpec(GCM_TAG_BITS, deriveNonce(baseNonce, chunkIndex)),
      )
      cipher.updateAAD("VLK1-DATA-$chunkIndex".toByteArray(StandardCharsets.UTF_8))
      val encryptedChunk = cipher.doFinal(buffer, 0, read)
      dos.writeInt(encryptedChunk.size)
      dos.write(encryptedChunk)
      chunkIndex++
    }

    // Authenticated EOF sentinel to defeat truncation attacks
    val eofCipher = Cipher.getInstance("AES/GCM/NoPadding")
    eofCipher.init(
      Cipher.ENCRYPT_MODE,
      key,
      GCMParameterSpec(GCM_TAG_BITS, deriveNonce(baseNonce, chunkIndex)),
    )
    eofCipher.updateAAD("VLK1-EOF-$chunkIndex".toByteArray(StandardCharsets.UTF_8))
    val eofTag = eofCipher.doFinal(ByteArray(0))
    dos.writeInt(-1)
    dos.writeInt(eofTag.size)
    dos.write(eofTag)
    dos.flush()
    return totalPlainBytes
  }

  fun readMetadata(input: InputStream, key: SecretKey): EncryptedFileMetadata {
    val dis = DataInputStream(input)
    val magic = ByteArray(4)
    dis.readFully(magic)
    require(magic.contentEquals(MAGIC)) { "Invalid BeastLock file signature" }
    val version = dis.readByte()
    require(version == FORMAT_VERSION) { "Unsupported BeastLock version: $version" }
    val baseNonce = ByteArray(NONCE_BYTES)
    dis.readFully(baseNonce)
    dis.readInt() // chunkSize
    val metaLen = dis.readInt()
    require(metaLen in 17..1_048_576) { "Invalid encrypted metadata length" }
    val encryptedMeta = ByteArray(metaLen)
    dis.readFully(encryptedMeta)

    val metaCipher = Cipher.getInstance("AES/GCM/NoPadding")
    metaCipher.init(
      Cipher.DECRYPT_MODE,
      key,
      GCMParameterSpec(GCM_TAG_BITS, deriveNonce(baseNonce, -1L)),
    )
    metaCipher.updateAAD("VLK1-META".toByteArray(StandardCharsets.UTF_8))
    val plainMeta = metaCipher.doFinal(encryptedMeta)
    return json.decodeFromString(String(plainMeta, StandardCharsets.UTF_8))
  }

  /**
   * Re-encrypts only the VLK1 metadata header block using the existing file's
   * baseNonce and key while copying all encrypted data chunks and the EOF
   * sentinel verbatim. File payload bytes are never decrypted.
   */
  fun rewriteEncryptedMetadata(
    input: InputStream,
    output: OutputStream,
    key: SecretKey,
    transformMetadata: (EncryptedFileMetadata) -> EncryptedFileMetadata,
  ): EncryptedFileMetadata {
    val dis = DataInputStream(input)
    val magic = ByteArray(4)
    dis.readFully(magic)
    require(magic.contentEquals(MAGIC)) { "Invalid BeastLock file signature" }
    val version = dis.readByte()
    require(version == FORMAT_VERSION) { "Unsupported BeastLock version: $version" }

    val baseNonce = ByteArray(NONCE_BYTES)
    dis.readFully(baseNonce)
    val chunkSize = dis.readInt()
    require(chunkSize in 1024..4_194_304) { "Invalid chunk size: $chunkSize" }

    val metaLen = dis.readInt()
    require(metaLen in 17..1_048_576) { "Invalid encrypted metadata length" }
    val encryptedMeta = ByteArray(metaLen)
    dis.readFully(encryptedMeta)

    val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
    decryptCipher.init(
      Cipher.DECRYPT_MODE,
      key,
      GCMParameterSpec(GCM_TAG_BITS, deriveNonce(baseNonce, -1L)),
    )
    decryptCipher.updateAAD("VLK1-META".toByteArray(StandardCharsets.UTF_8))
    val oldMeta = json.decodeFromString<EncryptedFileMetadata>(
      String(decryptCipher.doFinal(encryptedMeta), StandardCharsets.UTF_8),
    )

    val updatedMeta = transformMetadata(oldMeta)
    val updatedMetaBytes = json.encodeToString(updatedMeta).toByteArray(StandardCharsets.UTF_8)

    val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
    encryptCipher.init(
      Cipher.ENCRYPT_MODE,
      key,
      GCMParameterSpec(GCM_TAG_BITS, deriveNonce(baseNonce, -1L)),
    )
    encryptCipher.updateAAD("VLK1-META".toByteArray(StandardCharsets.UTF_8))
    val newEncryptedMeta = encryptCipher.doFinal(updatedMetaBytes)

    val dos = DataOutputStream(output)
    dos.write(MAGIC)
    dos.writeByte(FORMAT_VERSION.toInt())
    dos.write(baseNonce)
    dos.writeInt(chunkSize)
    dos.writeInt(newEncryptedMeta.size)
    dos.write(newEncryptedMeta)

    // Copy remaining encrypted data chunks and EOF sentinel verbatim without decryption
    val copyBuffer = ByteArray(DEFAULT_CHUNK_SIZE)
    while (true) {
      val read = dis.read(copyBuffer)
      if (read == -1) break
      dos.write(copyBuffer, 0, read)
    }
    dos.flush()
    return updatedMeta
  }

  fun decryptStream(
    input: InputStream,
    output: OutputStream,
    key: SecretKey,
  ): EncryptedFileMetadata {
    val dis = DataInputStream(input)
    val magic = ByteArray(4)
    dis.readFully(magic)
    require(magic.contentEquals(MAGIC)) { "Invalid BeastLock file signature" }
    val version = dis.readByte()
    require(version == FORMAT_VERSION) { "Unsupported BeastLock version: $version" }

    val baseNonce = ByteArray(NONCE_BYTES)
    dis.readFully(baseNonce)
    val chunkSize = dis.readInt()
    require(chunkSize in 1024..4_194_304) { "Invalid chunk size: $chunkSize" }

    val metaLen = dis.readInt()
    require(metaLen in 17..1_048_576) { "Invalid encrypted metadata length" }
    val encryptedMeta = ByteArray(metaLen)
    dis.readFully(encryptedMeta)

    val metaCipher = Cipher.getInstance("AES/GCM/NoPadding")
    metaCipher.init(
      Cipher.DECRYPT_MODE,
      key,
      GCMParameterSpec(GCM_TAG_BITS, deriveNonce(baseNonce, -1L)),
    )
    metaCipher.updateAAD("VLK1-META".toByteArray(StandardCharsets.UTF_8))
    val metadata = json.decodeFromString<EncryptedFileMetadata>(
      String(metaCipher.doFinal(encryptedMeta), StandardCharsets.UTF_8),
    )

    var chunkIndex = 0L
    while (true) {
      val len = try {
        dis.readInt()
      } catch (eof: EOFException) {
        throw SecurityException("Encrypted file was truncated before EOF sentinel")
      }
      if (len == -1) {
        val eofTagLen = dis.readInt()
        require(eofTagLen in 16..64) { "Invalid EOF sentinel length" }
        val eofTag = ByteArray(eofTagLen)
        dis.readFully(eofTag)
        val eofCipher = Cipher.getInstance("AES/GCM/NoPadding")
        eofCipher.init(
          Cipher.DECRYPT_MODE,
          key,
          GCMParameterSpec(GCM_TAG_BITS, deriveNonce(baseNonce, chunkIndex)),
        )
        eofCipher.updateAAD("VLK1-EOF-$chunkIndex".toByteArray(StandardCharsets.UTF_8))
        eofCipher.doFinal(eofTag)
        break
      }
      require(len in 17..(chunkSize + 64)) { "Corrupted chunk length: $len" }
      val encryptedChunk = ByteArray(len)
      dis.readFully(encryptedChunk)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(
        Cipher.DECRYPT_MODE,
        key,
        GCMParameterSpec(GCM_TAG_BITS, deriveNonce(baseNonce, chunkIndex)),
      )
      cipher.updateAAD("VLK1-DATA-$chunkIndex".toByteArray(StandardCharsets.UTF_8))
      val plainChunk = cipher.doFinal(encryptedChunk)
      output.write(plainChunk)
      chunkIndex++
    }
    output.flush()
    return metadata
  }

  fun decryptToByteArray(input: InputStream, key: SecretKey, maxBytes: Int = 8 * 1024 * 1024): Pair<EncryptedFileMetadata, ByteArray> {
    val baos = ByteArrayOutputStream()
    val meta = decryptStream(input, baos, key)
    val data = baos.toByteArray()
    require(data.size <= maxBytes) { "Preview size exceeds limit" }
    return meta to data
  }

  private fun deriveNonce(baseNonce: ByteArray, index: Long): ByteArray {
    val nonce = baseNonce.copyOf()
    val indexBytes = ByteBuffer.allocate(8).putLong(index).array()
    for (i in 0 until 8) {
      nonce[4 + i] = (nonce[4 + i].toInt() xor indexBytes[i].toInt()).toByte()
    }
    return nonce
  }

  private fun readFullyUpTo(input: InputStream, buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
      val read = input.read(buffer, total, buffer.size - total)
      if (read == -1) break
      total += read
    }
    return total
  }
}
