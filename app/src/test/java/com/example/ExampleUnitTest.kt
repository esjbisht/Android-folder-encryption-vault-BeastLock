package com.example

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.example.crypto.BiometricAuthResult
import com.example.crypto.BiometricHelper
import com.example.crypto.UriPermissionStatus
import com.example.data.local.VaultFolderEntity
import com.example.data.preferences.SecurityPreferencesRepository
import com.example.data.storage.EncryptionProgress
import com.example.service.CryptoProgressForegroundService
import com.example.ui.FolderFilter
import com.example.ui.FolderSortOrder
import com.example.ui.VaultUiState
import com.example.ui.isPartiallyEncrypted
import com.example.ui.matchesFolderSearchQuery
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.VaultProgressTone
import com.example.ui.theme.VaultThemeEngine
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

  @Test
  fun encryptionProgressComputesFileAndOverallByteFractionsWithSafeClamping() {
    val active = EncryptionProgress(
      folderName = "Tax Docs",
      isLocking = true,
      currentFileName = "return_2025.pdf",
      processedFiles = 1,
      totalFiles = 4,
      processedBytes = 250L,
      totalBytes = 1_000L,
      folderId = "folder-1",
      currentFileId = "file-2",
      currentFileProcessedBytes = 250L,
      currentFileTotalBytes = 500L,
    )
    assertEquals(0.5f, active.currentFileFraction, 0.001f)
    assertEquals(0.5f, active.overallFraction, 0.001f)

    val overflow = active.copy(
      processedBytes = 900L,
      currentFileProcessedBytes = 500L,
    )
    assertEquals(1.0f, overflow.currentFileFraction, 0.001f)
    assertEquals(1.0f, overflow.overallFraction, 0.001f)

    val zeroByteFallback = EncryptionProgress(
      folderName = "Empty",
      isLocking = false,
      currentFileName = "empty.txt",
      processedFiles = 1,
      totalFiles = 2,
      processedBytes = 0L,
      totalBytes = 0L,
      currentFileProcessedBytes = 0L,
      currentFileTotalBytes = 0L,
    )
    assertEquals(0f, zeroByteFallback.currentFileFraction, 0.001f)
    assertEquals(0.5f, zeroByteFallback.overallFraction, 0.001f)
  }

  @Test
  fun vaultUiStateTracksBusyFoldersAndActiveFilesWithoutBlockingUnrelatedFolders() {
    val progress = EncryptionProgress(
      folderName = "Passports",
      isLocking = true,
      currentFileName = "passport.pdf",
      processedFiles = 0,
      totalFiles = 2,
      processedBytes = 0L,
      totalBytes = 1_000L,
      folderId = "folder-busy",
      currentFileId = "doc://file-1",
      currentFileProcessedBytes = 400L,
      currentFileTotalBytes = 500L,
    )
    val state = VaultUiState(
      busyFolderIds = setOf("folder-queued"),
      encryptionProgress = progress,
    )

    assertTrue(state.isFolderBusy("folder-busy"))
    assertTrue(state.isFolderBusy("folder-queued"))
    assertFalse(state.isFolderBusy("folder-other"))

    assertTrue(state.isFileBusy("folder-busy", "doc://file-1", "different.pdf"))
    assertTrue(state.isFileBusy("folder-busy", "other-id", "passport.pdf"))
    assertFalse(state.isFileBusy("folder-busy", "doc://file-2", "visa.pdf"))
    assertFalse(state.isFileBusy("folder-other", "doc://file-1", "passport.pdf"))
  }

  @Test
  fun foregroundServiceNotificationCopyMasksStealthPrivateVaultNames() {
    val publicCopy = CryptoProgressForegroundService.formatNotificationCopy(
      EncryptionProgress(
        folderName = "Finance",
        isLocking = true,
        currentFileName = "ledger.xlsx",
        processedFiles = 1,
        totalFiles = 3,
        processedBytes = 500L,
        totalBytes = 1_000L,
        folderId = "pub",
        currentFileId = "f2",
        currentFileProcessedBytes = 250L,
        currentFileTotalBytes = 500L,
        isStealthMasked = false,
      ),
    )
    assertEquals("Encrypting Finance", publicCopy.title)
    assertTrue(publicCopy.text.contains("ledger.xlsx"))
    assertTrue(publicCopy.text.contains("2/3"))
    assertEquals(750, publicCopy.permille)
    assertFalse(publicCopy.indeterminate)

    val stealthCopy = CryptoProgressForegroundService.formatNotificationCopy(
      EncryptionProgress(
        folderName = "SecretDiary",
        isLocking = false,
        currentFileName = "private_entry.txt",
        processedFiles = 0,
        totalFiles = 2,
        processedBytes = 0L,
        totalBytes = 1_000L,
        folderId = "stealth",
        currentFileId = "f1",
        currentFileProcessedBytes = 200L,
        currentFileTotalBytes = 500L,
        isStealthMasked = true,
      ),
    )
    assertEquals("Decrypting Private Vault…", stealthCopy.title)
    assertFalse(stealthCopy.title.contains("SecretDiary"))
    assertFalse(stealthCopy.text.contains("private_entry.txt"))
    assertEquals("Protected file 1/2 • 20%", stealthCopy.text)
    assertEquals(200, stealthCopy.permille)
    assertFalse(stealthCopy.indeterminate)
  }

  @Test
  fun dashboardFiltersComputeLiveCountsAndFilterPartialAndRevokedFolders() {
    val lockedSaf = VaultFolderEntity(
      id = "f-locked",
      name = "Tax Returns",
      treeUri = "content://tree/tax",
      isInternalVault = false,
      isLocked = true,
      fileCount = 4,
      encryptedFileCount = 4,
      totalBytes = 4_000L,
      encryptedBytes = 4_000L,
      unencryptedBytes = 0L,
      lastActionTimestamp = 300L,
      createdAt = 100L,
    )
    val unlockedPrivate = VaultFolderEntity(
      id = "f-unlocked",
      name = "Receipts",
      treeUri = "internal://receipts",
      isInternalVault = true,
      isLocked = false,
      fileCount = 3,
      encryptedFileCount = 0,
      totalBytes = 9_000L,
      encryptedBytes = 0L,
      unencryptedBytes = 9_000L,
      lastActionTimestamp = 500L,
      createdAt = 100L,
    )
    val partialSaf = VaultFolderEntity(
      id = "f-partial",
      name = "Archive",
      treeUri = "content://tree/archive",
      isInternalVault = false,
      isLocked = false,
      fileCount = 5,
      encryptedFileCount = 2,
      totalBytes = 2_000L,
      encryptedBytes = 800L,
      unencryptedBytes = 1_200L,
      lastActionTimestamp = 400L,
      createdAt = 100L,
    )

    assertTrue(partialSaf.isPartiallyEncrypted())
    assertFalse(lockedSaf.isPartiallyEncrypted())
    assertFalse(unlockedPrivate.isPartiallyEncrypted())

    val baseState = VaultUiState(
      folders = listOf(lockedSaf, unlockedPrivate, partialSaf),
      folderPermissionStatuses = mapOf(
        "f-locked" to UriPermissionStatus.PERSISTED_READ_WRITE,
        "f-partial" to UriPermissionStatus.REVOKED,
      ),
    )

    val counts = baseState.folderFilterCounts
    assertEquals(3, counts[FolderFilter.ALL])
    assertEquals(1, counts[FolderFilter.LOCKED])
    assertEquals(2, counts[FolderFilter.UNLOCKED])
    assertEquals(1, counts[FolderFilter.PARTIAL])
    assertEquals(1, counts[FolderFilter.REVOKED])

    assertEquals(
      listOf(partialSaf),
      baseState.copy(folderFilter = FolderFilter.PARTIAL).filteredFolders,
    )
    assertEquals(
      listOf(partialSaf),
      baseState.copy(folderFilter = FolderFilter.REVOKED).filteredFolders,
    )

    assertTrue(matchesFolderSearchQuery(partialSaf, "partial", UriPermissionStatus.REVOKED))
    assertTrue(matchesFolderSearchQuery(partialSaf, "status:revoked", UriPermissionStatus.REVOKED))
    assertTrue(matchesFolderSearchQuery(partialSaf, "saf", UriPermissionStatus.REVOKED))
    assertFalse(matchesFolderSearchQuery(unlockedPrivate, "saf", UriPermissionStatus.INTERNAL_SANDBOX))
    assertTrue(matchesFolderSearchQuery(unlockedPrivate, "private", UriPermissionStatus.INTERNAL_SANDBOX))
    assertTrue(matchesFolderSearchQuery(lockedSaf, "loc", UriPermissionStatus.PERSISTED_READ_WRITE))
    assertFalse(matchesFolderSearchQuery(unlockedPrivate, "loc", UriPermissionStatus.INTERNAL_SANDBOX))
  }

  @Test
  fun dashboardSortOrderOrdersFoldersByRecentNameSizeAndStatus() {
    val aLocked = VaultFolderEntity(
      id = "a",
      name = "Alpha",
      treeUri = "content://a",
      isInternalVault = false,
      isLocked = true,
      fileCount = 2,
      encryptedFileCount = 2,
      totalBytes = 1_000L,
      encryptedBytes = 1_000L,
      unencryptedBytes = 0L,
      lastActionTimestamp = 100L,
      createdAt = 10L,
    )
    val zUnlocked = VaultFolderEntity(
      id = "z",
      name = "Zeta",
      treeUri = "content://z",
      isInternalVault = false,
      isLocked = false,
      fileCount = 3,
      encryptedFileCount = 0,
      totalBytes = 5_000L,
      encryptedBytes = 0L,
      unencryptedBytes = 5_000L,
      lastActionTimestamp = 300L,
      createdAt = 10L,
    )
    val mRevokedPartial = VaultFolderEntity(
      id = "m",
      name = "Middle",
      treeUri = "content://m",
      isInternalVault = false,
      isLocked = false,
      fileCount = 4,
      encryptedFileCount = 1,
      totalBytes = 3_000L,
      encryptedBytes = 500L,
      unencryptedBytes = 2_500L,
      lastActionTimestamp = 200L,
      createdAt = 10L,
    )

    val state = VaultUiState(
      folders = listOf(aLocked, zUnlocked, mRevokedPartial),
      folderPermissionStatuses = mapOf("m" to UriPermissionStatus.REVOKED),
    )

    assertEquals(
      listOf("z", "m", "a"),
      state.copy(folderSortOrder = FolderSortOrder.RECENT).filteredFolders.map { it.id },
    )
    assertEquals(
      listOf("a", "m", "z"),
      state.copy(folderSortOrder = FolderSortOrder.NAME_AZ).filteredFolders.map { it.id },
    )
    assertEquals(
      listOf("z", "m", "a"),
      state.copy(folderSortOrder = FolderSortOrder.SIZE_DESC).filteredFolders.map { it.id },
    )
    assertEquals(
      listOf("m", "z", "a"),
      state.copy(folderSortOrder = FolderSortOrder.STATUS).filteredFolders.map { it.id },
    )
  }

  @Test
  fun deferredAutoLockPendingFlagTracksQueuedStateInVaultUiState() {
    val defaultState = VaultUiState()
    assertFalse(defaultState.deferredAutoLockPending)

    val deferredState = defaultState.copy(deferredAutoLockPending = true)
    assertTrue(deferredState.deferredAutoLockPending)
  }

  @Test
  fun themeEngineResolvesLightDarkSystemAndDynamicColorAcrossApiLevels() {
    assertFalse(VaultThemeEngine.resolveIsDarkTheme(ThemeMode.SYSTEM, systemIsDark = false))
    assertTrue(VaultThemeEngine.resolveIsDarkTheme(ThemeMode.SYSTEM, systemIsDark = true))
    assertFalse(VaultThemeEngine.resolveIsDarkTheme(ThemeMode.LIGHT, systemIsDark = true))
    assertTrue(VaultThemeEngine.resolveIsDarkTheme(ThemeMode.DARK, systemIsDark = false))

    val lightSpecPreS = VaultThemeEngine.resolveThemeSpec(
      themeMode = ThemeMode.LIGHT,
      systemIsDark = true,
      dynamicColor = true,
      sdkInt = Build.VERSION_CODES.R,
    )
    assertFalse(lightSpecPreS.isDark)
    assertFalse(lightSpecPreS.usesDynamicColor)
    assertFalse(lightSpecPreS.semanticColors.isDark)

    val darkSpecOnS = VaultThemeEngine.resolveThemeSpec(
      themeMode = ThemeMode.DARK,
      systemIsDark = false,
      dynamicColor = true,
      sdkInt = Build.VERSION_CODES.S,
    )
    assertTrue(darkSpecOnS.isDark)
    assertTrue(darkSpecOnS.usesDynamicColor)
    assertTrue(darkSpecOnS.semanticColors.isDark)
  }

  @Test
  fun themeEngineMaintainsWcagReadabilityForTextStatusBadgesAndProgressBarsInLightAndDark() {
    listOf(false, true).forEach { isDark ->
      val scheme = VaultThemeEngine.colorSchemeFor(isDark)
      val semantic = VaultThemeEngine.semanticColorsFor(isDark)
      val label = if (isDark) "Dark" else "Light"

      // Core text readability on background, surface, and card containers (WCAG AA >= 4.5:1)
      assertTrue("$label onBackground contrast", VaultThemeEngine.contrastRatio(scheme.onBackground, scheme.background) >= 4.5)
      assertTrue("$label onSurface contrast", VaultThemeEngine.contrastRatio(scheme.onSurface, scheme.surface) >= 4.5)
      assertTrue("$label onSurfaceVariant on card contrast", VaultThemeEngine.contrastRatio(scheme.onSurfaceVariant, scheme.surfaceContainer) >= 4.5)
      assertTrue("$label primary on card contrast", VaultThemeEngine.contrastRatio(scheme.primary, scheme.surfaceContainer) >= 4.5)
      assertTrue("$label progressSurface text contrast", VaultThemeEngine.contrastRatio(semantic.onProgressSurface, semantic.progressSurface) >= 4.5)

      // Status badge container/text readability (WCAG AA >= 4.5:1)
      assertTrue("$label locked badge contrast", VaultThemeEngine.contrastRatio(semantic.onLockedContainer, semantic.lockedContainer) >= 4.5)
      assertTrue("$label unlocked badge contrast", VaultThemeEngine.contrastRatio(semantic.onUnlockedContainer, semantic.unlockedContainer) >= 4.5)
      assertTrue("$label partial badge contrast", VaultThemeEngine.contrastRatio(semantic.onPartialContainer, semantic.partialContainer) >= 4.5)
      assertTrue("$label warning badge contrast", VaultThemeEngine.contrastRatio(semantic.onWarningContainer, semantic.warningContainer) >= 4.5)
      assertTrue("$label danger badge contrast", VaultThemeEngine.contrastRatio(semantic.onDangerContainer, semantic.dangerContainer) >= 4.5)
      assertTrue("$label info badge contrast", VaultThemeEngine.contrastRatio(semantic.onInfoContainer, semantic.infoContainer) >= 4.5)

      // Progress bar consistency: track is distinct from card/strip surface and indicators >= 3.0:1 vs track
      assertNotEquals("$label track differs from surfaceContainer", semantic.progressTrack, scheme.surfaceContainer)
      assertNotEquals("$label track differs from progressSurface", semantic.progressTrack, semantic.progressSurface)
      VaultProgressTone.entries.forEach { tone ->
        val palette = VaultThemeEngine.progressPaletteFor(semantic, tone)
        val ratio = VaultThemeEngine.contrastRatio(palette.indicatorColor, palette.trackColor)
        assertTrue("$label $tone indicator-to-track contrast ($ratio) >= 3.0", ratio >= 3.0)
      }
    }
  }
}
