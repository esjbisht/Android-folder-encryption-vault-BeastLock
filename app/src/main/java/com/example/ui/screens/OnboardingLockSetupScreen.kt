package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.crypto.BiometricAvailability
import com.example.data.preferences.PrimaryLockType
import com.example.ui.components.NumericPinKeypad
import com.example.ui.components.PatternLockGrid
import com.example.ui.components.PinDotsIndicator
import com.example.ui.components.ThemeModeDropdownButton
import com.example.ui.theme.ThemeMode

private const val CUSTOM_QUESTION_SENTINEL = "Write custom security question…"

private val PRESET_QUESTIONS_1 = listOf(
  "What was the name of your first school?",
  "What city were your parents married in?",
  "What was the model of your first mobile phone?",
  "What is your childhood nickname?",
)

private val PRESET_QUESTIONS_2 = listOf(
  "What was your favorite childhood book?",
  "What street did your best friend live on?",
  "What was the name of your first pet?",
  "What is the middle name of your oldest sibling?",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingLockSetupScreen(
  biometricAvailability: BiometricAvailability,
  themeMode: ThemeMode,
  onSelectThemeMode: (ThemeMode) -> Unit,
  onCompleteSetup: (
    lockType: PrimaryLockType,
    pin: String?,
    patternNodes: List<Int>?,
    enableBiometrics: Boolean,
    q1: String,
    a1: String,
    q2: String,
    a2: String,
    shareCustomQuestions: Boolean,
    customQuestionsToQueue: List<String>,
    externalDualKeyUri: Uri?,
  ) -> Unit,
) {
  // 0: Overview, 1: Choose Lock Type & Credential (+ Confirm), 2: Security Questions & Dual Key Recovery
  var step by remember { mutableIntStateOf(0) }
  var selectedLockType by remember { mutableStateOf(PrimaryLockType.PIN_6) }

  // PIN states
  var pin by remember { mutableStateOf("") }
  var confirmPin by remember { mutableStateOf("") }
  var isConfirmingPin by remember { mutableStateOf(false) }
  var pinMismatchError by remember { mutableStateOf(false) }

  // Pattern states
  var primaryPattern by remember { mutableStateOf<List<Int>?>(null) }
  var confirmPatternDraft by remember { mutableStateOf<List<Int>?>(null) }
  var patternError by remember { mutableStateOf<String?>(null) }

  // Optional secondary backup credential
  var secondaryPattern by remember { mutableStateOf<List<Int>?>(null) }
  var secondaryPin by remember { mutableStateOf<String?>(null) }
  var showSecondaryPatternDialog by remember { mutableStateOf(false) }
  var showSecondaryPinDialog by remember { mutableStateOf(false) }

  val biometricsSupported = biometricAvailability == BiometricAvailability.AVAILABLE_STRONG ||
    biometricAvailability == BiometricAvailability.AVAILABLE_WEAK_OR_DEVICE
  var enableBiometrics by remember(biometricsSupported) { mutableStateOf(biometricsSupported) }

  // Security questions + custom question inputs
  var selectedPresetQ1 by remember { mutableStateOf(PRESET_QUESTIONS_1[0]) }
  var isCustomQ1 by remember { mutableStateOf(false) }
  var customQ1Text by remember { mutableStateOf("") }
  var answer1 by remember { mutableStateOf("") }

  var selectedPresetQ2 by remember { mutableStateOf(PRESET_QUESTIONS_2[0]) }
  var isCustomQ2 by remember { mutableStateOf(false) }
  var customQ2Text by remember { mutableStateOf("") }
  var answer2 by remember { mutableStateOf("") }

  var q1Expanded by remember { mutableStateOf(false) }
  var q2Expanded by remember { mutableStateOf(false) }
  var shareCustomQuestions by remember { mutableStateOf(true) }
  var recoveryError by remember { mutableStateOf<String?>(null) }

  var externalDualKeyUri by remember { mutableStateOf<Uri?>(null) }
  val dualKeyFolderPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree(),
  ) { uri ->
    if (uri != null) externalDualKeyUri = uri
  }

  val targetPinLength = if (selectedLockType == PrimaryLockType.PIN_4) 4 else 6

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .statusBarsPadding()
      .navigationBarsPadding(),
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 600.dp)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (step > 0) {
          IconButton(
            onClick = {
              pinMismatchError = false
              patternError = null
              recoveryError = null
              if (step == 1 && (isConfirmingPin || confirmPatternDraft != null)) {
                isConfirmingPin = false
                confirmPin = ""
                pin = ""
                confirmPatternDraft = null
              } else {
                step--
              }
            },
            modifier = Modifier.testTag("onboarding_back_button"),
          ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
          }
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "BEASTLOCK INITIALIZATION • STEP ${step + 1}/3",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.height(6.dp))
          LinearProgressIndicator(
            progress = { (step + 1) / 3f },
            modifier = Modifier
              .fillMaxWidth()
              .height(6.dp)
              .clip(RoundedCornerShape(3.dp)),
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        ThemeModeDropdownButton(
          currentMode = themeMode,
          onSelectMode = onSelectThemeMode,
          testTagPrefix = "onboarding_theme_switcher",
        )
      }

      when (step) {
        0 -> {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(210.dp)
              .clip(MaterialTheme.shapes.large),
          ) {
            Image(
              painter = painterResource(id = R.drawable.img_vault_security_hero),
              contentDescription = "BeastLock AES-256 cryptographic shield",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
            Box(
              modifier = Modifier
                .fillMaxSize()
                .background(
                  Brush.horizontalGradient(
                    colors = listOf(
                      Color(0xE60A0F16),
                      Color(0x990A0F16),
                      Color(0x330A0F16),
                    ),
                  ),
                )
                .padding(20.dp),
              contentAlignment = Alignment.BottomStart,
            ) {
              Column {
                Surface(
                  color = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                  shape = RoundedCornerShape(50),
                ) {
                  Text(
                    text = "AES-256-GCM • DUAL KEY RECOVERY",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                  )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                  text = "BeastLock",
                  style = MaterialTheme.typography.displayLarge,
                  color = Color.White,
                )
                Text(
                  text = "Lock internal & external folders with Pattern, 4/6-digit PIN & Biometrics.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = Color(0xFFCBD5E1),
                )
              }
            }
          }

          FeatureCard(
            icon = Icons.Rounded.Shield,
            title = "Auto-Locking Folder Protection",
            subtitle = "Added device folders lock automatically with AES-256-GCM and require BeastLock auth on every open.",
          )
          FeatureCard(
            icon = Icons.Rounded.Fingerprint,
            title = "Choice of Lock + Biometrics Always Ready",
            subtitle = "Pick 3×3 Pattern, 4-Digit PIN, or 6-Digit PIN on install. Biometrics stays ON across all modes unless toggled off.",
          )
          FeatureCard(
            icon = Icons.Rounded.Key,
            title = "Dual Hidden Read-Only Key Recovery Vault",
            subtitle = "PBKDF2-wrapped keys mirror safely across internal storage & an external .BeastLock_Keys folder with SHA-256 checks.",
          )

          Spacer(modifier = Modifier.height(4.dp))
          Button(
            onClick = { step = 1 },
            modifier = Modifier
              .fillMaxWidth()
              .height(54.dp)
              .testTag("onboarding_get_started_button"),
          ) {
            Text("Choose Lock Type & Key", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
          }
        }

        1 -> {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = "Choose Primary Lock Type",
              style = MaterialTheme.typography.headlineMedium,
            )
            Text(
              text = "Pick how you want to unlock BeastLock. Biometrics remains active in every lock mode unless switched off.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )

            SingleChoiceSegmentedButtonRow(
              modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_lock_type_selector"),
            ) {
              val lockOptions = listOf(
                Triple(PrimaryLockType.PATTERN, "Pattern", Icons.Rounded.Gesture),
                Triple(PrimaryLockType.PIN_4, "4-Digit PIN", Icons.Rounded.Dialpad),
                Triple(PrimaryLockType.PIN_6, "6-Digit PIN", Icons.Rounded.Pin),
              )
              lockOptions.forEachIndexed { index, (type, label, icon) ->
                SegmentedButton(
                  selected = selectedLockType == type,
                  onClick = {
                    selectedLockType = type
                    pin = ""
                    confirmPin = ""
                    isConfirmingPin = false
                    pinMismatchError = false
                    confirmPatternDraft = null
                    primaryPattern = null
                    patternError = null
                  },
                  shape = SegmentedButtonDefaults.itemShape(index = index, count = lockOptions.size),
                  icon = {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
                  },
                  modifier = Modifier.testTag("onboarding_lock_type_${type.name.lowercase()}"),
                ) {
                  Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }
            }

            Card(
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 12.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Fingerprint,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text("Biometric Unlock (Default ON)", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = if (biometricsSupported) {
                      "Enabled alongside ${selectedLockType.label} until you switch it off"
                    } else {
                      "No device biometrics enrolled (${selectedLockType.label} will unlock directly)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = enableBiometrics,
                  onCheckedChange = { enableBiometrics = it },
                  enabled = biometricsSupported,
                  modifier = Modifier.testTag("onboarding_biometric_switch"),
                )
              }
            }

            if (selectedLockType == PrimaryLockType.PATTERN) {
              Icon(
                imageVector = Icons.Rounded.Gesture,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
              )
              Text(
                text = if (confirmPatternDraft == null) {
                  "Draw 3×3 Unlock Pattern"
                } else {
                  "Redraw 3×3 Pattern to Confirm"
                },
                style = MaterialTheme.typography.titleLarge,
              )
              PatternLockGrid(
                isError = patternError != null,
                helperText = patternError ?: if (confirmPatternDraft == null) {
                  "Connect at least 4 dots on the 3×3 grid"
                } else {
                  "Draw the exact same pattern again to confirm"
                },
                onPatternCompleted = { nodes ->
                  if (nodes.size < 4) {
                    patternError = "Connect at least 4 dots."
                    return@PatternLockGrid
                  }
                  val draft = confirmPatternDraft
                  if (draft == null) {
                    confirmPatternDraft = nodes
                    patternError = null
                  } else if (draft == nodes) {
                    primaryPattern = nodes
                    patternError = null
                    step = 2
                  } else {
                    patternError = "Patterns didn't match. Draw your pattern again."
                    confirmPatternDraft = null
                  }
                },
                modifier = Modifier.testTag("onboarding_primary_pattern_grid"),
              )
              if (confirmPatternDraft != null) {
                TextButton(
                  onClick = {
                    confirmPatternDraft = null
                    patternError = null
                  },
                ) {
                  Text("Reset Pattern")
                }
              }
            } else {
              Icon(
                imageVector = if (isConfirmingPin) Icons.Rounded.CheckCircle else Icons.Rounded.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
              )
              Text(
                text = if (isConfirmingPin) {
                  "Confirm $targetPinLength-Digit Master PIN"
                } else {
                  "Set $targetPinLength-Digit Master PIN"
                },
                style = MaterialTheme.typography.titleLarge,
              )
              Text(
                text = when {
                  pinMismatchError -> "PINs did not match. Re-enter your $targetPinLength-digit PIN."
                  isConfirmingPin -> "Re-enter your $targetPinLength-digit PIN to confirm."
                  else -> "Derives a PBKDF2-HMAC-SHA256 wrapping key for your 256-bit vault key."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (pinMismatchError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
              PinDotsIndicator(
                totalDigits = targetPinLength,
                enteredDigits = if (isConfirmingPin) confirmPin.length else pin.length,
                isError = pinMismatchError,
              )
              NumericPinKeypad(
                onDigit = { digit ->
                  if (!isConfirmingPin) {
                    if (pin.length < targetPinLength) {
                      pin += digit
                      if (pin.length == targetPinLength) {
                        confirmPin = ""
                        pinMismatchError = false
                        isConfirmingPin = true
                      }
                    }
                  } else {
                    if (confirmPin.length < targetPinLength) {
                      pinMismatchError = false
                      confirmPin += digit
                      if (confirmPin.length == targetPinLength) {
                        if (confirmPin == pin) {
                          step = 2
                        } else {
                          pinMismatchError = true
                          confirmPin = ""
                        }
                      }
                    }
                  }
                },
                onBackspace = {
                  if (isConfirmingPin) {
                    if (confirmPin.isNotEmpty()) confirmPin = confirmPin.dropLast(1)
                  } else {
                    if (pin.isNotEmpty()) pin = pin.dropLast(1)
                  }
                },
              )
              if (isConfirmingPin) {
                TextButton(
                  onClick = {
                    pin = ""
                    confirmPin = ""
                    isConfirmingPin = false
                    pinMismatchError = false
                  },
                ) {
                  Text("Choose a different PIN")
                }
              }
            }
          }
        }

        2 -> {
          Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = "Security Questions & Dual Key Recovery",
              style = MaterialTheme.typography.headlineMedium,
            )
            Text(
              text = "Pick a preset question or write your own custom security question. Plaintext answers NEVER leave your device.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Question 1 selector + custom input
            ExposedDropdownMenuBox(
              expanded = q1Expanded,
              onExpandedChange = { q1Expanded = !q1Expanded },
            ) {
              OutlinedTextField(
                value = if (isCustomQ1) CUSTOM_QUESTION_SENTINEL else selectedPresetQ1,
                onValueChange = {},
                readOnly = true,
                label = { Text("Security Question 1") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = q1Expanded) },
                modifier = Modifier
                  .fillMaxWidth()
                  .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                  .testTag("recovery_q1_dropdown"),
              )
              ExposedDropdownMenu(
                expanded = q1Expanded,
                onDismissRequest = { q1Expanded = false },
              ) {
                PRESET_QUESTIONS_1.forEach { preset ->
                  DropdownMenuItem(
                    text = { Text(preset) },
                    onClick = {
                      selectedPresetQ1 = preset
                      isCustomQ1 = false
                      q1Expanded = false
                      recoveryError = null
                    },
                  )
                }
                DropdownMenuItem(
                  leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                  text = { Text(CUSTOM_QUESTION_SENTINEL, fontWeight = FontWeight.SemiBold) },
                  onClick = {
                    isCustomQ1 = true
                    q1Expanded = false
                    recoveryError = null
                  },
                )
              }
            }

            if (isCustomQ1) {
              OutlinedTextField(
                value = customQ1Text,
                onValueChange = {
                  if (it.length <= 160) {
                    customQ1Text = it
                    recoveryError = null
                  }
                },
                label = { Text("Custom Security Question 1 (min 5 chars)") },
                placeholder = { Text("e.g. What was my first childhood bicycle brand?") },
                singleLine = true,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("recovery_q1_custom_input"),
              )
            }

            OutlinedTextField(
              value = answer1,
              onValueChange = {
                answer1 = it
                recoveryError = null
              },
              label = { Text("Answer 1 (kept local only)") },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("recovery_a1_input"),
            )

            // Question 2 selector + custom input
            ExposedDropdownMenuBox(
              expanded = q2Expanded,
              onExpandedChange = { q2Expanded = !q2Expanded },
            ) {
              OutlinedTextField(
                value = if (isCustomQ2) CUSTOM_QUESTION_SENTINEL else selectedPresetQ2,
                onValueChange = {},
                readOnly = true,
                label = { Text("Security Question 2") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = q2Expanded) },
                modifier = Modifier
                  .fillMaxWidth()
                  .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                  .testTag("recovery_q2_dropdown"),
              )
              ExposedDropdownMenu(
                expanded = q2Expanded,
                onDismissRequest = { q2Expanded = false },
              ) {
                PRESET_QUESTIONS_2.forEach { preset ->
                  DropdownMenuItem(
                    text = { Text(preset) },
                    onClick = {
                      selectedPresetQ2 = preset
                      isCustomQ2 = false
                      q2Expanded = false
                      recoveryError = null
                    },
                  )
                }
                DropdownMenuItem(
                  leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                  text = { Text(CUSTOM_QUESTION_SENTINEL, fontWeight = FontWeight.SemiBold) },
                  onClick = {
                    isCustomQ2 = true
                    q2Expanded = false
                    recoveryError = null
                  },
                )
              }
            }

            if (isCustomQ2) {
              OutlinedTextField(
                value = customQ2Text,
                onValueChange = {
                  if (it.length <= 160) {
                    customQ2Text = it
                    recoveryError = null
                  }
                },
                label = { Text("Custom Security Question 2 (min 5 chars)") },
                placeholder = { Text("e.g. Which mountain did I hike first?") },
                singleLine = true,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("recovery_q2_custom_input"),
              )
            }

            OutlinedTextField(
              value = answer2,
              onValueChange = {
                answer2 = it
                recoveryError = null
              },
              label = { Text("Answer 2 (kept local only)") },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("recovery_a2_input"),
            )

            if (isCustomQ1 || isCustomQ2) {
              Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                  Icon(Icons.Rounded.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                  Spacer(modifier = Modifier.width(12.dp))
                  Column(modifier = Modifier.weight(1f)) {
                    Text("Share Custom Question Ideas", style = MaterialTheme.typography.titleSmall)
                    Text(
                      text = "Save custom question wording only to our database to improve presets in future updates. Answers are NEVER uploaded.",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  Switch(
                    checked = shareCustomQuestions,
                    onCheckedChange = { shareCustomQuestions = it },
                    modifier = Modifier.testTag("onboarding_share_custom_questions_switch"),
                  )
                }
              }
            }

            // Optional secondary credential (Pattern if PIN primary; PIN if Pattern primary)
            Card(
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 12.dp),
              ) {
                Icon(
                  imageVector = if (selectedLockType == PrimaryLockType.PATTERN) Icons.Rounded.Pin else Icons.Rounded.Gesture,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = if (selectedLockType == PrimaryLockType.PATTERN) {
                      "Backup 4/6-Digit PIN (Optional)"
                    } else {
                      "3×3 Pattern Fallback (Optional)"
                    },
                    style = MaterialTheme.typography.titleSmall,
                  )
                  Text(
                    text = if (selectedLockType == PrimaryLockType.PATTERN) {
                      secondaryPin?.let { "${it.length}-digit backup PIN configured" }
                        ?: "Optionally add a numeric PIN fallback alongside Biometrics & Pattern"
                    } else {
                      secondaryPattern?.let { "${it.size}-dot 3×3 Pattern configured" }
                        ?: "Optionally add a quick 3×3 Pattern fallback alongside Biometrics & PIN"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                TextButton(
                  onClick = {
                    if (selectedLockType == PrimaryLockType.PATTERN) {
                      showSecondaryPinDialog = true
                    } else {
                      showSecondaryPatternDialog = true
                    }
                  },
                  modifier = Modifier.testTag("onboarding_secondary_auth_button"),
                ) {
                  val hasSecondary = if (selectedLockType == PrimaryLockType.PATTERN) {
                    secondaryPin != null
                  } else {
                    secondaryPattern != null
                  }
                  Text(if (hasSecondary) "Change" else "Set Up")
                }
              }
            }

            // Dual Hidden Read-Only Key Recovery Mirror Link
            Card(
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
              border = if (externalDualKeyUri != null) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(14.dp),
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Rounded.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                  Spacer(modifier = Modifier.width(10.dp))
                  Column(modifier = Modifier.weight(1f)) {
                    Text("Dual Hidden Key Recovery Mirror", style = MaterialTheme.typography.titleSmall)
                    Text(
                      text = "Internal read-only mirror starts automatically. Optionally link an external folder (.BeastLock_Keys) for dual-memory protection & SHA-256 auto-healing.",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
                OutlinedButton(
                  onClick = { dualKeyFolderPicker.launch(null) },
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_link_external_key_folder_button"),
                ) {
                  Text(
                    text = if (externalDualKeyUri != null) {
                      "External .BeastLock_Keys Folder Linked ✓"
                    } else {
                      "Link External Key Recovery Folder (Optional)"
                    },
                  )
                }
              }
            }

            if (recoveryError != null) {
              Text(
                text = recoveryError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
              )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Button(
              onClick = {
                val finalQ1 = (if (isCustomQ1) customQ1Text else selectedPresetQ1).trim()
                val finalQ2 = (if (isCustomQ2) customQ2Text else selectedPresetQ2).trim()
                when {
                  finalQ1.length < 5 || finalQ2.length < 5 -> {
                    recoveryError = "Each security question must be at least 5 characters."
                  }
                  finalQ1.equals(finalQ2, ignoreCase = true) -> {
                    recoveryError = "Security Question 1 and Question 2 must be different."
                  }
                  answer1.trim().length < 2 || answer2.trim().length < 2 -> {
                    recoveryError = "Please provide answers of at least 2 characters each."
                  }
                  else -> {
                    val customQuestions = buildList {
                      if (isCustomQ1) add(finalQ1)
                      if (isCustomQ2) add(finalQ2)
                    }
                    val finalPin = if (selectedLockType == PrimaryLockType.PATTERN) {
                      secondaryPin
                    } else {
                      pin
                    }
                    val finalPattern = if (selectedLockType == PrimaryLockType.PATTERN) {
                      primaryPattern
                    } else {
                      secondaryPattern
                    }
                    onCompleteSetup(
                      selectedLockType,
                      finalPin,
                      finalPattern,
                      enableBiometrics,
                      finalQ1,
                      answer1,
                      finalQ2,
                      answer2,
                      shareCustomQuestions && customQuestions.isNotEmpty(),
                      customQuestions,
                      externalDualKeyUri,
                    )
                  }
                }
              },
              modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("onboarding_finish_button"),
            ) {
              Text("Initialize Encrypted Vault", style = MaterialTheme.typography.labelLarge)
            }
          }
        }
      }
    }
  }

  if (showSecondaryPatternDialog) {
    OnboardingPatternSetupDialog(
      hasPattern = secondaryPattern != null,
      onDismiss = { showSecondaryPatternDialog = false },
      onSavePattern = { nodes ->
        secondaryPattern = nodes
        showSecondaryPatternDialog = false
      },
      onClearPattern = {
        secondaryPattern = null
        showSecondaryPatternDialog = false
      },
    )
  }

  if (showSecondaryPinDialog) {
    OnboardingSecondaryPinDialog(
      hasPin = secondaryPin != null,
      onDismiss = { showSecondaryPinDialog = false },
      onSavePin = { newPin ->
        secondaryPin = newPin
        showSecondaryPinDialog = false
      },
      onClearPin = {
        secondaryPin = null
        showSecondaryPinDialog = false
      },
    )
  }
}

@Composable
private fun OnboardingSecondaryPinDialog(
  hasPin: Boolean,
  onDismiss: () -> Unit,
  onSavePin: (String) -> Unit,
  onClearPin: () -> Unit,
) {
  var len by remember { mutableIntStateOf(4) }
  var draftPin by remember { mutableStateOf("") }
  var confirmDraft by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Backup Numeric PIN") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          listOf(4 to "4-Digit", 6 to "6-Digit").forEachIndexed { idx, (count, label) ->
            SegmentedButton(
              selected = len == count,
              onClick = {
                len = count
                draftPin = ""
                confirmDraft = ""
                error = null
              },
              shape = SegmentedButtonDefaults.itemShape(index = idx, count = 2),
            ) {
              Text(label)
            }
          }
        }
        OutlinedTextField(
          value = draftPin,
          onValueChange = {
            if (it.length <= len && it.all(Char::isDigit)) {
              draftPin = it
              error = null
            }
          },
          label = { Text("$len-Digit Backup PIN") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = confirmDraft,
          onValueChange = {
            if (it.length <= len && it.all(Char::isDigit)) {
              confirmDraft = it
              error = null
            }
          },
          label = { Text("Confirm Backup PIN") },
          visualTransformation = PasswordVisualTransformation(),
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
          when {
            draftPin.length != len -> error = "PIN must be $len digits."
            draftPin != confirmDraft -> error = "PINs do not match."
            else -> onSavePin(draftPin)
          }
        },
      ) {
        Text("Save Backup PIN")
      }
    },
    dismissButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasPin) {
          TextButton(onClick = onClearPin) {
            Text("Remove", color = MaterialTheme.colorScheme.error)
          }
        }
        TextButton(onClick = onDismiss) { Text("Cancel") }
      }
    },
  )
}

@Composable
private fun OnboardingPatternSetupDialog(
  hasPattern: Boolean,
  onDismiss: () -> Unit,
  onSavePattern: (List<Int>) -> Unit,
  onClearPattern: () -> Unit,
) {
  var firstPattern by remember { mutableStateOf<List<Int>?>(null) }
  var errorText by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(if (firstPattern == null) "Set 3×3 Pattern Lock" else "Confirm 3×3 Pattern")
    },
    text = {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = if (firstPattern == null) {
            "Connect at least 4 dots on the 3×3 grid. Use this whenever BiometricPrompt is unavailable or bypassed."
          } else {
            "Draw the same 3×3 Pattern again to confirm."
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PatternLockGrid(
          isError = errorText != null,
          helperText = errorText ?: if (firstPattern == null) {
            "Draw pattern (min 4 dots)"
          } else {
            "Redraw pattern to confirm"
          },
          onPatternCompleted = { nodes ->
            if (nodes.size < 4) {
              errorText = "Connect at least 4 dots."
              return@PatternLockGrid
            }
            val first = firstPattern
            if (first == null) {
              firstPattern = nodes
              errorText = null
            } else if (first == nodes) {
              onSavePattern(nodes)
            } else {
              errorText = "Patterns didn't match. Draw again."
            }
          },
          modifier = Modifier.testTag("onboarding_pattern_grid"),
        )
      }
    },
    confirmButton = {
      if (firstPattern != null) {
        TextButton(onClick = {
          firstPattern = null
          errorText = null
        }) {
          Text("Redraw")
        }
      }
    },
    dismissButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasPattern) {
          TextButton(onClick = onClearPattern) {
            Text("Remove", color = MaterialTheme.colorScheme.error)
          }
        }
        TextButton(onClick = onDismiss) { Text("Cancel") }
      }
    },
    modifier = Modifier.testTag("onboarding_pattern_dialog"),
  )
}

@Composable
private fun FeatureCard(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  subtitle: String,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      verticalAlignment = Alignment.Top,
      modifier = Modifier.padding(16.dp),
    ) {
      Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          modifier = Modifier
            .padding(10.dp)
            .size(22.dp),
        )
      }
      Spacer(modifier = Modifier.width(14.dp))
      Column {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
