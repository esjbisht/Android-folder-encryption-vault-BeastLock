package com.example.ui

import android.app.Application
import android.net.Uri
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.crypto.KeystoreManager
import com.example.data.audit.AuditCategory
import com.example.data.audit.AuditExportFormat
import com.example.data.audit.AuditExportScope
import com.example.data.audit.EncryptedAuditLogRepository
import com.example.data.backup.CloudBackupManager
import com.example.data.backup.DriveAuthExpiredException
import com.example.data.backup.DriveVaultBackupItem
import com.example.data.backup.GoogleDriveAuthHelper
import com.example.data.local.VaultDatabase
import com.example.data.preferences.SecurityPreferencesRepository
import com.example.data.preferences.SecuritySettings
import com.example.data.storage.FolderLockerRepository
import com.example.data.storage.MigrationExportFormat
import com.example.data.storage.VaultFileItem
import com.example.data.storage.VaultMaintenanceManager
import com.example.security.BatteryPowerMonitor
import com.example.security.DevicePowerState
import com.example.security.PanicSensitivity
import com.example.security.QuickTileCommand
import com.example.security.VaultQuickActionBus
import com.example.ui.theme.ThemeMode
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
  private val folderRepo = FolderLockerRepository(application, vaultDao, auditLogRepo)
  private val cloudBackupManager = CloudBackupManager(application, vaultDao, prefsRepo, auditLogRepo)
  private val maintenanceManager = VaultMaintenanceManager(application, vaultDao, auditLogRepo)
  private val batteryMonitor = BatteryPowerMonitor(application)

  private var sessionDek: SecretKey? = null
  private var driveAccessToken: String? = null
  private var backgroundedAtMillis: Long = 0L
  private var autoLockJob: Job? = null
  private var migrationJob: Job? = null
  private var integrityScanJob: Job? = null
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
    batteryMonitor.start()
    viewModelScope.launch {
      auditLogRepo.loadOrRefresh()
    }
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
    super.onCleared()
  }

  fun completeOnboarding(
    pin: String,
    enableBiometrics: Boolean,
    question1: String,
    answer1: String,
    question2: String,
    answer2: String,
  ) {
    viewModelScope.launch {
      runCatching {
        val dek = prefsRepo.initializeVault(
          pin = pin,
          enableBiometrics = enableBiometrics,
          question1 = question1,
          answer1 = answer1,
          question2 = question2,
          answer2 = answer2,
        )
        sessionDek = dek
        auditLogRepo.recordEvent(
          action = "VAULT_CREATED",
          folderName = "BeastLock",
          details = "Generated 256-bit AES DEK, hardware Keystore wrap & PBKDF2 recovery questions",
          category = AuditCategory.ACCESS_EVENT,
        )
        _uiState.update {
          it.copy(
            sessionState = VaultSessionState.Unlocked,
            securitySettings = it.securitySettings.copy(isVaultInitialized = true),
            authError = null,
            statusMessage = "Vault initialized with AES-256-GCM encryption",
          )
        }
        resetIdleTimer()
      }.onFailure { err ->
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

  fun onBiometricUnlockSuccess() {
    viewModelScope.launch {
      val dek = runCatching { prefsRepo.unlockWithKeystoreAfterBiometric() }.getOrNull()
      if (dek != null) {
        onSessionUnlocked(
          dek,
          authAction = "AUTH_BIOMETRIC",
          authDetails = "Authenticated via BiometricPrompt & Android Keystore",
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
      auditLogRepo.recordEvent(
        action = "FOLDER_ACCESSED",
        folderName = folder.name,
        details = "Opened folder explorer (${folder.fileCount} files, ${if (folder.isLocked) "locked" else "unlocked"})",
        folderId = folder.id,
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
    viewModelScope.launch {
      val dek = runCatching {
        prefsRepo.recoverAndResetPin(answer1, answer2, newPin)
      }.getOrNull()

      if (dek != null) {
        sessionDek = dek
        prefsRepo.markAutoLocked(false, "")
        prefsRepo.markPanicLocked(false, "")
        prefsRepo.recordBackgroundedAt(0L)
        auditLogRepo.recordEvent(
          action = "PIN_RECOVERED",
          folderName = "BeastLock",
          details = "Master PIN reset using verified security questions (DEK preserved)",
          category = AuditCategory.ACCESS_EVENT,
        )
        _uiState.update {
          it.copy(
            sessionState = VaultSessionState.Unlocked,
            authError = null,
            recoveryError = null,
            statusMessage = "Master PIN reset and vault unlocked",
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
    viewModelScope.launch {
      val details = if (source == "QUICK_TILE") {
        "Vault locked immediately via Android Quick Settings tile"
      } else {
        "Vault manually locked and session DEK cleared from memory"
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
        authError = null,
        recoveryError = null,
      )
    }

    if (source != "QUICK_TILE") {
      _panicEvents.tryEmit(settings.panicExitToDeviceHome)
    }

    viewModelScope.launch(NonCancellable) {
      val pausedByBattery = isBatterySaverBlockingNow()
      val sealedCount = if (settings.panicReencryptFolders && capturedDek != null && !pausedByBattery) {
        reencryptAllUnlockedFolders(capturedDek)
      } else {
        0
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

    val settings = _uiState.value.securitySettings
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
      val reencryptedCount = if (settings.autoLockReencryptFolders && !pausedByBattery) {
        reencryptAllUnlockedFolders(dek)
      } else {
        0
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
          authError = null,
        )
      }
    }
  }

  private suspend fun performIdleTimeoutLock(timeoutSeconds: Int) {
    val dek = sessionDek ?: return
    val settings = _uiState.value.securitySettings
    if (!isAppInForeground || !settings.idleTimeoutEnabled) return

    cancelIdleTimer()
    withContext(NonCancellable) {
      _uiState.update { it.copy(isAutoLockReencrypting = true) }

      val pausedByBattery = isBatterySaverBlockingNow()
      val reencryptedCount = if (settings.autoLockReencryptFolders && !pausedByBattery) {
        reencryptAllUnlockedFolders(dek)
      } else {
        0
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
  }

  fun addDeviceFolder(treeUri: Uri) {
    viewModelScope.launch {
      val obfuscate = _uiState.value.securitySettings.obfuscateFilenamesDefault
      folderRepo.registerSafFolder(treeUri, obfuscateDefault = obfuscate)
        .onSuccess { folder ->
          _uiState.update { it.copy(statusMessage = "Added \"${folder.name}\" to BeastLock") }
          if (folder.fileCount > 0 && !folder.isLocked) {
            requestLockFolderWithSelection(folder.id, reloadDetail = false)
          }
        }
        .onFailure { err ->
          _uiState.update { it.copy(errorMessage = err.message ?: "Failed to register folder") }
        }
    }
  }

  fun createPrivateFolder(name: String) {
    viewModelScope.launch {
      val obfuscate = _uiState.value.securitySettings.obfuscateFilenamesDefault
      folderRepo.createInternalVaultFolder(name, obfuscateDefault = obfuscate)
        .onSuccess { folder ->
          _uiState.update { it.copy(statusMessage = "Created private folder \"${folder.name}\"") }
        }
        .onFailure { err ->
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
        if (updated.autoCloudBackupSync) {
          maybeRunAutoCloudSync(dek)
        }
      }.onFailure { err ->
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

  fun verifyMasterPinForReencrypt(pin: String, onResult: (Boolean) -> Unit) {
    viewModelScope.launch {
      val ok = runCatching { prefsRepo.unlockWithPin(pin) != null }.getOrDefault(false)
      if (!ok) {
        auditLogRepo.recordEvent(
          action = "REENCRYPT_AUTH_FAILED",
          folderName = "Batch Key Rotation",
          details = "Incorrect Master PIN entered before Re-encrypt All key rotation",
          category = AuditCategory.FAILED_ATTEMPT,
          isWarning = true,
        )
      }
      onResult(ok)
    }
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
  ) {
    viewModelScope.launch {
      folderRepo.updateFolderSecurityOptions(folderId, requireBio, obfuscateFilenames)
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
          auditLogRepo.recordEvent(
            action = "FILE_PEEKED",
            folderName = item.displayName,
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
      val ok = prefsRepo.changePin(currentPin, newPin)
      if (ok) {
        auditLogRepo.recordEvent(
          action = "PIN_CHANGED",
          folderName = "BeastLock",
          details = "Re-wrapped 256-bit DEK with updated Master PIN",
          category = AuditCategory.CRYPTO_OPERATION,
        )
        _uiState.update { it.copy(statusMessage = "Master PIN updated") }
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
        auditLogRepo.recordEvent(
          action = "PATTERN_UPDATED",
          folderName = "BeastLock",
          details = "Wrapped 256-bit DEK with PBKDF2 3x3 Unlock Pattern fallback",
          category = AuditCategory.CRYPTO_OPERATION,
        )
        _uiState.update {
          it.copy(statusMessage = "Unlock Pattern fallback saved alongside Master PIN")
        }
      }
      onDone(ok)
    }
  }

  fun removeUnlockPattern() {
    viewModelScope.launch {
      prefsRepo.removeUnlockPattern()
      auditLogRepo.recordEvent(
        action = "PATTERN_REMOVED",
        folderName = "BeastLock",
        details = "Removed 3x3 Unlock Pattern fallback; Master PIN remains active",
        category = AuditCategory.CRYPTO_OPERATION,
      )
      _uiState.update {
        it.copy(statusMessage = "Unlock Pattern removed; 6-digit Master PIN remains active")
      }
    }
  }

  fun setPreferPatternFallback(prefer: Boolean) {
    viewModelScope.launch {
      prefsRepo.setPreferPatternFallback(prefer)
    }
  }

  fun updateSecurityQuestions(
    q1: String,
    a1: String,
    q2: String,
    a2: String,
    onDone: () -> Unit,
  ) {
    val dek = sessionDek ?: return
    viewModelScope.launch {
      prefsRepo.updateSecurityQuestions(dek, q1, a1, q2, a2)
      auditLogRepo.recordEvent(
        action = "RECOVERY_UPDATED",
        folderName = "BeastLock",
        details = "Updated PBKDF2 recovery questions",
        category = AuditCategory.CRYPTO_OPERATION,
      )
      _uiState.update { it.copy(statusMessage = "Recovery questions updated") }
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
      auditLogRepo.recordEvent(
        action = "POLICY_UPDATED",
        folderName = "Idle Timeout",
        details = if (enabled) "Foreground inactivity timeout enabled" else "Foreground inactivity timeout disabled",
        category = AuditCategory.ACCESS_EVENT,
      )
      if (enabled) {
        resetIdleTimer()
      } else {
        cancelIdleTimer()
      }
    }
  }

  fun setIdleTimeoutSeconds(seconds: Int) {
    val clamped = seconds.coerceAtLeast(1)
    viewModelScope.launch {
      prefsRepo.setIdleTimeoutSeconds(clamped)
      _uiState.update {
        it.copy(securitySettings = it.securitySettings.copy(idleTimeoutSeconds = clamped))
      }
      auditLogRepo.recordEvent(
        action = "POLICY_UPDATED",
        folderName = "Idle Timeout",
        details = "Foreground inactivity timeout set to ${formatDurationLabel(clamped)}",
        category = AuditCategory.ACCESS_EVENT,
      )
      resetIdleTimer()
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
    viewModelScope.launch {
      val ok = runCatching { prefsRepo.unlockWithPin(pin) != null }.getOrDefault(false)
      if (!ok) {
        auditLogRepo.recordEvent(
          action = "MIGRATION_AUTH_FAILED",
          folderName = "Exit Migration",
          details = "Incorrect Master PIN entered before decrypt & migrate export",
          category = AuditCategory.FAILED_ATTEMPT,
          isWarning = true,
        )
      }
      onResult(ok)
    }
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
