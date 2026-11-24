package com.example.crypto

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.biometric.BiometricPrompt
import androidx.documentfile.provider.DocumentFile
import com.example.data.local.VaultFolderEntity
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.crypto.SecretKey

enum class UriPermissionStatus(val label: String) {
  PERSISTED_READ_WRITE("Persistable R/W"),
  TRANSIENT("Transient Grant"),
  READ_ONLY("Read-Only Grant"),
  REVOKED("Permission Revoked"),
  INTERNAL_SANDBOX("Private Sandbox"),
  ;

  val isWritable: Boolean
    get() = this == PERSISTED_READ_WRITE || this == TRANSIENT || this == INTERNAL_SANDBOX

  val needsRegrant: Boolean
    get() = this == REVOKED || this == READ_ONLY
}

/**
 * Unified AES-256-GCM & Android Keystore encryption engine for BeastLock.
 *
 * Coordinates:
 * - Hardware-backed [KeystoreManager] master key wrapping/unwrapping and [BiometricHelper] key access gating
 * - Per-folder AES-256-GCM key generation, wrapping, recovery, and key-fingerprint verification
 * - Streaming chunked VLK1 AES-256-GCM encryption/decryption for SAF and internal sandbox files
 * - Storage Access Framework (`OpenDocumentTree`) persistable permission acquisition & auditing
 */
class EncryptionManager(
  private val context: Context,
  private val keystoreManager: KeystoreManager = KeystoreManager(context),
  private val dualKeyVaultManager: DualKeyVaultManager? = null,
  private val externalTreeUriProvider: () -> String = { "" },
) {

  fun wrapMasterKey(dek: SecretKey): String = keystoreManager.wrapDekWithKeystore(dek)

  fun prepareKeyAccessCryptoObject(wrappedDekB64: String): BiometricPrompt.CryptoObject? =
    runCatching {
      BiometricPrompt.CryptoObject(keystoreManager.prepareDekUnwrapCipher(wrappedDekB64))
    }.getOrNull()

  fun unwrapMasterKey(
    wrappedDekB64: String,
    unlockedCryptoObject: BiometricPrompt.CryptoObject? = null,
  ): SecretKey {
    val unlockedCipher = unlockedCryptoObject?.cipher
    return if (unlockedCipher != null) {
      keystoreManager.finishUnwrapDekWithCipher(wrappedDekB64, unlockedCipher)
    } else {
      keystoreManager.unwrapDekWithKeystore(wrappedDekB64)
    }
  }

  /**
   * Requests user biometric verification through [BiometricHelper] before unwrapping and
   * returning access to the Keystore-protected encryption key.
   */
  fun requestBiometricKeyAccess(
    biometricHelper: BiometricHelper,
    wrappedDekB64: String?,
    title: String,
    subtitle: String,
    negativeButtonText: String,
    allowDeviceCredential: Boolean = false,
    onAuthAttemptFailed: (reason: String) -> Unit = {},
    onKeyAccessed: (SecretKey) -> Unit,
    onFallbackToSecondary: () -> Unit,
    onCancelled: () -> Unit = {},
    onError: (String) -> Unit = {},
  ) {
    if (wrappedDekB64.isNullOrBlank()) {
      onFallbackToSecondary()
      return
    }
    biometricHelper.authenticateBeforeKeyAccess(
      title = title,
      subtitle = subtitle,
      negativeButtonText = negativeButtonText,
      allowDeviceCredential = allowDeviceCredential,
      prepareCryptoObject = { prepareKeyAccessCryptoObject(wrappedDekB64) },
      onAuthAttemptFailed = onAuthAttemptFailed,
    ) { result ->
      when (result) {
        is BiometricAuthResult.Success -> {
          val unwrapped = runCatching {
            unwrapMasterKey(wrappedDekB64, result.cryptoObject)
          }.getOrNull()
          if (unwrapped != null) {
            onKeyAccessed(unwrapped)
          } else {
            onError("Keystore key unwrap failed after biometric verification.")
            onFallbackToSecondary()
          }
        }
        BiometricAuthResult.FallbackToPin -> onFallbackToSecondary()
        BiometricAuthResult.Cancelled -> onCancelled()
        is BiometricAuthResult.Error -> {
          onError(result.message)
          onFallbackToSecondary()
        }
      }
    }
  }

  fun generateFolderKey(): SecretKey = AesFolderCipher.generateAes256Key()

  fun wrapFolderKey(folderKey: SecretKey, masterDek: SecretKey): String =
    AesFolderCipher.wrapDekWithKey(folderKey, masterDek)

  fun unwrapFolderKey(wrappedFolderKey: String, masterDek: SecretKey): SecretKey =
    AesFolderCipher.unwrapDekWithKey(wrappedFolderKey, masterDek)

  fun computeKeyFingerprint(key: SecretKey): String =
    AesFolderCipher.computeKeyFingerprint(key)

  fun generateObfuscatedCipherName(): String =
    "${UUID.randomUUID()}${AesFolderCipher.ENCRYPTED_EXTENSION}"

  suspend fun resolveFolderKey(
    folder: VaultFolderEntity,
    masterDek: SecretKey,
    onRecovered: (suspend (SecretKey, DualKeyFolderRecord) -> Unit)? = null,
  ): SecretKey {
    val wrapped = folder.wrappedFolderKey
    if (!wrapped.isNullOrBlank()) {
      val direct = runCatching { unwrapFolderKey(wrapped, masterDek) }.getOrNull()
      if (direct != null) return direct
    }
    if (dualKeyVaultManager != null) {
      val recovered = dualKeyVaultManager.recoverFolderKey(
        folderId = folder.id,
        expectedKeyFingerprint = folder.keyFingerprint,
        masterDek = masterDek,
        externalTreeUri = externalTreeUriProvider(),
      )
      if (recovered != null) {
        val (key, rec) = recovered
        onRecovered?.invoke(key, rec)
        return key
      }
    }
    return if (wrapped.isNullOrBlank()) {
      masterDek
    } else {
      unwrapFolderKey(wrapped, masterDek)
    }
  }

  fun encryptStream(
    input: InputStream,
    output: OutputStream,
    key: SecretKey,
    metadata: EncryptedFileMetadata,
  ): Long = AesFolderCipher.encryptStream(
    input = input,
    output = output,
    key = key,
    metadata = metadata,
  )

  fun decryptStream(
    input: InputStream,
    output: OutputStream,
    key: SecretKey,
  ): EncryptedFileMetadata = AesFolderCipher.decryptStream(
    input = input,
    output = output,
    key = key,
  )

  fun decryptToByteArray(
    input: InputStream,
    key: SecretKey,
    maxBytes: Int = 24 * 1024 * 1024,
  ): Pair<EncryptedFileMetadata, ByteArray> =
    AesFolderCipher.decryptToByteArray(input = input, key = key, maxBytes = maxBytes)

  fun readMetadata(input: InputStream, key: SecretKey): EncryptedFileMetadata =
    AesFolderCipher.readMetadata(input = input, key = key)

  fun rewriteEncryptedMetadata(
    input: InputStream,
    output: OutputStream,
    key: SecretKey,
    transform: (EncryptedFileMetadata) -> EncryptedFileMetadata,
  ): EncryptedFileMetadata = AesFolderCipher.rewriteEncryptedMetadata(
    input = input,
    output = output,
    key = key,
    transformMetadata = transform,
  )

  fun encryptLocalFile(
    sourceFile: File,
    targetFile: File,
    key: SecretKey,
    mimeType: String,
    originalName: String = sourceFile.name,
  ): EncryptedFileMetadata {
    val meta = EncryptedFileMetadata(
      originalName = originalName,
      mimeType = mimeType,
      originalSize = sourceFile.length(),
      encryptedAt = System.currentTimeMillis(),
    )
    sourceFile.inputStream().use { input ->
      targetFile.outputStream().use { output ->
        encryptStream(input, output, key, meta)
      }
    }
    return meta
  }

  fun decryptLocalFile(
    sourceFile: File,
    targetFile: File,
    key: SecretKey,
  ): EncryptedFileMetadata = sourceFile.inputStream().use { input ->
    targetFile.outputStream().use { output ->
      decryptStream(input, output, key)
    }
  }

  fun takePersistableFolderPermission(treeUri: Uri): Boolean {
    if (treeUri.scheme == "file") return false
    return try {
      context.contentResolver.takePersistableUriPermission(
        treeUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
      )
      true
    } catch (_: SecurityException) {
      false
    }
  }

  fun checkTreeUriPermission(treeUri: Uri, allowTransientFallback: Boolean = false): UriPermissionStatus {
    if (treeUri.scheme == "app-internal") return UriPermissionStatus.INTERNAL_SANDBOX
    if (treeUri.scheme == "file") {
      val path = treeUri.path ?: return UriPermissionStatus.REVOKED
      val dir = File(path)
      return when {
        !dir.exists() || !dir.isDirectory -> UriPermissionStatus.REVOKED
        dir.canRead() && dir.canWrite() -> UriPermissionStatus.PERSISTED_READ_WRITE
        dir.canRead() -> UriPermissionStatus.READ_ONLY
        else -> UriPermissionStatus.REVOKED
      }
    }
    val persisted = context.contentResolver.persistedUriPermissions.firstOrNull { perm ->
      perm.uri == treeUri || perm.uri.toString() == treeUri.toString()
    }
    if (persisted != null) {
      return when {
        persisted.isReadPermission && persisted.isWritePermission -> {
          val docAccessible = runCatching { DocumentFile.fromTreeUri(context, treeUri)?.exists() != false }.getOrDefault(false)
          if (docAccessible) UriPermissionStatus.PERSISTED_READ_WRITE else UriPermissionStatus.REVOKED
        }
        persisted.isReadPermission -> UriPermissionStatus.READ_ONLY
        else -> UriPermissionStatus.REVOKED
      }
    }
    if (allowTransientFallback) {
      val doc = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
      if (doc != null && doc.exists() && doc.canRead()) {
        return if (doc.canWrite()) UriPermissionStatus.TRANSIENT else UriPermissionStatus.READ_ONLY
      }
    }
    return UriPermissionStatus.REVOKED
  }

  fun checkFolderUriPermission(folder: VaultFolderEntity): UriPermissionStatus {
    if (folder.isInternalVault) return UriPermissionStatus.INTERNAL_SANDBOX
    val uri = runCatching { Uri.parse(folder.treeUri) }.getOrNull() ?: return UriPermissionStatus.REVOKED
    return checkTreeUriPermission(uri, allowTransientFallback = false)
  }
}
