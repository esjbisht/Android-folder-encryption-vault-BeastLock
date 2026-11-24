package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.backup.CloudBackupProgress
import com.example.data.storage.EncryptionProgress
import com.example.ui.theme.BeastLockTheme
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.VaultProgressTone
import com.example.ui.theme.VaultThemeEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VaultStatusBadge(
  isLocked: Boolean,
  isPartiallyEncrypted: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val colors = BeastLockTheme.colors
  val (containerColor, contentColor, accentColor, icon, label) = when {
    isPartiallyEncrypted -> Quintuple(
      colors.partialContainer,
      colors.onPartialContainer,
      colors.partialAccent,
      Icons.Rounded.EnhancedEncryption,
      "PARTIAL",
    )
    isLocked -> Quintuple(
      colors.lockedContainer,
      colors.onLockedContainer,
      colors.lockedAccent,
      Icons.Rounded.Lock,
      "LOCKED",
    )
    else -> Quintuple(
      colors.unlockedContainer,
      colors.onUnlockedContainer,
      colors.unlockedAccent,
      Icons.Rounded.LockOpen,
      "UNLOCKED",
    )
  }
  Surface(
    color = containerColor,
    contentColor = contentColor,
    shape = RoundedCornerShape(50),
    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
    modifier = modifier,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(13.dp),
      )
      Spacer(modifier = Modifier.width(5.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
    }
  }
}

private data class Quintuple<A, B, C, D, E>(
  val first: A,
  val second: B,
  val third: C,
  val fourth: D,
  val fifth: E,
)

@Composable
fun VaultLinearProgressBar(
  progress: Float?,
  tone: VaultProgressTone = VaultProgressTone.NEUTRAL,
  height: Dp = 5.dp,
  modifier: Modifier = Modifier,
) {
  val palette = VaultThemeEngine.progressPaletteFor(BeastLockTheme.colors, tone)
  val barModifier = modifier
    .fillMaxWidth()
    .height(height)
    .clip(RoundedCornerShape(50))

  if (progress == null) {
    LinearProgressIndicator(
      color = palette.indicatorColor,
      trackColor = palette.trackColor,
      strokeCap = StrokeCap.Round,
      modifier = barModifier,
    )
  } else {
    val clamped = progress.coerceIn(0f, 1f)
    LinearProgressIndicator(
      progress = { clamped },
      color = palette.indicatorColor,
      trackColor = palette.trackColor,
      strokeCap = StrokeCap.Round,
      drawStopIndicator = {},
      modifier = barModifier,
    )
  }
}

@Composable
fun VaultCircularProgressIndicator(
  modifier: Modifier = Modifier,
  tone: VaultProgressTone = VaultProgressTone.NEUTRAL,
  strokeWidth: Dp = 3.dp,
) {
  val palette = VaultThemeEngine.progressPaletteFor(BeastLockTheme.colors, tone)
  CircularProgressIndicator(
    color = palette.indicatorColor,
    trackColor = palette.trackColor,
    strokeWidth = strokeWidth,
    strokeCap = StrokeCap.Round,
    modifier = modifier,
  )
}

@Composable
fun PinDotsIndicator(
  totalDigits: Int = 6,
  enteredDigits: Int,
  isError: Boolean = false,
  modifier: Modifier = Modifier,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier,
  ) {
    repeat(totalDigits) { index ->
      val filled = index < enteredDigits
      val dotSize by animateDpAsState(
        targetValue = if (filled) 16.dp else 13.dp,
        animationSpec = spring(),
        label = "pin_dot_size",
      )
      val dotColor by animateColorAsState(
        targetValue = when {
          isError -> MaterialTheme.colorScheme.error
          filled -> MaterialTheme.colorScheme.primary
          else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        },
        label = "pin_dot_color",
      )
      Box(
        modifier = Modifier
          .size(dotSize)
          .clip(CircleShape)
          .background(if (filled) dotColor else MaterialTheme.colorScheme.surface)
          .border(2.dp, dotColor, CircleShape),
      )
    }
  }
}

@Composable
fun NumericPinKeypad(
  onDigit: (String) -> Unit,
  onBackspace: () -> Unit,
  onBiometricClick: (() -> Unit)? = null,
  showBiometricButton: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val rows = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
  )
  Column(
    verticalArrangement = Arrangement.spacedBy(12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier,
  ) {
    rows.forEach { row ->
      Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        row.forEach { digit ->
          KeypadDigitButton(
            label = digit,
            onClick = { onDigit(digit) },
            testTag = "pin_key_$digit",
          )
        }
      }
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(20.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (showBiometricButton && onBiometricClick != null) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickable(onClick = onBiometricClick)
            .minimumInteractiveComponentSize()
            .testTag("pin_key_biometric"),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Rounded.Fingerprint,
              contentDescription = "Authenticate with biometrics",
              modifier = Modifier.size(30.dp),
            )
          }
        }
      } else {
        Spacer(modifier = Modifier.size(72.dp))
      }

      KeypadDigitButton(
        label = "0",
        onClick = { onDigit("0") },
        testTag = "pin_key_0",
      )

      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
          .size(72.dp)
          .clip(CircleShape)
          .clickable(onClick = onBackspace)
          .minimumInteractiveComponentSize()
          .testTag("pin_key_backspace"),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.AutoMirrored.Rounded.Backspace,
            contentDescription = "Delete digit",
            modifier = Modifier.size(24.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun KeypadDigitButton(
  label: String,
  onClick: () -> Unit,
  testTag: String,
) {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 2.dp,
    modifier = Modifier
      .size(72.dp)
      .clip(CircleShape)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
      .clickable(onClick = onClick)
      .minimumInteractiveComponentSize()
      .testTag(testTag),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        text = label,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
fun EncryptionProgressDialog(progress: EncryptionProgress) {
  val tone = if (progress.isLocking) VaultProgressTone.ENCRYPT else VaultProgressTone.DECRYPT
  val accentColor = if (progress.isLocking) {
    BeastLockTheme.colors.lockedAccent
  } else {
    BeastLockTheme.colors.partialAccent
  }
  AlertDialog(
    onDismissRequest = {},
    icon = {
      Icon(
        imageVector = if (progress.isLocking) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
        contentDescription = null,
        tint = accentColor,
      )
    },
    title = {
      Text(
        text = if (progress.isLocking) "Encrypting Folder" else "Decrypting Folder",
        style = MaterialTheme.typography.titleLarge,
      )
    },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = progress.folderName,
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          text = "AES-256-GCM • ${progress.currentFileName}",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val fraction = if (progress.totalFiles > 0) {
          (progress.processedFiles.toFloat() / progress.totalFiles.toFloat()).coerceIn(0f, 1f)
        } else 0f
        VaultLinearProgressBar(
          progress = fraction,
          tone = tone,
          height = 8.dp,
        )
        Text(
          text = "${progress.processedFiles} / ${progress.totalFiles} files • ${formatByteSize(progress.processedBytes)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {},
  )
}

@Composable
fun EncryptionProgressStrip(
  progress: EncryptionProgress,
  deferredAutoLockPending: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val totalFiles = progress.totalFiles.coerceAtLeast(1)
  val activeIndex = (progress.processedFiles + 1).coerceIn(1, totalFiles)
  val overallFraction = progress.overallFraction.coerceIn(0f, 1f)
  val percent = (overallFraction * 100f).toInt().coerceIn(0, 100)
  val isIndeterminate = progress.totalFiles <= 0 && progress.totalBytes <= 0L
  val currentBytes = (progress.processedBytes + progress.currentFileProcessedBytes)
    .let { processed ->
      if (progress.totalBytes > 0L) processed.coerceAtMost(progress.totalBytes) else processed
    }
    .coerceAtLeast(0L)

  val title = if (progress.isStealthMasked) {
    if (progress.isLocking) "Encrypting Private Vault" else "Decrypting Private Vault"
  } else {
    val actionWord = if (progress.isLocking) "Encrypting" else "Decrypting"
    "$actionWord • ${progress.folderName}"
  }

  val subtitle = if (progress.isStealthMasked) {
    if (isIndeterminate) {
      "AES-256-GCM • Preparing Private Vault…"
    } else {
      "Protected file $activeIndex/$totalFiles • ${formatByteSize(currentBytes)}"
    }
  } else {
    if (isIndeterminate) {
      "AES-256-GCM • ${progress.currentFileName}"
    } else {
      "${progress.currentFileName} • ${progress.processedFiles}/$totalFiles • ${formatByteSize(currentBytes)}"
    }
  }

  val colors = BeastLockTheme.colors
  val tone = if (progress.isLocking) VaultProgressTone.ENCRYPT else VaultProgressTone.DECRYPT
  val accentColor = if (progress.isLocking) colors.progressEncrypt else colors.progressDecrypt

  Surface(
    color = colors.progressSurface,
    contentColor = colors.onProgressSurface,
    tonalElevation = 4.dp,
    shadowElevation = 2.dp,
    modifier = modifier
      .fillMaxWidth()
      .testTag("encryption_progress_strip"),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 10.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
          ) {
            Icon(
              imageVector = if (progress.isLocking) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
              contentDescription = null,
              tint = accentColor,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = title,
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = if (isIndeterminate) "Working" else "$percent%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = accentColor,
          )
        }

        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelSmall,
          color = colors.onProgressSurface.copy(alpha = 0.78f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )

        VaultLinearProgressBar(
          progress = if (isIndeterminate) null else overallFraction,
          tone = tone,
          height = 6.dp,
          modifier = Modifier.testTag("encryption_progress_strip_bar"),
        )

        if (deferredAutoLockPending) {
          Text(
            text = "Auto-lock queued — vault will seal automatically once file encryption finishes",
            style = MaterialTheme.typography.labelSmall,
            color = colors.warningAccent,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("encryption_progress_deferred_autolock"),
          )
        }
      }
      HorizontalDivider(color = colors.progressSurfaceBorder)
    }
  }
}

@Composable
fun CloudBackupProgressDialog(progress: CloudBackupProgress) {
  AlertDialog(
    onDismissRequest = {},
    icon = {
      Icon(
        imageVector = Icons.Rounded.CloudUpload,
        contentDescription = null,
        tint = BeastLockTheme.colors.infoAccent,
      )
    },
    title = {
      Text(text = progress.phase, style = MaterialTheme.typography.titleLarge)
    },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = progress.currentFile,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val fraction = if (progress.totalFiles > 0) {
          (progress.processedFiles.toFloat() / progress.totalFiles.toFloat()).coerceIn(0f, 1f)
        } else 0f
        VaultLinearProgressBar(
          progress = fraction,
          tone = VaultProgressTone.NEUTRAL,
          height = 8.dp,
        )
        Text(
          text = "${progress.processedFiles} / ${progress.totalFiles} files processed",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {},
  )
}

fun formatByteSize(bytes: Long): String = when {
  bytes < 1024L -> "$bytes B"
  bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
  bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
  else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

fun formatTimestamp(millis: Long): String {
  val formatter = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
  return formatter.format(Date(millis))
}

@Composable
fun ThemeModeDropdownButton(
  currentMode: ThemeMode,
  onSelectMode: (ThemeMode) -> Unit,
  modifier: Modifier = Modifier,
  testTagPrefix: String = "theme_switcher",
) {
  var expanded by remember { mutableStateOf(false) }

  val currentIcon = when (currentMode) {
    ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
    ThemeMode.LIGHT -> Icons.Rounded.LightMode
    ThemeMode.DARK -> Icons.Rounded.DarkMode
  }

  Box(modifier = modifier) {
    FilledTonalIconButton(
      onClick = { expanded = true },
      colors = IconButtonDefaults.filledTonalIconButtonColors(),
      modifier = Modifier.testTag("${testTagPrefix}_button"),
    ) {
      Icon(
        imageVector = currentIcon,
        contentDescription = "Switch theme (${currentMode.label})",
      )
    }

    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      val options = listOf(
        Triple(ThemeMode.SYSTEM, "System Default", Icons.Rounded.BrightnessAuto),
        Triple(ThemeMode.LIGHT, "Light Mode", Icons.Rounded.LightMode),
        Triple(ThemeMode.DARK, "Dark Mode", Icons.Rounded.DarkMode),
      )
      options.forEach { (mode, title, icon) ->
        val selected = currentMode == mode
        DropdownMenuItem(
          text = {
            Text(
              text = title,
              fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
          },
          leadingIcon = {
            Icon(
              imageVector = icon,
              contentDescription = null,
              tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          trailingIcon = if (selected) {
            {
              Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          } else null,
          onClick = {
            expanded = false
            onSelectMode(mode)
          },
          modifier = Modifier.testTag("${testTagPrefix}_option_${mode.name}"),
        )
      }
    }
  }
}
