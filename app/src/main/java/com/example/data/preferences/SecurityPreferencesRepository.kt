package com.example.data.preferences

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.crypto.AesFolderCipher
import com.example.crypto.DualKeyVaultBundle
import com.example.crypto.KeystoreManager
import com.example.security.PanicSensitivity
import com.example.ui.theme.ThemeMode
import javax.crypto.Cipher
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.securityDataStore: DataStore<Preferences> by preferencesDataStore(name = "vault_security_prefs")

enum class PrimaryLockType(val label: String) {
  PATTERN("3×3 Pattern"),
  PIN_4("4-Digit PIN"),
  PIN_6("6-Digit PIN"),
  ;

  companion object {
    fun fromName(raw: String?): PrimaryLockType =
      entries.firstOrNull { it.name == raw } ?: PIN_6
  }
}

enum class StealthGestureMode(val label: String) {
  LONG_PRESS_SHIELD("Long-Press Hero Shield"),
  TRIPLE_TAP_SHIELD("Triple-Tap Hero Shield"),
  ;

  companion object {
    fun fromName(raw: String?): StealthGestureMode =
      entries.firstOrNull { it.name == raw } ?: LONG_PRESS_SHIELD
  }
}

data class DualKeyCredentialSnapshot(
  val keyFingerprint: String,
  val primaryLockType: PrimaryLockType,
  val pinLength: Int,
  val pinSaltB64: String?,
  val pinWrappedDek: String?,
  val pinVerifier: String?,
  val patternSaltB64: String?,
  val patternWrappedDek: String?,
  val patternVerifier: String?,
  val recoveryQ1: String,
  val recoveryQ2: String,
  val recoverySaltB64: String?,
  val recoveryWrappedDek: String?,
  val recoveryVerifier: String?,
  val biometricEnabled: Boolean,
)

data class SecuritySettings(
  val isVaultInitialized: Boolean = false,
  val primaryLockType: PrimaryLockType = PrimaryLockType.PIN_6,
  val pinLength: Int = 6,
  val hasPinCredential: Boolean = false,
  val biometricEnabled: Boolean = true,
  val autoLockEnabled: Boolean = true,
  val autoLockTimeoutSeconds: Int = 30,
  val autoLockReencryptFolders: Boolean = true,
  val requireBiometricAfterAutoLock: Boolean = true,
  val lastBackgroundedAtMillis: Long = 0L,
  val wasAutoLocked: Boolean = false,
  val lastAutoLockSummary: String = "",
  val panicModeEnabled: Boolean = true,
  val panicSensitivity: PanicSensitivity = PanicSensitivity.STANDARD,
  val panicExitToDeviceHome: Boolean = true,
  val panicReencryptFolders: Boolean = true,
  val wasPanicLocked: Boolean = false,
  val obfuscateFilenamesDefault: Boolean = true,
  val keyFingerprint: String = "",
  val failedAttempts: Int = 0,
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val dynamicColor: Boolean = false,
  val hasSecurityQuestions: Boolean = false,
  val securityQuestion1: String = "",
  val securityQuestion2: String = "",
  val shareCustomQuestionSuggestions: Boolean = true,
  val pendingCustomQuestionsToSync: String = "",
  val dualKeyExternalTreeUri: String = "",
  val dualKeyLastSyncAtMillis: Long = 0L,
  val dualKeyChecksumValid: Boolean = true,
  val dualKeyTamperAlert: String = "",
  val autoBackupTreeUri: String = "",
  val autoBackupProviderLabel: String = "Google Drive",
  val lastAutoBackupAtMillis: Long = 0L,
  val batterySaverModeEnabled: Boolean = true,
  val pendingBatteryPausedAutoSync: Boolean = false,
  val pendingBatteryPausedReencrypt: Boolean = false,
  val hasPatternLock: Boolean = false,
  val preferPatternFallback: Boolean = false,
  val patternStealthMode: Boolean = false,
  val googleDriveSyncConnected: Boolean = false,
  val googleDriveAccountEmail: String = "",
  val googleDriveAutoSyncEnabled: Boolean = false,
  val lastGoogleDriveSyncAtMillis: Long = 0L,
  val idleTimeoutEnabled: Boolean = true,
  val idleTimeoutSeconds: Int = 120,
  val hasCustomStealthCode: Boolean = false,
  val stealthGestureMode: StealthGestureMode = StealthGestureMode.LONG_PRESS_SHIELD,
  val alwaysBlockScreenshots: Boolean = false,
)

class SecurityPreferencesRepository(
  private val context: Context,
  private val keystoreManager: KeystoreManager,
) {
  private object Keys {
    val INITIALIZED = booleanPreferencesKey("vault_initialized")
    val PRIMARY_LOCK_TYPE = stringPreferencesKey("primary_lock_type")
    val PIN_LENGTH = intPreferencesKey("pin_length")
    val PIN_SALT = stringPreferencesKey("pin_salt")
    val PIN_VERIFIER = stringPreferencesKey("pin_verifier")
    val PIN_WRAPPED_DEK = stringPreferencesKey("pin_wrapped_dek")
    val KEYSTORE_WRAPPED_DEK = stringPreferencesKey("keystore_wrapped_dek")
    val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
    val AUTO_LOCK_ENABLED = booleanPreferencesKey("auto_lock_enabled")
    val AUTO_LOCK_SECONDS = intPreferencesKey("auto_lock_seconds")
    val AUTO_LOCK_REENCRYPT = booleanPreferencesKey("auto_lock_reencrypt_folders")
    val REQUIRE_BIO_AFTER_AUTOLOCK = booleanPreferencesKey("require_bio_after_autolock")
    val LAST_BACKGROUNDED_AT = longPreferencesKey("last_backgrounded_at")
    val WAS_AUTO_LOCKED = booleanPreferencesKey("was_auto_locked")
    val LAST_AUTOLOCK_SUMMARY = stringPreferencesKey("last_autolock_summary")
    val PANIC_MODE_ENABLED = booleanPreferencesKey("panic_mode_enabled")
    val PANIC_SENSITIVITY = stringPreferencesKey("panic_sensitivity")
    val PANIC_EXIT_TO_HOME = booleanPreferencesKey("panic_exit_to_home")
    val PANIC_REENCRYPT = booleanPreferencesKey("panic_reencrypt_folders")
    val WAS_PANIC_LOCKED = booleanPreferencesKey("was_panic_locked")
    val OBFUSCATE_DEFAULT = booleanPreferencesKey("obfuscate_filenames_default")
    val KEY_FINGERPRINT = stringPreferencesKey("key_fingerprint")
    val FAILED_ATTEMPTS = intPreferencesKey("failed_attempts")
    val DARK_THEME = booleanPreferencesKey("dark_theme")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")

    // Security Questions Recovery Keys (answers are never stored; only PBKDF2 salt + verifier + wrapped DEK)
    val RECOVERY_Q1 = stringPreferencesKey("recovery_question_1")
    val RECOVERY_Q2 = stringPreferencesKey("recovery_question_2")
    val RECOVERY_SALT = stringPreferencesKey("recovery_salt")
    val RECOVERY_VERIFIER = stringPreferencesKey("recovery_verifier")
    val RECOVERY_WRAPPED_DEK = stringPreferencesKey("recovery_wrapped_dek")
    val SHARE_CUSTOM_QUESTIONS = booleanPreferencesKey("share_custom_questions")
    val PENDING_CUSTOM_QUESTIONS = stringPreferencesKey("pending_custom_questions")
    val DUAL_KEY_EXTERNAL_TREE_URI = stringPreferencesKey("dual_key_external_tree_uri")
    val DUAL_KEY_LAST_SYNC_AT = longPreferencesKey("dual_key_last_sync_at")
    val DUAL_KEY_CHECKSUM_VALID = booleanPreferencesKey("dual_key_checksum_valid")
    val DUAL_KEY_TAMPER_ALERT = stringPreferencesKey("dual_key_tamper_alert")
    val AUTO_BACKUP_TREE_URI = stringPreferencesKey("auto_backup_tree_uri")
    val AUTO_BACKUP_PROVIDER = stringPreferencesKey("auto_backup_provider_label")
    val LAST_AUTO_BACKUP_AT = longPreferencesKey("last_auto_backup_at")
    val BATTERY_SAVER_ENABLED = booleanPreferencesKey("battery_saver_mode_enabled")
    val PENDING_BATTERY_PAUSED_SYNC = booleanPreferencesKey("pending_battery_paused_sync")
    val PENDING_BATTERY_PAUSED_REENCRYPT = booleanPreferencesKey("pending_battery_paused_reencrypt")
    val PATTERN_SALT = stringPreferencesKey("pattern_salt")
    val PATTERN_VERIFIER = stringPreferencesKey("pattern_verifier")
    val PATTERN_WRAPPED_DEK = stringPreferencesKey("pattern_wrapped_dek")
    val PREFER_PATTERN_FALLBACK = booleanPreferencesKey("prefer_pattern_fallback")
    val PATTERN_STEALTH_MODE = booleanPreferencesKey("pattern_stealth_mode")
    val GDRIVE_SYNC_CONNECTED = booleanPreferencesKey("gdrive_sync_connected")
    val GDRIVE_ACCOUNT_EMAIL = stringPreferencesKey("gdrive_account_email")
    val GDRIVE_AUTO_SYNC_ENABLED = booleanPreferencesKey("gdrive_auto_sync_enabled")
    val LAST_GDRIVE_SYNC_AT = longPreferencesKey("last_gdrive_sync_at")
    val IDLE_TIMEOUT_ENABLED = booleanPreferencesKey("idle_timeout_enabled")
    val IDLE_TIMEOUT_SECONDS = intPreferencesKey("idle_timeout_seconds")
    val STEALTH_CODE_SALT = stringPreferencesKey("stealth_code_salt")
    val STEALTH_CODE_VERIFIER = stringPreferencesKey("stealth_code_verifier")
    val STEALTH_GESTURE_MODE = stringPreferencesKey("stealth_gesture_mode")
    val ALWAYS_BLOCK_SCREENSHOTS = booleanPreferencesKey("always_block_screenshots")
  }

  val securitySettings: Flow<SecuritySettings> = context.securityDataStore.data.map { prefs ->
    val q1 = prefs[Keys.RECOVERY_Q1] ?: ""
    val q2 = prefs[Keys.RECOVERY_Q2] ?: ""
    val hasPattern = !prefs[Keys.PATTERN_VERIFIER].isNullOrBlank() && !prefs[Keys.PATTERN_WRAPPED_DEK].isNullOrBlank()
    val hasPin = !prefs[Keys.PIN_VERIFIER].isNullOrBlank() && !prefs[Keys.PIN_WRAPPED_DEK].isNullOrBlank()
    val resolvedPinLength = if ((prefs[Keys.PIN_LENGTH] ?: 6) == 4) 4 else 6
    val resolvedLockType = prefs[Keys.PRIMARY_LOCK_TYPE]?.let { PrimaryLockType.fromName(it) } ?: when {
      hasPattern && !hasPin -> PrimaryLockType.PATTERN
      resolvedPinLength == 4 -> PrimaryLockType.PIN_4
      else -> PrimaryLockType.PIN_6
    }
    val storedMode = prefs[Keys.THEME_MODE]
    val resolvedThemeMode = if (storedMode != null) {
      ThemeMode.fromName(storedMode)
    } else {
      when (prefs[Keys.DARK_THEME]) {
        true -> ThemeMode.DARK
        false -> ThemeMode.LIGHT
        null -> ThemeMode.SYSTEM
      }
    }
    SecuritySettings(
      isVaultInitialized = prefs[Keys.INITIALIZED] ?: false,
      primaryLockType = resolvedLockType,
      pinLength = resolvedPinLength,
      hasPinCredential = hasPin,
      biometricEnabled = prefs[Keys.BIOMETRIC_ENABLED] ?: true,
      autoLockEnabled = prefs[Keys.AUTO_LOCK_ENABLED] ?: true,
      autoLockTimeoutSeconds = prefs[Keys.AUTO_LOCK_SECONDS] ?: 30,
      autoLockReencryptFolders = prefs[Keys.AUTO_LOCK_REENCRYPT] ?: true,
      requireBiometricAfterAutoLock = prefs[Keys.REQUIRE_BIO_AFTER_AUTOLOCK] ?: true,
      lastBackgroundedAtMillis = prefs[Keys.LAST_BACKGROUNDED_AT] ?: 0L,
      wasAutoLocked = prefs[Keys.WAS_AUTO_LOCKED] ?: false,
      lastAutoLockSummary = prefs[Keys.LAST_AUTOLOCK_SUMMARY] ?: "",
      panicModeEnabled = prefs[Keys.PANIC_MODE_ENABLED] ?: true,
      panicSensitivity = PanicSensitivity.fromName(prefs[Keys.PANIC_SENSITIVITY]),
      panicExitToDeviceHome = prefs[Keys.PANIC_EXIT_TO_HOME] ?: true,
      panicReencryptFolders = prefs[Keys.PANIC_REENCRYPT] ?: true,
      wasPanicLocked = prefs[Keys.WAS_PANIC_LOCKED] ?: false,
      obfuscateFilenamesDefault = prefs[Keys.OBFUSCATE_DEFAULT] ?: true,
      keyFingerprint = prefs[Keys.KEY_FINGERPRINT] ?: "",
      failedAttempts = prefs[Keys.FAILED_ATTEMPTS] ?: 0,
      themeMode = resolvedThemeMode,
      dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
      hasSecurityQuestions = q1.isNotBlank() && q2.isNotBlank() && !prefs[Keys.RECOVERY_WRAPPED_DEK].isNullOrBlank(),
      securityQuestion1 = q1,
      securityQuestion2 = q2,
      shareCustomQuestionSuggestions = prefs[Keys.SHARE_CUSTOM_QUESTIONS] ?: true,
      pendingCustomQuestionsToSync = prefs[Keys.PENDING_CUSTOM_QUESTIONS] ?: "",
      dualKeyExternalTreeUri = prefs[Keys.DUAL_KEY_EXTERNAL_TREE_URI] ?: "",
      dualKeyLastSyncAtMillis = prefs[Keys.DUAL_KEY_LAST_SYNC_AT] ?: 0L,
      dualKeyChecksumValid = prefs[Keys.DUAL_KEY_CHECKSUM_VALID] ?: true,
      dualKeyTamperAlert = prefs[Keys.DUAL_KEY_TAMPER_ALERT] ?: "",
      autoBackupTreeUri = prefs[Keys.AUTO_BACKUP_TREE_URI] ?: "",
      autoBackupProviderLabel = prefs[Keys.AUTO_BACKUP_PROVIDER] ?: "Google Drive",
      lastAutoBackupAtMillis = prefs[Keys.LAST_AUTO_BACKUP_AT] ?: 0L,
      batterySaverModeEnabled = prefs[Keys.BATTERY_SAVER_ENABLED] ?: true,
      pendingBatteryPausedAutoSync = prefs[Keys.PENDING_BATTERY_PAUSED_SYNC] ?: false,
      pendingBatteryPausedReencrypt = prefs[Keys.PENDING_BATTERY_PAUSED_REENCRYPT] ?: false,
      hasPatternLock = hasPattern,
      preferPatternFallback = prefs[Keys.PREFER_PATTERN_FALLBACK] ?: false,
      patternStealthMode = prefs[Keys.PATTERN_STEALTH_MODE] ?: false,
      googleDriveSyncConnected = prefs[Keys.GDRIVE_SYNC_CONNECTED] ?: false,
      googleDriveAccountEmail = prefs[Keys.GDRIVE_ACCOUNT_EMAIL] ?: "",
      googleDriveAutoSyncEnabled = prefs[Keys.GDRIVE_AUTO_SYNC_ENABLED] ?: false,
      lastGoogleDriveSyncAtMillis = prefs[Keys.LAST_GDRIVE_SYNC_AT] ?: 0L,
      idleTimeoutEnabled = prefs[Keys.IDLE_TIMEOUT_ENABLED] ?: true,
      idleTimeoutSeconds = (prefs[Keys.IDLE_TIMEOUT_SECONDS] ?: 120).coerceAtLeast(1),
      hasCustomStealthCode = !prefs[Keys.STEALTH_CODE_VERIFIER].isNullOrBlank() &&
        !prefs[Keys.STEALTH_CODE_SALT].isNullOrBlank(),
      stealthGestureMode = StealthGestureMode.fromName(prefs[Keys.STEALTH_GESTURE_MODE]),
      alwaysBlockScreenshots = prefs[Keys.ALWAYS_BLOCK_SCREENSHOTS] ?: false,
    )
  }

  suspend fun initializeVault(
    pin: String,
    enableBiometrics: Boolean,
    question1: String,
    answer1: String,
    question2: String,
    answer2: String,
  ): SecretKey {
    val resolvedType = if (pin.length == 4) PrimaryLockType.PIN_4 else PrimaryLockType.PIN_6
    return initializeVaultWithChoice(
      lockType = resolvedType,
      pin = pin,
      patternNodes = null,
      enableBiometrics = enableBiometrics,
      question1 = question1,
      answer1 = answer1,
      question2 = question2,
      answer2 = answer2,
      shareCustomQuestionSuggestions = false,
    )
  }

  suspend fun initializeVaultWithChoice(
    lockType: PrimaryLockType,
    pin: String?,
    patternNodes: List<Int>?,
    enableBiometrics: Boolean,
    question1: String,
    answer1: String,
    question2: String,
    answer2: String,
    shareCustomQuestionSuggestions: Boolean = true,
  ): SecretKey {
    val dek = AesFolderCipher.generateAes256Key()
    val keystoreWrapped = keystoreManager.wrapDekWithKeystore(dek)
    val fingerprint = AesFolderCipher.computeKeyFingerprint(dek)

    val pinSalt = pin?.takeIf { it.length == 4 || it.length == 6 }?.let { AesFolderCipher.generateSalt() }
    val pinWrapped = if (pin != null && pinSalt != null) {
      val pinKey = AesFolderCipher.derivePinKey(pin, pinSalt)
      AesFolderCipher.wrapDekWithKey(dek, pinKey)
    } else null
    val pinVerifier = if (pin != null && pinSalt != null) {
      AesFolderCipher.computePinVerifier(pin, pinSalt)
    } else null

    val canonicalPattern = patternNodes?.let { canonicalPatternString(it) }
    val patternSalt = canonicalPattern?.let { AesFolderCipher.generateSalt() }
    val patternWrapped = if (canonicalPattern != null && patternSalt != null) {
      val pKey = AesFolderCipher.derivePinKey(canonicalPattern, patternSalt)
      AesFolderCipher.wrapDekWithKey(dek, pKey)
    } else null
    val patternVerifier = if (canonicalPattern != null && patternSalt != null) {
      AesFolderCipher.computePinVerifier(canonicalPattern, patternSalt)
    } else null

    require(pinWrapped != null || patternWrapped != null) {
      "Either a Master PIN or a 3×3 Pattern must be configured."
    }

    val recoverySalt = AesFolderCipher.generateSalt()
    val normalizedAnswers = normalizeRecoveryAnswers(answer1, answer2)
    val recoveryKey = AesFolderCipher.derivePinKey(normalizedAnswers, recoverySalt)
    val recoveryWrapped = AesFolderCipher.wrapDekWithKey(dek, recoveryKey)
    val recoveryVerifier = AesFolderCipher.computePinVerifier(normalizedAnswers, recoverySalt)
    val resolvedPinLength = when {
      pin != null && pin.length == 4 -> 4
      lockType == PrimaryLockType.PIN_4 -> 4
      else -> 6
    }

    context.securityDataStore.edit { prefs ->
      prefs[Keys.INITIALIZED] = true
      prefs[Keys.PRIMARY_LOCK_TYPE] = lockType.name
      prefs[Keys.PIN_LENGTH] = resolvedPinLength
      if (pinSalt != null && pinVerifier != null && pinWrapped != null) {
        prefs[Keys.PIN_SALT] = Base64.encodeToString(pinSalt, Base64.NO_WRAP)
        prefs[Keys.PIN_VERIFIER] = pinVerifier
        prefs[Keys.PIN_WRAPPED_DEK] = pinWrapped
      } else {
        prefs.remove(Keys.PIN_SALT)
        prefs.remove(Keys.PIN_VERIFIER)
        prefs.remove(Keys.PIN_WRAPPED_DEK)
      }
      if (patternSalt != null && patternVerifier != null && patternWrapped != null) {
        prefs[Keys.PATTERN_SALT] = Base64.encodeToString(patternSalt, Base64.NO_WRAP)
        prefs[Keys.PATTERN_VERIFIER] = patternVerifier
        prefs[Keys.PATTERN_WRAPPED_DEK] = patternWrapped
        prefs[Keys.PREFER_PATTERN_FALLBACK] = (lockType == PrimaryLockType.PATTERN)
      } else {
        prefs.remove(Keys.PATTERN_SALT)
        prefs.remove(Keys.PATTERN_VERIFIER)
        prefs.remove(Keys.PATTERN_WRAPPED_DEK)
        prefs[Keys.PREFER_PATTERN_FALLBACK] = false
      }
      prefs[Keys.KEYSTORE_WRAPPED_DEK] = keystoreWrapped
      prefs[Keys.BIOMETRIC_ENABLED] = enableBiometrics
      prefs[Keys.KEY_FINGERPRINT] = fingerprint
      prefs[Keys.FAILED_ATTEMPTS] = 0
      prefs[Keys.AUTO_LOCK_SECONDS] = 30
      prefs[Keys.OBFUSCATE_DEFAULT] = true
      prefs[Keys.RECOVERY_Q1] = question1.trim()
      prefs[Keys.RECOVERY_Q2] = question2.trim()
      prefs[Keys.RECOVERY_SALT] = Base64.encodeToString(recoverySalt, Base64.NO_WRAP)
      prefs[Keys.RECOVERY_VERIFIER] = recoveryVerifier
      prefs[Keys.RECOVERY_WRAPPED_DEK] = recoveryWrapped
      prefs[Keys.SHARE_CUSTOM_QUESTIONS] = shareCustomQuestionSuggestions
    }
    return dek
  }

  suspend fun unlockWithPin(pin: String): SecretKey? {
    val prefs = context.securityDataStore.data.first()
    val saltB64 = prefs[Keys.PIN_SALT] ?: return null
    val verifier = prefs[Keys.PIN_VERIFIER] ?: return null
    val wrappedDek = prefs[Keys.PIN_WRAPPED_DEK] ?: return null
    val salt = Base64.decode(saltB64, Base64.NO_WRAP)

    val candidateVerifier = AesFolderCipher.computePinVerifier(pin, salt)
    if (candidateVerifier != verifier) {
      context.securityDataStore.edit { p ->
        p[Keys.FAILED_ATTEMPTS] = (p[Keys.FAILED_ATTEMPTS] ?: 0) + 1
      }
      return null
    }

    val pinKey = AesFolderCipher.derivePinKey(pin, salt)
    val dek = AesFolderCipher.unwrapDekWithKey(wrappedDek, pinKey)
    context.securityDataStore.edit { p -> p[Keys.FAILED_ATTEMPTS] = 0 }
    return dek
  }

  suspend fun prepareKeystoreUnwrapCipher(): Cipher? {
    val prefs = context.securityDataStore.data.first()
    val wrapped = prefs[Keys.KEYSTORE_WRAPPED_DEK] ?: return null
    return runCatching { keystoreManager.prepareDekUnwrapCipher(wrapped) }.getOrNull()
  }

  suspend fun unlockWithKeystoreCipherAfterBiometric(unlockedCipher: Cipher?): SecretKey? {
    val prefs = context.securityDataStore.data.first()
    val wrapped = prefs[Keys.KEYSTORE_WRAPPED_DEK] ?: return null
    val dek = if (unlockedCipher != null) {
      keystoreManager.finishUnwrapDekWithCipher(wrapped, unlockedCipher)
    } else {
      keystoreManager.unwrapDekWithKeystore(wrapped)
    }
    context.securityDataStore.edit { p -> p[Keys.FAILED_ATTEMPTS] = 0 }
    return dek
  }

  suspend fun unlockWithKeystoreAfterBiometric(): SecretKey? =
    unlockWithKeystoreCipherAfterBiometric(unlockedCipher = null)

  suspend fun recoverAndResetPin(
    answer1: String,
    answer2: String,
    newPin: String,
  ): SecretKey? = recoverWithSecurityQuestions(
    answer1 = answer1,
    answer2 = answer2,
    newPin = newPin,
    newPatternNodes = null,
    newLockType = if (newPin.length == 4) PrimaryLockType.PIN_4 else PrimaryLockType.PIN_6,
  )

  suspend fun recoverWithSecurityQuestions(
    answer1: String,
    answer2: String,
    newPin: String? = null,
    newPatternNodes: List<Int>? = null,
    newLockType: PrimaryLockType? = null,
  ): SecretKey? {
    val prefs = context.securityDataStore.data.first()
    val recoverySaltB64 = prefs[Keys.RECOVERY_SALT] ?: return null
    val recoveryVerifier = prefs[Keys.RECOVERY_VERIFIER] ?: return null
    val recoveryWrappedDek = prefs[Keys.RECOVERY_WRAPPED_DEK] ?: return null

    val recoverySalt = Base64.decode(recoverySaltB64, Base64.NO_WRAP)
    val normalizedAnswers = normalizeRecoveryAnswers(answer1, answer2)
    val candidateVerifier = AesFolderCipher.computePinVerifier(normalizedAnswers, recoverySalt)
    if (candidateVerifier != recoveryVerifier) {
      context.securityDataStore.edit { p ->
        p[Keys.FAILED_ATTEMPTS] = (p[Keys.FAILED_ATTEMPTS] ?: 0) + 1
      }
      return null
    }

    val recoveryKey = AesFolderCipher.derivePinKey(normalizedAnswers, recoverySalt)
    val dek = AesFolderCipher.unwrapDekWithKey(recoveryWrappedDek, recoveryKey)
    val newKeystoreWrapped = keystoreManager.wrapDekWithKeystore(dek)

    val validNewPin = newPin?.takeIf { it.length == 4 || it.length == 6 }
    val canonicalPattern = newPatternNodes?.let { canonicalPatternString(it) }

    context.securityDataStore.edit { p ->
      if (validNewPin != null) {
        val newPinSalt = AesFolderCipher.generateSalt()
        val newPinKey = AesFolderCipher.derivePinKey(validNewPin, newPinSalt)
        p[Keys.PIN_SALT] = Base64.encodeToString(newPinSalt, Base64.NO_WRAP)
        p[Keys.PIN_VERIFIER] = AesFolderCipher.computePinVerifier(validNewPin, newPinSalt)
        p[Keys.PIN_WRAPPED_DEK] = AesFolderCipher.wrapDekWithKey(dek, newPinKey)
        p[Keys.PIN_LENGTH] = validNewPin.length
      }
      if (canonicalPattern != null) {
        val pSalt = AesFolderCipher.generateSalt()
        val pKey = AesFolderCipher.derivePinKey(canonicalPattern, pSalt)
        p[Keys.PATTERN_SALT] = Base64.encodeToString(pSalt, Base64.NO_WRAP)
        p[Keys.PATTERN_VERIFIER] = AesFolderCipher.computePinVerifier(canonicalPattern, pSalt)
        p[Keys.PATTERN_WRAPPED_DEK] = AesFolderCipher.wrapDekWithKey(dek, pKey)
        p[Keys.PREFER_PATTERN_FALLBACK] = (newLockType == PrimaryLockType.PATTERN)
      }
      if (newLockType != null) {
        p[Keys.PRIMARY_LOCK_TYPE] = newLockType.name
      }
      p[Keys.KEYSTORE_WRAPPED_DEK] = newKeystoreWrapped
      p[Keys.FAILED_ATTEMPTS] = 0
    }
    return dek
  }

  suspend fun updateSecurityQuestions(
    dek: SecretKey,
    question1: String,
    answer1: String,
    question2: String,
    answer2: String,
  ) {
    val recoverySalt = AesFolderCipher.generateSalt()
    val normalizedAnswers = normalizeRecoveryAnswers(answer1, answer2)
    val recoveryKey = AesFolderCipher.derivePinKey(normalizedAnswers, recoverySalt)
    val recoveryWrapped = AesFolderCipher.wrapDekWithKey(dek, recoveryKey)
    val recoveryVerifier = AesFolderCipher.computePinVerifier(normalizedAnswers, recoverySalt)

    context.securityDataStore.edit { p ->
      p[Keys.RECOVERY_Q1] = question1.trim()
      p[Keys.RECOVERY_Q2] = question2.trim()
      p[Keys.RECOVERY_SALT] = Base64.encodeToString(recoverySalt, Base64.NO_WRAP)
      p[Keys.RECOVERY_VERIFIER] = recoveryVerifier
      p[Keys.RECOVERY_WRAPPED_DEK] = recoveryWrapped
    }
  }

  suspend fun getPinWrappedBackupCredentials(): Triple<String, String, String>? {
    val prefs = context.securityDataStore.data.first()
    val salt = prefs[Keys.PIN_SALT] ?: return null
    val verifier = prefs[Keys.PIN_VERIFIER] ?: return null
    val wrapped = prefs[Keys.PIN_WRAPPED_DEK] ?: return null
    return Triple(salt, verifier, wrapped)
  }

  suspend fun changePin(currentPin: String, newPin: String): Boolean {
    val dek = unlockWithPin(currentPin) ?: return false
    return setOrUpdatePin(dek = dek, newPin = newPin, makePrimaryIfPin = true)
  }

  suspend fun setOrUpdatePin(
    dek: SecretKey,
    newPin: String,
    makePrimaryIfPin: Boolean = false,
  ): Boolean {
    if (newPin.length != 4 && newPin.length != 6) return false
    val newSalt = AesFolderCipher.generateSalt()
    val newPinKey = AesFolderCipher.derivePinKey(newPin, newSalt)
    val newWrapped = AesFolderCipher.wrapDekWithKey(dek, newPinKey)
    val newVerifier = AesFolderCipher.computePinVerifier(newPin, newSalt)
    val nextPinType = if (newPin.length == 4) PrimaryLockType.PIN_4 else PrimaryLockType.PIN_6

    context.securityDataStore.edit { prefs ->
      prefs[Keys.PIN_SALT] = Base64.encodeToString(newSalt, Base64.NO_WRAP)
      prefs[Keys.PIN_VERIFIER] = newVerifier
      prefs[Keys.PIN_WRAPPED_DEK] = newWrapped
      prefs[Keys.PIN_LENGTH] = newPin.length
      val currentType = PrimaryLockType.fromName(prefs[Keys.PRIMARY_LOCK_TYPE])
      if (makePrimaryIfPin && currentType != PrimaryLockType.PATTERN) {
        prefs[Keys.PRIMARY_LOCK_TYPE] = nextPinType.name
      }
      prefs[Keys.FAILED_ATTEMPTS] = 0
    }
    return true
  }

  suspend fun verifyCurrentPin(pin: String): Boolean = unlockWithPin(pin) != null

  suspend fun setBiometricEnabled(enabled: Boolean) {
    context.securityDataStore.edit { it[Keys.BIOMETRIC_ENABLED] = enabled }
  }

  suspend fun setAutoLockEnabled(enabled: Boolean) {
    context.securityDataStore.edit { it[Keys.AUTO_LOCK_ENABLED] = enabled }
  }

  suspend fun setAutoLockTimeout(seconds: Int) {
    context.securityDataStore.edit { it[Keys.AUTO_LOCK_SECONDS] = seconds.coerceAtLeast(0) }
  }

  suspend fun setAutoLockReencryptFolders(enabled: Boolean) {
    context.securityDataStore.edit { it[Keys.AUTO_LOCK_REENCRYPT] = enabled }
  }

  suspend fun setRequireBiometricAfterAutoLock(enabled: Boolean) {
    context.securityDataStore.edit { it[Keys.REQUIRE_BIO_AFTER_AUTOLOCK] = enabled }
  }

  suspend fun recordBackgroundedAt(timestampMillis: Long) {
    context.securityDataStore.edit { it[Keys.LAST_BACKGROUNDED_AT] = timestampMillis }
  }

  suspend fun markAutoLocked(wasAutoLocked: Boolean, summary: String = "") {
    context.securityDataStore.edit {
      it[Keys.WAS_AUTO_LOCKED] = wasAutoLocked
      if (wasAutoLocked) {
        it[Keys.WAS_PANIC_LOCKED] = false
        it[Keys.LAST_AUTOLOCK_SUMMARY] = summary
        it[Keys.LAST_BACKGROUNDED_AT] = 0L
      } else if (!(it[Keys.WAS_PANIC_LOCKED] ?: false)) {
        it[Keys.LAST_AUTOLOCK_SUMMARY] = summary
      }
    }
  }

  suspend fun markPanicLocked(wasPanicLocked: Boolean, summary: String = "") {
    context.securityDataStore.edit {
      it[Keys.WAS_PANIC_LOCKED] = wasPanicLocked
      if (wasPanicLocked) {
        it[Keys.WAS_AUTO_LOCKED] = false
        it[Keys.LAST_AUTOLOCK_SUMMARY] = summary
        it[Keys.LAST_BACKGROUNDED_AT] = 0L
      } else {
        it[Keys.WAS_AUTO_LOCKED] = false
        it[Keys.LAST_AUTOLOCK_SUMMARY] = summary
      }
    }
  }

  suspend fun setPanicModeEnabled(enabled: Boolean) {
    context.securityDataStore.edit { it[Keys.PANIC_MODE_ENABLED] = enabled }
  }

  suspend fun setPanicSensitivity(sensitivity: PanicSensitivity) {
    context.securityDataStore.edit { it[Keys.PANIC_SENSITIVITY] = sensitivity.name }
  }

  suspend fun setPanicExitToDeviceHome(enabled: Boolean) {
    context.securityDataStore.edit { it[Keys.PANIC_EXIT_TO_HOME] = enabled }
  }

  suspend fun setPanicReencryptFolders(enabled: Boolean) {
    context.securityDataStore.edit { it[Keys.PANIC_REENCRYPT] = enabled }
  }

  suspend fun setObfuscateFilenamesDefault(enabled: Boolean) {
    context.securityDataStore.edit { it[Keys.OBFUSCATE_DEFAULT] = enabled }
  }

  suspend fun setThemeMode(mode: ThemeMode) {
    context.securityDataStore.edit { it[Keys.THEME_MODE] = mode.name }
  }

  suspend fun setDynamicColor(enabled: Boolean) {
    context.securityDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
  }

  suspend fun setAutoBackupDestination(treeUri: String, providerLabel: String) {
    context.securityDataStore.edit {
      it[Keys.AUTO_BACKUP_TREE_URI] = treeUri
      it[Keys.AUTO_BACKUP_PROVIDER] = providerLabel
    }
  }

  suspend fun clearAutoBackupDestination() {
    context.securityDataStore.edit {
      it.remove(Keys.AUTO_BACKUP_TREE_URI)
    }
  }

  suspend fun recordAutoBackupTimestamp(timestampMillis: Long) {
    context.securityDataStore.edit { it[Keys.LAST_AUTO_BACKUP_AT] = timestampMillis }
  }

  suspend fun setBatterySaverModeEnabled(enabled: Boolean) {
    context.securityDataStore.edit { it[Keys.BATTERY_SAVER_ENABLED] = enabled }
  }

  suspend fun setPendingBatteryPausedAutoSync(pending: Boolean) {
    context.securityDataStore.edit { it[Keys.PENDING_BATTERY_PAUSED_SYNC] = pending }
  }

  suspend fun setPendingBatteryPausedReencrypt(pending: Boolean) {
    context.securityDataStore.edit { it[Keys.PENDING_BATTERY_PAUSED_REENCRYPT] = pending }
  }

  suspend fun setUnlockPattern(
    dek: SecretKey,
    patternNodes: List<Int>,
    makeDefaultFallback: Boolean = true,
  ): Boolean {
    val canonical = canonicalPatternString(patternNodes) ?: return false
    val salt = AesFolderCipher.generateSalt()
    val patternKey = AesFolderCipher.derivePinKey(canonical, salt)
    val patternWrapped = AesFolderCipher.wrapDekWithKey(dek, patternKey)
    val patternVerifier = AesFolderCipher.computePinVerifier(canonical, salt)

    context.securityDataStore.edit { prefs ->
      prefs[Keys.PATTERN_SALT] = Base64.encodeToString(salt, Base64.NO_WRAP)
      prefs[Keys.PATTERN_VERIFIER] = patternVerifier
      prefs[Keys.PATTERN_WRAPPED_DEK] = patternWrapped
      prefs[Keys.PREFER_PATTERN_FALLBACK] = makeDefaultFallback
    }
    return true
  }

  suspend fun unlockWithPattern(patternNodes: List<Int>): SecretKey? {
    val canonical = canonicalPatternString(patternNodes) ?: return null
    val prefs = context.securityDataStore.data.first()
    val saltB64 = prefs[Keys.PATTERN_SALT] ?: return null
    val verifier = prefs[Keys.PATTERN_VERIFIER] ?: return null
    val wrappedDek = prefs[Keys.PATTERN_WRAPPED_DEK] ?: return null
    val salt = Base64.decode(saltB64, Base64.NO_WRAP)

    val candidateVerifier = AesFolderCipher.computePinVerifier(canonical, salt)
    if (candidateVerifier != verifier) {
      context.securityDataStore.edit { p ->
        p[Keys.FAILED_ATTEMPTS] = (p[Keys.FAILED_ATTEMPTS] ?: 0) + 1
      }
      return null
    }

    val patternKey = AesFolderCipher.derivePinKey(canonical, salt)
    val dek = AesFolderCipher.unwrapDekWithKey(wrappedDek, patternKey)
    context.securityDataStore.edit { p -> p[Keys.FAILED_ATTEMPTS] = 0 }
    return dek
  }

  suspend fun removeUnlockPattern() {
    context.securityDataStore.edit { prefs ->
      prefs.remove(Keys.PATTERN_SALT)
      prefs.remove(Keys.PATTERN_VERIFIER)
      prefs.remove(Keys.PATTERN_WRAPPED_DEK)
      prefs[Keys.PREFER_PATTERN_FALLBACK] = false
      prefs[Keys.PATTERN_STEALTH_MODE] = false
    }
  }

  suspend fun setPreferPatternFallback(prefer: Boolean) {
    context.securityDataStore.edit { prefs ->
      prefs[Keys.PREFER_PATTERN_FALLBACK] = prefer
    }
  }

  suspend fun setPatternStealthMode(enabled: Boolean) {
    context.securityDataStore.edit { prefs ->
      prefs[Keys.PATTERN_STEALTH_MODE] = enabled
    }
  }

  suspend fun setGoogleDriveConnection(connected: Boolean, email: String = "") {
    context.securityDataStore.edit { prefs ->
      prefs[Keys.GDRIVE_SYNC_CONNECTED] = connected
      if (email.isNotBlank()) {
        prefs[Keys.GDRIVE_ACCOUNT_EMAIL] = email
      } else if (!connected) {
        prefs.remove(Keys.GDRIVE_ACCOUNT_EMAIL)
      }
      if (!connected) {
        prefs[Keys.GDRIVE_AUTO_SYNC_ENABLED] = false
      }
    }
  }

  suspend fun setGoogleDriveAutoSyncEnabled(enabled: Boolean) {
    context.securityDataStore.edit { prefs ->
      prefs[Keys.GDRIVE_AUTO_SYNC_ENABLED] = enabled
    }
  }

  suspend fun recordGoogleDriveSyncTimestamp(timestampMillis: Long) {
    context.securityDataStore.edit { prefs ->
      prefs[Keys.LAST_GDRIVE_SYNC_AT] = timestampMillis
    }
  }

  suspend fun setIdleTimeoutEnabled(enabled: Boolean) {
    context.securityDataStore.edit { prefs ->
      prefs[Keys.IDLE_TIMEOUT_ENABLED] = enabled
    }
  }

  suspend fun setIdleTimeoutSeconds(seconds: Int) {
    context.securityDataStore.edit { prefs ->
      prefs[Keys.IDLE_TIMEOUT_SECONDS] = seconds.coerceAtLeast(1)
    }
  }

  suspend fun setStealthGestureMode(mode: StealthGestureMode) {
    context.securityDataStore.edit { prefs ->
      prefs[Keys.STEALTH_GESTURE_MODE] = mode.name
    }
  }

  suspend fun setAlwaysBlockScreenshots(enabled: Boolean) {
    context.securityDataStore.edit { prefs ->
      prefs[Keys.ALWAYS_BLOCK_SCREENSHOTS] = enabled
    }
  }

  suspend fun setStealthAccessCode(rawCode: String): Boolean {
    val clean = normalizeStealthCode(rawCode) ?: return false
    val salt = AesFolderCipher.generateSalt()
    val verifier = AesFolderCipher.computePinVerifier(clean, salt)
    context.securityDataStore.edit { prefs ->
      prefs[Keys.STEALTH_CODE_SALT] = Base64.encodeToString(salt, Base64.NO_WRAP)
      prefs[Keys.STEALTH_CODE_VERIFIER] = verifier
    }
    return true
  }

  suspend fun verifyStealthAccessCode(candidate: String): Boolean {
    val clean = candidate.trim()
    if (clean.length < 4) return false
    val prefs = context.securityDataStore.data.first()
    val saltB64 = prefs[Keys.STEALTH_CODE_SALT]
    val verifier = prefs[Keys.STEALTH_CODE_VERIFIER]
    return if (!saltB64.isNullOrBlank() && !verifier.isNullOrBlank()) {
      val salt = Base64.decode(saltB64, Base64.NO_WRAP)
      AesFolderCipher.computePinVerifier(clean, salt) == verifier
    } else {
      clean == DEFAULT_STEALTH_CODE
    }
  }

  suspend fun setPrimaryLockType(lockType: PrimaryLockType) {
    context.securityDataStore.edit { prefs ->
      prefs[Keys.PRIMARY_LOCK_TYPE] = lockType.name
      if (lockType == PrimaryLockType.PIN_4) prefs[Keys.PIN_LENGTH] = 4
      if (lockType == PrimaryLockType.PIN_6) prefs[Keys.PIN_LENGTH] = 6
      prefs[Keys.PREFER_PATTERN_FALLBACK] = (lockType == PrimaryLockType.PATTERN)
    }
  }

  suspend fun setShareCustomQuestionSuggestions(enabled: Boolean) {
    context.securityDataStore.edit { prefs ->
      prefs[Keys.SHARE_CUSTOM_QUESTIONS] = enabled
    }
  }

  suspend fun queueCustomQuestionsForSync(questions: List<String>) {
    val clean = questions.map { it.trim() }.filter { it.length >= 5 }.distinct()
    if (clean.isEmpty()) return
    context.securityDataStore.edit { prefs ->
      val existing = prefs[Keys.PENDING_CUSTOM_QUESTIONS].orEmpty()
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
      val merged = (existing + clean).distinct().take(10)
      prefs[Keys.PENDING_CUSTOM_QUESTIONS] = merged.joinToString("\n")
    }
  }

  suspend fun clearQueuedCustomQuestions() {
    context.securityDataStore.edit { prefs ->
      prefs.remove(Keys.PENDING_CUSTOM_QUESTIONS)
    }
  }

  suspend fun setDualKeyExternalTreeUri(treeUri: String) {
    context.securityDataStore.edit { prefs ->
      if (treeUri.isBlank()) {
        prefs.remove(Keys.DUAL_KEY_EXTERNAL_TREE_URI)
      } else {
        prefs[Keys.DUAL_KEY_EXTERNAL_TREE_URI] = treeUri
      }
    }
  }

  suspend fun recordDualKeySyncStatus(
    syncedAtMillis: Long,
    checksumValid: Boolean,
    tamperAlert: String? = null,
  ) {
    context.securityDataStore.edit { prefs ->
      prefs[Keys.DUAL_KEY_LAST_SYNC_AT] = syncedAtMillis
      prefs[Keys.DUAL_KEY_CHECKSUM_VALID] = checksumValid
      if (tamperAlert.isNullOrBlank()) {
        prefs.remove(Keys.DUAL_KEY_TAMPER_ALERT)
      } else {
        prefs[Keys.DUAL_KEY_TAMPER_ALERT] = tamperAlert
      }
    }
  }

  suspend fun getKeyWrapSnapshot(): DualKeyCredentialSnapshot? {
    val prefs = context.securityDataStore.data.first()
    if (prefs[Keys.INITIALIZED] != true) return null
    val hasPattern = !prefs[Keys.PATTERN_VERIFIER].isNullOrBlank() && !prefs[Keys.PATTERN_WRAPPED_DEK].isNullOrBlank()
    val hasPin = !prefs[Keys.PIN_VERIFIER].isNullOrBlank() && !prefs[Keys.PIN_WRAPPED_DEK].isNullOrBlank()
    val pinLen = if ((prefs[Keys.PIN_LENGTH] ?: 6) == 4) 4 else 6
    val lockType = prefs[Keys.PRIMARY_LOCK_TYPE]?.let { PrimaryLockType.fromName(it) } ?: when {
      hasPattern && !hasPin -> PrimaryLockType.PATTERN
      pinLen == 4 -> PrimaryLockType.PIN_4
      else -> PrimaryLockType.PIN_6
    }
    return DualKeyCredentialSnapshot(
      keyFingerprint = prefs[Keys.KEY_FINGERPRINT] ?: "",
      primaryLockType = lockType,
      pinLength = pinLen,
      pinSaltB64 = prefs[Keys.PIN_SALT],
      pinWrappedDek = prefs[Keys.PIN_WRAPPED_DEK],
      pinVerifier = prefs[Keys.PIN_VERIFIER],
      patternSaltB64 = prefs[Keys.PATTERN_SALT],
      patternWrappedDek = prefs[Keys.PATTERN_WRAPPED_DEK],
      patternVerifier = prefs[Keys.PATTERN_VERIFIER],
      recoveryQ1 = prefs[Keys.RECOVERY_Q1] ?: "",
      recoveryQ2 = prefs[Keys.RECOVERY_Q2] ?: "",
      recoverySaltB64 = prefs[Keys.RECOVERY_SALT],
      recoveryWrappedDek = prefs[Keys.RECOVERY_WRAPPED_DEK],
      recoveryVerifier = prefs[Keys.RECOVERY_VERIFIER],
      biometricEnabled = prefs[Keys.BIOMETRIC_ENABLED] ?: true,
    )
  }

  suspend fun restoreFromDualVaultBundle(bundle: DualKeyVaultBundle, dek: SecretKey) {
    val keystoreWrapped = keystoreManager.wrapDekWithKeystore(dek)
    val fingerprint = AesFolderCipher.computeKeyFingerprint(dek)
    val lockType = PrimaryLockType.fromName(bundle.primaryLockType)
    context.securityDataStore.edit { prefs ->
      prefs[Keys.INITIALIZED] = true
      prefs[Keys.PRIMARY_LOCK_TYPE] = lockType.name
      prefs[Keys.PIN_LENGTH] = if (bundle.pinLength == 4) 4 else 6
      prefs[Keys.KEY_FINGERPRINT] = fingerprint
      prefs[Keys.KEYSTORE_WRAPPED_DEK] = keystoreWrapped
      if (bundle.pinSaltB64 != null && bundle.pinVerifier != null && bundle.pinWrappedDek != null) {
        prefs[Keys.PIN_SALT] = bundle.pinSaltB64
        prefs[Keys.PIN_VERIFIER] = bundle.pinVerifier
        prefs[Keys.PIN_WRAPPED_DEK] = bundle.pinWrappedDek
      }
      if (bundle.patternSaltB64 != null && bundle.patternVerifier != null && bundle.patternWrappedDek != null) {
        prefs[Keys.PATTERN_SALT] = bundle.patternSaltB64
        prefs[Keys.PATTERN_VERIFIER] = bundle.patternVerifier
        prefs[Keys.PATTERN_WRAPPED_DEK] = bundle.patternWrappedDek
      }
      if (bundle.recoveryQ1.isNotBlank() && bundle.recoveryQ2.isNotBlank() &&
        bundle.recoverySaltB64 != null && bundle.recoveryVerifier != null && bundle.recoveryWrappedDek != null
      ) {
        prefs[Keys.RECOVERY_Q1] = bundle.recoveryQ1
        prefs[Keys.RECOVERY_Q2] = bundle.recoveryQ2
        prefs[Keys.RECOVERY_SALT] = bundle.recoverySaltB64
        prefs[Keys.RECOVERY_VERIFIER] = bundle.recoveryVerifier
        prefs[Keys.RECOVERY_WRAPPED_DEK] = bundle.recoveryWrappedDek
      }
      prefs[Keys.PREFER_PATTERN_FALLBACK] = (lockType == PrimaryLockType.PATTERN)
      prefs[Keys.FAILED_ATTEMPTS] = 0
    }
  }

  fun normalizeRecoveryAnswersPublic(a1: String, a2: String): String = normalizeRecoveryAnswers(a1, a2)

  private fun normalizeRecoveryAnswers(a1: String, a2: String): String {
    val c1 = a1.trim().lowercase().replace(Regex("\\s+"), " ")
    val c2 = a2.trim().lowercase().replace(Regex("\\s+"), " ")
    return "$c1||$c2"
  }

  companion object {
    const val MIN_PATTERN_NODES = 4
    const val DEFAULT_STEALTH_CODE = "#7788"

    fun normalizeStealthCode(raw: String): String? {
      val trimmed = raw.trim()
      if (trimmed.length < 4 || trimmed.length > 32) return null
      return if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    }

    fun canonicalPatternString(nodes: List<Int>): String? {
      val uniqueValid = nodes.distinct().filter { it in 0..8 }
      if (uniqueValid.size < MIN_PATTERN_NODES || uniqueValid.size != nodes.size) return null
      return uniqueValid.joinToString("-")
    }
  }
}
