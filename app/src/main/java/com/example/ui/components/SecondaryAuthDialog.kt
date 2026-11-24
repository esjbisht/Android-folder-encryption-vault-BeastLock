package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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

/**
 * Secondary authentication dialog triggered when BiometricPrompt is dismissed, unavailable,
 * or required when opening protected folders / performing sensitive vault operations.
 */
@Composable
fun SecondaryAuthChallengeDialog(
  title: String = "Secondary Authentication Required",
  description: String,
  hasPatternLock: Boolean,
  hasPinCredential: Boolean = true,
  expectedPinLength: Int = 6,
  preferPatternDefault: Boolean,
  stealthMode: Boolean = false,
  onDismiss: () -> Unit,
  onVerifyPattern: (nodes: List<Int>, onChecked: (Boolean) -> Unit) -> Unit,
  onVerifyPin: (pin: String, onChecked: (Boolean) -> Unit) -> Unit,
) {
  val hasBoth = hasPatternLock && hasPinCredential
  var usePattern by remember(hasPatternLock, hasPinCredential, preferPatternDefault) {
    mutableStateOf(
      when {
        hasPatternLock && !hasPinCredential -> true
        hasPatternLock && preferPatternDefault -> true
        else -> false
      },
    )
  }
  var pin by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  var isVerifying by remember { mutableStateOf(false) }

  val pinLenLabel = if (expectedPinLength == 4) "4-digit" else "6-digit"

  AlertDialog(
    onDismissRequest = { if (!isVerifying) onDismiss() },
    icon = {
      Icon(
        imageVector = if (usePattern && hasPatternLock) Icons.Rounded.Gesture else Icons.Rounded.VerifiedUser,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    title = { Text(title) },
    text = {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
      ) {
        Text(
          text = description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.fillMaxWidth(),
        )

        if (hasBoth) {
          SingleChoiceSegmentedButtonRow(
            modifier = Modifier
              .fillMaxWidth()
              .testTag("secondary_auth_switcher"),
          ) {
            SegmentedButton(
              selected = usePattern,
              onClick = {
                usePattern = true
                error = null
              },
              enabled = !isVerifying,
              shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
              icon = {
                Icon(Icons.Rounded.Gesture, contentDescription = null, modifier = Modifier.size(18.dp))
              },
              modifier = Modifier.testTag("secondary_auth_tab_pattern"),
            ) {
              Text("3×3 Pattern")
            }
            SegmentedButton(
              selected = !usePattern,
              onClick = {
                usePattern = false
                error = null
              },
              enabled = !isVerifying,
              shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
              icon = {
                Icon(Icons.Rounded.Pin, contentDescription = null, modifier = Modifier.size(18.dp))
              },
              modifier = Modifier.testTag("secondary_auth_tab_pin"),
            ) {
              Text("$pinLenLabel PIN")
            }
          }
        }

        if ((usePattern && hasPatternLock) || (!hasPinCredential && hasPatternLock)) {
          PatternLockGrid(
            isError = error != null,
            enabled = !isVerifying,
            stealthMode = stealthMode,
            helperText = if (isVerifying) {
              "Verifying 3×3 Pattern..."
            } else {
              "Draw your 3×3 Pattern (min 4 dots)"
            },
            onPatternCompleted = { nodes ->
              if (nodes.size < 4) {
                error = "Connect at least 4 dots."
                return@PatternLockGrid
              }
              isVerifying = true
              error = null
              onVerifyPattern(nodes) { ok ->
                isVerifying = false
                if (!ok) {
                  error = if (hasPinCredential) {
                    "Incorrect 3×3 Pattern. Try again or switch to PIN."
                  } else {
                    "Incorrect 3×3 Pattern. Try again."
                  }
                }
              }
            },
            modifier = Modifier.testTag("secondary_auth_pattern_grid"),
          )
        } else {
          OutlinedTextField(
            value = pin,
            onValueChange = {
              pin = it.filter { ch -> ch.isDigit() }.take(12)
              error = null
            },
            label = { Text("$pinLenLabel Master PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            isError = error != null,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("reencrypt_pin_input"),
          )
        }

        if (error != null) {
          Text(
            text = error!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    },
    confirmButton = {
      if ((!usePattern || !hasPatternLock) && hasPinCredential) {
        Button(
          onClick = {
            if (pin.length < 4) {
              error = "Enter your $pinLenLabel Master PIN"
            } else {
              isVerifying = true
              onVerifyPin(pin) { ok ->
                isVerifying = false
                if (!ok) {
                  error = "Incorrect Master PIN"
                }
              }
            }
          },
          enabled = !isVerifying,
          modifier = Modifier.testTag("reencrypt_pin_confirm_button"),
        ) {
          Text(if (isVerifying) "Verifying..." else "Authorize")
        }
      }
    },
    dismissButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (usePattern && hasBoth) {
          TextButton(
            onClick = {
              usePattern = false
              error = null
            },
            enabled = !isVerifying,
            modifier = Modifier.testTag("secondary_auth_use_pin_button"),
          ) {
            Text("Use PIN")
          }
        }
        TextButton(
          onClick = onDismiss,
          enabled = !isVerifying,
          modifier = Modifier.testTag("secondary_auth_cancel_button"),
        ) {
          Spacer(modifier = Modifier.width(2.dp))
          Text("Cancel")
        }
      }
    },
    modifier = Modifier.testTag("reencrypt_pin_dialog"),
  )
}
