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
import com.example.crypto.KeystoreManager
import com.example.security.PanicSensitivity
import com.example.ui.theme.ThemeMode
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.securityDataStore: DataStore<Preferences> by preferencesDataStore(name = "vault_security_prefs")

data class SecuritySettings(
  val isVaultInitialized: Boolean = false,
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
  val autoBackupTreeUri: String = "",
  val autoBackupProviderLabel: String = "Google Drive",
  val lastAutoBackupAtMillis: Long = 0L,
  val batterySaverModeEnabled: Boolean = true,
  val pendingBatteryPausedAutoSync: Boolean = false,
  val pendingBatteryPausedReencrypt: Boolean = false,
  val hasPatternLock: Boolean = false,
  val preferPatternFallback: Boolean = false,
)

class SecurityPreferencesRepository(
  private val context: Context,
  private val keystoreManager: KeystoreManager,
) {
  private object Keys {
    val INITIALIZED = booleanPreferencesKey("vault_initialized")
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
  }

  val securitySettings: Flow<SecuritySettings> = context.securityDataStore.data.map { prefs ->
    val q1 = prefs[Keys.RECOVERY_Q1] ?: ""
    val q2 = prefs[Keys.RECOVERY_Q2] ?: ""
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
      autoBackupTreeUri = prefs[Keys.AUTO_BACKUP_TREE_URI] ?: "",
      autoBackupProviderLabel = prefs[Keys.AUTO_BACKUP_PROVIDER] ?: "Google Drive",
      lastAutoBackupAtMillis = prefs[Keys.LAST_AUTO_BACKUP_AT] ?: 0L,
      batterySaverModeEnabled = prefs[Keys.BATTERY_SAVER_ENABLED] ?: true,
      pendingBatteryPausedAutoSync = prefs[Keys.PENDING_BATTERY_PAUSED_SYNC] ?: false,
      pendingBatteryPausedReencrypt = prefs[Keys.PENDING_BATTERY_PAUSED_REENCRYPT] ?: false,
      hasPatternLock = !prefs[Keys.PATTERN_VERIFIER].isNullOrBlank() && !prefs[Keys.PATTERN_WRAPPED_DEK].isNullOrBlank(),
      preferPatternFallback = prefs[Keys.PREFER_PATTERN_FALLBACK] ?: false,
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
    val dek = AesFolderCipher.generateAes256Key()
    val salt = AesFolderCipher.generateSalt()
    val pinKey = AesFolderCipher.derivePinKey(pin, salt)
    val pinWrapped = AesFolderCipher.wrapDekWithKey(dek, pinKey)
    val pinVerifier = AesFolderCipher.computePinVerifier(pin, salt)
    val keystoreWrapped = keystoreManager.wrapDekWithKeystore(dek)
    val fingerprint = AesFolderCipher.computeKeyFingerprint(dek)

    val recoverySalt = AesFolderCipher.generateSalt()
    val normalizedAnswers = normalizeRecoveryAnswers(answer1, answer2)
    val recoveryKey = AesFolderCipher.derivePinKey(normalizedAnswers, recoverySalt)
    val recoveryWrapped = AesFolderCipher.wrapDekWithKey(dek, recoveryKey)
    val recoveryVerifier = AesFolderCipher.computePinVerifier(normalizedAnswers, recoverySalt)

    context.securityDataStore.edit { prefs ->
      prefs[Keys.INITIALIZED] = true
      prefs[Keys.PIN_SALT] = Base64.encodeToString(salt, Base64.NO_WRAP)
      prefs[Keys.PIN_VERIFIER] = pinVerifier
      prefs[Keys.PIN_WRAPPED_DEK] = pinWrapped
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

  suspend fun unlockWithKeystoreAfterBiometric(): SecretKey? {
    val prefs = context.securityDataStore.data.first()
    val wrapped = prefs[Keys.KEYSTORE_WRAPPED_DEK] ?: return null
    val dek = keystoreManager.unwrapDekWithKeystore(wrapped)
    context.securityDataStore.edit { p -> p[Keys.FAILED_ATTEMPTS] = 0 }
    return dek
  }

  suspend fun recoverAndResetPin(
    answer1: String,
    answer2: String,
    newPin: String,
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

    val newPinSalt = AesFolderCipher.generateSalt()
    val newPinKey = AesFolderCipher.derivePinKey(newPin, newPinSalt)
    val newPinWrapped = AesFolderCipher.wrapDekWithKey(dek, newPinKey)
    val newPinVerifier = AesFolderCipher.computePinVerifier(newPin, newPinSalt)
    val newKeystoreWrapped = keystoreManager.wrapDekWithKeystore(dek)

    context.securityDataStore.edit { p ->
      p[Keys.PIN_SALT] = Base64.encodeToString(newPinSalt, Base64.NO_WRAP)
      p[Keys.PIN_VERIFIER] = newPinVerifier
      p[Keys.PIN_WRAPPED_DEK] = newPinWrapped
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
    val newSalt = AesFolderCipher.generateSalt()
    val newPinKey = AesFolderCipher.derivePinKey(newPin, newSalt)
    val newWrapped = AesFolderCipher.wrapDekWithKey(dek, newPinKey)
    val newVerifier = AesFolderCipher.computePinVerifier(newPin, newSalt)

    context.securityDataStore.edit { prefs ->
      prefs[Keys.PIN_SALT] = Base64.encodeToString(newSalt, Base64.NO_WRAP)
      prefs[Keys.PIN_VERIFIER] = newVerifier
      prefs[Keys.PIN_WRAPPED_DEK] = newWrapped
      prefs[Keys.FAILED_ATTEMPTS] = 0
    }
    return true
  }

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
    }
  }

  suspend fun setPreferPatternFallback(prefer: Boolean) {
    context.securityDataStore.edit { prefs ->
      prefs[Keys.PREFER_PATTERN_FALLBACK] = prefer
    }
  }

  private fun normalizeRecoveryAnswers(a1: String, a2: String): String {
    val c1 = a1.trim().lowercase().replace(Regex("\\s+"), " ")
    val c2 = a2.trim().lowercase().replace(Regex("\\s+"), " ")
    return "$c1||$c2"
  }

  companion object {
    const val MIN_PATTERN_NODES = 4

    fun canonicalPatternString(nodes: List<Int>): String? {
      val uniqueValid = nodes.distinct().filter { it in 0..8 }
      if (uniqueValid.size < MIN_PATTERN_NODES || uniqueValid.size != nodes.size) return null
      return uniqueValid.joinToString("-")
    }
  }
}
