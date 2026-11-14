package com.example.crypto

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

sealed interface BiometricAuthResult {
  data object Success : BiometricAuthResult
  data object FallbackToPin : BiometricAuthResult
  data object Cancelled : BiometricAuthResult
  data class Error(val message: String) : BiometricAuthResult
}

object BiometricAuthHelper {

  fun authenticate(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    negativeButtonText: String,
    allowDeviceCredential: Boolean = false,
    onAuthAttemptFailed: (reason: String) -> Unit = {},
    onResult: (BiometricAuthResult) -> Unit,
  ) {
    val executor = ContextCompat.getMainExecutor(activity)
    val biometricManager = BiometricManager.from(activity)

    val authenticators = if (allowDeviceCredential && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
      BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
    }

    val canAuth = biometricManager.canAuthenticate(authenticators)
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
      onResult(BiometricAuthResult.FallbackToPin)
      return
    }

    val callback = object : BiometricPrompt.AuthenticationCallback() {
      override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
        onResult(BiometricAuthResult.Success)
      }

      override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
        when (errorCode) {
          BiometricPrompt.ERROR_NEGATIVE_BUTTON -> onResult(BiometricAuthResult.FallbackToPin)
          BiometricPrompt.ERROR_USER_CANCELED,
          BiometricPrompt.ERROR_CANCELED -> onResult(BiometricAuthResult.Cancelled)
          BiometricPrompt.ERROR_NO_BIOMETRICS,
          BiometricPrompt.ERROR_HW_NOT_PRESENT,
          BiometricPrompt.ERROR_HW_UNAVAILABLE -> onResult(BiometricAuthResult.FallbackToPin)
          BiometricPrompt.ERROR_LOCKOUT,
          BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
            onAuthAttemptFailed("Biometric lockout: $errString")
            onResult(BiometricAuthResult.Error(errString.toString()))
          }
          else -> onResult(BiometricAuthResult.Error(errString.toString()))
        }
      }

      override fun onAuthenticationFailed() {
        onAuthAttemptFailed("Biometric credential not recognized")
      }
    }

    val prompt = BiometricPrompt(activity, executor, callback)
    val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setSubtitle(subtitle)
      .setConfirmationRequired(false)
      .setAllowedAuthenticators(authenticators)

    if (!(allowDeviceCredential && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)) {
      promptInfoBuilder.setNegativeButtonText(negativeButtonText)
    }

    prompt.authenticate(promptInfoBuilder.build())
  }
}
