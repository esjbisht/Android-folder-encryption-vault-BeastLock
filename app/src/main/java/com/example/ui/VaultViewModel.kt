package com.example.ui

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.StatFs
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.crypto.DualKeyVaultManager
import com.example.crypto.EncryptionManager
import com.example.crypto.KeystoreManager
import com.example.crypto.UriPermissionStatus
import com.example.data.audit.AuditCategory
import com.example.data.audit.AuditExportFormat
import com.example.data.audit.AuditExportScope
import com.example.data.audit.EncryptedAuditLogRepository
import com.example.data.backup.CloudBackupManager
import com.example.data.backup.DriveAuthExpiredException
import com.example.data.backup.DriveVaultBackupItem
import com.example.data.backup.GoogleDriveAuthHelper
import com.example.data.local.VaultDatabase
import com.example.data.local.VaultFolderEntity
import com.example.data.preferences.PrimaryLockType
import com.example.data.preferences.SecurityPreferencesRepository
import com.example.data.preferences.SecuritySettings
import com.example.data.preferences.StealthGestureMode
import com.example.data.storage.FolderLockerRepository
import com.example.data.storage.MigrationExportFormat
import com.example.data.storage.VaultFileItem
import com.example.data.storage.VaultMaintenanceManager
import com.example.data.telemetry.CloudTelemetryRepository
import com.example.data.telemetry.FirebaseGoogleAuthHelper
import com.example.data.telemetry.IssueCategory
import com.example.data.telemetry.SystemIssueRecorder
import com.example.security.BatteryPowerMonitor
import com.example.security.DevicePowerState
import com.example.security.PanicSensitivity
import com.example.security.QuickTileCommand
import com.example.security.VaultQuickActionBus
import com.example.ui.theme.ThemeMode
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VaultViewModel private constructor(
  application: Application,
  private val keystoreManager: KeystoreManager,
  overrideAuditKey: SecretKey?,
) : AndroidViewModel(application) {

  constructor(application: Application) : this(
    application = application,
    keystoreManager = KeystoreManager(application),
    overrideAuditKey = null,
  )

  private val database = VaultDatabase.getInstance(application)
  private val vaultDao = database.vaultDao()
  private val auditLogRepo = EncryptedAuditLogRepository(application, keystoreManager, overrideTestKey = overrideAuditKey)
  private val prefsRepo = SecurityPreferencesRepository(application, keystoreManager)
  private val dualKeyManager = DualKeyVaultManager(application)
  private val encryptionManager = EncryptionManager(
    context = application,
    keystoreManager = keystoreManager,
    dualKeyVaultManager = dualKeyManager,
    externalTreeUriProvider = { _uiState.value.securitySettings.dualKeyExternalTreeUri },
  )
  private val issueRecorder = SystemIssueRecorder(application, vaultDao)
  private val cloudTelemetryRepo: CloudTelemetryRepository? = runCatching {
    CloudTelemetryRepository(application)
  }.getOrNull()
  private val folderRepo = FolderLockerRepository(
    context = application,
    vaultDao = vaultDao,
    auditLogRepo = auditLogRepo,
    dualKeyVaultManager = dualKeyManager,
    externalTreeUriProvider = { _uiState.value.securitySettings.dualKeyExternalTreeUri },
    encryptionManager = encryptionManager,
  )
  private val cloudBackupManager = CloudBackupManager(application, vaultDao, prefsRepo, auditLogRepo)
  private val maintenanceManager = VaultMaintenanceManager(application, vaultDao, auditLogRepo)
  private val batteryMonitor = BatteryPowerMonitor(application)

  private var sessionDek: SecretKey? = null
  private var driveAccessToken: String? = null
  private var backgroundedAtMillis: Long = 0L
  private var autoLockJob: Job? = null
  private var migrationJob: Job? = null
  private var integrityScanJob: Job? = null
  private var cloudReportsJob: Job? = null
  private var firebaseAuthListener: FirebaseAuth.AuthStateListener? = null
  private var initialDualKeyVerifyDone: Boolean = false
  private var lastBlockingState: Boolean = false
  private val idleResetSignal = MutableSharedFlow<Long>(
    replay = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )
  @Volatile
  private var idleSequence: Long = 0L
  @Volatile
  private var isAppInForeground: Boolean = true

  private val _uiState = MutableStateFlow(
    VaultUiState(
      biometricAvailability = keystoreManager.checkBiometricAvailability(),
      devicePowerState = batteryMonitor.powerState.value,
    ),
  )
  val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

  private val _panicEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
  val panicEvents: SharedFlow<Boolean> = _panicEvents.asSharedFlow()

  private val _driveReauthRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val driveReauthRequired: SharedFlow<Unit> = _driveReauthRequired.asSharedFlow()

  init {
    SystemIssueRecorder.installUncaughtCrashHook(application)
    batteryMonitor.start()
    viewModelScope.launch {
      auditLogRepo.loadOrRefresh()
      issueRecorder.ingestPendingCrashMarker()
    }
    viewModelScope.launch {
      issueRecorder.observeIssues().collect { issues ->
        _uiState.update { it.copy(systemIssues = issues) }
      }
    }
    setupFirebaseAuthObserver()
    viewModelScope.launch {
      VaultQuickActionBus.commands.collect { command ->
        when (command) {
          QuickTileCommand.LOCK_VAULT -> lockVaultNow(source = "QUICK_TILE")
          QuickTileCommand.PANIC_MODE -> triggerPanicLock(source = "QUICK_TILE")
        }
      }
    }
    viewModelScope.launch {
      combine(
        prefsRepo.securitySettings,
        folderRepo.observeFolders(),
        auditLogRepo.snapshot,
        cloudBackupManager.observeBackupRecords(),
        batteryMonitor.powerState,
      ) { settings, folders, auditSnap, backups, powerState ->
        Quintuple(settings, folders, auditSnap, backups, powerState)
      }.collect { (settings, folders, auditSnap, backups, powerState) ->
        val isBlockingNow = settings.batterySaverModeEnabled && powerState.isBatterySaverPaused
        val recoveredFromPause = lastBlockingState && !isBlockingNow
        lastBlockingState = isBlockingNow

        val isUnlocked = settings.isVaultInitialized && sessionDek != null
        VaultQuickActionBus.updateVaultState(
          unlocked = isUnlocked,
          panicExitToHome = settings.panicExitToDeviceHome,
          context = getApplication(),
        )

        _uiState.update { current ->
          val nextSession = when {
            !settings.isVaultInitialized -> VaultSessionState.NeedsOnboarding
            sessionDek != null -> VaultSessionState.Unlocked
            else -> VaultSessionState.Locked
          }
          current.copy(
            sessionState = nextSession,
            securitySettings = settings,
            biometricAvailability = keystoreManager.checkBiometricAvailability(),
            devicePowerState = powerState,
            folders = folders,
            auditLogSnapshot = auditSnap,
            backupRecords = backups,
            deviceStorageInfo = readDeviceStorageInfo(),
          )
        }

        if (settings.isVaultInitialized && !initialDualKeyVerifyDone) {
          initialDualKeyVerifyDone = true
          verifyDualKeyVaultInternal(settings.dualKeyExternalTreeUri)
        }

        if (isUnlocked) {
          refreshFolderPermissions(folders)
        }

        if (recoveredFromPause) {
          resumeBatteryPausedWorkIfReady(settings, powerState)
        }
      }
    }
    viewModelScope.launch {
      idleResetSignal.collectLatest {
        val settings = _uiState.value.securitySettings
        if (!isAppInForeground || sessionDek == null || !settings.idleTimeoutEnabled || settings.idleTimeoutSeconds <= 0) {
          return@collectLatest
        }
        var remainingMs = settings.idleTimeoutSeconds.toLong() * 1000L
        while (remainingMs > 0L) {
          val stepMs = remainingMs.coerceAtMost(5_000L)
          delay(stepMs)
          val latestSettings = _uiState.value.securitySettings
          if (!isAppInForeground || sessionDek == null || !latestSettings.idleTimeoutEnabled || latestSettings.idleTimeoutSeconds <= 0) {
            return@collectLatest
          }
          if (isBusyWithForegroundWork()) {
            remainingMs = latestSettings.idleTimeoutSeconds.toLong() * 1000L
          } else {
            remainingMs -= stepMs
          }
        }
        performIdleTimeoutLock(_uiState.value.securitySettings.idleTimeoutSeconds)
      }
    }
  }

  override fun onCleared() {
    batteryMonitor.stop()
    cloudReportsJob?.cancel()
    runCatching {
      firebaseAuthListener?.let { Firebase.auth.removeAuthStateListener(it) }
    }
    super.onCleared()
  }

  fun completeOnboarding(
    pin: String,
    enableBiometrics: Boolean,
    question1: String,
    answer1: String,
    question2: String,
    answer2: String,
    patternNodes: List<Int>? = null,
  ) {
    val resolvedType = if (pin.length == 4) PrimaryLockType.PIN_4 else PrimaryLockType.PIN_6
    completeOnboardingWithChoice(
      lockType = resolvedType,
      pin = pin,
      patternNodes = patternNodes,
      enableBiometrics = enableBiometrics,
      question1 = question1,
      answer1 = answer1,
      question2 = question2,
      answer2 = answer2,
      shareCustomQuestionSuggestions = false,
    )
  }

  fun completeOnboardingWithChoice(
    lockType: PrimaryLockType,
    pin: String?,
    patternNodes: List<Int>?,
    enableBiometrics: Boolean,
    question1: String,
    answer1: String,
    question2: String,
    answer2: String,
    shareCustomQuestionSuggestions: Boolean = true,
    customQuestionsToQueue: List<String> = emptyList(),
    externalDualKeyUri: Uri? = null,
  ) {
    viewModelScope.launch {
      runCatching {
        val dek = prefsRepo.initializeVaultWithChoice(
          lockType = lockType,
          pin = pin,
          patternNodes = patternNodes,
          enableBiometrics = enableBiometrics,
          question1 = question1,
          answer1 = answer1,
          question2 = question2,
          answer2 = answer2,
          shareCustomQuestionSuggestions = shareCustomQuestionSuggestions,
        )
        if (externalDualKeyUri != null) {
          runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
              externalDualKeyUri,
              android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
          }
          prefsRepo.setDualKeyExternalTreeUri(externalDualKeyUri.toString())
        }
        if (shareCustomQuestionSuggestions && customQuestionsToQueue.isNotEmpty()) {
          prefsRepo.queueCustomQuestionsForSync(customQuestionsToQueue)
        }
        sessionDek = dek
        folderRepo.migrateLegacyPrivateVaultStorage(dek)
        syncDualKeyVaultInternal(dek)
        flushQueuedCustomQuestionsIfSignedIn()

        auditLogRepo.recordEvent(
          action = "VAULT_CREATED",
          folderName = "BeastLock",
          details = "Initialized 256-bit AES vault (${lockType.label}, Biometrics=${if (enableBiometrics) "ON" else "OFF"}, Dual Key Recovery active)",
          category = AuditCategory.ACCESS_EVENT,
        )
        _uiState.update {
          it.copy(
            sessionState = VaultSessionState.Unlocked,
            securitySettings = it.securitySettings.copy(isVaultInitialized = true),
            authError = null,
            statusMessage = "Vault ready (${lockType.label}) • Dual hidden key mirror synced",
          )
        }
        resetIdleTimer()
      }.onFailure { err ->
        issueRecorder.recordIssueAsync(
          category = IssueCategory.CRYPTO_ERROR,
          errorCode = "VAULT_INIT_FAILED",
          rawSummary = err.message ?: "Vault initialization failed",
        )
        _uiState.update { it.copy(errorMessage = err.message ?: "Vault initialization failed") }
      }
    }
  }

  fun unlockWithPin(pin: String) {
    viewModelScope.launch {
      val dek = runCatching { prefsRepo.unlockWithPin(pin) }.getOrNull()
      if (dek != null) {
        onSessionUnlocked(dek, authAction = "AUTH_PIN", authDetails = "Unlocked vault using PBKDF2 Master PIN")
      } else {
        val totalAttempts = _uiState.value.securitySettings.failedAttempts + 1
        auditLogRepo.recordEvent(
          action = "AUTH_FAILED",
          folderName = "BeastLock Gate",
          details = "Invalid Master PIN entered (attempt #$totalAttempts)",
          category = AuditCategory.FAILED_ATTEMPT,
          isWarning = true,
        )
        _uiState.update {
          it.copy(authError = "Incorrect Vault PIN. Try again or use recovery questions.")
        }
      }
    }
  }

  fun unlockWithPattern(patternNodes: List<Int>) {
    viewModelScope.launch {
      val dek = runCatching { prefsRepo.unlockWithPattern(patternNodes) }.getOrNull()
      if (dek != null) {
        onSessionUnlocked(
          dek,
          authAction = "AUTH_PATTERN",
          authDetails = "Unlocked vault using PBKDF2 3x3 Unlock Pattern",
        )
      } else {
        val totalAttempts = _uiState.value.securitySettings.failedAttempts + 1
        auditLogRepo.recordEvent(
          action = "AUTH_FAILED",
          folderName = "BeastLock Gate",
          details = "Invalid Unlock Pattern entered (attempt #$totalAttempts)",
          category = AuditCategory.FAILED_ATTEMPT,
          isWarning = true,
        )
        _uiState.update {
          it.copy(authError = "Incorrect Unlock Pattern. Try again, switch to PIN, or use recovery questions.")
        }
      }
    }
  }

  suspend fun prepareBiometricKeyCryptoObject(): BiometricPrompt.CryptoObject? =
    prefsRepo.prepareKeystoreUnwrapCipher()?.let { BiometricPrompt.CryptoObject(it) }

  fun onBiometricUnlockSuccess(unlockedCryptoObject: BiometricPrompt.CryptoObject? = null) {
    viewModelScope.launch {
      val dek = runCatching {
        prefsRepo.unlockWithKeystoreCipherAfterBiometric(unlockedCryptoObject?.cipher)
      }.getOrNull()
      if (dek != null) {
        val cryptoDetail = if (unlockedCryptoObject?.cipher != null) {
          "Authenticated via BiometricPrompt CryptoObject & Android Keystore"
        } else {
          "Authenticated via BiometricPrompt & Android Keystore"
        }
        onSessionUnlocked(
          dek,
          authAction = "AUTH_BIOMETRIC",
          authDetails = cryptoDetail,
        )
      } else {
        auditLogRepo.recordEvent(
          action = "AUTH_FAILED",
          folderName = "BeastLock Gate",
          details = "Biometric succeeded but Keystore DEK unwrap failed",
          category = AuditCategory.FAILED_ATTEMPT,
          isWarning = true,
        )
        _uiState.update {
          it.copy(authError = "Keystore key unavailable. Please unlock with your Vault PIN.")
        }
      }
    }
  }

  fun recordFailedBiometricAttempt(reason: String, targetName: String = "BeastLock Gate") {
    viewModelScope.launch {
      auditLogRepo.recordEvent(
        action = "BIOMETRIC_FAILED",
        folderName = targetName,
        details = reason,
        category = AuditCategory.FAILED_ATTEMPT,
        isWarning = true,
      )
    }
  }

  fun recordFolderAccessed(folderId: String) {
    val folder = _uiState.value.folders.find { it.id == folderId } ?: return
    viewModelScope.launch {
      val auditName = if (folder.isStealthHidden) "Stealth Private Vault" else folder.name
      val auditId = if (folder.isStealthHidden) null else folder.id
      auditLogRepo.recordEvent(
        action = "FOLDER_ACCESSED",
        folderName = auditName,
        details = if (folder.isStealthHidden) {
          "Opened encrypted stealth vault folder"
        } else {
          "Opened folder explorer (${folder.fileCount} files, ${if (folder.isLocked) "locked" else "unlocked"})"
        },
        folderId = auditId,
        category = AuditCategory.ACCESS_EVENT,
      )
    }
  }

  private suspend fun onSessionUnlocked(
    dek: SecretKey,
    authAction: String,
    authDetails: String,
  ) {
    sessionDek = dek
    folderRepo.migrateLegacyPrivateVaultStorage(dek)
    val settings = _uiState.value.securitySettings
    val pendingBgMillis = settings.lastBackgroundedAtMillis
    val elapsedSec = if (pendingBgMillis > 0L) {
      ((System.currentTimeMillis() - pendingBgMillis) / 1000L).toInt()
    } else 0

    var catchUpSealedCount = 0
    val pausedByBattery = isBatterySaverBlockingNow()
    if (settings.autoLockEnabled &&
      settings.autoLockReencryptFolders &&
      ((pendingBgMillis > 0L && elapsedSec >= settings.autoLockTimeoutSeconds) || settings.pendingBatteryPausedReencrypt)
    ) {
      if (pausedByBattery) {
        prefsRepo.setPendingBatteryPausedReencrypt(true)
      } else {
        catchUpSealedCount = reencryptAllUnlockedFolders(dek)
        prefsRepo.setPendingBatteryPausedReencrypt(false)
        if (catchUpSealedCount > 0) {
          auditLogRepo.recordEvent(
            action = "AUTO_LOCK_REENCRYPT",
            folderName = "Auto-Lock",
            details = "Re-encrypted $catchUpSealedCount unlocked folder(s) after background timeout",
            category = AuditCategory.CRYPTO_OPERATION,
          )
        }
      }
    }

    val wasAutoLockedNotice = settings.wasAutoLocked
    val wasPanicLockedNotice = settings.wasPanicLocked
    prefsRepo.markAutoLocked(false, "")
    prefsRepo.markPanicLocked(false, "")
    prefsRepo.recordBackgroundedAt(0L)
    VaultQuickActionBus.updateVaultState(
      unlocked = true,
      panicExitToHome = settings.panicExitToDeviceHome,
      context = getApplication(),
    )
    auditLogRepo.recordEvent(
      action = authAction,
      folderName = "BeastLock",
      details = authDetails,
      category = AuditCategory.ACCESS_EVENT,
    )

    val statusMsg = when {
      catchUpSealedCount > 0 -> "Auto-lock sealed $catchUpSealedCount folder(s) after background timeout"
      wasPanicLockedNotice -> "Vault re-authenticated after Panic Mode seal"
      wasAutoLockedNotice -> "Vault authenticated after automatic lock"
      else -> null
    }
    _uiState.update {
      it.copy(
        sessionState = VaultSessionState.Unlocked,
        authError = null,
        statusMessage = statusMsg ?: it.statusMessage,
      )
    }
    resetIdleTimer()
    syncDualKeyVaultInternal(dek)
    flushQueuedCustomQuestionsIfSignedIn()
    if (settings.pendingBatteryPausedAutoSync && !pausedByBattery) {
      resumeBatteryPausedWorkIfReady(settings, batteryMonitor.powerState.value)
    }
    scanOrphanedFiles()
  }

  fun recoverPinWithSecurityQuestions(
    answer1: String,
    answer2: String,
    newPin: String,
  ) {
    val targetType = if (newPin.length == 4) PrimaryLockType.PIN_4 else PrimaryLockType.PIN_6
    recoverWithSecurityQuestions(
      answer1 = answer1,
      answer2 = answer2,
      newPin = newPin,
      newPatternNodes = null,
      newLockType = targetType,
    )
  }

  fun recoverWithSecurityQuestions(
    answer1: String,
    answer2: String,
    newPin: String? = null,
    newPatternNodes: List<Int>? = null,
    newLockType: PrimaryLockType? = null,
  ) {
    viewModelScope.launch {
      val dek = runCatching {
        prefsRepo.recoverWithSecurityQuestions(
          answer1 = answer1,
          answer2 = answer2,
          newPin = newPin,
          newPatternNodes = newPatternNodes,
          newLockType = newLockType,
        )
      }.getOrNull()

      if (dek != null) {
        sessionDek = dek
        folderRepo.migrateLegacyPrivateVaultStorage(dek)
        prefsRepo.markAutoLocked(false, "")
        prefsRepo.markPanicLocked(false, "")
        prefsRepo.recordBackgroundedAt(0L)
        syncDualKeyVaultInternal(dek)
        auditLogRepo.recordEvent(
          action = "PIN_RECOVERED",
          folderName = "BeastLock",
          details = "Primary vault credentials reset using verified security questions (DEK preserved & Dual Vault synced)",
          category = AuditCategory.ACCESS_EVENT,
        )
        _uiState.update {
          it.copy(
            sessionState = VaultSessionState.Unlocked,
            authError = null,
            recoveryError = null,
            statusMessage = "Vault credentials reset and unlocked",
          )
        }
        resetIdleTimer()
      } else {
        auditLogRepo.recordEvent(
          action = "RECOVERY_FAILED",
          folderName = "BeastLock Gate",
          details = "Security question answers did not match stored PBKDF2 verifier",
          category = AuditCategory.FAILED_ATTEMPT,
          isWarning = true,
        )
        _uiState.update {
          it.copy(recoveryError = "Security answers do not match. Check spelling and try again.")
        }
      }
    }
  }

  fun lockVaultNow(source: String = "MANUAL") {
    val capturedDek = sessionDek
    autoLockJob?.cancel()
    autoLockJob = null
    sessionDek = null
    driveAccessToken = null
    cancelIdleTimer()
    VaultQuickActionBus.updateVaultState(
      unlocked = false,
      panicExitToHome = _uiState.value.securitySettings.panicExitToDeviceHome,
      context = getApplication(),
    )
    viewModelScope.launch(NonCancellable) {
      val sealedPrivate = folderRepo.concealAndResealPrivateFolders(capturedDek)
      val details = buildString {
        append(
          if (source == "QUICK_TILE") {
            "Vault locked immediately via Android Quick Settings tile"
          } else {
            "Vault manually locked and session DEK cleared from memory"
          },
        )
        if (sealedPrivate > 0) {
          append(" • Re-encrypted $sealedPrivate stealth folder(s)")
        }
      }
      auditLogRepo.recordEvent(
        action = "VAULT_SEALED",
        folderName = "BeastLock",
        details = details,
        category = AuditCategory.ACCESS_EVENT,
      )
    }
    _uiState.update {
      it.copy(
        sessionState = if (it.securitySettings.isVaultInitialized) {
          VaultSessionState.Locked
        } else {
          VaultSessionState.NeedsOnboarding
        },
        activeFolderFiles = emptyList(),
        securePreview = null,
        pendingRestoreUriRequiringPin = null,
        pendingDriveRestoreItem = null,
        isSelectionMode = false,
        selectedFolderIds = emptySet(),
        isStealthRevealed = false,
        pendingStealthTriggerLabel = null,
        isStealthPickerInFlight = false,
        pendingFolderPickerPreview = null,
        authError = null,
      )
    }
  }

  fun triggerPanicLock(source: String = "SHAKE_GESTURE") {
    val current = _uiState.value
    if (current.sessionState != VaultSessionState.Unlocked) return

    val settings = current.securitySettings
    val capturedDek = sessionDek

    autoLockJob?.cancel()
    autoLockJob = null
    backgroundedAtMillis = 0L
    sessionDek = null
    driveAccessToken = null
    cancelIdleTimer()
    VaultQuickActionBus.updateVaultState(
      unlocked = false,
      panicExitToHome = settings.panicExitToDeviceHome,
      context = getApplication(),
    )

    _uiState.update {
      it.copy(
        sessionState = if (settings.isVaultInitialized) {
          VaultSessionState.Locked
        } else {
          VaultSessionState.NeedsOnboarding
        },
        activeFolderFiles = emptyList(),
        securePreview = null,
        pendingRestoreUriRequiringPin = null,
        pendingDriveRestoreItem = null,
        isSelectionMode = false,
        selectedFolderIds = emptySet(),
        isStealthRevealed = false,
        pendingStealthTriggerLabel = null,
        isStealthPickerInFlight = false,
        pendingFolderPickerPreview = null,
        authError = null,
        recoveryError = null,
      )
    }

    if (source != "QUICK_TILE") {
      _panicEvents.tryEmit(settings.panicExitToDeviceHome)
    }

    viewModelScope.launch(NonCancellable) {
      val pausedByBattery = isBatterySaverBlockingNow()
      val sealedPrivate = folderRepo.concealAndResealPrivateFolders(capturedDek)
      val sealedCount = if (settings.panicReencryptFolders && capturedDek != null && !pausedByBattery) {
        reencryptAllUnlockedFolders(capturedDek) + sealedPrivate
      } else {
        sealedPrivate
      }
      if (settings.panicReencryptFolders && pausedByBattery) {
        prefsRepo.setPendingBatteryPausedReencrypt(true)
      }

      val triggerLabel = when (source) {
        "SETTINGS_TEST" -> "manual panic test"
        "QUICK_TILE" -> "Quick Settings tile"
        else -> "shake gesture"
      }
      val summary = if (sealedCount > 0) {
        "Panic Mode ($triggerLabel) sealed session and re-encrypted $sealedCount folder(s)"
      } else {
        "Panic Mode ($triggerLabel) sealed current session immediately"
      }

      prefsRepo.markPanicLocked(wasPanicLocked = true, summary = summary)
      prefsRepo.recordBackgroundedAt(0L)
      auditLogRepo.recordEvent(
        action = "PANIC_LOCK",
        folderName = "Panic Mode",
        details = summary,
        category = AuditCategory.ACCESS_EVENT,
        isWarning = false,
      )
    }
  }

  fun onUserInteraction() {
    if (isAppInForeground && sessionDek != null) {
      resetIdleTimer()
    }
  }

  private fun resetIdleTimer() {
    idleResetSignal.tryEmit(++idleSequence)
  }

  private fun cancelIdleTimer() {
    idleResetSignal.tryEmit(++idleSequence)
  }

  private fun isBusyWithForegroundWork(): Boolean {
    val state = _uiState.value
    return state.encryptionProgress != null ||
      state.cloudBackupProgress != null ||
      state.migrationProgress != null ||
      state.isAutoLockReencrypting ||
      state.isCleaningOrphans ||
      state.isScanningIntegrity
  }

  fun onAppBackgrounded() {
    isAppInForeground = false
    cancelIdleTimer()

    val stateNow = _uiState.value
    val anyUnsealedPrivate = stateNow.folders.any {
      (it.isStealthHidden || it.isInternalVault) && (!it.isLocked || it.encryptedFileCount < it.fileCount)
    }
    if (!stateNow.isStealthPickerInFlight &&
      (stateNow.isStealthRevealed || stateNow.pendingStealthTriggerLabel != null || anyUnsealedPrivate)
    ) {
      concealPrivateVault(source = "APP_EXIT", immediateReencrypt = true)
    }

    val settings = stateNow.securitySettings
    if (!settings.autoLockEnabled || sessionDek == null) return

    val now = System.currentTimeMillis()
    backgroundedAtMillis = now
    autoLockJob?.cancel()

    autoLockJob = viewModelScope.launch {
      prefsRepo.recordBackgroundedAt(now)
      val timeoutSec = settings.autoLockTimeoutSeconds.coerceAtLeast(0)
      if (timeoutSec > 0) {
        delay(timeoutSec * 1000L)
      }
      performAutoLock(elapsedSeconds = timeoutSec)
    }
  }

  fun onAppForegrounded() {
    isAppInForeground = true
    autoLockJob?.cancel()
    autoLockJob = null

    val settings = _uiState.value.securitySettings
    val bgStart = if (backgroundedAtMillis > 0L) backgroundedAtMillis else settings.lastBackgroundedAtMillis
    backgroundedAtMillis = 0L

    if (!settings.autoLockEnabled) {
      viewModelScope.launch { prefsRepo.recordBackgroundedAt(0L) }
      if (sessionDek != null) resetIdleTimer()
      return
    }

    val dek = sessionDek
    if (dek != null) {
      refreshFolderPermissions()
    }
    if (dek != null && bgStart > 0L && _uiState.value.sessionState == VaultSessionState.Unlocked) {
      val elapsedSec = ((System.currentTimeMillis() - bgStart) / 1000L).toInt()
      if (elapsedSec >= settings.autoLockTimeoutSeconds) {
        viewModelScope.launch {
          performAutoLock(elapsedSeconds = elapsedSec)
        }
      } else {
        viewModelScope.launch { prefsRepo.recordBackgroundedAt(0L) }
        resetIdleTimer()
      }
    } else if (dek != null) {
      resetIdleTimer()
    }
  }

  private suspend fun performAutoLock(elapsedSeconds: Int) {
    val dek = sessionDek ?: return
    val settings = _uiState.value.securitySettings
    cancelIdleTimer()

    withContext(NonCancellable) {
      _uiState.update { it.copy(isAutoLockReencrypting = true) }

      val pausedByBattery = isBatterySaverBlockingNow()
      val sealedPrivate = folderRepo.concealAndResealPrivateFolders(dek)
      val reencryptedCount = if (settings.autoLockReencryptFolders && !pausedByBattery) {
        reencryptAllUnlockedFolders(dek) + sealedPrivate
      } else {
        sealedPrivate
      }

      if (settings.autoLockReencryptFolders && pausedByBattery) {
        prefsRepo.setPendingBatteryPausedReencrypt(true)
        val pState = batteryMonitor.powerState.value
        auditLogRepo.recordEvent(
          action = "BATTERY_SAVER_PAUSED",
          folderName = "Auto-Lock Re-Encryption",
          details = "Session locked; heavy background folder re-encryption paused (${pState.batteryPercent}%${if (pState.isSystemPowerSaveMode) ", Power Save active" else ""}) until charged > 20%",
          category = AuditCategory.CRYPTO_OPERATION,
        )
      }

      val durationText = formatDurationLabel(elapsedSeconds)
      val summary = when {
        reencryptedCount > 0 -> "Re-encrypted $reencryptedCount folder(s) after $durationText in background"
        settings.autoLockReencryptFolders && pausedByBattery ->
          "Vault sealed after $durationText (folder re-encryption paused by Battery Saver)"
        else -> "Vault sealed after $durationText in background"
      }

      auditLogRepo.recordEvent(
        action = "AUTO_LOCK",
        folderName = "Auto-Lock",
        details = summary,
        category = AuditCategory.ACCESS_EVENT,
      )
      prefsRepo.markAutoLocked(wasAutoLocked = true, summary = summary)

      sessionDek = null
      driveAccessToken = null
      VaultQuickActionBus.updateVaultState(
        unlocked = false,
        panicExitToHome = settings.panicExitToDeviceHome,
        context = getApplication(),
      )
      _uiState.update {
        it.copy(
          isAutoLockReencrypting = false,
          sessionState = if (settings.isVaultInitialized || it.sessionState == VaultSessionState.Unlocked) {
            VaultSessionState.Locked
          } else {
            VaultSessionState.NeedsOnboarding
          },
          activeFolderFiles = emptyList(),
          securePreview = null,
          pendingRestoreUriRequiringPin = null,
          pendingDriveRestoreItem = null,
          isSelectionMode = false,
          selectedFolderIds = emptySet(),
          isStealthRevealed = false,
          pendingStealthTriggerLabel = null,
          isStealthPickerInFlight = false,
          pendingFolderPickerPreview = null,
          authError = null,
        )
      }
    }
  }

  private suspend fun performIdleTimeoutLock(timeoutSeconds: Int) {
    val dek = sessionDek ?: return
    val settings = _uiState.value.securitySettings
    if (!isAppInForeground || !settings.idleTimeoutEnabled) return

    withContext(NonCancellable) {
      _uiState.update { it.copy(isAutoLockReencrypting = true) }

      val pausedByBattery = isBatterySaverBlockingNow()
      val sealedPrivate = folderRepo.concealAndResealPrivateFolders(dek)
      val reencryptedCount = if (settings.autoLockReencryptFolders && !pausedByBattery) {
        reencryptAllUnlockedFolders(dek) + sealedPrivate
      } else {
        sealedPrivate
      }

      if (settings.autoLockReencryptFolders && pausedByBattery) {
        prefsRepo.setPendingBatteryPausedReencrypt(true)
        val pState = batteryMonitor.powerState.value
        auditLogRepo.recordEvent(
          action = "BATTERY_SAVER_PAUSED",
          folderName = "Idle Timeout Re-Encryption",
          details = "Session locked due to inactivity; heavy folder re-encryption paused (${pState.batteryPercent}%${if (pState.isSystemPowerSaveMode) ", Power Save active" else ""}) until charged > 20%",
          category = AuditCategory.CRYPTO_OPERATION,
        )
      }

      val durationText = formatDurationLabel(timeoutSeconds)
      val summary = when {
        reencryptedCount > 0 -> "Re-encrypted $reencryptedCount folder(s) after $durationText of inactivity"
        settings.autoLockReencryptFolders && pausedByBattery ->
          "Vault sealed after $durationText of inactivity (folder re-encryption paused by Battery Saver)"
        else -> "Vault sealed after $durationText of inactivity"
      }

      auditLogRepo.recordEvent(
        action = "IDLE_LOCK",
        folderName = "Idle Timeout",
        details = summary,
        category = AuditCategory.ACCESS_EVENT,
      )
      prefsRepo.markAutoLocked(wasAutoLocked = true, summary = summary)

      sessionDek = null
      driveAccessToken = null
      cancelIdleTimer()
      VaultQuickActionBus.updateVaultState(
        unlocked = false,
        panicExitToHome = settings.panicExitToDeviceHome,
        context = getApplication(),
      )
      _uiState.update {
        it.copy(
          isAutoLockReencrypting = false,
          sessionState = if (settings.isVaultInitialized || it.sessionState == VaultSessionState.Unlocked) {
            VaultSessionState.Locked
          } else {
            VaultSessionState.NeedsOnboarding
          },
          activeFolderFiles = emptyList(),
          securePreview = null,
          pendingRestoreUriRequiringPin = null,
          pendingDriveRestoreItem = null,
          isSelectionMode = false,
          selectedFolderIds = emptySet(),
          isStealthRevealed = false,
          pendingStealthTriggerLabel = null,
          isStealthPickerInFlight = false,
          pendingFolderPickerPreview = null,
          authError = null,
        )
      }
    }
  }

  private suspend fun reencryptAllUnlockedFolders(dek: SecretKey): Int {
    val unlockedFolders = vaultDao.getAllFoldersOnce()
      .filter { !it.isLocked && it.fileCount > 0 }
    var sealed = 0
    for (folder in unlockedFolders) {
      folderRepo.lockFolder(folder.id, dek).onSuccess { sealed++ }
    }
    return sealed
  }

  private fun formatDurationLabel(seconds: Int): String = when {
    seconds <= 0 -> "immediate backgrounding"
    seconds < 60 -> "${seconds}s"
    seconds % 60 == 0 -> "${seconds / 60}m"
    else -> "${seconds / 60}m ${seconds % 60}s"
  }

  fun setFilter(filter: FolderFilter) {
    _uiState.update { it.copy(folderFilter = filter) }
  }

  fun setSearchQuery(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
    val trimmed = query.trim()
    if (sessionDek != null && trimmed.length >= 4 && trimmed.startsWith("#")) {
      viewModelScope.launch {
        if (prefsRepo.verifyStealthAccessCode(trimmed)) {
          _uiState.update {
            it.copy(
              searchQuery = "",
              folderFilter = FolderFilter.ALL,
            )
          }
          requestRevealStealthVault(source = "SEARCH_CODE")
        }
      }
    }
  }

  fun requestRevealStealthVault(source: String = "GESTURE") {
    if (sessionDek == null) return
    _uiState.update { it.copy(pendingStealthTriggerLabel = source) }
  }

  fun dismissStealthAuthChallenge() {
    _uiState.update { it.copy(pendingStealthTriggerLabel = null) }
  }

  fun onStealthAuthVerified() {
    _uiState.update {
      it.copy(
        isStealthRevealed = true,
        pendingStealthTriggerLabel = null,
      )
    }
    viewModelScope.launch {
      auditLogRepo.recordEvent(
        action = "STEALTH_VAULT_ACCESSED",
        folderName = "Stealth Private Vault",
        details = "Authenticated access to hidden Stealth Private Vault drawer",
        category = AuditCategory.ACCESS_EVENT,
      )
    }
  }

  fun markStealthPickerInFlight(inFlight: Boolean) {
    _uiState.update { it.copy(isStealthPickerInFlight = inFlight) }
  }

  fun concealPrivateVault(
    source: String = "EXIT",
    immediateReencrypt: Boolean = true,
  ) {
    val dek = sessionDek
    _uiState.update { current ->
      val hasStealthActiveFiles = current.activeFolderFiles.any { item ->
        current.folders.any { f -> f.id == item.folderId && f.isStealthHidden }
      }
      current.copy(
        isStealthRevealed = false,
        pendingStealthTriggerLabel = null,
        isStealthPickerInFlight = false,
        securePreview = if (current.isStealthRevealed || hasStealthActiveFiles) null else current.securePreview,
        activeFolderFiles = if (hasStealthActiveFiles) emptyList() else current.activeFolderFiles,
      )
    }
    if (immediateReencrypt && dek != null) {
      viewModelScope.launch(NonCancellable) {
        val sealedCount = folderRepo.concealAndResealPrivateFolders(dek)
        if (sealedCount > 0) {
          auditLogRepo.recordEvent(
            action = "STEALTH_VAULT_CONCEALED",
            folderName = "Stealth Private Vault",
            details = "Concealed drawer and re-encrypted $sealedCount private folder(s) ($source)",
            category = AuditCategory.CRYPTO_OPERATION,
          )
        }
      }
    }
  }

  private fun refreshFolderPermissions(folders: List<VaultFolderEntity> = _uiState.value.folders) {
    viewModelScope.launch {
      val statuses = folderRepo.auditFolderPermissions(folders)
      _uiState.update { it.copy(folderPermissionStatuses = statuses) }
    }
  }

  fun previewPickedDeviceFolder(treeUri: Uri) {
    val defaultObfuscate = _uiState.value.securitySettings.obfuscateFilenamesDefault
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          pendingFolderPickerPreview = PendingFolderPickerPreview(
            treeUri = treeUri,
            displayName = "Scanning folder...",
            permissionStatus = UriPermissionStatus.TRANSIENT,
            plaintextFileCount = 0,
            encryptedFileCount = 0,
            totalBytes = 0L,
            obfuscateFilenames = defaultObfuscate,
            requireAuthOnOpen = true,
            isScanning = true,
          ),
        )
      }
      folderRepo.inspectPickedSafFolder(treeUri)
        .onSuccess { inspection ->
          _uiState.update { current ->
            val existingPreview = current.pendingFolderPickerPreview
            current.copy(
              pendingFolderPickerPreview = PendingFolderPickerPreview(
                treeUri = inspection.treeUri,
                displayName = inspection.displayName,
                permissionStatus = inspection.permissionStatus,
                plaintextFileCount = inspection.plaintextCount,
                encryptedFileCount = inspection.encryptedCount,
                totalBytes = inspection.totalBytes,
                existingFolderId = inspection.existingFolderId,
                obfuscateFilenames = existingPreview?.obfuscateFilenames ?: defaultObfuscate,
                requireAuthOnOpen = existingPreview?.requireAuthOnOpen ?: true,
                isScanning = false,
              ),
            )
          }
        }
        .onFailure { err ->
          issueRecorder.recordIssueAsync(
            category = IssueCategory.SAF_STORAGE,
            errorCode = "INSPECT_PICKED_FOLDER_FAILED",
            rawSummary = err.message ?: "Failed to inspect picked folder",
          )
          _uiState.update {
            it.copy(
              pendingFolderPickerPreview = null,
              errorMessage = err.message ?: "Unable to read selected folder",
            )
          }
        }
    }
  }

  fun updateFolderPickerPreviewOptions(
    obfuscateFilenames: Boolean,
    requireAuthOnOpen: Boolean,
  ) {
    _uiState.update { current ->
      val preview = current.pendingFolderPickerPreview ?: return@update current
      current.copy(
        pendingFolderPickerPreview = preview.copy(
          obfuscateFilenames = obfuscateFilenames,
          requireAuthOnOpen = requireAuthOnOpen,
        ),
      )
    }
  }

  fun dismissFolderPickerPreview() {
    _uiState.update { it.copy(pendingFolderPickerPreview = null) }
  }

  fun confirmFolderPickerPreview(encryptNow: Boolean) {
    val preview = _uiState.value.pendingFolderPickerPreview ?: return
    if (preview.isScanning) return
    _uiState.update { it.copy(pendingFolderPickerPreview = null) }
    viewModelScope.launch {
      folderRepo.registerSafFolder(
        treeUri = preview.treeUri,
        obfuscateDefault = preview.obfuscateFilenames,
        requireBioDefault = preview.requireAuthOnOpen,
      ).onSuccess { registered ->
        folderRepo.updateFolderSecurityOptions(
          folderId = registered.id,
          requireBiometricPerUnlock = preview.requireAuthOnOpen,
          obfuscateFilenames = preview.obfuscateFilenames,
          requireAuthOnOpen = preview.requireAuthOnOpen,
        )
        val dek = sessionDek
        if (encryptNow && dek != null && registered.fileCount > registered.encryptedFileCount) {
          folderRepo.lockFolder(registered.id, dek) { progress ->
            _uiState.update { it.copy(encryptionProgress = progress) }
          }.onSuccess { lockedFolder ->
            syncDualKeyVaultInternal(dek)
            refreshFolderPermissions()
            _uiState.update {
              it.copy(
                encryptionProgress = null,
                statusMessage = "Encrypted & locked \"${lockedFolder.name}\" (${lockedFolder.encryptedFileCount} files secured • ${preview.permissionStatus.label})",
              )
            }
          }.onFailure { err ->
            issueRecorder.recordIssueAsync(
              category = IssueCategory.CRYPTO_ERROR,
              errorCode = "PICKER_LOCK_FAILED",
              rawSummary = err.message ?: "Encryption after folder selection failed",
            )
            _uiState.update {
              it.copy(
                encryptionProgress = null,
                errorMessage = err.message ?: "Registered folder, but encryption failed",
              )
            }
          }
        } else {
          syncDualKeyVaultInternal(dek)
          refreshFolderPermissions()
          val actionLabel = when {
            preview.existingFolderId != null ->
              "Updated \"${registered.name}\" (${preview.permissionStatus.label})"
            encryptNow && registered.encryptedFileCount > 0 ->
              "Registered \"${registered.name}\" (${registered.encryptedFileCount} encrypted file(s) detected)"
            else ->
              "Registered \"${registered.name}\" (${preview.permissionStatus.label})"
          }
          _uiState.update { it.copy(statusMessage = actionLabel) }
        }
      }.onFailure { err ->
        issueRecorder.recordIssueAsync(
          category = IssueCategory.SAF_STORAGE,
          errorCode = "CONFIRM_PICKED_FOLDER_FAILED",
          rawSummary = err.message ?: "Failed to register selected folder",
        )
        _uiState.update {
          it.copy(errorMessage = err.message ?: "Failed to register selected folder")
        }
      }
    }
  }

  fun regrantDeviceFolderPermission(folderId: String, treeUri: Uri) {
    viewModelScope.launch {
      folderRepo.regrantSafFolderPermission(folderId, treeUri)
        .onSuccess { updated ->
          refreshFolderPermissions()
          _uiState.update {
            it.copy(statusMessage = "Restored persistent access to \"${updated.name}\"")
          }
        }
        .onFailure { err ->
          issueRecorder.recordIssueAsync(
            category = IssueCategory.SAF_STORAGE,
            errorCode = "REGRANT_PERMISSION_FAILED",
            rawSummary = err.message ?: "Failed to restore folder permission",
          )
          _uiState.update {
            it.copy(errorMessage = err.message ?: "Could not restore folder access")
          }
        }
    }
  }

  fun addDeviceFolder(treeUri: Uri) {
    viewModelScope.launch {
      val obfuscate = _uiState.value.securitySettings.obfuscateFilenamesDefault
      folderRepo.registerSafFolder(treeUri, obfuscateDefault = obfuscate, requireBioDefault = true)
        .onSuccess { folder ->
          val dek = sessionDek
          if (dek != null && folder.fileCount > 0 && !folder.isLocked) {
            folderRepo.lockFolder(folder.id, dek) { progress ->
              _uiState.update { it.copy(encryptionProgress = progress) }
            }.onSuccess { lockedFolder ->
              syncDualKeyVaultInternal(dek)
              refreshFolderPermissions()
              _uiState.update {
                it.copy(
                  encryptionProgress = null,
                  statusMessage = "Added & auto-locked \"${lockedFolder.name}\" (${lockedFolder.encryptedFileCount} files secured)",
                )
              }
            }.onFailure { err ->
              issueRecorder.recordIssueAsync(
                category = IssueCategory.CRYPTO_ERROR,
                errorCode = "AUTO_LOCK_ON_ADD_FAILED",
                rawSummary = err.message ?: "Auto-lock after adding device folder failed",
              )
              _uiState.update {
                it.copy(
                  encryptionProgress = null,
                  errorMessage = err.message ?: "Added folder, but auto-lock encryption failed",
                )
              }
            }
          } else {
            syncDualKeyVaultInternal(dek)
            refreshFolderPermissions()
            _uiState.update {
              it.copy(statusMessage = "Protected \"${folder.name}\" (Authentication required on open)")
            }
          }
        }
        .onFailure { err ->
          issueRecorder.recordIssueAsync(
            category = IssueCategory.SAF_STORAGE,
            errorCode = "ADD_DEVICE_FOLDER_FAILED",
            rawSummary = err.message ?: "Failed to register SAF device folder",
          )
          _uiState.update { it.copy(errorMessage = err.message ?: "Failed to register folder") }
        }
    }
  }

  fun createPrivateFolder(name: String) {
    viewModelScope.launch {
      val obfuscate = _uiState.value.securitySettings.obfuscateFilenamesDefault
      folderRepo.createInternalVaultFolder(
        folderName = name,
        obfuscateDefault = obfuscate,
        requireBioDefault = true,
        dek = sessionDek,
        stealthHidden = true,
      )
        .onSuccess {
          syncDualKeyVaultInternal(sessionDek)
          _uiState.update {
            it.copy(
              isStealthRevealed = true,
              statusMessage = "Created always-encrypted hidden folder",
            )
          }
        }
        .onFailure { err ->
          issueRecorder.recordIssueAsync(
            category = IssueCategory.SAF_STORAGE,
            errorCode = "CREATE_PRIVATE_FOLDER_FAILED",
            rawSummary = err.message ?: "Failed to create private folder",
          )
          _uiState.update { it.copy(errorMessage = err.message ?: "Failed to create folder") }
        }
    }
  }

  fun importFiles(
    folderId: String,
    uris: List<Uri>,
    moveOriginals: Boolean = true,
  ) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
      folderRepo.importFilesWithReport(folderId, uris, sessionDek, moveOriginals = moveOriginals)
        .onSuccess { res ->
          loadFolderFiles(folderId)
          val msg = when {
            res.importedCount == 0 -> "No files were imported"
            moveOriginals && res.movedWithoutCopyCount == res.importedCount ->
              "Added ${res.importedCount} file(s) (moved without copies)"
            moveOriginals && res.movedWithoutCopyCount > 0 ->
              "Added ${res.importedCount} file(s) (${res.movedWithoutCopyCount} moved, ${res.readOnlySourceCount} from read-only picker)"
            else -> "Added ${res.importedCount} file(s)"
          }
          _uiState.update { it.copy(statusMessage = msg) }
          val dek = sessionDek
          if (dek != null && res.importedCount > 0) {
            maybeRunAutoCloudSync(dek, onlyIfFolderSyncEnabledId = folderId)
          }
        }
        .onFailure { err ->
          _uiState.update { it.copy(errorMessage = err.message ?: "File import failed") }
        }
    }
  }

  fun loadFolderFiles(folderId: String) {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingFiles = true) }
      folderRepo.refreshFolderMetrics(folderId, sessionDek)
      folderRepo.listFolderFiles(folderId, sessionDek)
        .onSuccess { files ->
          _uiState.update { it.copy(activeFolderFiles = files, isLoadingFiles = false) }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isLoadingFiles = false,
              errorMessage = err.message ?: "Could not read folder contents",
            )
          }
        }
    }
  }

  fun requestLockFolderWithSelection(folderId: String, reloadDetail: Boolean = false) {
    val folder = _uiState.value.folders.find { it.id == folderId } ?: return
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          selectiveLockPrompt = SelectiveLockPromptState(
            folder = folder,
            isLoading = true,
            reloadDetailAfterLock = reloadDetail,
          ),
        )
      }
      folderRepo.listFolderFiles(folderId, sessionDek)
        .onSuccess { files ->
          val plaintext = files.filter { !it.isEncrypted }
          val encryptedCount = files.count { it.isEncrypted }
          if (plaintext.isEmpty()) {
            _uiState.update { it.copy(selectiveLockPrompt = null) }
            if (!folder.isLocked) {
              lockFolder(folderId, reloadDetail = reloadDetail)
            } else {
              _uiState.update {
                it.copy(statusMessage = "All files in \"${folder.name}\" are already encrypted")
              }
            }
          } else {
            _uiState.update {
              it.copy(
                selectiveLockPrompt = SelectiveLockPromptState(
                  folder = folder,
                  plaintextFiles = plaintext,
                  alreadyEncryptedCount = encryptedCount,
                  isLoading = false,
                  reloadDetailAfterLock = reloadDetail,
                ),
              )
            }
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              selectiveLockPrompt = null,
              errorMessage = err.message ?: "Could not inspect folder contents",
            )
          }
        }
    }
  }

  fun dismissSelectiveLockPrompt() {
    _uiState.update { it.copy(selectiveLockPrompt = null) }
  }

  fun lockFolder(
    folderId: String,
    reloadDetail: Boolean = false,
    selectedFileIds: Set<String>? = null,
  ) {
    val dek = sessionDek ?: return
    viewModelScope.launch {
      _uiState.update { it.copy(selectiveLockPrompt = null) }
      folderRepo.lockFolder(folderId, dek, selectedFileIds = selectedFileIds) { progress ->
        _uiState.update { it.copy(encryptionProgress = progress) }
      }.onSuccess { updated ->
        val remainingPlain = (updated.fileCount - updated.encryptedFileCount).coerceAtLeast(0)
        val msg = when {
          selectedFileIds != null && remainingPlain > 0 ->
            "Encrypted ${updated.encryptedFileCount}/${updated.fileCount} file(s) in \"${updated.name}\" ($remainingPlain kept unencrypted)"
          else ->
            "Locked \"${updated.name}\" with AES-256-GCM"
        }
        _uiState.update {
          it.copy(
            encryptionProgress = null,
            statusMessage = msg,
          )
        }
        if (reloadDetail) loadFolderFiles(folderId)
        syncDualKeyVaultInternal(dek)
        if (updated.autoCloudBackupSync) {
          maybeRunAutoCloudSync(dek)
        }
      }.onFailure { err ->
        issueRecorder.recordIssueAsync(
          category = IssueCategory.CRYPTO_ERROR,
          errorCode = "LOCK_FOLDER_FAILED",
          rawSummary = err.message ?: "Encryption failed",
        )
        _uiState.update {
          it.copy(
            encryptionProgress = null,
            errorMessage = err.message ?: "Encryption failed",
          )
        }
      }
    }
  }

  fun unlockFolder(folderId: String, reloadDetail: Boolean = false) {
    val dek = sessionDek ?: return
    viewModelScope.launch {
      folderRepo.unlockFolder(folderId, dek) { progress ->
        _uiState.update { it.copy(encryptionProgress = progress) }
      }.onSuccess { updated ->
        _uiState.update {
          it.copy(
            encryptionProgress = null,
            statusMessage = "Unlocked \"${updated.name}\"",
          )
        }
        if (reloadDetail) loadFolderFiles(folderId)
      }.onFailure { err ->
        issueRecorder.recordIssueAsync(
          category = IssueCategory.CRYPTO_ERROR,
          errorCode = "UNLOCK_FOLDER_FAILED",
          rawSummary = err.message ?: "Decryption failed — key or tag mismatch",
        )
        _uiState.update {
          it.copy(
            encryptionProgress = null,
            errorMessage = err.message ?: "Decryption failed — key or tag mismatch",
          )
        }
      }
    }
  }

  fun decryptFilesInFolder(
    folderId: String,
    fileIds: Set<String>,
    reloadDetail: Boolean = true,
  ) {
    if (fileIds.isEmpty()) return
    val dek = sessionDek ?: return
    viewModelScope.launch {
      folderRepo.decryptFilesInFolder(folderId, fileIds, dek) { progress ->
        _uiState.update { it.copy(encryptionProgress = progress) }
      }.onSuccess { updated ->
        val msg = if (updated.isLocked) {
          "Decrypted ${fileIds.size} file(s) • ${updated.encryptedFileCount} still encrypted"
        } else {
          "Decrypted ${fileIds.size} file(s) in \"${updated.name}\""
        }
        _uiState.update {
          it.copy(
            encryptionProgress = null,
            statusMessage = msg,
          )
        }
        if (reloadDetail) loadFolderFiles(folderId)
      }.onFailure { err ->
        issueRecorder.recordIssueAsync(
          category = IssueCategory.CRYPTO_ERROR,
          errorCode = "DECRYPT_FILE_FAILED",
          rawSummary = err.message ?: "File decryption failed — key or tag mismatch",
        )
        _uiState.update {
          it.copy(
            encryptionProgress = null,
            errorMessage = err.message ?: "File decryption failed — key or tag mismatch",
          )
        }
      }
    }
  }

  fun decryptAnyDeviceFolder(treeUri: Uri) {
    val dek = sessionDek ?: return
    viewModelScope.launch {
      folderRepo.decryptAnySafFolder(treeUri, dek) { progress ->
        _uiState.update { it.copy(encryptionProgress = progress) }
      }.onSuccess { res ->
        val msg = if (res.decryptedFiles > 0) {
          "Decrypted ${res.decryptedFiles} file(s) in \"${res.folderName}\" in-place (no copies left)"
        } else {
          "No encrypted .vlock files found in \"${res.folderName}\""
        }
        _uiState.update {
          it.copy(
            encryptionProgress = null,
            statusMessage = msg,
          )
        }
      }.onFailure { err ->
        issueRecorder.recordIssueAsync(
          category = IssueCategory.CRYPTO_ERROR,
          errorCode = "DECRYPT_ANY_FOLDER_FAILED",
          rawSummary = err.message ?: "Could not decrypt device folder",
        )
        _uiState.update {
          it.copy(
            encryptionProgress = null,
            errorMessage = err.message ?: "Could not decrypt device folder",
          )
        }
      }
    }
  }

  fun removeFilesInFolder(
    folderId: String,
    fileIds: Set<String>,
    reloadDetail: Boolean = true,
  ) {
    if (fileIds.isEmpty()) return
    viewModelScope.launch {
      folderRepo.removeFilesInFolder(folderId, fileIds)
        .onSuccess { updated ->
          if (reloadDetail) loadFolderFiles(folderId)
          _uiState.update {
            it.copy(
              statusMessage = "Removed ${fileIds.size} file(s) from \"${updated.name}\"",
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(errorMessage = err.message ?: "Failed to remove selected file(s)")
          }
        }
    }
  }

  fun enterFolderSelectionMode(initialFolderId: String? = null) {
    _uiState.update { current ->
      val nextIds = if (initialFolderId != null) {
        current.selectedFolderIds + initialFolderId
      } else {
        current.selectedFolderIds
      }
      current.copy(isSelectionMode = true, selectedFolderIds = nextIds)
    }
  }

  fun toggleFolderSelection(folderId: String) {
    _uiState.update { current ->
      val updated = if (folderId in current.selectedFolderIds) {
        current.selectedFolderIds - folderId
      } else {
        current.selectedFolderIds + folderId
      }
      current.copy(
        isSelectionMode = true,
        selectedFolderIds = updated,
      )
    }
  }

  fun selectAllFilteredFolders() {
    _uiState.update { current ->
      val visibleIds = current.filteredFolders.map { it.id }.toSet()
      val allSelected = visibleIds.isNotEmpty() && visibleIds.all { it in current.selectedFolderIds }
      val nextIds = if (allSelected) {
        current.selectedFolderIds - visibleIds
      } else {
        current.selectedFolderIds + visibleIds
      }
      current.copy(isSelectionMode = true, selectedFolderIds = nextIds)
    }
  }

  fun clearFolderSelection() {
    _uiState.update { it.copy(isSelectionMode = false, selectedFolderIds = emptySet()) }
  }

  fun encryptSelectedFolders() {
    val dek = sessionDek ?: return
    val ids = _uiState.value.selectedFolderIds.toList()
    if (ids.isEmpty()) return

    viewModelScope.launch {
      val result = folderRepo.lockFoldersBatch(ids, dek) { progress ->
        _uiState.update { it.copy(encryptionProgress = progress) }
      }
      val msg = when {
        result.succeededCount == 0 && result.failedCount == 0 ->
          "Selected folders are already encrypted"
        result.failedCount > 0 ->
          "Encrypted ${result.succeededCount} folder(s); ${result.failedCount} failed (${result.firstError.orEmpty()})"
        else ->
          "Encrypted ${result.succeededCount} folder(s) (${result.totalFiles} files) with AES-256-GCM"
      }
      _uiState.update {
        it.copy(
          encryptionProgress = null,
          isSelectionMode = false,
          selectedFolderIds = emptySet(),
          statusMessage = if (result.failedCount == 0) msg else null,
          errorMessage = if (result.failedCount > 0) msg else null,
        )
      }
      if (result.succeededCount > 0) {
        maybeRunAutoCloudSync(dek)
      }
    }
  }

  fun decryptSelectedFolders() {
    val dek = sessionDek ?: return
    val ids = _uiState.value.selectedFolderIds.toList()
    if (ids.isEmpty()) return

    viewModelScope.launch {
      val result = folderRepo.unlockFoldersBatch(ids, dek) { progress ->
        _uiState.update { it.copy(encryptionProgress = progress) }
      }
      val msg = when {
        result.succeededCount == 0 && result.failedCount == 0 ->
          "Selected folders are already unlocked"
        result.failedCount > 0 ->
          "Decrypted ${result.succeededCount} folder(s); ${result.failedCount} failed (${result.firstError.orEmpty()})"
        else ->
          "Decrypted ${result.succeededCount} folder(s) (${result.totalFiles} files)"
      }
      _uiState.update {
        it.copy(
          encryptionProgress = null,
          isSelectionMode = false,
          selectedFolderIds = emptySet(),
          statusMessage = if (result.failedCount == 0) msg else null,
          errorMessage = if (result.failedCount > 0) msg else null,
        )
      }
    }
  }

  fun verifyMasterPinForAction(
    pin: String,
    actionLabel: String = "Sensitive Vault Action",
    onResult: (Boolean) -> Unit,
  ) {
    viewModelScope.launch {
      val ok = runCatching { prefsRepo.unlockWithPin(pin) != null }.getOrDefault(false)
      if (!ok) {
        auditLogRepo.recordEvent(
          action = "SECONDARY_AUTH_FAILED",
          folderName = actionLabel,
          details = "Incorrect Master PIN entered for $actionLabel",
          category = AuditCategory.FAILED_ATTEMPT,
          isWarning = true,
        )
      }
      onResult(ok)
    }
  }

  fun verifyPatternForAction(
    patternNodes: List<Int>,
    actionLabel: String = "Sensitive Vault Action",
    onResult: (Boolean) -> Unit,
  ) {
    viewModelScope.launch {
      val ok = runCatching { prefsRepo.unlockWithPattern(patternNodes) != null }.getOrDefault(false)
      if (!ok) {
        auditLogRepo.recordEvent(
          action = "SECONDARY_AUTH_FAILED",
          folderName = actionLabel,
          details = "Incorrect 3x3 Unlock Pattern entered for $actionLabel",
          category = AuditCategory.FAILED_ATTEMPT,
          isWarning = true,
        )
      }
      onResult(ok)
    }
  }

  fun verifyMasterPinForReencrypt(pin: String, onResult: (Boolean) -> Unit) {
    verifyMasterPinForAction(pin, actionLabel = "Batch Key Rotation", onResult = onResult)
  }

  fun reencryptSelectedFolders(encryptUnlockedFolders: Boolean = true) {
    val dek = sessionDek ?: return
    val ids = _uiState.value.selectedFolderIds.toList()
    if (ids.isEmpty()) return

    viewModelScope.launch {
      val result = folderRepo.reencryptFoldersBatch(
        folderIds = ids,
        masterDek = dek,
        encryptUnlockedFolders = encryptUnlockedFolders,
      ) { progress ->
        _uiState.update { it.copy(encryptionProgress = progress) }
      }
      val msg = when {
        result.failedCount > 0 ->
          "Rotated keys for ${result.succeededCount} folder(s); ${result.failedCount} failed (${result.firstError.orEmpty()})"
        result.succeededCount == 0 && result.skippedCount > 0 ->
          "Skipped ${result.skippedCount} unlocked folder(s)"
        result.skippedCount > 0 ->
          "Rotated keys for ${result.succeededCount} folder(s) (${result.totalFiles} files); skipped ${result.skippedCount} unlocked"
        else ->
          "Rotated AES-256 keys & re-encrypted ${result.succeededCount} folder(s) (${result.totalFiles} files)"
      }
      _uiState.update {
        it.copy(
          encryptionProgress = null,
          isSelectionMode = false,
          selectedFolderIds = emptySet(),
          statusMessage = if (result.failedCount == 0) msg else null,
          errorMessage = if (result.failedCount > 0) msg else null,
        )
      }
      if (result.succeededCount > 0) {
        syncDualKeyVaultInternal(dek)
        maybeRunAutoCloudSync(dek)
      }
    }
  }

  fun batchRenameFiles(
    folderId: String,
    renameMap: Map<String, String>,
  ) {
    if (renameMap.isEmpty()) return
    val dek = sessionDek
    viewModelScope.launch {
      folderRepo.batchRenameFolderFiles(folderId, renameMap, dek) { progress ->
        _uiState.update { it.copy(encryptionProgress = progress) }
      }.onSuccess { res ->
        loadFolderFiles(folderId)
        val msg = when {
          res.failedCount > 0 ->
            "Renamed ${res.renamedCount} file(s); ${res.failedCount} failed (${res.firstError.orEmpty()})"
          res.renamedCount == 0 ->
            "All selected filenames already match the target format"
          else ->
            "Renamed ${res.renamedCount} file(s) without decrypting payloads"
        }
        _uiState.update {
          it.copy(
            encryptionProgress = null,
            statusMessage = if (res.failedCount == 0) msg else null,
            errorMessage = if (res.failedCount > 0) msg else null,
          )
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            encryptionProgress = null,
            errorMessage = err.message ?: "Batch file rename failed",
          )
        }
      }
    }
  }

  fun batchRenameFolders(renameMap: Map<String, String>) {
    if (renameMap.isEmpty()) return
    viewModelScope.launch {
      folderRepo.batchRenameFolders(renameMap)
        .onSuccess { res ->
          val msg = when {
            res.failedCount > 0 ->
              "Renamed ${res.renamedCount} folder(s); ${res.failedCount} failed (${res.firstError.orEmpty()})"
            res.renamedCount == 0 ->
              "All selected folder names already match the target format"
            else ->
              "Organized ${res.renamedCount} folder name(s) without decrypting contents"
          }
          _uiState.update {
            it.copy(
              isSelectionMode = false,
              selectedFolderIds = emptySet(),
              statusMessage = if (res.failedCount == 0) msg else null,
              errorMessage = if (res.failedCount > 0) msg else null,
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(errorMessage = err.message ?: "Batch folder rename failed")
          }
        }
    }
  }

  fun setFolderAutoCloudBackupSync(folderId: String, enabled: Boolean) {
    viewModelScope.launch {
      folderRepo.setFolderAutoCloudBackupSync(folderId, enabled)
      val folderName = _uiState.value.folders.find { it.id == folderId }?.name ?: "Folder"
      _uiState.update {
        it.copy(
          statusMessage = if (enabled) {
            "Auto cloud sync enabled for \"$folderName\""
          } else {
            "Auto cloud sync disabled for \"$folderName\""
          },
        )
      }
    }
  }

  fun setSelectedFoldersAutoCloudBackupSync(enabled: Boolean) {
    val ids = _uiState.value.selectedFolderIds.toList()
    if (ids.isEmpty()) return
    viewModelScope.launch {
      val updated = folderRepo.setFoldersAutoCloudBackupSyncBatch(ids, enabled)
      _uiState.update {
        it.copy(
          statusMessage = if (enabled) {
            "Enabled auto cloud sync for ${ids.size} selected folder(s)"
          } else {
            "Disabled auto cloud sync for ${ids.size} selected folder(s)"
          },
        )
      }
      val dek = sessionDek
      if (updated > 0 && enabled && dek != null) {
        maybeRunAutoCloudSync(dek)
      }
    }
  }

  fun configureAutoBackupDestination(treeUri: Uri, providerLabel: String) {
    viewModelScope.launch {
      try {
        getApplication<Application>().contentResolver.takePersistableUriPermission(
          treeUri,
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
      } catch (_: SecurityException) {
        // Fallback for transient test/document providers
      }
      prefsRepo.setAutoBackupDestination(treeUri.toString(), providerLabel)
      auditLogRepo.recordEvent(
        action = "AUTO_SYNC_CONFIGURED",
        folderName = providerLabel,
        details = "Linked automatic cloud backup sync folder",
        category = AuditCategory.CLOUD_SYNC,
      )
      val dek = sessionDek
      if (dek != null) {
        cloudBackupManager.syncAutoBackupToTree(treeUri, providerLabel, dek) { progress ->
          _uiState.update { it.copy(cloudBackupProgress = progress) }
        }.onSuccess { res ->
          _uiState.update {
            it.copy(
              cloudBackupProgress = null,
              statusMessage = if (res != null) {
                "Auto-sync enabled & backed up ${res.record.folderCount} folder(s) to $providerLabel"
              } else {
                "Auto-sync folder linked ($providerLabel). Enable sync on folders to upload."
              },
            )
          }
        }.onFailure { err ->
          _uiState.update {
            it.copy(
              cloudBackupProgress = null,
              errorMessage = err.message ?: "Auto-sync destination linked, but initial upload failed",
            )
          }
        }
      }
    }
  }

  fun clearAutoBackupDestination() {
    viewModelScope.launch {
      prefsRepo.clearAutoBackupDestination()
      auditLogRepo.recordEvent(
        action = "AUTO_SYNC_DISABLED",
        folderName = "Cloud Backup",
        details = "Unlinked automatic cloud backup sync destination",
        category = AuditCategory.CLOUD_SYNC,
      )
      _uiState.update { it.copy(statusMessage = "Automatic cloud backup destination unlinked") }
    }
  }

  fun syncAutoBackupNow() {
    val dek = sessionDek ?: return
    val settings = _uiState.value.securitySettings
    val uriString = settings.autoBackupTreeUri
    if (uriString.isBlank()) {
      _uiState.update { it.copy(errorMessage = "Select an auto-sync cloud folder first") }
      return
    }
    viewModelScope.launch {
      cloudBackupManager.syncAutoBackupToTree(
        treeUri = Uri.parse(uriString),
        providerLabel = settings.autoBackupProviderLabel,
        dek = dek,
      ) { progress ->
        _uiState.update { it.copy(cloudBackupProgress = progress) }
      }.onSuccess { res ->
        _uiState.update {
          it.copy(
            cloudBackupProgress = null,
            statusMessage = if (res != null) {
              "Auto-synced ${res.record.folderCount} folder(s) (${res.record.fileCount} files) to ${settings.autoBackupProviderLabel}"
            } else {
              "No folders have automatic cloud backup sync enabled"
            },
          )
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            cloudBackupProgress = null,
            errorMessage = err.message ?: "Auto cloud backup sync failed",
          )
        }
      }
    }
  }

  private fun isBatterySaverBlockingNow(): Boolean {
    val settings = _uiState.value.securitySettings
    val power = batteryMonitor.powerState.value
    return settings.batterySaverModeEnabled && power.isBatterySaverPaused
  }

  private suspend fun resolveDriveToken(): String? {
    val existing = driveAccessToken
    if (!existing.isNullOrBlank()) return existing
    val silent = GoogleDriveAuthHelper.trySilentToken(getApplication())
    if (!silent.isNullOrBlank()) {
      driveAccessToken = silent
    }
    return silent
  }

  private fun maybeRunAutoCloudSync(
    dek: SecretKey,
    onlyIfFolderSyncEnabledId: String? = null,
  ) {
    val settings = _uiState.value.securitySettings
    val hasSafSync = settings.autoBackupTreeUri.isNotBlank()
    val hasDriveSync = settings.googleDriveSyncConnected && settings.googleDriveAutoSyncEnabled
    if (!hasSafSync && !hasDriveSync) return

    if (onlyIfFolderSyncEnabledId != null) {
      val f = _uiState.value.folders.find { it.id == onlyIfFolderSyncEnabledId }
      if (f == null || !f.autoCloudBackupSync) return
    }
    if (isBatterySaverBlockingNow()) {
      val pState = batteryMonitor.powerState.value
      viewModelScope.launch {
        val alreadyPending = settings.pendingBatteryPausedAutoSync
        prefsRepo.setPendingBatteryPausedAutoSync(true)
        if (!alreadyPending) {
          val providerLabel = if (hasDriveSync) "Google Drive API" else settings.autoBackupProviderLabel
          auditLogRepo.recordEvent(
            action = "BATTERY_SAVER_PAUSED",
            folderName = providerLabel,
            details = "Automatic cloud backup sync paused (${pState.batteryPercent}%${if (pState.isSystemPowerSaveMode) ", Power Save active" else ""}); will resume once charged > 20%",
            category = AuditCategory.CLOUD_SYNC,
          )
        }
        _uiState.update {
          it.copy(
            statusMessage = "Battery Saver active (${pState.batteryPercent}%) — Auto cloud sync paused until charged > 20%",
          )
        }
      }
      return
    }
    viewModelScope.launch {
      prefsRepo.setPendingBatteryPausedAutoSync(false)
      if (hasSafSync) {
        cloudBackupManager.syncAutoBackupToTree(
          treeUri = Uri.parse(settings.autoBackupTreeUri),
          providerLabel = settings.autoBackupProviderLabel,
          dek = dek,
        )
      }
      if (hasDriveSync) {
        val token = resolveDriveToken()
        if (!token.isNullOrBlank()) {
          cloudBackupManager.syncVaultToGoogleDrive(
            accessToken = token,
            dek = dek,
            onlyAutoSyncEnabled = true,
          ).onSuccess {
            cloudBackupManager.listGoogleDriveBackups(token).onSuccess { list ->
              _uiState.update { it.copy(driveBackups = list) }
            }
          }.onFailure { err ->
            if (err is DriveAuthExpiredException) {
              driveAccessToken = null
            }
          }
        }
      }
    }
  }

  private fun resumeBatteryPausedWorkIfReady(
    settings: SecuritySettings,
    powerState: DevicePowerState,
  ) {
    val dek = sessionDek ?: return
    if (settings.pendingBatteryPausedReencrypt) {
      viewModelScope.launch {
        prefsRepo.setPendingBatteryPausedReencrypt(false)
        val sealedCount = reencryptAllUnlockedFolders(dek)
        if (sealedCount > 0) {
          auditLogRepo.recordEvent(
            action = "BATTERY_SAVER_RESUMED",
            folderName = "Auto-Lock",
            details = "Battery recovered (${powerState.batteryPercent}%) — re-encrypted $sealedCount deferred folder(s)",
            category = AuditCategory.CRYPTO_OPERATION,
          )
          _uiState.update {
            it.copy(statusMessage = "Battery > 20% — Re-encrypted $sealedCount deferred folder(s)")
          }
        }
      }
    }
    val hasSafSync = settings.autoBackupTreeUri.isNotBlank()
    val hasDriveSync = settings.googleDriveSyncConnected && settings.googleDriveAutoSyncEnabled
    if (settings.pendingBatteryPausedAutoSync && (hasSafSync || hasDriveSync)) {
      viewModelScope.launch {
        prefsRepo.setPendingBatteryPausedAutoSync(false)
        val providerLabel = if (hasDriveSync) "Google Drive API" else settings.autoBackupProviderLabel
        auditLogRepo.recordEvent(
          action = "BATTERY_SAVER_RESUMED",
          folderName = providerLabel,
          details = "Battery recovered (${powerState.batteryPercent}%) — resuming queued automatic cloud sync",
          category = AuditCategory.CLOUD_SYNC,
        )
        var resumedFileCount: Int? = null
        if (hasSafSync) {
          cloudBackupManager.syncAutoBackupToTree(
            treeUri = Uri.parse(settings.autoBackupTreeUri),
            providerLabel = settings.autoBackupProviderLabel,
            dek = dek,
          ).onSuccess { res ->
            if (res != null) resumedFileCount = res.record.fileCount
          }
        }
        if (hasDriveSync) {
          val token = resolveDriveToken()
          if (!token.isNullOrBlank()) {
            cloudBackupManager.syncVaultToGoogleDrive(
              accessToken = token,
              dek = dek,
              onlyAutoSyncEnabled = true,
            ).onSuccess { res ->
              if (res != null) resumedFileCount = res.record.fileCount
              cloudBackupManager.listGoogleDriveBackups(token).onSuccess { list ->
                _uiState.update { it.copy(driveBackups = list) }
              }
            }
          }
        }
        resumedFileCount?.let { count ->
          _uiState.update {
            it.copy(statusMessage = "Battery > 20% — Resumed queued cloud sync ($count files)")
          }
        }
      }
    }
  }

  fun updateFolderOptions(
    folderId: String,
    requireBio: Boolean,
    obfuscateFilenames: Boolean,
    requireAuthOnOpen: Boolean? = null,
  ) {
    viewModelScope.launch {
      folderRepo.updateFolderSecurityOptions(folderId, requireBio, obfuscateFilenames, requireAuthOnOpen)
    }
  }

  fun removeFolder(
    folderId: String,
    decryptLockedSafFirst: Boolean = true,
  ) {
    viewModelScope.launch {
      runCatching {
        folderRepo.removeFolderFromVault(
          folderId = folderId,
          dek = sessionDek,
          decryptLockedSafFirst = decryptLockedSafFirst,
        )
      }.onSuccess {
        _uiState.update {
          it.copy(
            selectedFolderIds = it.selectedFolderIds - folderId,
            activeFolderFiles = emptyList(),
            securePreview = null,
            statusMessage = "Folder removed from BeastLock",
          )
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(errorMessage = err.message ?: "Failed to remove folder from BeastLock")
        }
      }
    }
  }

  fun removeSelectedFolders(decryptLockedSafFirst: Boolean = true) {
    val ids = _uiState.value.selectedFolderIds.toList()
    if (ids.isEmpty()) return
    viewModelScope.launch {
      val result = folderRepo.removeFoldersBatch(
        folderIds = ids,
        dek = sessionDek,
        decryptLockedSafFirst = decryptLockedSafFirst,
      ) { progress ->
        _uiState.update { it.copy(encryptionProgress = progress) }
      }
      val msg = if (result.failedCount > 0) {
        "Removed ${result.succeededCount} folder(s); ${result.failedCount} failed (${result.firstError.orEmpty()})"
      } else {
        "Removed ${result.succeededCount} folder(s) from BeastLock list"
      }
      _uiState.update {
        it.copy(
          encryptionProgress = null,
          isSelectionMode = false,
          selectedFolderIds = emptySet(),
          activeFolderFiles = emptyList(),
          securePreview = null,
          statusMessage = if (result.failedCount == 0) msg else null,
          errorMessage = if (result.failedCount > 0) msg else null,
        )
      }
    }
  }

  fun peekFile(item: VaultFileItem) {
    viewModelScope.launch {
      _uiState.update { it.copy(isPreviewLoading = true) }
      folderRepo.peekFile(item, sessionDek)
        .onSuccess { preview ->
          val parentIsStealth = _uiState.value.folders.any { it.id == item.folderId && it.isStealthHidden }
          auditLogRepo.recordEvent(
            action = "FILE_PEEKED",
            folderName = if (parentIsStealth) "Stealth Private Vault" else item.displayName,
            details = if (item.isEncrypted) {
              "Decrypted secure in-memory preview (${item.mimeType})"
            } else {
              "Viewed unencrypted file (${item.mimeType})"
            },
            category = AuditCategory.ACCESS_EVENT,
          )
          _uiState.update { it.copy(securePreview = preview, isPreviewLoading = false) }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isPreviewLoading = false,
              errorMessage = err.message ?: "Failed to decrypt preview",
            )
          }
        }
    }
  }

  fun dismissPreview() {
    _uiState.update { it.copy(securePreview = null) }
  }

  fun exportCloudBackup(
    destinationUri: Uri,
    providerLabel: String,
    selectedFolderIds: Set<String>? = null,
  ) {
    val dek = sessionDek ?: return
    viewModelScope.launch {
      cloudBackupManager.exportEncryptedBackup(
        destinationUri = destinationUri,
        providerLabel = providerLabel,
        dek = dek,
        folderIds = selectedFolderIds,
      ) { progress ->
        _uiState.update { it.copy(cloudBackupProgress = progress) }
      }.onSuccess { result ->
        val skippedNote = if (result.skippedMissingFolders > 0) {
          " (${result.skippedMissingFolders} skipped due to missing SAF grant)"
        } else ""
        _uiState.update {
          it.copy(
            cloudBackupProgress = null,
            statusMessage = "Encrypted backup saved to $providerLabel (${result.record.fileCount} files)$skippedNote",
          )
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            cloudBackupProgress = null,
            errorMessage = err.message ?: "Cloud backup failed",
          )
        }
      }
    }
  }

  fun restoreCloudBackup(backupUri: Uri, backupPin: String? = null) {
    val dek = sessionDek ?: return
    viewModelScope.launch {
      cloudBackupManager.restoreEncryptedBackup(
        backupUri = backupUri,
        currentDek = dek,
        backupPinIfDifferentKey = backupPin,
      ) { progress ->
        _uiState.update { it.copy(cloudBackupProgress = progress) }
      }.onSuccess { result ->
        if (result.requiresBackupPin) {
          _uiState.update {
            it.copy(
              cloudBackupProgress = null,
              pendingRestoreUriRequiringPin = backupUri,
            )
          }
        } else {
          _uiState.update {
            it.copy(
              cloudBackupProgress = null,
              pendingRestoreUriRequiringPin = null,
              statusMessage = "Restored ${result.restoredFolders} encrypted folder(s) (${result.restoredFiles} files)",
            )
          }
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            cloudBackupProgress = null,
            errorMessage = err.message ?: "Failed to restore cloud backup",
          )
        }
      }
    }
  }

  fun dismissPendingRestorePinPrompt() {
    _uiState.update { it.copy(pendingRestoreUriRequiringPin = null) }
  }

  fun onGoogleDriveAuthorized(accessToken: String) {
    driveAccessToken = accessToken
    viewModelScope.launch {
      _uiState.update { it.copy(isDriveSyncBusy = true) }
      val email = cloudBackupManager.fetchGoogleDriveAccountEmail(accessToken).orEmpty()
      prefsRepo.setGoogleDriveConnection(connected = true, email = email)
      auditLogRepo.recordEvent(
        action = "GOOGLE_DRIVE_CONNECTED",
        folderName = "Google Drive",
        details = if (email.isNotBlank()) {
          "Authorized encrypted Google Drive appDataFolder sync for $email"
        } else {
          "Authorized encrypted Google Drive appDataFolder sync"
        },
        category = AuditCategory.CLOUD_SYNC,
      )
      cloudBackupManager.listGoogleDriveBackups(accessToken)
        .onSuccess { items ->
          val accountLabel = if (email.isNotBlank()) " ($email)" else ""
          _uiState.update {
            it.copy(
              isDriveSyncBusy = false,
              driveBackups = items,
              statusMessage = "Connected to Google Drive$accountLabel",
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isDriveSyncBusy = false,
              statusMessage = "Google Drive connected; snapshot refresh pending (${err.message ?: "offline"})",
            )
          }
        }
    }
  }

  fun reportGoogleDriveAuthError(message: String) {
    _uiState.update {
      it.copy(
        isDriveSyncBusy = false,
        errorMessage = message,
      )
    }
  }

  fun refreshGoogleDriveBackups() {
    viewModelScope.launch {
      _uiState.update { it.copy(isDriveSyncBusy = true) }
      val token = resolveDriveToken()
      if (token.isNullOrBlank()) {
        _uiState.update {
          it.copy(
            isDriveSyncBusy = false,
            errorMessage = "Authorize Google Drive access to list cloud snapshots.",
          )
        }
        _driveReauthRequired.tryEmit(Unit)
        return@launch
      }
      cloudBackupManager.listGoogleDriveBackups(token)
        .onSuccess { items ->
          _uiState.update {
            it.copy(
              isDriveSyncBusy = false,
              driveBackups = items,
            )
          }
        }
        .onFailure { err ->
          if (err is DriveAuthExpiredException) {
            driveAccessToken = null
            _driveReauthRequired.tryEmit(Unit)
          }
          _uiState.update {
            it.copy(
              isDriveSyncBusy = false,
              errorMessage = err.message ?: "Failed to list Google Drive backups",
            )
          }
        }
    }
  }

  fun syncVaultToGoogleDrive(
    selectedFolderIds: Set<String>? = null,
    onlyAutoSync: Boolean = false,
    createTimestampedSnapshot: Boolean = false,
  ) {
    val dek = sessionDek ?: return
    viewModelScope.launch {
      _uiState.update { it.copy(isDriveSyncBusy = true) }
      val token = resolveDriveToken()
      if (token.isNullOrBlank()) {
        _uiState.update {
          it.copy(
            isDriveSyncBusy = false,
            errorMessage = "Tap Connect Google Drive to authorize encrypted cloud sync.",
          )
        }
        _driveReauthRequired.tryEmit(Unit)
        return@launch
      }
      val archiveName = if (createTimestampedSnapshot) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        "BeastLock_Drive_$stamp.vlkbak"
      } else {
        CloudBackupManager.AUTO_SYNC_ARCHIVE_NAME
      }
      cloudBackupManager.syncVaultToGoogleDrive(
        accessToken = token,
        dek = dek,
        folderIds = selectedFolderIds,
        onlyAutoSyncEnabled = onlyAutoSync,
        archiveFileName = archiveName,
        updateExistingAutoSync = !createTimestampedSnapshot,
      ) { progress ->
        _uiState.update { it.copy(cloudBackupProgress = progress) }
      }.onSuccess { result ->
        val updatedList = cloudBackupManager.listGoogleDriveBackups(token).getOrNull()
        _uiState.update { state ->
          state.copy(
            isDriveSyncBusy = false,
            cloudBackupProgress = null,
            driveBackups = updatedList ?: state.driveBackups,
            statusMessage = if (result != null) {
              "Synced ${result.record.folderCount} encrypted folder(s) (${result.record.fileCount} files) to Google Drive"
            } else {
              "No eligible folders to sync to Google Drive"
            },
          )
        }
      }.onFailure { err ->
        if (err is DriveAuthExpiredException) {
          driveAccessToken = null
          _driveReauthRequired.tryEmit(Unit)
        }
        _uiState.update {
          it.copy(
            isDriveSyncBusy = false,
            cloudBackupProgress = null,
            errorMessage = err.message ?: "Google Drive sync failed",
          )
        }
      }
    }
  }

  fun restoreFromGoogleDrive(
    item: DriveVaultBackupItem,
    backupPin: String? = null,
  ) {
    val dek = sessionDek ?: return
    viewModelScope.launch {
      _uiState.update { it.copy(isDriveSyncBusy = true) }
      val token = resolveDriveToken()
      if (token.isNullOrBlank()) {
        _uiState.update {
          it.copy(
            isDriveSyncBusy = false,
            errorMessage = "Authorize Google Drive to download encrypted backup.",
          )
        }
        _driveReauthRequired.tryEmit(Unit)
        return@launch
      }
      cloudBackupManager.restoreFromGoogleDrive(
        accessToken = token,
        driveFileId = item.id,
        fileName = item.name,
        currentDek = dek,
        backupPinIfDifferentKey = backupPin,
      ) { progress ->
        _uiState.update { it.copy(cloudBackupProgress = progress) }
      }.onSuccess { result ->
        if (result.requiresBackupPin) {
          _uiState.update {
            it.copy(
              isDriveSyncBusy = false,
              cloudBackupProgress = null,
              pendingDriveRestoreItem = item,
            )
          }
        } else {
          _uiState.update {
            it.copy(
              isDriveSyncBusy = false,
              cloudBackupProgress = null,
              pendingDriveRestoreItem = null,
              statusMessage = "Restored ${result.restoredFolders} encrypted folder(s) (${result.restoredFiles} files) from Google Drive",
            )
          }
        }
      }.onFailure { err ->
        if (err is DriveAuthExpiredException) {
          driveAccessToken = null
          _driveReauthRequired.tryEmit(Unit)
        }
        _uiState.update {
          it.copy(
            isDriveSyncBusy = false,
            cloudBackupProgress = null,
            errorMessage = err.message ?: "Failed to restore Google Drive backup",
          )
        }
      }
    }
  }

  fun dismissPendingDriveRestorePin() {
    _uiState.update { it.copy(pendingDriveRestoreItem = null) }
  }

  fun deleteGoogleDriveBackup(item: DriveVaultBackupItem) {
    viewModelScope.launch {
      _uiState.update { it.copy(isDriveSyncBusy = true) }
      val token = resolveDriveToken()
      if (token.isNullOrBlank()) {
        _uiState.update {
          it.copy(
            isDriveSyncBusy = false,
            errorMessage = "Authorize Google Drive to remove cloud snapshots.",
          )
        }
        _driveReauthRequired.tryEmit(Unit)
        return@launch
      }
      cloudBackupManager.deleteGoogleDriveBackup(token, item.id)
        .onSuccess {
          _uiState.update { state ->
            state.copy(
              isDriveSyncBusy = false,
              driveBackups = state.driveBackups.filterNot { it.id == item.id },
              statusMessage = "Deleted \"${item.name}\" from Google Drive",
            )
          }
        }
        .onFailure { err ->
          if (err is DriveAuthExpiredException) {
            driveAccessToken = null
            _driveReauthRequired.tryEmit(Unit)
          }
          _uiState.update {
            it.copy(
              isDriveSyncBusy = false,
              errorMessage = err.message ?: "Failed to delete Google Drive backup",
            )
          }
        }
    }
  }

  fun setGoogleDriveAutoSyncEnabled(enabled: Boolean) {
    viewModelScope.launch {
      prefsRepo.setGoogleDriveAutoSyncEnabled(enabled)
      auditLogRepo.recordEvent(
        action = "POLICY_UPDATED",
        folderName = "Google Drive",
        details = if (enabled) {
          "Enabled automatic encrypted Google Drive API sync"
        } else {
          "Disabled automatic encrypted Google Drive API sync"
        },
        category = AuditCategory.CLOUD_SYNC,
      )
      if (enabled) {
        val dek = sessionDek
        if (dek != null) {
          maybeRunAutoCloudSync(dek)
        }
      }
    }
  }

  fun disconnectGoogleDrive() {
    driveAccessToken = null
    viewModelScope.launch {
      prefsRepo.setGoogleDriveConnection(connected = false, email = "")
      auditLogRepo.recordEvent(
        action = "GOOGLE_DRIVE_DISCONNECTED",
        folderName = "Google Drive",
        details = "Disconnected Google Drive API sync session from BeastLock",
        category = AuditCategory.CLOUD_SYNC,
      )
      _uiState.update {
        it.copy(
          driveBackups = emptyList(),
          pendingDriveRestoreItem = null,
          statusMessage = "Google Drive sync disconnected (existing Drive backups retained)",
        )
      }
    }
  }

  fun exportAuditReport(
    destinationUri: Uri,
    format: AuditExportFormat = AuditExportFormat.CSV,
    scope: AuditExportScope = AuditExportScope.ALL,
  ) {
    viewModelScope.launch {
      auditLogRepo.exportAuditLog(destinationUri, format = format, scope = scope)
        .onSuccess { count ->
          val actionName = if (format == AuditExportFormat.CSV) "AUDIT_CSV_EXPORTED" else "AUDIT_EXPORTED"
          auditLogRepo.recordEvent(
            action = actionName,
            folderName = "Security Audit",
            details = "Exported $count audit entry(ies) (${scope.label}) as ${format.label}",
            category = AuditCategory.ACCESS_EVENT,
          )
          _uiState.update {
            it.copy(statusMessage = "Exported $count ${scope.label.lowercase()} entry(ies) as ${format.extension.uppercase()}")
          }
        }
        .onFailure { err ->
          _uiState.update { it.copy(errorMessage = err.message ?: "Audit export failed") }
        }
    }
  }

  fun changePin(currentPin: String, newPin: String, onDone: (Boolean) -> Unit) {
    viewModelScope.launch {
      val settings = _uiState.value.securitySettings
      val dek = sessionDek
      val ok = if (!settings.hasPinCredential && dek != null) {
        prefsRepo.setOrUpdatePin(dek = dek, newPin = newPin, makePrimaryIfPin = false)
      } else {
        prefsRepo.changePin(currentPin, newPin)
      }
      if (ok) {
        syncDualKeyVaultInternal(sessionDek)
        auditLogRepo.recordEvent(
          action = "PIN_CHANGED",
          folderName = "BeastLock",
          details = "Re-wrapped 256-bit DEK with updated ${newPin.length}-digit Master PIN & synced Dual Key Vault",
          category = AuditCategory.CRYPTO_OPERATION,
        )
        _uiState.update { it.copy(statusMessage = "${newPin.length}-digit Master PIN saved & key mirrors synced") }
      } else {
        auditLogRepo.recordEvent(
          action = "PIN_CHANGE_FAILED",
          folderName = "BeastLock",
          details = "Incorrect current PIN entered when attempting PIN change",
          category = AuditCategory.FAILED_ATTEMPT,
          isWarning = true,
        )
        _uiState.update { it.copy(errorMessage = "Current PIN is incorrect") }
      }
      onDone(ok)
    }
  }

  fun setPrimaryLockType(lockType: PrimaryLockType) {
    viewModelScope.launch {
      val settings = _uiState.value.securitySettings
      if (lockType == PrimaryLockType.PATTERN && !settings.hasPatternLock) {
        _uiState.update { it.copy(errorMessage = "Set up a 3×3 Pattern first before selecting Pattern as primary lock.") }
        return@launch
      }
      if ((lockType == PrimaryLockType.PIN_4 || lockType == PrimaryLockType.PIN_6) && !settings.hasPinCredential) {
        _uiState.update { it.copy(errorMessage = "Set up a ${if (lockType == PrimaryLockType.PIN_4) "4" else "6"}-digit PIN first.") }
        return@launch
      }
      prefsRepo.setPrimaryLockType(lockType)
      syncDualKeyVaultInternal(sessionDek)
      auditLogRepo.recordEvent(
        action = "POLICY_UPDATED",
        folderName = "BeastLock",
        details = "Primary lock method switched to ${lockType.label}",
        category = AuditCategory.CRYPTO_OPERATION,
      )
      _uiState.update { it.copy(statusMessage = "Primary lock set to ${lockType.label}") }
    }
  }

  fun setOrUpdateUnlockPattern(
    patternNodes: List<Int>,
    makeDefaultFallback: Boolean = true,
    onDone: (Boolean) -> Unit,
  ) {
    val dek = sessionDek ?: run {
      onDone(false)
      return
    }
    viewModelScope.launch {
      val ok = runCatching {
        prefsRepo.setUnlockPattern(dek, patternNodes, makeDefaultFallback)
      }.getOrDefault(false)
      if (ok) {
        syncDualKeyVaultInternal(dek)
        auditLogRepo.recordEvent(
          action = "PATTERN_UPDATED",
          folderName = "BeastLock",
          details = "Wrapped 256-bit DEK with PBKDF2 3x3 Unlock Pattern & synced Dual Key Vault",
          category = AuditCategory.CRYPTO_OPERATION,
        )
        _uiState.update {
          it.copy(statusMessage = "Unlock Pattern saved & Dual Key Vault synced")
        }
      }
      onDone(ok)
    }
  }

  fun removeUnlockPattern() {
    val settings = _uiState.value.securitySettings
    if (!settings.hasPinCredential) {
      _uiState.update {
        it.copy(errorMessage = "Set a 4- or 6-digit Master PIN before removing your 3×3 Pattern lock.")
      }
      return
    }
    viewModelScope.launch {
      prefsRepo.removeUnlockPattern()
      if (settings.primaryLockType == PrimaryLockType.PATTERN) {
        val fallbackType = if (settings.pinLength == 4) PrimaryLockType.PIN_4 else PrimaryLockType.PIN_6
        prefsRepo.setPrimaryLockType(fallbackType)
      }
      syncDualKeyVaultInternal(sessionDek)
      auditLogRepo.recordEvent(
        action = "PATTERN_REMOVED",
        folderName = "BeastLock",
        details = "Removed 3x3 Unlock Pattern; Master PIN remains active",
        category = AuditCategory.CRYPTO_OPERATION,
      )
      _uiState.update {
        it.copy(statusMessage = "Unlock Pattern removed; ${settings.pinLength}-digit Master PIN remains active")
      }
    }
  }

  fun setPreferPatternFallback(prefer: Boolean) {
    viewModelScope.launch {
      prefsRepo.setPreferPatternFallback(prefer)
    }
  }

  fun setPatternStealthMode(enabled: Boolean) {
    viewModelScope.launch {
      prefsRepo.setPatternStealthMode(enabled)
    }
  }

  fun updateSecurityQuestions(
    q1: String,
    a1: String,
    q2: String,
    a2: String,
    onDone: () -> Unit,
  ) {
    updateSecurityQuestions(
      q1 = q1,
      a1 = a1,
      q2 = q2,
      a2 = a2,
      customQuestionsToShare = emptyList(),
      shareCustomSuggestions = _uiState.value.securitySettings.shareCustomQuestionSuggestions,
      onDone = onDone,
    )
  }

  fun updateSecurityQuestions(
    q1: String,
    a1: String,
    q2: String,
    a2: String,
    customQuestionsToShare: List<String>,
    shareCustomSuggestions: Boolean,
    onDone: () -> Unit,
  ) {
    val dek = sessionDek ?: return
    viewModelScope.launch {
      prefsRepo.updateSecurityQuestions(dek, q1, a1, q2, a2)
      prefsRepo.setShareCustomQuestionSuggestions(shareCustomSuggestions)
      if (shareCustomSuggestions && customQuestionsToShare.isNotEmpty()) {
        prefsRepo.queueCustomQuestionsForSync(customQuestionsToShare)
        flushQueuedCustomQuestionsIfSignedIn()
      }
      syncDualKeyVaultInternal(dek)
      auditLogRepo.recordEvent(
        action = "RECOVERY_UPDATED",
        folderName = "BeastLock",
        details = "Updated PBKDF2 recovery questions & synced Dual Key Vault",
        category = AuditCategory.CRYPTO_OPERATION,
      )
      _uiState.update { it.copy(statusMessage = "Recovery questions updated & key mirrors synced") }
      onDone()
    }
  }

  fun setBiometricEnabled(enabled: Boolean) {
    viewModelScope.launch { prefsRepo.setBiometricEnabled(enabled) }
  }

  fun setAutoLockEnabled(enabled: Boolean) {
    viewModelScope.launch { prefsRepo.setAutoLockEnabled(enabled) }
  }

  fun setAutoLockTimeout(seconds: Int) {
    viewModelScope.launch {
      prefsRepo.setAutoLockTimeout(seconds)
      auditLogRepo.recordEvent(
        action = "POLICY_UPDATED",
        folderName = "Auto-Lock",
        details = "Background auto-lock timer set to ${formatDurationLabel(seconds)}",
        category = AuditCategory.ACCESS_EVENT,
      )
    }
  }

  fun setAutoLockReencryptFolders(enabled: Boolean) {
    viewModelScope.launch { prefsRepo.setAutoLockReencryptFolders(enabled) }
  }

  fun setRequireBiometricAfterAutoLock(enabled: Boolean) {
    viewModelScope.launch { prefsRepo.setRequireBiometricAfterAutoLock(enabled) }
  }

  fun setIdleTimeoutEnabled(enabled: Boolean) {
    viewModelScope.launch {
      prefsRepo.setIdleTimeoutEnabled(enabled)
      _uiState.update {
        it.copy(securitySettings = it.securitySettings.copy(idleTimeoutEnabled = enabled))
      }
      if (enabled) {
        resetIdleTimer()
      } else {
        cancelIdleTimer()
      }
      auditLogRepo.recordEvent(
        action = "POLICY_UPDATED",
        folderName = "Idle Timeout",
        details = if (enabled) "Foreground inactivity timeout enabled" else "Foreground inactivity timeout disabled",
        category = AuditCategory.ACCESS_EVENT,
      )
    }
  }

  fun setIdleTimeoutSeconds(seconds: Int) {
    val clamped = seconds.coerceAtLeast(1)
    viewModelScope.launch {
      prefsRepo.setIdleTimeoutSeconds(clamped)
      _uiState.update {
        it.copy(securitySettings = it.securitySettings.copy(idleTimeoutSeconds = clamped))
      }
      resetIdleTimer()
      auditLogRepo.recordEvent(
        action = "POLICY_UPDATED",
        folderName = "Idle Timeout",
        details = "Foreground inactivity timeout set to ${formatDurationLabel(clamped)}",
        category = AuditCategory.ACCESS_EVENT,
      )
    }
  }

  fun setPanicModeEnabled(enabled: Boolean) {
    viewModelScope.launch {
      prefsRepo.setPanicModeEnabled(enabled)
      auditLogRepo.recordEvent(
        action = "POLICY_UPDATED",
        folderName = "Panic Mode",
        details = if (enabled) "Shake-to-lock Panic Mode enabled" else "Shake-to-lock Panic Mode disabled",
        category = AuditCategory.ACCESS_EVENT,
      )
    }
  }

  fun setPanicSensitivity(sensitivity: PanicSensitivity) {
    viewModelScope.launch {
      prefsRepo.setPanicSensitivity(sensitivity)
      auditLogRepo.recordEvent(
        action = "POLICY_UPDATED",
        folderName = "Panic Mode",
        details = "Shake sensitivity set to ${sensitivity.label} (${sensitivity.thresholdG}g)",
        category = AuditCategory.ACCESS_EVENT,
      )
    }
  }

  fun setPanicExitToDeviceHome(enabled: Boolean) {
    viewModelScope.launch { prefsRepo.setPanicExitToDeviceHome(enabled) }
  }

  fun setPanicReencryptFolders(enabled: Boolean) {
    viewModelScope.launch { prefsRepo.setPanicReencryptFolders(enabled) }
  }

  fun setObfuscateFilenamesDefault(enabled: Boolean) {
    viewModelScope.launch { prefsRepo.setObfuscateFilenamesDefault(enabled) }
  }

  fun setThemeMode(mode: ThemeMode) {
    viewModelScope.launch { prefsRepo.setThemeMode(mode) }
  }

  fun setDynamicColor(enabled: Boolean) {
    viewModelScope.launch { prefsRepo.setDynamicColor(enabled) }
  }

  fun setStealthGestureMode(mode: StealthGestureMode) {
    viewModelScope.launch {
      prefsRepo.setStealthGestureMode(mode)
      auditLogRepo.recordEvent(
        action = "POLICY_UPDATED",
        folderName = "Stealth Private Vault",
        details = "Stealth reveal gesture set to ${mode.label}",
        category = AuditCategory.ACCESS_EVENT,
      )
    }
  }

  fun setAlwaysBlockScreenshots(enabled: Boolean) {
    viewModelScope.launch {
      prefsRepo.setAlwaysBlockScreenshots(enabled)
      auditLogRepo.recordEvent(
        action = "POLICY_UPDATED",
        folderName = "Screen Security",
        details = if (enabled) {
          "Enabled app-wide screenshot and Recent Apps thumbnail blocking (FLAG_SECURE)"
        } else {
          "FLAG_SECURE restricted to Stealth Vault & Secure Previews"
        },
        category = AuditCategory.ACCESS_EVENT,
      )
    }
  }

  fun setStealthAccessCode(rawCode: String, onDone: (Boolean) -> Unit) {
    viewModelScope.launch {
      val ok = prefsRepo.setStealthAccessCode(rawCode)
      if (ok) {
        auditLogRepo.recordEvent(
          action = "STEALTH_CODE_UPDATED",
          folderName = "Stealth Private Vault",
          details = "Updated salted PBKDF2 stealth search access code",
          category = AuditCategory.CRYPTO_OPERATION,
        )
        _uiState.update { it.copy(statusMessage = "Stealth search access code updated") }
      } else {
        _uiState.update { it.copy(errorMessage = "Enter 4 to 32 characters for your stealth code") }
      }
      onDone(ok)
    }
  }

  fun scanOrphanedFiles(showCleanMessage: Boolean = false) {
    viewModelScope.launch {
      _uiState.update { it.copy(isScanningOrphans = true) }
      runCatching {
        maintenanceManager.scanOrphanedFiles(_uiState.value.securitySettings.autoBackupTreeUri)
      }.onSuccess { found ->
        _uiState.update {
          it.copy(
            isScanningOrphans = false,
            hasScannedOrphans = true,
            orphanCandidates = found,
            selectedOrphanIds = found.map { item -> item.id }.toSet(),
            statusMessage = if (showCleanMessage && found.isEmpty()) {
              "Vault hygiene clean — 0 orphaned files detected"
            } else {
              it.statusMessage
            },
          )
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            isScanningOrphans = false,
            errorMessage = err.message ?: "Failed to scan for orphaned files",
          )
        }
      }
    }
  }

  fun toggleOrphanSelection(candidateId: String) {
    _uiState.update { state ->
      val next = if (candidateId in state.selectedOrphanIds) {
        state.selectedOrphanIds - candidateId
      } else {
        state.selectedOrphanIds + candidateId
      }
      state.copy(selectedOrphanIds = next)
    }
  }

  fun selectAllOrphans(selectAll: Boolean) {
    _uiState.update { state ->
      state.copy(
        selectedOrphanIds = if (selectAll) state.orphanCandidates.map { it.id }.toSet() else emptySet(),
      )
    }
  }

  fun deleteSelectedOrphans() {
    val selectedIds = _uiState.value.selectedOrphanIds
    val targets = _uiState.value.orphanCandidates.filter { it.id in selectedIds }
    if (targets.isEmpty()) return

    viewModelScope.launch {
      _uiState.update { it.copy(isCleaningOrphans = true) }
      runCatching {
        maintenanceManager.deleteOrphanedFiles(targets)
      }.onSuccess { res ->
        val remaining = maintenanceManager.scanOrphanedFiles(_uiState.value.securitySettings.autoBackupTreeUri)
        val freedLabel = formatMigrationBytes(res.freedBytes)
        _uiState.update {
          it.copy(
            isCleaningOrphans = false,
            hasScannedOrphans = true,
            orphanCandidates = remaining,
            selectedOrphanIds = remaining.map { item -> item.id }.toSet(),
            lastOrphanCleanupResult = res,
            deviceStorageInfo = readDeviceStorageInfo(),
            statusMessage = if (res.failedCount == 0) {
              "Cleared ${res.deletedCount} orphaned item(s) ($freedLabel reclaimed)"
            } else {
              "Cleared ${res.deletedCount} item(s); ${res.failedCount} could not be removed"
            },
          )
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            isCleaningOrphans = false,
            errorMessage = err.message ?: "Failed to clear orphaned files",
          )
        }
      }
    }
  }

  fun dismissOrphanCleanupResult() {
    _uiState.update { it.copy(lastOrphanCleanupResult = null) }
  }

  fun runIntegrityDiagnosticScan() {
    val dek = sessionDek ?: return
    if (_uiState.value.isScanningIntegrity) return

    integrityScanJob?.cancel()
    integrityScanJob = viewModelScope.launch {
      _uiState.update {
        it.copy(
          isScanningIntegrity = true,
          integrityScanProgress = null,
        )
      }
      runCatching {
        maintenanceManager.scanVaultIntegrity(masterDek = dek) { progress ->
          _uiState.update { it.copy(integrityScanProgress = progress) }
        }
      }.onSuccess { report ->
        _uiState.update {
          it.copy(
            isScanningIntegrity = false,
            integrityScanProgress = null,
            integrityDiagnosticReport = report,
            statusMessage = if (report.corruptedFiles.isEmpty()) {
              "Integrity scan passed: ${report.healthyFiles}/${report.totalEncryptedFiles} encrypted file(s) verified"
            } else {
              "Integrity alert: ${report.corruptedFiles.size} corrupted file(s) identified"
            },
          )
        }
      }.onFailure { err ->
        if (err !is CancellationException) {
          _uiState.update {
            it.copy(
              isScanningIntegrity = false,
              integrityScanProgress = null,
              errorMessage = err.message ?: "Failed to run integrity diagnostic scan",
            )
          }
        }
      }
    }
  }

  fun cancelIntegrityDiagnosticScan() {
    integrityScanJob?.cancel()
    integrityScanJob = null
    _uiState.update {
      it.copy(
        isScanningIntegrity = false,
        integrityScanProgress = null,
      )
    }
  }

  fun dismissIntegrityReport() {
    if (_uiState.value.isScanningIntegrity) {
      cancelIntegrityDiagnosticScan()
    }
    _uiState.update { it.copy(integrityDiagnosticReport = null) }
  }

  fun verifyMasterPinForMigration(pin: String, onResult: (Boolean) -> Unit) {
    verifyMasterPinForAction(pin, actionLabel = "Exit Migration", onResult = onResult)
  }

  fun exportDecryptedMigration(
    folderIds: List<String>,
    format: MigrationExportFormat,
    destinationUri: Uri,
    removeOriginalsAfterSuccess: Boolean,
  ) {
    val dek = sessionDek ?: return
    if (folderIds.isEmpty()) return

    migrationJob?.cancel()
    migrationJob = viewModelScope.launch {
      try {
        getApplication<Application>().contentResolver.takePersistableUriPermission(
          destinationUri,
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
      } catch (_: SecurityException) {
        // Picker documents/files may not support persistable permissions
      }

      maintenanceManager.decryptAndExportFolders(
        folderIds = folderIds,
        masterDek = dek,
        format = format,
        destinationUri = destinationUri,
        removeOriginalsAfterSuccess = removeOriginalsAfterSuccess,
      ) { progress ->
        _uiState.update { it.copy(migrationProgress = progress) }
      }.onSuccess { result ->
        _uiState.update {
          it.copy(
            migrationProgress = null,
            migrationResult = result,
            deviceStorageInfo = readDeviceStorageInfo(),
          )
        }
      }.onFailure { err ->
        if (err is CancellationException) {
          _uiState.update {
            it.copy(
              migrationProgress = null,
              statusMessage = "Vault migration export cancelled",
            )
          }
        } else {
          _uiState.update {
            it.copy(
              migrationProgress = null,
              errorMessage = err.message ?: "Failed to decrypt and export vault content",
            )
          }
        }
      }
    }
  }

  fun cancelMigrationExport() {
    migrationJob?.cancel()
    migrationJob = null
    _uiState.update { it.copy(migrationProgress = null) }
  }

  fun dismissMigrationResult() {
    _uiState.update { it.copy(migrationResult = null) }
  }

  fun linkExternalDualKeyFolder(treeUri: Uri) {
    viewModelScope.launch {
      runCatching {
        getApplication<Application>().contentResolver.takePersistableUriPermission(
          treeUri,
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
      }
      prefsRepo.setDualKeyExternalTreeUri(treeUri.toString())
      val status = syncDualKeyVaultInternal(sessionDek)
      auditLogRepo.recordEvent(
        action = "DUAL_KEY_EXTERNAL_LINKED",
        folderName = ".BeastLock_Keys",
        details = "Linked hidden external key recovery vault with SHA-256 verification",
        category = AuditCategory.CRYPTO_OPERATION,
      )
      _uiState.update {
        it.copy(
          statusMessage = if (status?.externalHealthy == true) {
            "Dual internal & external .BeastLock_Keys vault synced (${status.bundleFolderCount} folder keys)"
          } else {
            "External key folder linked; internal read-only key vault updated"
          },
        )
      }
    }
  }

  fun verifyDualKeyVaultNow() {
    viewModelScope.launch {
      val externalUri = _uiState.value.securitySettings.dualKeyExternalTreeUri
      val verified = verifyDualKeyVaultInternal(externalUri)
      val syncAfter = if (sessionDek != null) syncDualKeyVaultInternal(sessionDek) else verified
      val finalStatus = syncAfter ?: verified
      _uiState.update {
        it.copy(
          statusMessage = when {
            finalStatus?.autoRepairedMirror != null ->
              "Key vault mismatch repaired automatically (${finalStatus.autoRepairedMirror})"
            finalStatus?.checksumValid == true ->
              "Dual Key Vault verified cleanly (SHA-256 valid, ${finalStatus.bundleFolderCount} folder keys)"
            else ->
              finalStatus?.tamperAlert ?: "Key vault verification completed"
          },
        )
      }
    }
  }

  private suspend fun syncDualKeyVaultInternal(dek: SecretKey?): com.example.crypto.DualKeyMirrorStatus? {
    val snapshot = prefsRepo.getKeyWrapSnapshot() ?: return null
    val folders = vaultDao.getAllFoldersOnce()
    val externalUri = _uiState.value.securitySettings.dualKeyExternalTreeUri
    val status = runCatching {
      dualKeyManager.syncDualVault(
        credentials = snapshot,
        folders = folders,
        masterDek = dek,
        externalTreeUri = externalUri,
      )
    }.getOrElse { err ->
      issueRecorder.recordIssueAsync(
        category = IssueCategory.KEY_RECOVERY,
        errorCode = "DUAL_KEY_SYNC_FAILED",
        rawSummary = err.message ?: "Dual Key Vault sync failed",
      )
      return null
    }
    prefsRepo.recordDualKeySyncStatus(
      syncedAtMillis = status.syncedAtMillis,
      checksumValid = status.checksumValid,
      tamperAlert = status.tamperAlert,
    )
    _uiState.update { it.copy(dualKeyStatus = status) }
    return status
  }

  private suspend fun verifyDualKeyVaultInternal(externalTreeUri: String): com.example.crypto.DualKeyMirrorStatus? {
    val status = runCatching {
      dualKeyManager.verifyAndAutoHeal(externalTreeUri)
    }.getOrElse { err ->
      issueRecorder.recordIssueAsync(
        category = IssueCategory.KEY_RECOVERY,
        errorCode = "DUAL_KEY_VERIFY_FAILED",
        rawSummary = err.message ?: "Dual Key Vault verification failed",
      )
      return null
    }
    if (!status.tamperAlert.isNullOrBlank()) {
      issueRecorder.recordIssueAsync(
        category = IssueCategory.KEY_RECOVERY,
        errorCode = "DUAL_KEY_TAMPER_OR_REPAIR",
        rawSummary = status.tamperAlert + (status.autoRepairedMirror?.let { " -> $it" } ?: ""),
      )
      auditLogRepo.recordEvent(
        action = "KEY_VAULT_VERIFIED",
        folderName = "Dual Key Vault",
        details = status.tamperAlert + (status.autoRepairedMirror?.let { " (auto-repaired: $it)" } ?: ""),
        category = AuditCategory.CRYPTO_OPERATION,
        isWarning = !status.checksumValid,
      )
    }
    prefsRepo.recordDualKeySyncStatus(
      syncedAtMillis = status.syncedAtMillis,
      checksumValid = status.checksumValid,
      tamperAlert = status.tamperAlert,
    )
    _uiState.update { it.copy(dualKeyStatus = status) }
    return status
  }

  private fun setupFirebaseAuthObserver() {
    val auth = runCatching { Firebase.auth }.getOrNull() ?: return
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
      val user = firebaseAuth.currentUser
      _uiState.update {
        it.copy(
          firebaseUserUid = user?.uid,
          firebaseUserEmail = user?.email,
        )
      }
      cloudReportsJob?.cancel()
      if (user != null && cloudTelemetryRepo != null) {
        cloudReportsJob = viewModelScope.launch {
          runCatching {
            cloudTelemetryRepo.observeSubmittedReports(user.uid).collect { reports ->
              _uiState.update { it.copy(cloudSubmittedReports = reports) }
            }
          }
        }
        viewModelScope.launch {
          flushQueuedCustomQuestionsIfSignedIn()
        }
      } else {
        _uiState.update { it.copy(cloudSubmittedReports = emptyList()) }
      }
    }
    firebaseAuthListener = listener
    auth.addAuthStateListener(listener)
    viewModelScope.launch {
      FirebaseGoogleAuthHelper.attemptSilentSignIn(getApplication())
    }
  }

  fun signInFirebaseWithGoogle(activity: Activity) {
    viewModelScope.launch {
      _uiState.update { it.copy(isSigningInFirebase = true) }
      FirebaseGoogleAuthHelper.signInInteractively(activity)
        .onSuccess { user ->
          _uiState.update {
            it.copy(
              isSigningInFirebase = false,
              firebaseUserUid = user.uid,
              firebaseUserEmail = user.email,
              statusMessage = "Signed in as ${user.email ?: "Google user"}",
            )
          }
          flushQueuedCustomQuestionsIfSignedIn()
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isSigningInFirebase = false,
              errorMessage = err.message ?: "Google Sign-In cancelled or failed",
            )
          }
        }
    }
  }

  fun signOutFirebase() {
    viewModelScope.launch {
      FirebaseGoogleAuthHelper.signOut(getApplication())
      _uiState.update {
        it.copy(
          firebaseUserUid = null,
          firebaseUserEmail = null,
          cloudSubmittedReports = emptyList(),
          statusMessage = "Signed out of Diagnostics & Cloud Database account",
        )
      }
    }
  }

  private suspend fun flushQueuedCustomQuestionsIfSignedIn() {
    val repo = cloudTelemetryRepo ?: return
    if (FirebaseGoogleAuthHelper.currentUser() == null) return
    val settings = _uiState.value.securitySettings
    if (!settings.shareCustomQuestionSuggestions) return
    val pending = settings.pendingCustomQuestionsToSync
      .split('\n')
      .map { it.trim() }
      .filter { it.length >= 5 }
    if (pending.isEmpty()) return

    var allOk = true
    for (q in pending) {
      val res = repo.submitCustomSecurityQuestion(q)
      if (res.isFailure) {
        allOk = false
      }
    }
    if (allOk) {
      prefsRepo.clearQueuedCustomQuestions()
    }
  }

  fun sendDiagnosticReport(
    userFeedback: String,
    selectedIssueIds: Set<String>,
  ) {
    val repo = cloudTelemetryRepo
    if (repo == null) {
      _uiState.update { it.copy(errorMessage = "Cloud database is unavailable in this environment.") }
      return
    }
    if (FirebaseGoogleAuthHelper.currentUser() == null) {
      _uiState.update { it.copy(errorMessage = "Sign in with Google first to send reports to the BeastLock database.") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isSendingDiagnosticReport = true) }
      val allIssues = _uiState.value.systemIssues
      val targets = allIssues.filter { it.id in selectedIssueIds && it.syncStatus == "PENDING" }
      val stateNow = _uiState.value
      val snapshotSummary = buildString {
        append("folders=${stateNow.folders.size};locked=${stateNow.lockedFoldersCount};")
        append("lockType=${stateNow.securitySettings.primaryLockType.name};")
        append("bio=${stateNow.securitySettings.biometricEnabled};")
        append("dualChecksum=${stateNow.securitySettings.dualKeyChecksumValid}")
      }

      var sentCount = 0
      var firstErr: String? = null

      if (targets.isNotEmpty()) {
        for (issue in targets) {
          repo.submitLocalIssueEntity(issue, userFeedback)
            .onSuccess { cloudId ->
              issueRecorder.markSent(issue.id, cloudId, userFeedback)
              sentCount++
            }
            .onFailure { err ->
              if (firstErr == null) firstErr = err.message ?: "Failed to upload diagnostic report"
            }
        }
      } else {
        val cleanFeedback = userFeedback.trim()
        if (cleanFeedback.isBlank()) {
          _uiState.update {
            it.copy(
              isSendingDiagnosticReport = false,
              errorMessage = "Write your feedback or select a recorded system issue to send.",
            )
          }
          return@launch
        }
        val localEntry = issueRecorder.recordIssue(
          category = IssueCategory.USER_FEEDBACK,
          errorCode = "USER_EXPRESSED_FEEDBACK",
          rawSummary = cleanFeedback.take(300),
          healthSnapshot = snapshotSummary,
          userFeedback = cleanFeedback,
        )
        repo.submitDiagnosticReport(
          category = IssueCategory.USER_FEEDBACK,
          errorCode = "USER_EXPRESSED_FEEDBACK",
          sanitizedSummary = cleanFeedback.take(300),
          userFeedback = cleanFeedback,
          healthSnapshot = snapshotSummary,
          occurredAtMillis = localEntry.occurredAtMillis,
        ).onSuccess { cloudId ->
          issueRecorder.markSent(localEntry.id, cloudId, cleanFeedback)
          sentCount = 1
        }.onFailure { err ->
          firstErr = err.message ?: "Failed to upload feedback"
        }
      }

      if (stateNow.securitySettings.shareCustomQuestionSuggestions &&
        stateNow.securitySettings.pendingCustomQuestionsToSync.isNotBlank()
      ) {
        flushQueuedCustomQuestionsIfSignedIn()
      }

      _uiState.update {
        it.copy(
          isSendingDiagnosticReport = false,
          statusMessage = if (firstErr == null) {
            "Sent $sentCount diagnostic report(s) to BeastLock database. Thank you!"
          } else null,
          errorMessage = firstErr,
        )
      }
    }
  }

  fun clearSentDiagnosticHistory() {
    viewModelScope.launch {
      issueRecorder.clearSentHistory()
      _uiState.update { it.copy(statusMessage = "Cleared locally archived sent reports") }
    }
  }

  fun setBatterySaverModeEnabled(enabled: Boolean) {
    viewModelScope.launch {
      prefsRepo.setBatterySaverModeEnabled(enabled)
      auditLogRepo.recordEvent(
        action = "POLICY_UPDATED",
        folderName = "Battery Saver",
        details = if (enabled) {
          "Battery Saver Mode enabled (pauses sync & heavy background encryption below 20% or in Power Save)"
        } else {
          "Battery Saver Mode disabled"
        },
        category = AuditCategory.ACCESS_EVENT,
      )
      if (!enabled) {
        val currentSettings = _uiState.value.securitySettings.copy(batterySaverModeEnabled = false)
        resumeBatteryPausedWorkIfReady(currentSettings, batteryMonitor.powerState.value)
      }
    }
  }

  fun refreshBatteryPowerState() {
    batteryMonitor.refreshFromSystem()
  }

  fun clearAuditLogs() {
    viewModelScope.launch { auditLogRepo.clearLogs() }
  }

  fun clearMessages() {
    _uiState.update { it.copy(statusMessage = null, errorMessage = null, authError = null, recoveryError = null) }
  }

  private fun formatMigrationBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
  }

  private fun readDeviceStorageInfo(): DeviceStorageInfo {
    return runCatching {
      val stat = StatFs(getApplication<Application>().filesDir.absolutePath)
      DeviceStorageInfo(
        totalDeviceBytes = stat.totalBytes,
        availableDeviceBytes = stat.availableBytes,
      )
    }.getOrDefault(DeviceStorageInfo())
  }

  private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
  )

  companion object {
    internal fun createForTest(
      application: Application,
      overrideTestKey: SecretKey,
    ): VaultViewModel = VaultViewModel(
      application = application,
      keystoreManager = KeystoreManager(application, overrideTestMasterKey = overrideTestKey),
      overrideAuditKey = overrideTestKey,
    )
  }
}
