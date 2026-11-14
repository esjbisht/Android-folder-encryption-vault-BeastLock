package com.example.ui.screens

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
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Shield
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.crypto.BiometricAvailability
import com.example.ui.components.NumericPinKeypad
import com.example.ui.components.PinDotsIndicator
import com.example.ui.components.ThemeModeDropdownButton
import com.example.ui.theme.ThemeMode

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
    pin: String,
    enableBiometrics: Boolean,
    q1: String,
    a1: String,
    q2: String,
    a2: String,
  ) -> Unit,
) {
  var step by remember { mutableIntStateOf(0) } // 0: Welcome, 1: PIN, 2: Confirm PIN, 3: Security Questions
  var pin by remember { mutableStateOf("") }
  var confirmPin by remember { mutableStateOf("") }
  var pinMismatchError by remember { mutableStateOf(false) }

  val biometricsSupported = biometricAvailability == BiometricAvailability.AVAILABLE_STRONG ||
    biometricAvailability == BiometricAvailability.AVAILABLE_WEAK_OR_DEVICE
  var enableBiometrics by remember { mutableStateOf(biometricsSupported) }

  var question1 by remember { mutableStateOf(PRESET_QUESTIONS_1[0]) }
  var answer1 by remember { mutableStateOf("") }
  var question2 by remember { mutableStateOf(PRESET_QUESTIONS_2[0]) }
  var answer2 by remember { mutableStateOf("") }
  var q1Expanded by remember { mutableStateOf(false) }
  var q2Expanded by remember { mutableStateOf(false) }
  var recoveryError by remember { mutableStateOf<String?>(null) }

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
      // Progress header
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (step > 0) {
          IconButton(
            onClick = {
              pinMismatchError = false
              recoveryError = null
              step--
            },
            modifier = Modifier.testTag("onboarding_back_button"),
          ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
          }
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "BEASTLOCK INITIALIZATION • STEP ${step + 1}/4",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.height(6.dp))
          LinearProgressIndicator(
            progress = { (step + 1) / 4f },
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
                    text = "MIT OPEN SOURCE • ZERO TELEMETRY",
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
                  text = "Lock any Android folder with AES-256-GCM & Biometrics.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = Color(0xFFCBD5E1),
                )
              }
            }
          }

          FeatureCard(
            icon = Icons.Rounded.Shield,
            title = "Chunked AES-256-GCM File Encryption",
            subtitle = "Every file is encrypted with a unique 96-bit nonce and 128-bit GCM tamper tag, with optional UUID filename obfuscation.",
          )
          FeatureCard(
            icon = Icons.Rounded.Fingerprint,
            title = "Biometric + Hardware Keystore Binding",
            subtitle = "Unlocks your 256-bit Data Encryption Key only after BiometricPrompt or PBKDF2 Master PIN verification.",
          )
          FeatureCard(
            icon = Icons.Rounded.HelpOutline,
            title = "Cryptographic Security Questions Recovery",
            subtitle = "Recovery answers wrap your key via PBKDF2-HMAC-SHA256. Plaintext answers are never stored.",
          )

          Spacer(modifier = Modifier.height(4.dp))
          Button(
            onClick = { step = 1 },
            modifier = Modifier
              .fillMaxWidth()
              .height(54.dp)
              .testTag("onboarding_get_started_button"),
          ) {
            Text("Create Vault Key & PIN", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
          }
        }

        1 -> {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(
              imageVector = Icons.Rounded.Key,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(40.dp),
            )
            Text(
              text = "Set 6-Digit Master PIN",
              style = MaterialTheme.typography.headlineMedium,
            )
            Text(
              text = "Your PIN derives a PBKDF2-HMAC-SHA256 wrapping key for your 256-bit AES vault key.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            PinDotsIndicator(enteredDigits = pin.length)
            Spacer(modifier = Modifier.height(8.dp))
            NumericPinKeypad(
              onDigit = { digit ->
                if (pin.length < 6) {
                  pin += digit
                  if (pin.length == 6) {
                    confirmPin = ""
                    pinMismatchError = false
                    step = 2
                  }
                }
              },
              onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
            )
          }
        }

        2 -> {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(
              imageVector = Icons.Rounded.CheckCircle,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(40.dp),
            )
            Text(
              text = "Confirm Master PIN",
              style = MaterialTheme.typography.headlineMedium,
            )
            Text(
              text = if (pinMismatchError) {
                "PINs did not match. Please re-enter the 6-digit PIN."
              } else {
                "Re-enter your 6-digit PIN to verify."
              },
              style = MaterialTheme.typography.bodyMedium,
              color = if (pinMismatchError) {
                MaterialTheme.colorScheme.error
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
            )
            PinDotsIndicator(enteredDigits = confirmPin.length, isError = pinMismatchError)

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
                  Text("Enable Biometric Unlock", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = if (biometricsSupported) {
                      "Use fingerprint or face unlock with Android Keystore"
                    } else {
                      "No biometrics enrolled on this device yet (PIN active)"
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

            NumericPinKeypad(
              onDigit = { digit ->
                if (confirmPin.length < 6) {
                  pinMismatchError = false
                  confirmPin += digit
                  if (confirmPin.length == 6) {
                    if (confirmPin == pin) {
                      step = 3
                    } else {
                      pinMismatchError = true
                      confirmPin = ""
                    }
                  }
                }
              },
              onBackspace = { if (confirmPin.isNotEmpty()) confirmPin = confirmPin.dropLast(1) },
            )

            TextButton(onClick = {
              pin = ""
              confirmPin = ""
              step = 1
            }) {
              Text("Choose a different PIN")
            }
          }
        }

        3 -> {
          Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = "Set Up PIN Recovery Questions",
              style = MaterialTheme.typography.headlineMedium,
            )
            Text(
              text = "If you ever forget your Master PIN, answering both questions accurately derives your PBKDF2 Recovery Key to reset the PIN without losing encrypted folders.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ExposedDropdownMenuBox(
              expanded = q1Expanded,
              onExpandedChange = { q1Expanded = !q1Expanded },
            ) {
              OutlinedTextField(
                value = question1,
                onValueChange = { question1 = it },
                label = { Text("Security Question 1") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = q1Expanded) },
                modifier = Modifier
                  .fillMaxWidth()
                  .menuAnchor(MenuAnchorType.PrimaryEditable, true)
                  .testTag("recovery_q1_input"),
              )
              ExposedDropdownMenu(
                expanded = q1Expanded,
                onDismissRequest = { q1Expanded = false },
              ) {
                PRESET_QUESTIONS_1.forEach { preset ->
                  DropdownMenuItem(
                    text = { Text(preset) },
                    onClick = {
                      question1 = preset
                      q1Expanded = false
                    },
                  )
                }
              }
            }

            OutlinedTextField(
              value = answer1,
              onValueChange = {
                answer1 = it
                recoveryError = null
              },
              label = { Text("Answer 1 (case-insensitive)") },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("recovery_a1_input"),
            )

            ExposedDropdownMenuBox(
              expanded = q2Expanded,
              onExpandedChange = { q2Expanded = !q2Expanded },
            ) {
              OutlinedTextField(
                value = question2,
                onValueChange = { question2 = it },
                label = { Text("Security Question 2") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = q2Expanded) },
                modifier = Modifier
                  .fillMaxWidth()
                  .menuAnchor(MenuAnchorType.PrimaryEditable, true)
                  .testTag("recovery_q2_input"),
              )
              ExposedDropdownMenu(
                expanded = q2Expanded,
                onDismissRequest = { q2Expanded = false },
              ) {
                PRESET_QUESTIONS_2.forEach { preset ->
                  DropdownMenuItem(
                    text = { Text(preset) },
                    onClick = {
                      question2 = preset
                      q2Expanded = false
                    },
                  )
                }
              }
            }

            OutlinedTextField(
              value = answer2,
              onValueChange = {
                answer2 = it
                recoveryError = null
              },
              label = { Text("Answer 2 (case-insensitive)") },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("recovery_a2_input"),
            )

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
                if (question1.isBlank() || question2.isBlank()) {
                  recoveryError = "Please enter both security questions."
                } else if (answer1.trim().length < 2 || answer2.trim().length < 2) {
                  recoveryError = "Please provide answers of at least 2 characters each."
                } else {
                  onCompleteSetup(pin, enableBiometrics, question1, answer1, question2, answer2)
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
