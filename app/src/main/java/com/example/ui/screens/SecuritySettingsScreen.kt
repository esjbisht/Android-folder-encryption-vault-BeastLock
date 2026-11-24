package com.example.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.crypto.BiometricAvailability
import com.example.crypto.DualKeyMirrorStatus
import com.example.data.preferences.PrimaryLockType
import com.example.data.preferences.SecurityPreferencesRepository
import com.example.data.preferences.SecuritySettings
import com.example.data.preferences.StealthGestureMode
import com.example.security.DevicePowerState
import com.example.security.PanicSensitivity
import com.example.ui.components.PatternLockGrid
import com.example.ui.components.VaultLinearProgressBar
import com.example.ui.components.VaultStatusBadge
import com.example.ui.components.formatByteSize
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.BeastLockTheme
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.VaultProgressTone

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
  isScanningIntegrity: Boolean,
  lastIntegrityCorruptedCount: Int?,
  dualKeyStatus: DualKeyMirrorStatus?,
  pendingIssuesCount: Int,
  onToggleBiometric: (Boolean) -> Unit,
  onSelectPrimaryLockType: (PrimaryLockType) -> Unit,
  onToggleAutoLockEnabled: (Boolean) -> Unit,
  onSelectAutoLockSeconds: (Int) -> Unit,
  onToggleAutoLockReencrypt: (Boolean) -> Unit,
  onToggleRequireBioAfterAutoLock: (Boolean) -> Unit,
  onToggleIdleTimeoutEnabled: (Boolean) -> Unit,
  onSelectIdleTimeoutSeconds: (Int) -> Unit,
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
  onTogglePatternStealthMode: (Boolean) -> Unit,
  onUpdateRecoveryQuestions: (
    q1: String,
    a1: String,
    q2: String,
    a2: String,
    customQuestionsToShare: List<String>,
    shareCustomSuggestions: Boolean,
    onDone: () -> Unit,
  ) -> Unit,
  onLinkExternalDualKeyFolder: (Uri) -> Unit,
  onVerifyDualKeyVault: () -> Unit,
  onOpenDiagnostics: () -> Unit,
  onOpenOrphanCleanup: () -> Unit,
  onOpenIntegrityDiagnostic: () -> Unit,
  onOpenMigrateOut: () -> Unit,
  onSelectStealthGestureMode: (StealthGestureMode) -> Unit = {},
  onToggleAlwaysBlockScreenshots: (Boolean) -> Unit = {},
  onChangeStealthCode: (newCode: String, onDone: (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
  onOpenStealthVault: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var showChangePinDialog by remember { mutableStateOf(false) }
  var showSetupPatternDialog by remember { mutableStateOf(false) }
  var showUpdateQuestionsDialog by remember { mutableStateOf(false) }
  var showOpenSourceSpecDialog by remember { mutableStateOf(false) }
  var showCustomDurationDialog by remember { mutableStateOf(false) }
  var showCustomIdleDurationDialog by remember { mutableStateOf(false) }
  var showChangeStealthCodeDialog by remember { mutableStateOf(false) }

  val dualKeyExternalPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree(),
  ) { uri ->
    if (uri != null) {
      onLinkExternalDualKeyFolder(uri)
    }
  }

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

      // Stealth Private Vault & Screen Protection
      item(key = "stealth_vault_card") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_stealth_vault_card"),
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
              Icon(Icons.Rounded.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Stealth Private Vault", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = "Private folders stay hidden from Dashboard, Search & Analytics; encrypted with .nomedia & auto-sealed on exit.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Dashboard Hero Shield Gesture", style = MaterialTheme.typography.labelLarge)
              }
              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                StealthGestureMode.entries.forEach { mode ->
                  FilterChip(
                    selected = settings.stealthGestureMode == mode,
                    onClick = { onSelectStealthGestureMode(mode) },
                    label = { Text(mode.label) },
                    modifier = Modifier.testTag("stealth_gesture_${mode.name.lowercase()}"),
                  )
                }
              }
            }

            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .clickable { showChangeStealthCodeDialog = true }
                .padding(vertical = 4.dp)
                .testTag("settings_change_stealth_code_row"),
            ) {
              Icon(Icons.Rounded.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Stealth Search Access Code", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = if (settings.hasCustomStealthCode) {
                    "Custom hashed code active • Type in Dashboard search bar to open"
                  } else {
                    "Default (${SecurityPreferencesRepository.DEFAULT_STEALTH_CODE}) • Tap to set custom code"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
              Icon(Icons.Rounded.Screenshot, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Always Block Screenshots & Previews", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = "Enforce Android FLAG_SECURE app-wide (always ON inside Stealth Vault)",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.alwaysBlockScreenshots,
                onCheckedChange = onToggleAlwaysBlockScreenshots,
                modifier = Modifier.testTag("settings_block_screenshots_switch"),
              )
            }

            FilledTonalButton(
              onClick = onOpenStealthVault,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_open_stealth_vault_button"),
            ) {
              Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text("Open Stealth Private Vault")
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

            Surface(
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              shape = MaterialTheme.shapes.medium,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_theme_preview_card"),
            ) {
              val previewColors = BeastLockTheme.colors
              Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(12.dp),
              ) {
                Text(
                  text = "Status & Progress Preview",
                  style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  VaultStatusBadge(isLocked = true)
                  VaultStatusBadge(isLocked = false, isPartiallyEncrypted = true)
                  VaultStatusBadge(isLocked = false)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                  ) {
                    Text(
                      text = "Encrypting Vault",
                      style = MaterialTheme.typography.labelSmall,
                      color = previewColors.progressEncrypt,
                      fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                      text = "72%",
                      style = MaterialTheme.typography.labelSmall,
                      color = previewColors.progressEncrypt,
                      fontWeight = FontWeight.Bold,
                    )
                  }
                  VaultLinearProgressBar(
                    progress = 0.72f,
                    tone = VaultProgressTone.ENCRYPT,
                    height = 5.dp,
                  )
                  Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                  ) {
                    Text(
                      text = "Decrypting Folder",
                      style = MaterialTheme.typography.labelSmall,
                      color = previewColors.progressDecrypt,
                      fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                      text = "45%",
                      style = MaterialTheme.typography.labelSmall,
                      color = previewColors.progressDecrypt,
                      fontWeight = FontWeight.Bold,
                    )
                  }
                  VaultLinearProgressBar(
                    progress = 0.45f,
                    tone = VaultProgressTone.DECRYPT,
                    height = 5.dp,
                  )
                }
              }
            }
          }
        }
      }

      // Unified Auto-Lock & Folder Sealing (Inactivity + Background Triggers)
      item(key = "autolock_card") {
        val anyAutoLockActive = settings.autoLockEnabled || settings.idleTimeoutEnabled
        val combinedPostureSummary = buildList {
          if (settings.idleTimeoutEnabled) {
            add("Idle: ${formatAutoLockDuration(settings.idleTimeoutSeconds)}")
          }
          if (settings.autoLockEnabled) {
            add("Background: ${formatAutoLockDuration(settings.autoLockTimeoutSeconds)}")
          }
        }.let { parts ->
          if (parts.isEmpty()) {
            "Disabled • Vault stays unlocked until manually sealed"
          } else {
            parts.joinToString(" • ") + if (settings.autoLockReencryptFolders) " • Seals folders" else ""
          }
        }

        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_unified_autolock_card"),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
              Icon(
                imageVector = Icons.Rounded.LockClock,
                contentDescription = "Auto-Lock & Folder Sealing",
                tint = if (anyAutoLockActive) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
              )
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Auto-Lock & Folder Sealing", style = MaterialTheme.typography.titleMedium)
                Text(
                  text = combinedPostureSummary,
                  style = MaterialTheme.typography.bodySmall,
                  color = if (anyAutoLockActive) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  },
                )
              }
            }

            if (settings.lastAutoLockSummary.isNotBlank()) {
              Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("settings_last_autolock_summary"),
              ) {
                Text(
                  text = "Last auto-lock: ${settings.lastAutoLockSummary}",
                  style = MaterialTheme.typography.labelSmall,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
              }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            // Trigger 1: Inactivity Auto-Lock
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text("Inactivity Auto-Lock", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = if (settings.idleTimeoutEnabled) {
                      "Active • Locks after ${formatAutoLockDuration(settings.idleTimeoutSeconds)} without input (defers during active encryption)"
                    } else {
                      "Disabled • Foreground inactivity will not lock vault"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = settings.idleTimeoutEnabled,
                  onCheckedChange = onToggleIdleTimeoutEnabled,
                  modifier = Modifier.testTag("settings_idle_timeout_switch"),
                )
              }

              if (settings.idleTimeoutEnabled) {
                val idlePresets = listOf(15 to "15s", 30 to "30s", 60 to "1 min", 120 to "2 min", 300 to "5 min", 900 to "15 min")
                val isIdlePreset = idlePresets.any { it.first == settings.idleTimeoutSeconds }
                FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  idlePresets.forEach { (sec, label) ->
                    FilterChip(
                      selected = settings.idleTimeoutSeconds == sec,
                      onClick = { onSelectIdleTimeoutSeconds(sec) },
                      label = { Text(label) },
                      modifier = Modifier.testTag("idle_timeout_chip_$sec"),
                    )
                  }
                  FilterChip(
                    selected = !isIdlePreset,
                    onClick = { showCustomIdleDurationDialog = true },
                    label = {
                      Text(
                        if (!isIdlePreset) {
                          "Custom (${formatAutoLockDuration(settings.idleTimeoutSeconds)})"
                        } else {
                          "Custom..."
                        },
                      )
                    },
                    modifier = Modifier.testTag("idle_timeout_chip_custom"),
                  )
                }
              }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            // Trigger 2: Background Auto-Lock
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text("Lock When App Goes to Background", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = if (settings.autoLockEnabled) {
                      "Active • Locks ${
                        if (settings.autoLockTimeoutSeconds == 0) {
                          "immediately on background"
                        } else {
                          "after ${formatAutoLockDuration(settings.autoLockTimeoutSeconds)} in background"
                        }
                      }"
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

            if (anyAutoLockActive) {
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

              Text(
                text = "Sealing & Re-Authentication",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
              )

              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.EnhancedEncryption, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text("Re-Encrypt Unlocked Folders", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "Automatically seal unlocked & partial folders with AES-256-GCM after active file crypto finishes",
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

      // Master PIN, Pattern, Primary Lock Method & Security Questions Recovery
      item(key = "credentials_card") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column {
            Column(
              verticalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.padding(16.dp),
            ) {
              Text("Primary Lock Method", style = MaterialTheme.typography.titleSmall)
              Text(
                text = "Active: ${settings.primaryLockType.label} • Biometrics stays ${if (settings.biometricEnabled) "ON" else "OFF"} across Pattern & PIN modes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("settings_primary_lock_selector"),
              ) {
                val methods = listOf(
                  Triple(PrimaryLockType.PATTERN, "Pattern", Icons.Rounded.Gesture),
                  Triple(PrimaryLockType.PIN_4, "4-Digit", Icons.Rounded.Dialpad),
                  Triple(PrimaryLockType.PIN_6, "6-Digit", Icons.Rounded.Pin),
                )
                methods.forEachIndexed { idx, (type, label, icon) ->
                  val canSelect = when (type) {
                    PrimaryLockType.PATTERN -> settings.hasPatternLock
                    PrimaryLockType.PIN_4 -> settings.hasPinCredential && settings.pinLength == 4
                    PrimaryLockType.PIN_6 -> settings.hasPinCredential && settings.pinLength == 6
                  }
                  SegmentedButton(
                    selected = settings.primaryLockType == type,
                    onClick = {
                      if (canSelect) {
                        onSelectPrimaryLockType(type)
                      } else if (type == PrimaryLockType.PATTERN) {
                        showSetupPatternDialog = true
                      } else {
                        showChangePinDialog = true
                      }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = methods.size),
                    icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("settings_lock_type_${type.name.lowercase()}"),
                  ) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                  }
                }
              }
            }

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
                Text(
                  text = if (settings.hasPinCredential) {
                    "Change Master PIN (${settings.pinLength}-Digit Active)"
                  } else {
                    "Add 4- or 6-Digit Master PIN"
                  },
                  style = MaterialTheme.typography.titleSmall,
                )
                Text(
                  text = "Switch between 4-digit & 6-digit PINs or re-wrap your 256-bit DEK cleanly",
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
                Text("3×3 Pattern Lock", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = if (settings.hasPatternLock) {
                    "Configured • Active alongside Biometrics${if (settings.hasPinCredential) " & ${settings.pinLength}-digit PIN" else ""}"
                  } else {
                    "Not set • Draw a 3×3 Pattern to complement Biometrics & PIN"
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
                  Text("Default Lock Screen to 3×3 Pattern", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "Open the 3×3 Pattern lock screen when Biometrics is dismissed or unavailable",
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

              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 10.dp),
              ) {
                Spacer(modifier = Modifier.width(36.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text("Stealth Pattern Mode", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "Hide drawn connecting lines on the 3×3 Pattern grid from onlookers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = settings.patternStealthMode,
                  onCheckedChange = onTogglePatternStealthMode,
                  modifier = Modifier.testTag("settings_pattern_stealth_switch"),
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
                    "Configured • Supports preset or custom questions (answers stay local)"
                  } else {
                    "Configure questions to recover forgotten PIN or Pattern"
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
                .clickable(onClick = onOpenIntegrityDiagnostic)
                .padding(16.dp)
                .testTag("settings_integrity_scan_row"),
            ) {
              Icon(
                imageVector = Icons.Rounded.VerifiedUser,
                contentDescription = null,
                tint = if ((lastIntegrityCorruptedCount ?: 0) > 0) {
                  MaterialTheme.colorScheme.error
                } else {
                  MaterialTheme.colorScheme.primary
                },
              )
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Encrypted File Integrity Diagnostic", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = when {
                    isScanningIntegrity -> "Verifying VLK1 headers & AES-256-GCM chunk tags..."
                    (lastIntegrityCorruptedCount ?: 0) > 0 ->
                      "$lastIntegrityCorruptedCount corrupted file(s) detected — tap to view affected names"
                    lastIntegrityCorruptedCount == 0 ->
                      "Last scan passed (0 corrupted files) — tap to re-scan"
                    else -> "Scan .vlock files for tampered headers, bit rot, or truncated chunks"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = if ((lastIntegrityCorruptedCount ?: 0) > 0) {
                    MaterialTheme.colorScheme.error
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  },
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

      // Dual Hidden Read-Only Key Recovery Vault
      item(key = "dual_key_vault_card") {
        val alertText = dualKeyStatus?.tamperAlert?.takeIf { it.isNotBlank() }
          ?: settings.dualKeyTamperAlert.takeIf { it.isNotBlank() }
        val isExternalLinked = settings.dualKeyExternalTreeUri.isNotBlank()
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          border = if (alertText != null) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_dual_key_vault_card"),
        ) {
          Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(16.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
              Icon(
                imageVector = Icons.Rounded.FolderSpecial,
                contentDescription = null,
                tint = if (alertText != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
              )
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text("Dual Hidden Key Recovery Vault", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = buildString {
                    append("Internal: Read-Only & Hidden (.beastlock_keystore)")
                    append(" • External (.BeastLock_Keys): ")
                    append(if (isExternalLinked) "Linked" else "Not linked")
                    if (settings.dualKeyLastSyncAtMillis > 0L) {
                      append(" • Synced ${formatTimestamp(settings.dualKeyLastSyncAtMillis)}")
                    }
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            if (alertText != null) {
              Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("dual_key_tamper_banner"),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.padding(10.dp),
                ) {
                  Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(alertText, style = MaterialTheme.typography.bodySmall)
                }
              }
            } else {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Rounded.CheckCircle,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "SHA-256 checksum verified • Plaintext keys are never written to disk",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              OutlinedButton(
                onClick = { dualKeyExternalPicker.launch(null) },
                modifier = Modifier
                  .weight(1f)
                  .testTag("settings_link_external_key_folder_button"),
              ) {
                Text(
                  text = if (isExternalLinked) "Change External" else "Link External",
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              FilledTonalButton(
                onClick = onVerifyDualKeyVault,
                modifier = Modifier
                  .weight(1f)
                  .testTag("settings_verify_dual_key_button"),
              ) {
                Text("Verify & Sync", maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }
      }

      // System Failures, Crash Log & User Feedback
      item(key = "diagnostics_support_card") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDiagnostics)
            .testTag("settings_open_diagnostics_button"),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.BugReport,
              contentDescription = null,
              tint = if (pendingIssuesCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text("Diagnostics, Crash Recorder & Feedback", style = MaterialTheme.typography.titleSmall)
              Text(
                text = if (pendingIssuesCount > 0) {
                  "$pendingIssuesCount recorded system failure(s) ready for you to review & send"
                } else {
                  "Review sanitized diagnostics or express feedback directly to our database"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (pendingIssuesCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
              )
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
      hasExistingPin = settings.hasPinCredential,
      currentPinLength = settings.pinLength,
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
      canRemovePattern = settings.hasPinCredential,
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
      initialShareCustomSuggestions = settings.shareCustomQuestionSuggestions,
      onDismiss = { showUpdateQuestionsDialog = false },
      onSave = { q1, a1, q2, a2, customQuestionsToShare, shareSuggestions ->
        onUpdateRecoveryQuestions(q1, a1, q2, a2, customQuestionsToShare, shareSuggestions) {
          showUpdateQuestionsDialog = false
        }
      },
    )
  }

  if (showCustomDurationDialog) {
    CustomTimeoutDurationDialog(
      title = "Custom Auto-Lock Duration",
      description = "Set how long the app can remain in the background before re-encrypting unlocked folders and requiring authentication.",
      minSeconds = 1,
      currentSeconds = settings.autoLockTimeoutSeconds,
      testTagPrefix = "custom_duration",
      onDismiss = { showCustomDurationDialog = false },
      onConfirmSeconds = { seconds ->
        showCustomDurationDialog = false
        onSelectAutoLockSeconds(seconds)
      },
    )
  }

  if (showCustomIdleDurationDialog) {
    CustomTimeoutDurationDialog(
      title = "Custom Idle Timeout Duration",
      description = "Set how long BeastLock can remain open without user activity before automatically locking the vault.",
      minSeconds = 1,
      currentSeconds = settings.idleTimeoutSeconds,
      testTagPrefix = "custom_idle_duration",
      onDismiss = { showCustomIdleDurationDialog = false },
      onConfirmSeconds = { seconds ->
        showCustomIdleDurationDialog = false
        onSelectIdleTimeoutSeconds(seconds)
      },
    )
  }

  if (showChangeStealthCodeDialog) {
    ChangeStealthCodeDialog(
      hasCustomCode = settings.hasCustomStealthCode,
      onDismiss = { showChangeStealthCodeDialog = false },
      onConfirmCode = { code ->
        onChangeStealthCode(code) { ok ->
          if (ok) showChangeStealthCodeDialog = false
        }
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
  hasExistingPin: Boolean,
  currentPinLength: Int,
  onDismiss: () -> Unit,
  onSubmit: (current: String, next: String, callback: (Boolean) -> Unit) -> Unit,
) {
  var currentPin by remember { mutableStateOf("") }
  var targetLength by remember { mutableIntStateOf(if (currentPinLength == 4) 4 else 6) }
  var newPin by remember { mutableStateOf("") }
  var confirmPin by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (hasExistingPin) "Change Master PIN" else "Add Master PIN") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          listOf(4 to "4-Digit PIN", 6 to "6-Digit PIN").forEachIndexed { idx, (digits, label) ->
            SegmentedButton(
              selected = targetLength == digits,
              onClick = {
                targetLength = digits
                newPin = ""
                confirmPin = ""
                error = null
              },
              shape = SegmentedButtonDefaults.itemShape(index = idx, count = 2),
            ) {
              Text(label)
            }
          }
        }
        if (hasExistingPin) {
          OutlinedTextField(
            value = currentPin,
            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) { currentPin = it; error = null } },
            label = { Text("Current ${if (currentPinLength == 4) "4" else "6"}-digit PIN") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("change_pin_current"),
          )
        }
        OutlinedTextField(
          value = newPin,
          onValueChange = { if (it.length <= targetLength && it.all(Char::isDigit)) { newPin = it; error = null } },
          label = { Text("New $targetLength-digit PIN") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("change_pin_new"),
        )
        OutlinedTextField(
          value = confirmPin,
          onValueChange = { if (it.length <= targetLength && it.all(Char::isDigit)) { confirmPin = it; error = null } },
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
            hasExistingPin && currentPin.length != currentPinLength ->
              error = "Current PIN must be $currentPinLength digits."
            newPin.length != targetLength ->
              error = "New PIN must be $targetLength digits."
            newPin != confirmPin ->
              error = "New PINs do not match."
            else -> onSubmit(currentPin, newPin) { ok ->
              if (!ok) error = "Current PIN is incorrect."
            }
          }
        },
        modifier = Modifier.testTag("change_pin_submit"),
      ) {
        Text(if (hasExistingPin) "Update PIN" else "Save PIN")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateRecoveryQuestionsDialog(
  initialQ1: String,
  initialQ2: String,
  initialShareCustomSuggestions: Boolean,
  onDismiss: () -> Unit,
  onSave: (
    q1: String,
    a1: String,
    q2: String,
    a2: String,
    customQuestionsToShare: List<String>,
    shareCustomSuggestions: Boolean,
  ) -> Unit,
) {
  val customSentinel = "Write custom security question…"
  val presets1 = remember {
    listOf(
      "What was the name of your first school?",
      "What city were your parents married in?",
      "What was the model of your first mobile phone?",
      "What is your childhood nickname?",
    )
  }
  val presets2 = remember {
    listOf(
      "What was your favorite childhood book?",
      "What street did your best friend live on?",
      "What was the name of your first pet?",
      "What is the middle name of your oldest sibling?",
    )
  }

  var isCustomQ1 by remember { mutableStateOf(initialQ1 !in presets1) }
  var q1Preset by remember { mutableStateOf(if (initialQ1 in presets1) initialQ1 else presets1.first()) }
  var q1Custom by remember { mutableStateOf(if (initialQ1 !in presets1) initialQ1 else "") }
  var q1Expanded by remember { mutableStateOf(false) }
  var a1 by remember { mutableStateOf("") }

  var isCustomQ2 by remember { mutableStateOf(initialQ2 !in presets2) }
  var q2Preset by remember { mutableStateOf(if (initialQ2 in presets2) initialQ2 else presets2.first()) }
  var q2Custom by remember { mutableStateOf(if (initialQ2 !in presets2) initialQ2 else "") }
  var q2Expanded by remember { mutableStateOf(false) }
  var a2 by remember { mutableStateOf("") }

  var shareSuggestions by remember { mutableStateOf(initialShareCustomSuggestions) }
  var error by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Update Security Questions") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
      ) {
        ExposedDropdownMenuBox(expanded = q1Expanded, onExpandedChange = { q1Expanded = !q1Expanded }) {
          OutlinedTextField(
            value = if (isCustomQ1) customSentinel else q1Preset,
            onValueChange = {},
            readOnly = true,
            label = { Text("Security Question 1") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = q1Expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
          )
          ExposedDropdownMenu(expanded = q1Expanded, onDismissRequest = { q1Expanded = false }) {
            presets1.forEach { preset ->
              DropdownMenuItem(
                text = { Text(preset) },
                onClick = { q1Preset = preset; isCustomQ1 = false; q1Expanded = false },
              )
            }
            DropdownMenuItem(
              leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
              text = { Text(customSentinel) },
              onClick = { isCustomQ1 = true; q1Expanded = false },
            )
          }
        }
        if (isCustomQ1) {
          OutlinedTextField(
            value = q1Custom,
            onValueChange = { if (it.length <= 160) { q1Custom = it; error = null } },
            label = { Text("Custom Question 1 (min 5 chars)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("settings_custom_q1_input"),
          )
        }
        OutlinedTextField(
          value = a1,
          onValueChange = { a1 = it; error = null },
          label = { Text("Answer 1 (kept local only)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        ExposedDropdownMenuBox(expanded = q2Expanded, onExpandedChange = { q2Expanded = !q2Expanded }) {
          OutlinedTextField(
            value = if (isCustomQ2) customSentinel else q2Preset,
            onValueChange = {},
            readOnly = true,
            label = { Text("Security Question 2") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = q2Expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
          )
          ExposedDropdownMenu(expanded = q2Expanded, onDismissRequest = { q2Expanded = false }) {
            presets2.forEach { preset ->
              DropdownMenuItem(
                text = { Text(preset) },
                onClick = { q2Preset = preset; isCustomQ2 = false; q2Expanded = false },
              )
            }
            DropdownMenuItem(
              leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
              text = { Text(customSentinel) },
              onClick = { isCustomQ2 = true; q2Expanded = false },
            )
          }
        }
        if (isCustomQ2) {
          OutlinedTextField(
            value = q2Custom,
            onValueChange = { if (it.length <= 160) { q2Custom = it; error = null } },
            label = { Text("Custom Question 2 (min 5 chars)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("settings_custom_q2_input"),
          )
        }
        OutlinedTextField(
          value = a2,
          onValueChange = { a2 = it; error = null },
          label = { Text("Answer 2 (kept local only)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        if (isCustomQ1 || isCustomQ2) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
              text = "Share custom question wording anonymously (never answers)",
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.weight(1f),
            )
            Switch(
              checked = shareSuggestions,
              onCheckedChange = { shareSuggestions = it },
              modifier = Modifier.testTag("settings_share_custom_questions_switch"),
            )
          }
        }
        if (error != null) {
          Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val finalQ1 = (if (isCustomQ1) q1Custom else q1Preset).trim()
          val finalQ2 = (if (isCustomQ2) q2Custom else q2Preset).trim()
          when {
            finalQ1.length < 5 || finalQ2.length < 5 ->
              error = "Each question must be at least 5 characters."
            finalQ1.equals(finalQ2, ignoreCase = true) ->
              error = "Questions must be distinct."
            a1.trim().length < 2 || a2.trim().length < 2 ->
              error = "Provide both answers (min 2 characters)."
            else -> {
              val customPrompts = buildList {
                if (isCustomQ1) add(finalQ1)
                if (isCustomQ2) add(finalQ2)
              }
              onSave(finalQ1, a1, finalQ2, a2, customPrompts, shareSuggestions)
            }
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
private fun CustomTimeoutDurationDialog(
  title: String,
  description: String,
  minSeconds: Int,
  currentSeconds: Int,
  testTagPrefix: String,
  onDismiss: () -> Unit,
  onConfirmSeconds: (Int) -> Unit,
) {
  var useMinutes by remember { mutableStateOf(currentSeconds >= 60 && currentSeconds % 60 == 0) }
  var rawInput by remember {
    val initial = if (useMinutes) {
      (currentSeconds / 60).coerceAtLeast(1)
    } else {
      currentSeconds.coerceAtLeast(minSeconds)
    }
    mutableStateOf(initial.toString())
  }
  var error by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            selected = !useMinutes,
            onClick = { useMinutes = false },
            label = { Text("Seconds") },
            modifier = Modifier.testTag("${testTagPrefix}_unit_seconds"),
          )
          FilterChip(
            selected = useMinutes,
            onClick = { useMinutes = true },
            label = { Text("Minutes") },
            modifier = Modifier.testTag("${testTagPrefix}_unit_minutes"),
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
            .testTag("${testTagPrefix}_value_input"),
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
          if (totalSeconds == null || totalSeconds < minSeconds || totalSeconds > 86_400) {
            error = "Enter a valid duration between $minSeconds second(s) and 24 hours."
          } else {
            onConfirmSeconds(totalSeconds)
          }
        },
        modifier = Modifier.testTag("${testTagPrefix}_confirm"),
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
  canRemovePattern: Boolean,
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
            "Connect at least 4 dots on the 3×3 grid. Your Pattern wraps the vault key with PBKDF2-HMAC-SHA256."
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
        if (hasExistingPattern && canRemovePattern) {
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

@Composable
private fun ChangeStealthCodeDialog(
  hasCustomCode: Boolean,
  onDismiss: () -> Unit,
  onConfirmCode: (String) -> Unit,
) {
  var rawCode by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val normalizedPreview = remember(rawCode) {
    SecurityPreferencesRepository.normalizeStealthCode(rawCode)
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = Icons.Rounded.VisibilityOff,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    title = { Text(if (hasCustomCode) "Update Stealth Search Code" else "Set Custom Stealth Code") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "Type this code into BeastLock's Dashboard search bar to summon the authenticated Stealth Vault Drawer. Codes are stored solely as salted PBKDF2 hashes.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = rawCode,
          onValueChange = { input ->
            if (input.length <= 32) {
              rawCode = input
              error = null
            }
          },
          label = { Text("New Stealth Code (min 4 chars)") },
          placeholder = { Text("e.g. #9922") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("stealth_code_input"),
        )
        if (normalizedPreview != null) {
          Text(
            text = "Search bar trigger: $normalizedPreview",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        if (error != null) {
          Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val normalized = SecurityPreferencesRepository.normalizeStealthCode(rawCode)
          if (normalized == null) {
            error = "Enter 4 to 32 characters (e.g. #9922)."
          } else {
            onConfirmCode(normalized)
          }
        },
        modifier = Modifier.testTag("stealth_code_save_button"),
      ) {
        Text("Save Hash")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}



