package com.example.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class BiometricAvailability {
  AVAILABLE_STRONG,
  AVAILABLE_WEAK_OR_DEVICE,
  NONE_ENROLLED,
  UNAVAILABLE;

  val canAuthenticate: Boolean
    get() = this == AVAILABLE_STRONG || this == AVAILABLE_WEAK_OR_DEVICE
}

/**
 * Manages the hardware-backed Android Keystore AES-256-GCM wrapping keys:
 * - `vaultlock_keystore_master_v1`: wraps/unwraps the vault's 256-bit Data Encryption Key (DEK)
 * - `vaultlock_audit_log_v1`: encrypts/decrypts the local tamper-evident security audit log file
 */
class KeystoreManager(
  private val context: Context,
  private val overrideTestMasterKey: SecretKey? = null,
) {

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "vaultlock_keystore_master_v1"
    private const val AUDIT_KEY_ALIAS = "vaultlock_audit_log_v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
  }

  fun checkBiometricAvailability(): BiometricAvailability {
    val manager = BiometricManager.from(context)
    val strongOrDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
      BiometricManager.Authenticators.BIOMETRIC_STRONG
    }
    return when (manager.canAuthenticate(strongOrDevice)) {
      BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE_STRONG
      BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
        val weak = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if (weak == BiometricManager.BIOMETRIC_SUCCESS) {
          BiometricAvailability.AVAILABLE_WEAK_OR_DEVICE
        } else {
          BiometricAvailability.NONE_ENROLLED
        }
      }
      else -> {
        val weak = manager.canAuthenticate(
          BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        when (weak) {
          BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE_WEAK_OR_DEVICE
          BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
          else -> BiometricAvailability.UNAVAILABLE
        }
      }
    }
  }

  private fun getOrCreateKeystoreKey(alias: String = KEY_ALIAS): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getKey(alias, null) as? SecretKey
    if (existing != null) return existing

    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val builder = KeyGenParameterSpec.Builder(
      alias,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .setRandomizedEncryptionRequired(true)

    keyGenerator.init(builder.build())
    return keyGenerator.generateKey()
  }

  fun wrapDekWithKeystore(dek: SecretKey): String {
    val masterKey = overrideTestMasterKey ?: getOrCreateKeystoreKey(KEY_ALIAS)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, masterKey)
    cipher.updateAAD("VLK1-KS".toByteArray(StandardCharsets.UTF_8))
    val iv = cipher.iv
    val encrypted = cipher.doFinal(dek.encoded)
    return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
  }

  fun unwrapDekWithKeystore(wrappedBase64: String): SecretKey {
    val masterKey = overrideTestMasterKey ?: getOrCreateKeystoreKey(KEY_ALIAS)
    val payload = Base64.decode(wrappedBase64, Base64.NO_WRAP)
    require(payload.size > IV_BYTES + 16) { "Corrupted keystore wrapped payload" }
    val iv = payload.copyOfRange(0, IV_BYTES)
    val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
    cipher.updateAAD("VLK1-KS".toByteArray(StandardCharsets.UTF_8))
    val rawKey = cipher.doFinal(ciphertext)
    return AesFolderCipher.secretKeyFromBytes(rawKey)
  }

  fun encryptAuditPayload(plaintext: ByteArray, overrideKey: SecretKey? = null): ByteArray {
    val key = overrideKey ?: getOrCreateKeystoreKey(AUDIT_KEY_ALIAS)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    cipher.updateAAD("VLK1-AUDIT-FILE".toByteArray(StandardCharsets.UTF_8))
    val iv = cipher.iv
    val ciphertext = cipher.doFinal(plaintext)
    return iv + ciphertext
  }

  fun decryptAuditPayload(payload: ByteArray, overrideKey: SecretKey? = null): ByteArray {
    require(payload.size > IV_BYTES + 16) { "Corrupted encrypted audit file" }
    val key = overrideKey ?: getOrCreateKeystoreKey(AUDIT_KEY_ALIAS)
    val iv = payload.copyOfRange(0, IV_BYTES)
    val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
    cipher.updateAAD("VLK1-AUDIT-FILE".toByteArray(StandardCharsets.UTF_8))
    return cipher.doFinal(ciphertext)
  }
}
