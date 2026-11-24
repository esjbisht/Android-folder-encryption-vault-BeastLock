package com.example.crypto

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor

sealed interface BiometricAuthResult {
  data class Success(
    val cryptoObject: BiometricPrompt.CryptoObject? = null,
    val authenticationType: Int = BiometricPrompt.AUTHENTICATION_RESULT_TYPE_UNKNOWN,
  ) : BiometricAuthResult

  data object FallbackToPin : BiometricAuthResult
  data object Cancelled : BiometricAuthResult
  data class Error(val message: String, val errorCode: Int = -1) : BiometricAuthResult
}

/**
 * Encapsulates [androidx.biometric.BiometricPrompt] lifecycle, authenticator capability checks,
 * CryptoObject-bound key gating, and deterministic fallback/error mapping so callers request
 * user biometric authentication before accessing encrypted vault keys.
 */
class BiometricHelper(
  private val activity: FragmentActivity,
) {
  private val biometricManager: BiometricManager = BiometricManager.from(activity)
  private val executor: Executor = ContextCompat.getMainExecutor(activity)
  private val mainHandler = Handler(Looper.getMainLooper())
  private var activePrompt: BiometricPrompt? = null

  fun checkAvailability(): BiometricAvailability = checkAvailability(activity)

  fun canAuthenticate(
    allowDeviceCredential: Boolean = false,
    requireStrongBiometric: Boolean = false,
  ): Boolean {
    val authenticators = resolveAuthenticators(
      allowDeviceCredential = allowDeviceCredential,
      requireStrongBiometric = requireStrongBiometric,
    )
    return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
  }

  fun hasEnrolledBiometrics(): Boolean = canAuthenticate(
    allowDeviceCredential = false,
    requireStrongBiometric = false,
  )

  /**
   * Triggers [BiometricPrompt] before accessing or unlocking an encrypted folder,
   * requiring fingerprint or facial recognition ([BIOMETRIC_STRONG] with a Keystore
   * [BiometricPrompt.CryptoObject] when available, or [BIOMETRIC_STRONG] | [BIOMETRIC_WEAK]
   * for Class 2 face/fingerprint sensors). Never falls back to OS device PIN/pattern so
   * BeastLock's own Pattern/PIN fallback remains in full control via the negative button.
   */
  fun authenticateForFolderAccess(
    folderName: String? = null,
    isUnlocking: Boolean = false,
    title: String? = null,
    subtitle: String? = null,
    negativeButtonText: String,
    confirmationRequired: Boolean = false,
    prepareCryptoObject: () -> BiometricPrompt.CryptoObject? = { null },
    onAuthAttemptFailed: (reason: String) -> Unit = {},
    onResult: (BiometricAuthResult) -> Unit,
  ) {
    val resolvedTitle = title ?: when {
      !folderName.isNullOrBlank() && isUnlocking -> "Unlock \"$folderName\""
      !folderName.isNullOrBlank() -> "Access \"$folderName\""
      isUnlocking -> "Unlock Encrypted Folder"
      else -> "Access Encrypted Folder"
    }
    val resolvedSubtitle = subtitle ?: if (isUnlocking) {
      "Verify with fingerprint or facial recognition to decrypt folder contents"
    } else {
      "Verify with fingerprint or facial recognition to open encrypted folder"
    }

    authenticateBeforeKeyAccess(
      title = resolvedTitle,
      subtitle = resolvedSubtitle,
      negativeButtonText = negativeButtonText,
      allowDeviceCredential = false,
      confirmationRequired = confirmationRequired,
      prepareCryptoObject = prepareCryptoObject,
      onAuthAttemptFailed = onAuthAttemptFailed,
      onResult = onResult,
    )
  }

  /**
   * Requests user biometric authentication prior to accessing an encryption key.
   *
   * When [prepareCryptoObject] supplies a Keystore-backed [BiometricPrompt.CryptoObject] and
   * [BiometricManager.Authenticators.BIOMETRIC_STRONG] is available, the OS prompt unlocks the
   * cryptographic operation directly and delivers the unlocked CryptoObject in
   * [BiometricAuthResult.Success]. On devices with only Weak biometrics enrolled (such as 2D
   * facial recognition), falls back to a biometric-only prompt (`BIOMETRIC_STRONG | BIOMETRIC_WEAK`)
   * before releasing key access.
   */
  fun authenticateBeforeKeyAccess(
    title: String,
    subtitle: String,
    negativeButtonText: String,
    description: String? = null,
    allowDeviceCredential: Boolean = false,
    confirmationRequired: Boolean = false,
    prepareCryptoObject: () -> BiometricPrompt.CryptoObject? = { null },
    onAuthAttemptFailed: (reason: String) -> Unit = {},
    onResult: (BiometricAuthResult) -> Unit,
  ) {
    val preparedCrypto = runCatching { prepareCryptoObject() }.getOrNull()
    val useStrongCrypto = preparedCrypto != null &&
      canAuthenticate(allowDeviceCredential = false, requireStrongBiometric = true)

    if (useStrongCrypto) {
      authenticate(
        title = title,
        subtitle = subtitle,
        negativeButtonText = negativeButtonText,
        description = description,
        allowDeviceCredential = false,
        cryptoObject = preparedCrypto,
        confirmationRequired = confirmationRequired,
        onAuthAttemptFailed = onAuthAttemptFailed,
        onResult = onResult,
      )
    } else {
      authenticate(
        title = title,
        subtitle = subtitle,
        negativeButtonText = negativeButtonText,
        description = description,
        allowDeviceCredential = allowDeviceCredential,
        cryptoObject = null,
        confirmationRequired = confirmationRequired,
        onAuthAttemptFailed = onAuthAttemptFailed,
        onResult = onResult,
      )
    }
  }

  fun authenticate(
    title: String,
    subtitle: String,
    negativeButtonText: String,
    description: String? = null,
    allowDeviceCredential: Boolean = false,
    cryptoObject: BiometricPrompt.CryptoObject? = null,
    confirmationRequired: Boolean = false,
    onAuthAttemptFailed: (reason: String) -> Unit = {},
    onResult: (BiometricAuthResult) -> Unit,
  ) {
    val requireStrong = cryptoObject != null
    val effectiveAllowDeviceCredential = allowDeviceCredential && !requireStrong
    val authenticators = resolveAuthenticators(
      allowDeviceCredential = effectiveAllowDeviceCredential,
      requireStrongBiometric = requireStrong,
    )

    if (biometricManager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
      onResult(BiometricAuthResult.FallbackToPin)
      return
    }

    var resultDelivered = false
    fun deliverOnce(authResult: BiometricAuthResult) {
      if (resultDelivered) return
      resultDelivered = true
      onResult(authResult)
    }

    val callback = object : BiometricPrompt.AuthenticationCallback() {
      override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
        activePrompt = null
        deliverOnce(
          BiometricAuthResult.Success(
            cryptoObject = result.cryptoObject,
            authenticationType = result.authenticationType,
          ),
        )
      }

      override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
        activePrompt = null
        val mapped = mapAuthErrorCode(errorCode, errString)
        if (errorCode == BiometricPrompt.ERROR_LOCKOUT ||
          errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT
        ) {
          onAuthAttemptFailed("Biometric lockout: $errString")
        }
        deliverOnce(mapped)
      }

      override fun onAuthenticationFailed() {
        onAuthAttemptFailed("Biometric credential not recognized")
      }
    }

    val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setSubtitle(subtitle)
      .setConfirmationRequired(confirmationRequired)
      .setAllowedAuthenticators(authenticators)

    if (!description.isNullOrBlank()) {
      promptInfoBuilder.setDescription(description)
    }

    val usesDeviceCredential = effectiveAllowDeviceCredential &&
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    if (!usesDeviceCredential) {
      promptInfoBuilder.setNegativeButtonText(negativeButtonText)
    }

    val launchPrompt = {
      runCatching {
        activePrompt?.cancelAuthentication()
        val prompt = BiometricPrompt(activity, executor, callback)
        activePrompt = prompt
        val info = promptInfoBuilder.build()
        if (cryptoObject != null) {
          prompt.authenticate(info, cryptoObject)
        } else {
          prompt.authenticate(info)
        }
      }.onFailure { err ->
        activePrompt = null
        onAuthAttemptFailed("Biometric prompt error: ${err.message ?: err::class.java.simpleName}")
        deliverOnce(BiometricAuthResult.FallbackToPin)
      }
    }

    if (Looper.myLooper() == Looper.getMainLooper()) {
      launchPrompt()
    } else {
      mainHandler.post { launchPrompt() }
    }
  }

  fun cancelAuthentication() {
    val cancelBlock = {
      runCatching { activePrompt?.cancelAuthentication() }
      activePrompt = null
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      cancelBlock()
    } else {
      mainHandler.post { cancelBlock() }
    }
  }

  companion object {
    fun resolveAuthenticators(
      allowDeviceCredential: Boolean,
      requireStrongBiometric: Boolean = false,
      sdkInt: Int = Build.VERSION.SDK_INT,
    ): Int = when {
      requireStrongBiometric -> BiometricManager.Authenticators.BIOMETRIC_STRONG
      allowDeviceCredential && sdkInt >= Build.VERSION_CODES.R ->
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
          BiometricManager.Authenticators.DEVICE_CREDENTIAL
      else ->
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
          BiometricManager.Authenticators.BIOMETRIC_WEAK
    }

    fun mapAuthErrorCode(errorCode: Int, errString: CharSequence): BiometricAuthResult =
      when (errorCode) {
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_HW_UNAVAILABLE,
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
        -> BiometricAuthResult.FallbackToPin
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_CANCELED,
        -> BiometricAuthResult.Cancelled
        else -> BiometricAuthResult.Error(
          message = errString.toString(),
          errorCode = errorCode,
        )
      }

    fun resolveAvailabilityStatus(
      strongStatus: Int,
      weakOrStrongStatus: Int,
    ): BiometricAvailability = when {
      strongStatus == BiometricManager.BIOMETRIC_SUCCESS ->
        BiometricAvailability.AVAILABLE_STRONG
      weakOrStrongStatus == BiometricManager.BIOMETRIC_SUCCESS ->
        BiometricAvailability.AVAILABLE_WEAK_OR_DEVICE
      strongStatus == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
        weakOrStrongStatus == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
        BiometricAvailability.NONE_ENROLLED
      else ->
        BiometricAvailability.UNAVAILABLE
    }

    fun checkAvailability(context: Context): BiometricAvailability {
      val manager = BiometricManager.from(context)
      val strongStatus = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
      val weakOrStrongStatus = manager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
          BiometricManager.Authenticators.BIOMETRIC_WEAK,
      )
      return resolveAvailabilityStatus(strongStatus, weakOrStrongStatus)
    }
  }
}
