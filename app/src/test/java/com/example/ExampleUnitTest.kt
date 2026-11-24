package com.example

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.example.crypto.BiometricAuthResult
import com.example.crypto.BiometricHelper
import com.example.crypto.UriPermissionStatus
import com.example.data.local.VaultFolderEntity
import com.example.data.preferences.SecurityPreferencesRepository
import com.example.ui.VaultUiState
import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun stealthCodeNormalizationRequiresMinimumLengthAndNormalizesHashPrefix() {
    assertEquals("#7788", SecurityPreferencesRepository.DEFAULT_STEALTH_CODE)
    assertEquals("#7788", SecurityPreferencesRepository.normalizeStealthCode("7788"))
    assertEquals("#7788", SecurityPreferencesRepository.normalizeStealthCode("  #7788  "))
    assertNull(SecurityPreferencesRepository.normalizeStealthCode("123"))
    assertNull(SecurityPreferencesRepository.normalizeStealthCode("   "))
  }

  @Test
  fun vaultUiStateIsolatesStealthHiddenFoldersFromPublicDashboardAndAnalyticsUnlessRevealed() {
    val publicDoc = VaultFolderEntity(
      id = "pub-1",
      name = "Work Docs",
      treeUri = "content://tree/pub-1",
      isInternalVault = false,
      isLocked = true,
      fileCount = 3,
      encryptedFileCount = 3,
      totalBytes = 1_000L,
      encryptedBytes = 1_000L,
      unencryptedBytes = 0L,
      lastActionTimestamp = 1L,
      createdAt = 1L,
      isStealthHidden = false,
    )
    val stealthPrivate = VaultFolderEntity(
      id = "stealth-1",
      name = "Hidden Vault",
      treeUri = "internal://stealth-1",
      isInternalVault = true,
      isLocked = true,
      fileCount = 5,
      encryptedFileCount = 5,
      totalBytes = 4_000L,
      encryptedBytes = 4_000L,
      unencryptedBytes = 0L,
      lastActionTimestamp = 2L,
      createdAt = 2L,
      isStealthHidden = true,
    )

    val concealedState = VaultUiState(
      folders = listOf(publicDoc, stealthPrivate),
      isStealthRevealed = false,
    )
    assertEquals(listOf(publicDoc), concealedState.publicFolders)
    assertEquals(emptyList<VaultFolderEntity>(), concealedState.stealthFolders)
    assertEquals(listOf(publicDoc), concealedState.analyticsVisibleFolders)
    assertEquals(1, concealedState.lockedFoldersCount)
    assertEquals(3, concealedState.totalEncryptedFiles)
    assertEquals(1_000L, concealedState.totalProtectedBytes)
    assertEquals(1_000L, concealedState.totalEncryptedBytes)

    val revealedState = concealedState.copy(isStealthRevealed = true)
    assertEquals(listOf(publicDoc), revealedState.publicFolders)
    assertEquals(listOf(stealthPrivate), revealedState.stealthFolders)
    assertEquals(listOf(publicDoc, stealthPrivate), revealedState.analyticsVisibleFolders)
    assertEquals(5_000L, revealedState.totalEncryptedBytes)
  }

  @Test
  fun biometricHelperResolvesAuthenticatorsProperlyForCryptoObjectAndDeviceCredential() {
    assertEquals(
      BiometricManager.Authenticators.BIOMETRIC_STRONG,
      BiometricHelper.resolveAuthenticators(
        allowDeviceCredential = true,
        requireStrongBiometric = true,
        sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
      ),
    )

    assertEquals(
      BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
      BiometricHelper.resolveAuthenticators(
        allowDeviceCredential = true,
        requireStrongBiometric = false,
        sdkInt = Build.VERSION_CODES.R,
      ),
    )

    assertEquals(
      BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.BIOMETRIC_WEAK,
      BiometricHelper.resolveAuthenticators(
        allowDeviceCredential = true,
        requireStrongBiometric = false,
        sdkInt = Build.VERSION_CODES.Q,
      ),
    )

    assertEquals(
      BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.BIOMETRIC_WEAK,
      BiometricHelper.resolveAuthenticators(
        allowDeviceCredential = false,
        requireStrongBiometric = false,
        sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
      ),
    )
  }

  @Test
  fun biometricHelperResolvesAvailabilityForFingerprintAndFaceWithoutDeviceCredentialBypass() {
    assertEquals(
      com.example.crypto.BiometricAvailability.AVAILABLE_STRONG,
      BiometricHelper.resolveAvailabilityStatus(
        strongStatus = BiometricManager.BIOMETRIC_SUCCESS,
        weakOrStrongStatus = BiometricManager.BIOMETRIC_SUCCESS,
      ),
    )
    assertEquals(
      com.example.crypto.BiometricAvailability.AVAILABLE_WEAK_OR_DEVICE,
      BiometricHelper.resolveAvailabilityStatus(
        strongStatus = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
        weakOrStrongStatus = BiometricManager.BIOMETRIC_SUCCESS,
      ),
    )
    assertEquals(
      com.example.crypto.BiometricAvailability.NONE_ENROLLED,
      BiometricHelper.resolveAvailabilityStatus(
        strongStatus = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
        weakOrStrongStatus = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
      ),
    )
    assertEquals(
      com.example.crypto.BiometricAvailability.UNAVAILABLE,
      BiometricHelper.resolveAvailabilityStatus(
        strongStatus = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        weakOrStrongStatus = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
      ),
    )
  }

  @Test
  fun biometricHelperMapsPromptErrorCodesDeterministically() {
    assertEquals(
      BiometricAuthResult.FallbackToPin,
      BiometricHelper.mapAuthErrorCode(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "Use PIN"),
    )
    assertEquals(
      BiometricAuthResult.FallbackToPin,
      BiometricHelper.mapAuthErrorCode(BiometricPrompt.ERROR_NO_BIOMETRICS, "None enrolled"),
    )
    assertEquals(
      BiometricAuthResult.Cancelled,
      BiometricHelper.mapAuthErrorCode(BiometricPrompt.ERROR_USER_CANCELED, "Cancelled"),
    )
    val lockout = BiometricHelper.mapAuthErrorCode(BiometricPrompt.ERROR_LOCKOUT, "Too many attempts")
    assertEquals(
      BiometricAuthResult.Error("Too many attempts", BiometricPrompt.ERROR_LOCKOUT),
      lockout,
    )
  }

  @Test
  fun uriPermissionStatusIdentifiesWritableAndRevokedRegrantStates() {
    assertTrue(UriPermissionStatus.PERSISTED_READ_WRITE.isWritable)
    assertFalse(UriPermissionStatus.PERSISTED_READ_WRITE.needsRegrant)

    assertTrue(UriPermissionStatus.TRANSIENT.isWritable)
    assertFalse(UriPermissionStatus.TRANSIENT.needsRegrant)

    assertTrue(UriPermissionStatus.INTERNAL_SANDBOX.isWritable)
    assertFalse(UriPermissionStatus.INTERNAL_SANDBOX.needsRegrant)

    assertFalse(UriPermissionStatus.READ_ONLY.isWritable)
    assertTrue(UriPermissionStatus.READ_ONLY.needsRegrant)

    assertFalse(UriPermissionStatus.REVOKED.isWritable)
    assertTrue(UriPermissionStatus.REVOKED.needsRegrant)

    val state = VaultUiState(
      folderPermissionStatuses = mapOf(
        "f1" to UriPermissionStatus.REVOKED,
        "f2" to UriPermissionStatus.PERSISTED_READ_WRITE,
      ),
    )
    assertEquals(UriPermissionStatus.REVOKED, state.folderPermissionStatuses["f1"])
    assertEquals(UriPermissionStatus.PERSISTED_READ_WRITE, state.folderPermissionStatuses["f2"])
  }
}
