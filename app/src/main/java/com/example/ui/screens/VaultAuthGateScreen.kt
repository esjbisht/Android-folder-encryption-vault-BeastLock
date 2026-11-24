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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.example.data.preferences.SecuritySettings
import com.example.ui.components.NumericPinKeypad
import com.example.ui.components.PatternLockGrid
import com.example.ui.components.PinDotsIndicator
import com.example.ui.components.ThemeModeDropdownButton
import com.example.ui.theme.ThemeMode

@Composable
fun VaultAuthGateScreen(
  settings: SecuritySettings,
  biometricAvailability: BiometricAvailability,
  authError: String?,
  recoveryError: String?,
  biometricFallbackTrigger: Int = 0,
  biometricFallbackReason: String? = null,
  onPinSubmitted: (String) -> Unit,
  onPatternSubmitted: (List<Int>) -> Unit,
  onRequestBiometricPrompt: () -> Unit,
  onRecoverCredentials: (
    answer1: String,
    answer2: String,
    newPin: String?,
    newPatternNodes: List<Int>?,
    targetLockType: PrimaryLockType,
  ) -> Unit,
  onSelectThemeMode: (ThemeMode) -> Unit,
  onClearErrors: () -> Unit,
) {
  var enteredPin by remember { mutableStateOf("") }
  var showRecoveryDialog by remember { mutableStateOf(false) }

  val hasBothPinAndPattern = settings.hasPinCredential && settings.hasPatternLock
  var usePatternMode by remember(
    settings.hasPatternLock,
    settings.hasPinCredential,
    settings.primaryLockType,
    settings.preferPatternFallback,
  ) {
    mutableStateOf(
      when {
        !settings.hasPinCredential && settings.hasPatternLock -> true
        settings.hasPatternLock && (settings.primaryLockType == PrimaryLockType.PATTERN || settings.preferPatternFallback) -> true
        else -> false
      },
    )
  }
  var localPatternHint by remember { mutableStateOf<String?>(null) }

  val biometricsSupported =
    biometricAvailability == BiometricAvailability.AVAILABLE_STRONG ||
      biometricAvailability == BiometricAvailability.AVAILABLE_WEAK_OR_DEVICE
  val canUseBiometrics = biometricsSupported &&
    (settings.biometricEnabled || (settings.wasAutoLocked && settings.requireBiometricAfterAutoLock))

  val expectedPinDigits = settings.pinLength.let { if (it == 4) 4 else 6 }

  LaunchedEffect(canUseBiometrics, settings.wasAutoLocked, settings.wasPanicLocked) {
    if (canUseBiometrics && !settings.wasPanicLocked) {
      onRequestBiometricPrompt()
    }
  }

  LaunchedEffect(biometricFallbackTrigger) {
    if (biometricFallbackTrigger > 0) {
      if (settings.hasPatternLock && (settings.primaryLockType == PrimaryLockType.PATTERN || !settings.hasPinCredential || settings.preferPatternFallback)) {
        usePatternMode = true
      }
      if (!biometricFallbackReason.isNullOrBlank()) {
        localPatternHint = biometricFallbackReason
      }
    }
  }

  LaunchedEffect(authError) {
    if (authError != null) {
      enteredPin = ""
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
  ) {
    Image(
      painter = painterResource(id = R.drawable.img_vault_security_hero),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier
        .fillMaxWidth()
        .height(260.dp)
        .alpha(0.22f),
    )

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier
        .widthIn(max = 520.dp)
        .align(Alignment.Center)
        .fillMaxSize()
        .statusBarsPadding()
        .navigationBarsPadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 16.dp),
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(64.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Rounded.Lock,
              contentDescription = "Vault Locked",
              modifier = Modifier.size(30.dp),
            )
          }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
          text = "BeastLock Sealed",
          style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (settings.keyFingerprint.isNotBlank()) {
          Text(
            text = "KEY ${settings.keyFingerprint}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        if ((settings.wasAutoLocked || settings.wasPanicLocked) && settings.lastAutoLockSummary.isNotBlank()) {
          Spacer(modifier = Modifier.height(10.dp))
          Surface(
            color = if (settings.wasPanicLocked) {
              MaterialTheme.colorScheme.errorContainer
            } else {
              MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (settings.wasPanicLocked) {
              MaterialTheme.colorScheme.onErrorContainer
            } else {
              MaterialTheme.colorScheme.onSecondaryContainer
            },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.testTag(
              if (settings.wasPanicLocked) "panic_lock_notice_banner" else "autolock_notice_banner",
            ),
          ) {
            Text(
              text = settings.lastAutoLockSummary,
              style = MaterialTheme.typography.labelMedium,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
          }
        }
        if (canUseBiometrics) {
          Spacer(modifier = Modifier.height(8.dp))
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.testTag("auth_secondary_method_badge"),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.VerifiedUser,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
              )
              Text(
                text = "Biometrics + ${settings.primaryLockType.label} Ready",
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(10.dp))
        val activeError = localPatternHint ?: authError
        val subtitleText = when {
          activeError != null -> activeError
          usePatternMode && settings.hasPatternLock ->
            if (canUseBiometrics) "Draw your 3×3 Pattern or use biometrics" else "Draw your 3×3 Unlock Pattern"
          hasBothPinAndPattern ->
            if (canUseBiometrics) {
              "Enter your $expectedPinDigits-digit PIN, 3×3 Pattern, or biometrics"
            } else {
              "Enter your $expectedPinDigits-digit PIN or switch to Pattern"
            }
          canUseBiometrics -> "Enter your $expectedPinDigits-digit Master PIN or use biometrics"
          else -> "Enter your $expectedPinDigits-digit Master PIN"
        }
        Text(
          text = subtitleText,
          style = MaterialTheme.typography.bodyMedium,
          color = if (activeError != null) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
          textAlign = TextAlign.Center,
        )
      }

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if (hasBothPinAndPattern) {
          SingleChoiceSegmentedButtonRow(
            modifier = Modifier
              .widthIn(max = 280.dp)
              .fillMaxWidth()
              .testTag("auth_method_switcher"),
          ) {
            SegmentedButton(
              selected = !usePatternMode,
              onClick = {
                usePatternMode = false
                localPatternHint = null
                onClearErrors()
              },
              shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
              icon = {
                Icon(Icons.Rounded.Pin, contentDescription = null, modifier = Modifier.size(18.dp))
              },
              modifier = Modifier.testTag("auth_switch_pin"),
            ) {
              Text("${expectedPinDigits}-Digit PIN")
            }
            SegmentedButton(
              selected = usePatternMode,
              onClick = {
                usePatternMode = true
                enteredPin = ""
                localPatternHint = null
                onClearErrors()
              },
              shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
              icon = {
                Icon(Icons.Rounded.Gesture, contentDescription = null, modifier = Modifier.size(18.dp))
              },
              modifier = Modifier.testTag("auth_switch_pattern"),
            ) {
              Text("3×3 Pattern")
            }
          }
        }

        if (usePatternMode && settings.hasPatternLock) {
          PatternLockGrid(
            isError = localPatternHint != null || authError != null,
            stealthMode = settings.patternStealthMode,
            helperText = if (settings.patternStealthMode) {
              "Stealth Pattern active (path hidden • min 4 dots)"
            } else {
              "Draw 3×3 Unlock Pattern (min 4 dots)"
            },
            onPatternCompleted = { nodes ->
              if (nodes.size < 4) {
                localPatternHint = "Connect at least 4 dots to unlock."
              } else {
                localPatternHint = null
                onClearErrors()
                onPatternSubmitted(nodes)
              }
            },
            modifier = Modifier.testTag("auth_pattern_grid"),
          )
        } else {
          PinDotsIndicator(
            totalDigits = expectedPinDigits,
            enteredDigits = enteredPin.length,
            isError = authError != null,
          )

          NumericPinKeypad(
            onDigit = { digit ->
              if (enteredPin.length < expectedPinDigits) {
                localPatternHint = null
                onClearErrors()
                enteredPin += digit
                if (enteredPin.length == expectedPinDigits) {
                  onPinSubmitted(enteredPin)
                }
              }
            },
            onBackspace = {
              if (enteredPin.isNotEmpty()) {
                enteredPin = enteredPin.dropLast(1)
              }
            },
            onBiometricClick = onRequestBiometricPrompt,
            showBiometricButton = canUseBiometrics,
          )
        }
      }

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        if (canUseBiometrics) {
          FilledTonalButton(
            onClick = onRequestBiometricPrompt,
            modifier = Modifier.testTag("auth_biometric_button"),
          ) {
            Icon(Icons.Rounded.Fingerprint, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Unlock with Biometrics")
          }
        }
        if (settings.hasSecurityQuestions) {
          TextButton(
            onClick = {
              onClearErrors()
              showRecoveryDialog = true
            },
            modifier = Modifier.testTag("forgot_pin_recovery_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.HelpOutline,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Forgot Lock? Recover with Security Questions")
          }
        }
      }
    }

    ThemeModeDropdownButton(
      currentMode = settings.themeMode,
      onSelectMode = onSelectThemeMode,
      testTagPrefix = "auth_theme_switcher",
      modifier = Modifier
        .align(Alignment.TopEnd)
        .statusBarsPadding()
        .padding(top = 12.dp, end = 16.dp),
    )
  }

  if (showRecoveryDialog) {
    CredentialRecoveryDialog(
      initialLockType = settings.primaryLockType,
      question1 = settings.securityQuestion1,
      question2 = settings.securityQuestion2,
      errorMessage = recoveryError,
      onDismiss = {
        onClearErrors()
        showRecoveryDialog = false
      },
      onSubmitRecovery = { a1, a2, newPin, newPattern, targetType ->
        onRecoverCredentials(a1, a2, newPin, newPattern, targetType)
      },
    )
  }
}

@Composable
private fun CredentialRecoveryDialog(
  initialLockType: PrimaryLockType,
  question1: String,
  question2: String,
  errorMessage: String?,
  onDismiss: () -> Unit,
  onSubmitRecovery: (
    answer1: String,
    answer2: String,
    newPin: String?,
    newPattern: List<Int>?,
    targetType: PrimaryLockType,
  ) -> Unit,
) {
  var answer1 by remember { mutableStateOf("") }
  var answer2 by remember { mutableStateOf("") }
  var resetMode by remember { mutableStateOf(initialLockType) }
  var newPin by remember { mutableStateOf("") }
  var confirmPin by remember { mutableStateOf("") }
  var patternDraft by remember { mutableStateOf<List<Int>?>(null) }
  var confirmedPattern by remember { mutableStateOf<List<Int>?>(null) }
  var localError by remember { mutableStateOf<String?>(null) }

  val targetPinDigits = if (resetMode == PrimaryLockType.PIN_4) 4 else 6

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text("Recover Vault Access", style = MaterialTheme.typography.titleLarge)
    },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
      ) {
        Text(
          text = "Answer both security questions to unwrap your AES-256 vault key and set a new lock credential.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = question1, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
          value = answer1,
          onValueChange = {
            answer1 = it
            localError = null
          },
          label = { Text("Answer 1") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("recover_dialog_a1"),
        )
        Text(text = question2, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
          value = answer2,
          onValueChange = {
            answer2 = it
            localError = null
          },
          label = { Text("Answer 2") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("recover_dialog_a2"),
        )

        Text("New Lock Type", style = MaterialTheme.typography.labelMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          val types = listOf(
            Triple(PrimaryLockType.PATTERN, "Pattern", Icons.Rounded.Gesture),
            Triple(PrimaryLockType.PIN_4, "4-PIN", Icons.Rounded.Dialpad),
            Triple(PrimaryLockType.PIN_6, "6-PIN", Icons.Rounded.Pin),
          )
          types.forEachIndexed { idx, (type, label, icon) ->
            SegmentedButton(
              selected = resetMode == type,
              onClick = {
                resetMode = type
                newPin = ""
                confirmPin = ""
                patternDraft = null
                confirmedPattern = null
                localError = null
              },
              shape = SegmentedButtonDefaults.itemShape(index = idx, count = types.size),
              icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
            ) {
              Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }

        if (resetMode == PrimaryLockType.PATTERN) {
          PatternLockGrid(
            isError = localError != null,
            helperText = when {
              confirmedPattern != null -> "New pattern confirmed (${confirmedPattern!!.size} dots)"
              patternDraft != null -> "Redraw the same 3×3 pattern to confirm"
              else -> "Draw new 3×3 pattern (min 4 dots)"
            },
            onPatternCompleted = { nodes ->
              if (nodes.size < 4) {
                localError = "Connect at least 4 dots."
                return@PatternLockGrid
              }
              val first = patternDraft
              if (first == null) {
                patternDraft = nodes
                confirmedPattern = null
                localError = null
              } else if (first == nodes) {
                confirmedPattern = nodes
                localError = null
              } else {
                localError = "Patterns didn't match. Draw again."
                patternDraft = null
                confirmedPattern = null
              }
            },
            modifier = Modifier.testTag("recover_dialog_pattern_grid"),
          )
        } else {
          OutlinedTextField(
            value = newPin,
            onValueChange = {
              if (it.length <= targetPinDigits && it.all(Char::isDigit)) {
                newPin = it
                localError = null
              }
            },
            label = { Text("New $targetPinDigits-digit PIN") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("recover_dialog_new_pin"),
          )
          OutlinedTextField(
            value = confirmPin,
            onValueChange = {
              if (it.length <= targetPinDigits && it.all(Char::isDigit)) {
                confirmPin = it
                localError = null
              }
            },
            label = { Text("Confirm new $targetPinDigits-digit PIN") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("recover_dialog_confirm_pin"),
          )
        }

        val err = localError ?: errorMessage
        if (err != null) {
          Text(
            text = err,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          when {
            answer1.isBlank() || answer2.isBlank() -> localError = "Answer both questions."
            resetMode == PrimaryLockType.PATTERN -> {
              val pat = confirmedPattern
              if (pat == null) {
                localError = "Draw and confirm your new 3×3 pattern first."
              } else {
                onSubmitRecovery(answer1, answer2, null, pat, PrimaryLockType.PATTERN)
              }
            }
            newPin.length != targetPinDigits -> localError = "New PIN must be $targetPinDigits digits."
            newPin != confirmPin -> localError = "New PINs do not match."
            else -> onSubmitRecovery(answer1, answer2, newPin, null, resetMode)
          }
        },
        modifier = Modifier.testTag("recover_dialog_submit"),
      ) {
        Text("Reset & Unlock")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}
