package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.crypto.BiometricAvailability
import com.example.data.preferences.SecuritySettings
import com.example.security.DevicePowerState
import com.example.security.PanicSensitivity
import com.example.ui.components.PatternLockGrid
import com.example.ui.components.formatByteSize
import com.example.ui.theme.ThemeMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecuritySettingsScreen(
  settings: SecuritySettings,
  biometricAvailability: BiometricAvailability,
  hasAccelerometer: Boolean,
  devicePowerState: DevicePowerState,
  isBatterySaverBlockingTasks: Boolean,
  orphanCount: Int,
  orphanBytes: Long,
  isScanningOrphans: Boolean,
  onToggleBiometric: (Boolean) -> Unit,
  onToggleAutoLockEnabled: (Boolean) -> Unit,
  onSelectAutoLockSeconds: (Int) -> Unit,
  onToggleAutoLockReencrypt: (Boolean) -> Unit,
  onToggleRequireBioAfterAutoLock: (Boolean) -> Unit,
  onTogglePanicMode: (Boolean) -> Unit,
  onSelectPanicSensitivity: (PanicSensitivity) -> Unit,
  onTogglePanicExitToHome: (Boolean) -> Unit,
  onTogglePanicReencrypt: (Boolean) -> Unit,
  onTestPanicLock: () -> Unit,
  onToggleBatterySaverMode: (Boolean) -> Unit,
  onAddQuickTileLock: () -> Unit,
  onAddQuickTilePanic: () -> Unit,
  onToggleObfuscateDefault: (Boolean) -> Unit,
  onSelectThemeMode: (ThemeMode) -> Unit,
  onToggleDynamicColor: (Boolean) -> Unit,
  onChangePin: (currentPin: String, newPin: String, onDone: (Boolean) -> Unit) -> Unit,
  onSavePattern: (nodes: List<Int>, onDone: (Boolean) -> Unit) -> Unit,
  onRemovePattern: () -> Unit,
  onTogglePreferPatternFallback: (Boolean) -> Unit,
  onUpdateRecoveryQuestions: (q1: String, a1: String, q2: String, a2: String, onDone: () -> Unit) -> Unit,
  onOpenOrphanCleanup: () -> Unit,
  onOpenMigrateOut: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showChangePinDialog by remember { mutableStateOf(false) }
  var showSetupPatternDialog by remember { mutableStateOf(false) }
  var showUpdateQuestionsDialog by remember { mutableStateOf(false) }
  var showOpenSourceSpecDialog by remember { mutableStateOf(false) }
  var showCustomDurationDialog by remember { mutableStateOf(false) }

  val bioSupported = biometricAvailability == BiometricAvailability.AVAILABLE_STRONG ||
    biometricAvailability == BiometricAvailability.AVAILABLE_WEAK_OR_DEVICE

  Box(
    contentAlignment = Alignment.TopCenter,
    modifier = modifier.fillMaxSize(),
  ) {
    LazyColumn(
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier
        .widthIn(max = 760.dp)
        .fillMaxSize(),
    ) {
      item(key = "settings_title") {
        Column {
          Text("Security & Cryptography", style = MaterialTheme.typography.headlineMedium)
          Text(
            text = "Key Fingerprint: ${settings.keyFingerprint.ifBlank { "Initialized" }}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      // Biometrics & Keyguard
      item(key = "biometric_card") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
              Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Biometric Authentication", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = if (bioSupported) {
                    "Unlock BeastLock and decrypt folders via BiometricPrompt"
                  } else {
                    "Biometrics unavailable or not enrolled in Android Settings"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.biometricEnabled,
                onCheckedChange = onToggleBiometric,
                enabled = bioSupported,
                modifier = Modifier.testTag("settings_biometric_switch"),
              )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
              Icon(Icons.Rounded.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Obfuscate Filenames by Default", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = "Encrypt new folders using randomized <uuid>.vlock filenames",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.obfuscateFilenamesDefault,
                onCheckedChange = onToggleObfuscateDefault,
                modifier = Modifier.testTag("settings_obfuscate_switch"),
              )
            }
          }
        }
      }

      // Appearance & Dynamic Theme Switcher
      item(key = "appearance_theme_card") {
        val systemDark = isSystemInDarkTheme()
        val themeSummary = when (settings.themeMode) {
          ThemeMode.SYSTEM -> "Following system setting (currently ${if (systemDark) "Dark" else "Light"})"
          ThemeMode.LIGHT -> "Always using crisp Alabaster Light mode"
          ThemeMode.DARK -> "Always using Obsidian Dark security console"
        }

        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
              Icon(Icons.Rounded.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Appearance & Theme", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = themeSummary,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
              val modes = listOf(
                Triple(ThemeMode.SYSTEM, "System", Icons.Rounded.BrightnessAuto),
                Triple(ThemeMode.LIGHT, "Light", Icons.Rounded.LightMode),
                Triple(ThemeMode.DARK, "Dark", Icons.Rounded.DarkMode),
              )
              modes.forEachIndexed { index, (mode, label, icon) ->
                SegmentedButton(
                  selected = settings.themeMode == mode,
                  onClick = { onSelectThemeMode(mode) },
                  shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                  icon = {
                    SegmentedButtonDefaults.Icon(active = settings.themeMode == mode) {
                      Icon(imageVector = icon, contentDescription = null)
                    }
                  },
                  modifier = Modifier.testTag("theme_mode_${mode.name}"),
                ) {
                  Text(label)
                }
              }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text("Material You Dynamic Colors", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "Adapt vault surfaces and accents to your Android wallpaper",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = settings.dynamicColor,
                  onCheckedChange = onToggleDynamicColor,
                  modifier = Modifier.testTag("settings_dynamic_color_switch"),
                )
              }
            }
          }
        }
      }

      // Auto-Lock Timeout & Folder Re-Encryption
      item(key = "autolock_card") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
              Icon(Icons.Rounded.LockClock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Background Auto-Lock", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = if (settings.autoLockEnabled) {
                    "Active • Triggers after ${formatAutoLockDuration(settings.autoLockTimeoutSeconds)}"
                  } else {
                    "Disabled • Vault stays unlocked when backgrounded"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.autoLockEnabled,
                onCheckedChange = onToggleAutoLockEnabled,
                modifier = Modifier.testTag("settings_autolock_enabled_switch"),
              )
            }

            if (settings.autoLockEnabled) {
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.EnhancedEncryption, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text("Re-Encrypt Unlocked Folders", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "Automatically seal unlocked folders with AES-256-GCM when timer expires",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = settings.autoLockReencryptFolders,
                  onCheckedChange = onToggleAutoLockReencrypt,
                  modifier = Modifier.testTag("settings_autolock_reencrypt_switch"),
                )
              }

              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text("Require Biometric After Auto-Lock", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "Prompt fingerprint/face recognition upon returning",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = settings.requireBiometricAfterAutoLock,
                  onCheckedChange = onToggleRequireBioAfterAutoLock,
                  enabled = bioSupported,
                  modifier = Modifier.testTag("settings_autolock_bio_switch"),
                )
              }

              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Rounded.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                  Spacer(modifier = Modifier.width(8.dp))
                  Text("Background Grace Duration", style = MaterialTheme.typography.labelLarge)
                }
                val presets = listOf(0 to "Immediate", 15 to "15s", 30 to "30s", 60 to "1 min", 300 to "5 min", 900 to "15 min")
                val isPreset = presets.any { it.first == settings.autoLockTimeoutSeconds }
                FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  presets.forEach { (sec, label) ->
                    FilterChip(
                      selected = settings.autoLockTimeoutSeconds == sec,
                      onClick = { onSelectAutoLockSeconds(sec) },
                      label = { Text(label) },
                      modifier = Modifier.testTag("autolock_chip_$sec"),
                    )
                  }
                  FilterChip(
                    selected = !isPreset,
                    onClick = { showCustomDurationDialog = true },
                    label = {
                      Text(
                        if (!isPreset) {
                          "Custom (${formatAutoLockDuration(settings.autoLockTimeoutSeconds)})"
                        } else {
                          "Custom..."
                        },
                      )
                    },
                    modifier = Modifier.testTag("autolock_chip_custom"),
                  )
                }
              }
            }
          }
        }
      }

      // Panic Mode Shake Gesture
      item(key = "panic_mode_card") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
              Icon(Icons.Rounded.Vibration, contentDescription = null, tint = MaterialTheme.colorScheme.error)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Panic Mode (Shake Gesture)", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = when {
                    !hasAccelerometer -> "Hardware accelerometer unavailable on this device"
                    settings.panicModeEnabled -> "Active • Shake device to lock session (${settings.panicSensitivity.label})"
                    else -> "Disabled • Shaking device will not lock session"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.panicModeEnabled,
                onCheckedChange = onTogglePanicMode,
                modifier = Modifier.testTag("settings_panic_mode_switch"),
              )
            }

            if (settings.panicModeEnabled) {
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text("Return to Device Home Screen", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "Exit BeastLock to Android Home immediately after sealing session",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = settings.panicExitToDeviceHome,
                  onCheckedChange = onTogglePanicExitToHome,
                  modifier = Modifier.testTag("settings_panic_home_switch"),
                )
              }

              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.EnhancedEncryption, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text("Re-Encrypt Open Folders on Panic", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "Seal any unlocked folders with AES-256-GCM when Panic Mode fires",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = settings.panicReencryptFolders,
                  onCheckedChange = onTogglePanicReencrypt,
                  modifier = Modifier.testTag("settings_panic_reencrypt_switch"),
                )
              }

              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shake Sensitivity", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  PanicSensitivity.entries.forEach { level ->
                    FilterChip(
                      selected = settings.panicSensitivity == level,
                      onClick = { onSelectPanicSensitivity(level) },
                      label = { Text(level.label) },
                      modifier = Modifier.testTag("panic_sensitivity_${level.name}"),
                    )
                  }
                }
                Text(
                  text = "${settings.panicSensitivity.description} (${settings.panicSensitivity.thresholdG}g threshold)",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              OutlinedButton(
                onClick = onTestPanicLock,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("test_panic_mode_button"),
              ) {
                Icon(Icons.Rounded.Shield, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Trigger Panic Lock Now")
              }
            }
          }
        }
      }

      // Battery Saver Mode
      item(key = "battery_saver_card") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
              Icon(
                imageVector = if (isBatterySaverBlockingTasks) Icons.Rounded.BatteryAlert else Icons.Rounded.BatterySaver,
                contentDescription = null,
                tint = if (isBatterySaverBlockingTasks) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
              )
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Battery Saver Mode", style = MaterialTheme.typography.titleSmall)
                val powerLabel = buildString {
                  append("Battery ${devicePowerState.batteryPercent}%")
                  append(if (devicePowerState.isCharging) " • Charging" else " • On Battery")
                  if (devicePowerState.isSystemPowerSaveMode) append(" • System Power Save")
                }
                Text(
                  text = if (settings.batterySaverModeEnabled) {
                    "Active ($powerLabel) • Pauses auto-sync & background encryption below 20%; resumes > 20%"
                  } else {
                    "Disabled ($powerLabel) • Background tasks run at any battery level"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.batterySaverModeEnabled,
                onCheckedChange = onToggleBatterySaverMode,
                modifier = Modifier.testTag("settings_battery_saver_switch"),
              )
            }

            if (isBatterySaverBlockingTasks || settings.pendingBatteryPausedAutoSync || settings.pendingBatteryPausedReencrypt) {
              Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("settings_battery_saver_status_banner"),
              ) {
                Column(
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                  modifier = Modifier.padding(12.dp),
                ) {
                  Text(
                    text = if (isBatterySaverBlockingTasks) {
                      "Background Sync & Encryption Paused"
                    } else {
                      "Resuming Queued Background Tasks"
                    },
                    style = MaterialTheme.typography.labelLarge,
                  )
                  val queuedList = buildList {
                    if (settings.pendingBatteryPausedAutoSync) add("Auto Cloud Sync")
                    if (settings.pendingBatteryPausedReencrypt) add("Background Folder Re-encryption")
                  }
                  Text(
                    text = if (queuedList.isNotEmpty()) {
                      "Queued to resume automatically above 20%: ${queuedList.joinToString(", ")}"
                    } else {
                      "Automatic cloud sync and background encryption will resume automatically once battery > 20% and Power Save is off."
                    },
                    style = MaterialTheme.typography.bodySmall,
                  )
                }
              }
            }
          }
        }
      }

      // Quick Settings Tiles (Status Bar Pull-Down)
      item(key = "quick_settings_tiles_card") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
              Icon(Icons.Rounded.Widgets, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Quick Settings Status Bar Tiles", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = "Pin Lock Vault and Panic Mode tiles to Android's status bar pull-down menu for instant sealing.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              FilledTonalButton(
                onClick = onAddQuickTileLock,
                modifier = Modifier
                  .weight(1f)
                  .testTag("settings_add_lock_tile_button"),
              ) {
                Icon(Icons.Rounded.Lock, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Lock Tile")
              }
              FilledTonalButton(
                onClick = onAddQuickTilePanic,
                modifier = Modifier
                  .weight(1f)
                  .testTag("settings_add_panic_tile_button"),
              ) {
                Icon(Icons.Rounded.Shield, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Panic Tile")
              }
            }
          }
        }
      }

      // Master PIN & Security Questions Recovery
      item(key = "credentials_card") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .clickable { showChangePinDialog = true }
                .padding(16.dp)
                .testTag("settings_change_pin_row"),
            ) {
              Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Change Master PIN", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = "Re-wraps your 256-bit DEK without re-encrypting folders",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .clickable { showSetupPatternDialog = true }
                .padding(16.dp)
                .testTag("settings_unlock_pattern_row"),
            ) {
              Icon(Icons.Rounded.Gesture, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Unlock Pattern (PIN / Pattern Fallback)", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = if (settings.hasPatternLock) {
                    "Configured • Switch between 6-digit PIN and 3×3 Pattern on lock screen"
                  } else {
                    "Not set • Draw a 3×3 Pattern to unlock alongside PIN without biometrics"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            if (settings.hasPatternLock) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 10.dp),
              ) {
                Spacer(modifier = Modifier.width(36.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text("Default Lock Screen to Pattern", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "Open the 3×3 Pattern grid first (PIN switcher stays available)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = settings.preferPatternFallback,
                  onCheckedChange = onTogglePreferPatternFallback,
                  modifier = Modifier.testTag("settings_prefer_pattern_switch"),
                )
              }
            }

            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .clickable { showUpdateQuestionsDialog = true }
                .padding(16.dp)
                .testTag("settings_recovery_questions_row"),
            ) {
              Icon(Icons.Rounded.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Recovery Security Questions", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = if (settings.hasSecurityQuestions) {
                    "Configured • PBKDF2-HMAC-SHA256 key recovery active"
                  } else {
                    "Configure questions to recover forgotten Master PIN"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }

      // Storage Hygiene & Data Portability (Exit Strategy)
      item(key = "hygiene_portability_card") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenOrphanCleanup)
                .padding(16.dp)
                .testTag("settings_clear_orphans_row"),
            ) {
              Icon(
                imageVector = Icons.Rounded.CleaningServices,
                contentDescription = null,
                tint = if (orphanCount > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
              )
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Clear Orphaned Files", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = when {
                    isScanningOrphans -> "Scanning vault and cloud staging directories..."
                    orphanCount > 0 -> "$orphanCount leftover temp item(s) (${formatByteSize(orphanBytes)}) detected — tap to review"
                    else -> "Detect & remove temp files from interrupted re-encryption or cloud sync"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = if (orphanCount > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenMigrateOut)
                .padding(16.dp)
                .testTag("settings_migrate_out_row"),
            ) {
              Icon(Icons.Rounded.DriveFileMove, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Decrypt & Migrate Out (Exit Strategy)", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = "Decrypt all or selected folders to plain device storage or a standard ZIP archive",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }

      // Open Source MIT License & Cryptographic Specification
      item(key = "opensource_card") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier
            .fillMaxWidth()
            .clickable { showOpenSourceSpecDialog = true }
            .testTag("settings_opensource_card"),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
          ) {
            Icon(Icons.Rounded.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text("Open Source • MIT License", style = MaterialTheme.typography.titleSmall)
              Text(
                text = "Auditable AES-256-GCM (VLK1), Android Keystore & zero-telemetry architecture",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }

  if (showChangePinDialog) {
    ChangePinDialog(
      onDismiss = { showChangePinDialog = false },
      onSubmit = { current, next, onResult ->
        onChangePin(current, next) { ok ->
          onResult(ok)
          if (ok) showChangePinDialog = false
        }
      },
    )
  }

  if (showSetupPatternDialog) {
    SetupPatternDialog(
      hasExistingPattern = settings.hasPatternLock,
      onDismiss = { showSetupPatternDialog = false },
      onSavePattern = { nodes ->
        onSavePattern(nodes) { ok ->
          if (ok) showSetupPatternDialog = false
        }
      },
      onRemovePattern = {
        onRemovePattern()
        showSetupPatternDialog = false
      },
    )
  }

  if (showUpdateQuestionsDialog) {
    UpdateRecoveryQuestionsDialog(
      initialQ1 = settings.securityQuestion1.ifBlank { "What was the name of your first school?" },
      initialQ2 = settings.securityQuestion2.ifBlank { "What was the name of your first pet?" },
      onDismiss = { showUpdateQuestionsDialog = false },
      onSave = { q1, a1, q2, a2 ->
        onUpdateRecoveryQuestions(q1, a1, q2, a2) {
          showUpdateQuestionsDialog = false
        }
      },
    )
  }

  if (showCustomDurationDialog) {
    CustomAutoLockDurationDialog(
      currentSeconds = settings.autoLockTimeoutSeconds,
      onDismiss = { showCustomDurationDialog = false },
      onConfirmSeconds = { seconds ->
        showCustomDurationDialog = false
        onSelectAutoLockSeconds(seconds)
      },
    )
  }

  if (showOpenSourceSpecDialog) {
    AlertDialog(
      onDismissRequest = { showOpenSourceSpecDialog = false },
      title = { Text("BeastLock Open Source & Crypto Spec") },
      text = {
        Column(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
          Text("License: MIT Open Source License", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
          Text(
            text = "• File Cipher: Streaming 64 KiB chunked AES-256-GCM (VLK1) with random 96-bit base nonce, per-chunk AAD, and authenticated EOF sentinel.",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            text = "• Key Protection: 256-bit DEK wrapped by Android Keystore (BiometricPrompt) and PBKDF2WithHmacSHA256 (120,000 iterations) for PIN & Security Questions.",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            text = "• Cloud Backup (.vlkbak): Archives only AES-256-GCM encrypted payloads + PIN-wrapped key manifest to Google Drive, Dropbox, or SAF storage.",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            text = "• Panic Mode & Encrypted Audit: Accelerometer shake gesture locks the session and returns to Home; security events are stored in vault_audit.log.enc with Android Keystore AES-256-GCM + SHA-256 hash chaining.",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            text = "• Privacy: Zero analytics, zero ad SDKs, zero broad storage permissions.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
      },
      confirmButton = {
        TextButton(onClick = { showOpenSourceSpecDialog = false }) { Text("Close") }
      },
    )
  }
}

@Composable
private fun ChangePinDialog(
  onDismiss: () -> Unit,
  onSubmit: (current: String, next: String, callback: (Boolean) -> Unit) -> Unit,
) {
  var currentPin by remember { mutableStateOf("") }
  var newPin by remember { mutableStateOf("") }
  var confirmPin by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Change Master PIN") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = currentPin,
          onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) { currentPin = it; error = null } },
          label = { Text("Current 6-digit PIN") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("change_pin_current"),
        )
        OutlinedTextField(
          value = newPin,
          onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) { newPin = it; error = null } },
          label = { Text("New 6-digit PIN") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("change_pin_new"),
        )
        OutlinedTextField(
          value = confirmPin,
          onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) { confirmPin = it; error = null } },
          label = { Text("Confirm New PIN") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("change_pin_confirm"),
        )
        if (error != null) {
          Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          when {
            currentPin.length != 6 || newPin.length != 6 -> error = "PINs must be 6 digits."
            newPin != confirmPin -> error = "New PINs do not match."
            else -> onSubmit(currentPin, newPin) { ok ->
              if (!ok) error = "Current PIN is incorrect."
            }
          }
        },
        modifier = Modifier.testTag("change_pin_submit"),
      ) {
        Text("Update PIN")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

@Composable
private fun UpdateRecoveryQuestionsDialog(
  initialQ1: String,
  initialQ2: String,
  onDismiss: () -> Unit,
  onSave: (q1: String, a1: String, q2: String, a2: String) -> Unit,
) {
  var q1 by remember { mutableStateOf(initialQ1) }
  var a1 by remember { mutableStateOf("") }
  var q2 by remember { mutableStateOf(initialQ2) }
  var a2 by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Update Security Questions") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
      ) {
        OutlinedTextField(
          value = q1,
          onValueChange = { q1 = it },
          label = { Text("Security Question 1") },
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = a1,
          onValueChange = { a1 = it; error = null },
          label = { Text("Answer 1") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = q2,
          onValueChange = { q2 = it },
          label = { Text("Security Question 2") },
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = a2,
          onValueChange = { a2 = it; error = null },
          label = { Text("Answer 2") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
          Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (q1.isBlank() || q2.isBlank() || a1.trim().length < 2 || a2.trim().length < 2) {
            error = "Provide both questions and answers (min 2 characters)."
          } else {
            onSave(q1, a1, q2, a2)
          }
        },
      ) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

@Composable
private fun CustomAutoLockDurationDialog(
  currentSeconds: Int,
  onDismiss: () -> Unit,
  onConfirmSeconds: (Int) -> Unit,
) {
  var useMinutes by remember { mutableStateOf(currentSeconds >= 60 && currentSeconds % 60 == 0) }
  var rawInput by remember {
    val initial = if (useMinutes) (currentSeconds / 60).coerceAtLeast(1) else currentSeconds.coerceAtLeast(5)
    mutableStateOf(initial.toString())
  }
  var error by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Custom Auto-Lock Duration") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "Set how long the app can remain in the background before re-encrypting unlocked folders and requiring biometric authentication.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            selected = !useMinutes,
            onClick = { useMinutes = false },
            label = { Text("Seconds") },
            modifier = Modifier.testTag("custom_duration_unit_seconds"),
          )
          FilterChip(
            selected = useMinutes,
            onClick = { useMinutes = true },
            label = { Text("Minutes") },
            modifier = Modifier.testTag("custom_duration_unit_minutes"),
          )
        }
        OutlinedTextField(
          value = rawInput,
          onValueChange = { input ->
            if (input.all(Char::isDigit) && input.length <= 4) {
              rawInput = input
              error = null
            }
          },
          label = { Text(if (useMinutes) "Duration (minutes)" else "Duration (seconds)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("custom_duration_value_input"),
        )
        if (error != null) {
          Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val parsed = rawInput.toIntOrNull()
          val totalSeconds = when {
            parsed == null || parsed <= 0 -> null
            useMinutes -> parsed * 60
            else -> parsed
          }
          if (totalSeconds == null || totalSeconds > 86_400) {
            error = "Enter a valid duration between 1 second and 24 hours."
          } else {
            onConfirmSeconds(totalSeconds)
          }
        },
        modifier = Modifier.testTag("custom_duration_confirm"),
      ) {
        Text("Apply Duration")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

private fun formatAutoLockDuration(seconds: Int): String = when {
  seconds <= 0 -> "Immediate"
  seconds < 60 -> "${seconds}s"
  seconds % 60 == 0 -> "${seconds / 60} min"
  else -> "${seconds / 60}m ${seconds % 60}s"
}

@Composable
private fun SetupPatternDialog(
  hasExistingPattern: Boolean,
  onDismiss: () -> Unit,
  onSavePattern: (List<Int>) -> Unit,
  onRemovePattern: () -> Unit,
) {
  var firstPattern by remember { mutableStateOf<List<Int>?>(null) }
  var errorText by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        when {
          firstPattern != null -> "Confirm Unlock Pattern"
          hasExistingPattern -> "Update Unlock Pattern"
          else -> "Set Up Unlock Pattern"
        },
      )
    },
    text = {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = if (firstPattern == null) {
            "Connect at least 4 dots on the 3×3 grid. Your Pattern wraps the vault key with PBKDF2-HMAC-SHA256 alongside your PIN."
          } else {
            "Draw the same pattern again to verify and save."
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PatternLockGrid(
          isError = errorText != null,
          helperText = errorText ?: if (firstPattern == null) {
            "Draw new pattern (min 4 dots)"
          } else {
            "Redraw pattern to confirm"
          },
          onPatternCompleted = { nodes ->
            if (nodes.size < 4) {
              errorText = "Connect at least 4 dots."
              return@PatternLockGrid
            }
            val initial = firstPattern
            if (initial == null) {
              firstPattern = nodes
              errorText = null
            } else if (initial == nodes) {
              errorText = null
              onSavePattern(nodes)
            } else {
              errorText = "Patterns didn't match. Draw again."
            }
          },
          modifier = Modifier.testTag("setup_pattern_grid"),
        )
      }
    },
    confirmButton = {
      if (firstPattern != null) {
        TextButton(
          onClick = {
            firstPattern = null
            errorText = null
          },
        ) {
          Text("Redraw")
        }
      }
    },
    dismissButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasExistingPattern) {
          TextButton(
            onClick = onRemovePattern,
            modifier = Modifier.testTag("remove_pattern_button"),
          ) {
            Text("Remove", color = MaterialTheme.colorScheme.error)
          }
        }
        TextButton(onClick = onDismiss) { Text("Cancel") }
      }
    },
    modifier = Modifier.testTag("setup_pattern_dialog"),
  )
}


