package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.VaultFolderEntity
import com.example.data.storage.IntegrityDiagnosticReport
import com.example.ui.VaultUiState
import com.example.ui.components.VaultStatusBadge
import com.example.ui.components.formatByteSize
import kotlin.math.min

private enum class HighlightSegment {
  ALL,
  ENCRYPTED,
  UNENCRYPTED,
}

@Composable
fun StorageAnalyticsScreen(
  state: VaultUiState,
  onOpenFolder: (String) -> Unit,
  onLockFolder: (String) -> Unit,
  onNavigateToVault: () -> Unit,
  onScanOrphans: () -> Unit,
  onOpenOrphanCleanup: () -> Unit,
  onOpenMigrateOut: () -> Unit,
  onOpenIntegrityDiagnostic: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var selectedSegment by remember { mutableStateOf(HighlightSegment.ALL) }

  LaunchedEffect(Unit) {
    onScanOrphans()
  }

  val encryptedBytes = state.totalEncryptedBytes
  val unencryptedBytes = state.totalUnencryptedBytes
  val totalVaultBytes = (encryptedBytes + unencryptedBytes).coerceAtLeast(0L)
  val encryptedRatio = if (totalVaultBytes > 0L) {
    encryptedBytes.toFloat() / totalVaultBytes.toFloat()
  } else 0f
  val unencryptedRatio = if (totalVaultBytes > 0L) {
    unencryptedBytes.toFloat() / totalVaultBytes.toFloat()
  } else 0f

  val sortedFolders = remember(state.folders) {
    state.folders.sortedByDescending { it.totalBytes }
  }
  val maxFolderBytes = remember(sortedFolders) {
    sortedFolders.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1L) ?: 1L
  }

  Box(
    contentAlignment = Alignment.TopCenter,
    modifier = modifier.fillMaxSize(),
  ) {
    LazyColumn(
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
      modifier = Modifier
        .widthIn(max = 760.dp)
        .fillMaxSize(),
    ) {
      item(key = "analytics_header") {
        Column {
          Text("Vault Storage Analytics", style = MaterialTheme.typography.headlineMedium)
          Text(
            text = "Encrypted vs. unencrypted space across ${state.folders.size} protected folder(s)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      item(key = "hygiene_and_exit_card") {
        StorageHygieneAndExitCard(
          orphanCount = state.orphanCandidates.size,
          orphanBytes = state.totalOrphanBytes,
          isScanning = state.isScanningOrphans,
          isScanningIntegrity = state.isScanningIntegrity,
          integrityReport = state.integrityDiagnosticReport,
          hasFolders = state.folders.isNotEmpty(),
          onScanOrphans = onScanOrphans,
          onOpenOrphanCleanup = onOpenOrphanCleanup,
          onOpenMigrateOut = onOpenMigrateOut,
          onOpenIntegrityDiagnostic = onOpenIntegrityDiagnostic,
        )
      }

      if (state.folders.isEmpty()) {
        item(key = "analytics_empty") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            ) {
              Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(18.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.PieChart,
                  contentDescription = null,
                  modifier = Modifier
                    .padding(16.dp)
                    .size(34.dp),
                )
              }
              Text("No Storage Data Yet", style = MaterialTheme.typography.titleLarge)
              Text(
                text = "Add a device folder or private vault folder and import files to visualize encrypted vs. unencrypted storage consumption.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
              Button(
                onClick = onNavigateToVault,
                modifier = Modifier.testTag("analytics_go_to_vault_button"),
              ) {
                Text("Go to Vault")
              }
            }
          }
        }
      } else {
        // 1. Animated Donut Chart: Encrypted vs Unencrypted Vault Space
        item(key = "donut_chart_card") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("storage_donut_chart_card"),
          ) {
            Column(
              verticalArrangement = Arrangement.spacedBy(16.dp),
              modifier = Modifier.padding(18.dp),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = "Encrypted vs. Unencrypted Space",
                  style = MaterialTheme.typography.titleMedium,
                )
                Surface(
                  color = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                  shape = RoundedCornerShape(50),
                ) {
                  Text(
                    text = "${(encryptedRatio * 100).toInt()}% SEALED",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                  )
                }
              }

              VaultStorageDonutChart(
                encryptedRatio = encryptedRatio,
                unencryptedRatio = unencryptedRatio,
                encryptedBytes = encryptedBytes,
                unencryptedBytes = unencryptedBytes,
                totalVaultBytes = totalVaultBytes,
                selectedSegment = selectedSegment,
                onSelectSegment = { segment ->
                  selectedSegment = if (selectedSegment == segment) HighlightSegment.ALL else segment
                },
              )

              // Interactive Segment Cards
              Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                SegmentLegendCard(
                  label = "Encrypted (.vlock)",
                  bytesText = formatByteSize(encryptedBytes),
                  detailText = "${state.totalEncryptedFiles} files • ${(encryptedRatio * 100).toInt()}%",
                  dotColor = MaterialTheme.colorScheme.primary,
                  selected = selectedSegment == HighlightSegment.ENCRYPTED,
                  onClick = {
                    selectedSegment = if (selectedSegment == HighlightSegment.ENCRYPTED) {
                      HighlightSegment.ALL
                    } else {
                      HighlightSegment.ENCRYPTED
                    }
                  },
                  testTag = "legend_encrypted_card",
                  modifier = Modifier.weight(1f),
                )
                SegmentLegendCard(
                  label = "Unencrypted",
                  bytesText = formatByteSize(unencryptedBytes),
                  detailText = "${state.totalUnencryptedFiles} files • ${(unencryptedRatio * 100).toInt()}%",
                  dotColor = MaterialTheme.colorScheme.secondary,
                  selected = selectedSegment == HighlightSegment.UNENCRYPTED,
                  onClick = {
                    selectedSegment = if (selectedSegment == HighlightSegment.UNENCRYPTED) {
                      HighlightSegment.ALL
                    } else {
                      HighlightSegment.UNENCRYPTED
                    }
                  },
                  testTag = "legend_unencrypted_card",
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }

        // 2. Device Storage Context & Stacked Footprint Chart
        if (state.deviceStorageInfo.totalDeviceBytes > 0L) {
          item(key = "device_footprint_card") {
            DeviceFootprintCard(
              encryptedBytes = encryptedBytes,
              unencryptedBytes = unencryptedBytes,
              totalDeviceBytes = state.deviceStorageInfo.totalDeviceBytes,
              availableDeviceBytes = state.deviceStorageInfo.availableDeviceBytes,
            )
          }
        }

        // 3. Folder-by-Folder Storage Usage Horizontal Bar Chart
        item(key = "folder_bars_header") {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = "STORAGE BY FOLDER (${sortedFolders.size})",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = "Tap folder to inspect",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        items(sortedFolders, key = { it.id }) { folder ->
          FolderStorageBarCard(
            folder = folder,
            maxFolderBytes = maxFolderBytes,
            onOpen = { onOpenFolder(folder.id) },
            onQuickLock = { onLockFolder(folder.id) },
          )
        }

        // 4. Folder Posture & Storage Source Summary
        item(key = "posture_breakdown_card") {
          PostureBreakdownCard(state = state)
        }
      }
    }
  }
}

@Composable
private fun VaultStorageDonutChart(
  encryptedRatio: Float,
  unencryptedRatio: Float,
  encryptedBytes: Long,
  unencryptedBytes: Long,
  totalVaultBytes: Long,
  selectedSegment: HighlightSegment,
  onSelectSegment: (HighlightSegment) -> Unit,
) {
  val animatedEncSweep by animateFloatAsState(
    targetValue = if (totalVaultBytes == 0L) 0f else (encryptedRatio * 360f),
    animationSpec = spring(stiffness = 220f),
    label = "enc_sweep",
  )
  val animatedPlainSweep by animateFloatAsState(
    targetValue = if (totalVaultBytes == 0L) 0f else (unencryptedRatio * 360f),
    animationSpec = spring(stiffness = 220f),
    label = "plain_sweep",
  )

  val primaryColor = MaterialTheme.colorScheme.primary
  val secondaryColor = MaterialTheme.colorScheme.secondary
  val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh

  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .fillMaxWidth()
      .height(210.dp)
      .clickable { onSelectSegment(HighlightSegment.ALL) },
  ) {
    Canvas(
      modifier = Modifier
        .size(190.dp)
        .padding(8.dp),
    ) {
      val diameter = min(size.width, size.height)
      val strokeWidth = diameter * 0.16f
      val arcSize = Size(diameter - strokeWidth, diameter - strokeWidth)
      val topLeft = Offset(
        (size.width - arcSize.width) / 2f,
        (size.height - arcSize.height) / 2f,
      )

      // Base ring track
      drawArc(
        color = trackColor,
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidth),
      )

      if (totalVaultBytes > 0L) {
        val gap = if (animatedEncSweep > 4f && animatedPlainSweep > 4f) 4f else 0f
        if (animatedEncSweep > 0.5f) {
          val encStroke = if (selectedSegment == HighlightSegment.ENCRYPTED) strokeWidth * 1.18f else strokeWidth
          val encAlpha = if (selectedSegment == HighlightSegment.UNENCRYPTED) 0.35f else 1f
          drawArc(
            color = primaryColor.copy(alpha = encAlpha),
            startAngle = -90f,
            sweepAngle = (animatedEncSweep - gap).coerceAtLeast(1f),
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = encStroke, cap = StrokeCap.Butt),
          )
        }
        if (animatedPlainSweep > 0.5f) {
          val plainStroke = if (selectedSegment == HighlightSegment.UNENCRYPTED) strokeWidth * 1.18f else strokeWidth
          val plainAlpha = if (selectedSegment == HighlightSegment.ENCRYPTED) 0.35f else 1f
          drawArc(
            color = secondaryColor.copy(alpha = plainAlpha),
            startAngle = -90f + animatedEncSweep,
            sweepAngle = (animatedPlainSweep - gap).coerceAtLeast(1f),
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = plainStroke, cap = StrokeCap.Butt),
          )
        }
      }
    }

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.width(120.dp),
    ) {
      val headline = when (selectedSegment) {
        HighlightSegment.ENCRYPTED -> formatByteSize(encryptedBytes)
        HighlightSegment.UNENCRYPTED -> formatByteSize(unencryptedBytes)
        HighlightSegment.ALL -> formatByteSize(totalVaultBytes)
      }
      val sub = when (selectedSegment) {
        HighlightSegment.ENCRYPTED -> "Encrypted"
        HighlightSegment.UNENCRYPTED -> "Unencrypted"
        HighlightSegment.ALL -> "Total Vault"
      }
      Text(
        text = headline,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = sub,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SegmentLegendCard(
  label: String,
  bytesText: String,
  detailText: String,
  dotColor: Color,
  selected: Boolean,
  onClick: () -> Unit,
  testTag: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = if (selected) {
      MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
      MaterialTheme.colorScheme.surface
    },
    shape = MaterialTheme.shapes.small,
    modifier = modifier
      .clip(MaterialTheme.shapes.small)
      .border(
        width = if (selected) 1.5.dp else 1.dp,
        color = if (selected) dotColor else MaterialTheme.colorScheme.outlineVariant,
        shape = MaterialTheme.shapes.small,
      )
      .clickable(onClick = onClick)
      .testTag(testTag),
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(4.dp),
      modifier = Modifier.padding(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(dotColor),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = bytesText,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = detailText,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun DeviceFootprintCard(
  encryptedBytes: Long,
  unencryptedBytes: Long,
  totalDeviceBytes: Long,
  availableDeviceBytes: Long,
) {
  val usedOtherBytes = (totalDeviceBytes - availableDeviceBytes - encryptedBytes - unencryptedBytes)
    .coerceAtLeast(0L)
  val encFrac = (encryptedBytes.toFloat() / totalDeviceBytes.toFloat()).coerceIn(0f, 1f)
  val plainFrac = (unencryptedBytes.toFloat() / totalDeviceBytes.toFloat()).coerceIn(0f, 1f)
  val otherFrac = (usedOtherBytes.toFloat() / totalDeviceBytes.toFloat()).coerceIn(0f, 1f)

  val primaryColor = MaterialTheme.colorScheme.primary
  val secondaryColor = MaterialTheme.colorScheme.secondary
  val otherColor = MaterialTheme.colorScheme.outline
  val freeColor = MaterialTheme.colorScheme.surfaceContainerHigh

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.padding(16.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Rounded.SdStorage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Spacer(modifier = Modifier.width(8.dp))
          Text("Device Storage Footprint", style = MaterialTheme.typography.titleSmall)
        }
        Text(
          text = "${formatByteSize(availableDeviceBytes)} free",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Canvas(
        modifier = Modifier
          .fillMaxWidth()
          .height(14.dp),
      ) {
        val barWidth = size.width
        val barHeight = size.height
        val radius = CornerRadius(barHeight / 2f, barHeight / 2f)

        drawRoundRect(color = freeColor, cornerRadius = radius, size = size)

        var cursorX = 0f
        val encW = (barWidth * encFrac).let { if (encryptedBytes > 0L) it.coerceAtLeast(barHeight * 0.6f) else 0f }
        if (encW > 0f) {
          drawRoundRect(
            color = primaryColor,
            topLeft = Offset(cursorX, 0f),
            size = Size(encW.coerceAtMost(barWidth), barHeight),
            cornerRadius = radius,
          )
          cursorX += encW
        }

        val plainW = (barWidth * plainFrac).let { if (unencryptedBytes > 0L) it.coerceAtLeast(barHeight * 0.6f) else 0f }
        if (plainW > 0f && cursorX < barWidth) {
          drawRoundRect(
            color = secondaryColor,
            topLeft = Offset(cursorX, 0f),
            size = Size(plainW.coerceAtMost(barWidth - cursorX), barHeight),
            cornerRadius = radius,
          )
          cursorX += plainW
        }

        val otherW = (barWidth * otherFrac).coerceAtMost((barWidth - cursorX).coerceAtLeast(0f))
        if (otherW > 0f) {
          drawRect(
            color = otherColor.copy(alpha = 0.45f),
            topLeft = Offset(cursorX, 0f),
            size = Size(otherW, barHeight),
          )
        }
      }

      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = "Vault: ${formatByteSize(encryptedBytes + unencryptedBytes)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "Total Device: ${formatByteSize(totalDeviceBytes)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun FolderStorageBarCard(
  folder: VaultFolderEntity,
  maxFolderBytes: Long,
  onOpen: () -> Unit,
  onQuickLock: () -> Unit,
) {
  val encBytes = when {
    folder.encryptedBytes > 0L || folder.unencryptedBytes > 0L -> folder.encryptedBytes
    folder.isLocked -> folder.totalBytes
    else -> 0L
  }
  val plainBytes = when {
    folder.encryptedBytes > 0L || folder.unencryptedBytes > 0L -> folder.unencryptedBytes
    !folder.isLocked -> folder.totalBytes
    else -> 0L
  }
  val relativeToMax = (folder.totalBytes.toFloat() / maxFolderBytes.toFloat()).coerceIn(0f, 1f)
  val encShareOfBar = if (folder.totalBytes > 0L) {
    (encBytes.toFloat() / folder.totalBytes.toFloat()).coerceIn(0f, 1f)
  } else 0f

  val animatedTotalBar by animateFloatAsState(
    targetValue = relativeToMax,
    animationSpec = spring(stiffness = 260f),
    label = "folder_bar_${folder.id}",
  )

  val primaryColor = MaterialTheme.colorScheme.primary
  val secondaryColor = MaterialTheme.colorScheme.secondary
  val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    modifier = Modifier
      .fillMaxWidth()
      .clip(MaterialTheme.shapes.medium)
      .clickable(onClick = onOpen)
      .testTag("analytics_folder_bar_${folder.id}"),
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(14.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(
          imageVector = if (folder.isLocked) Icons.Rounded.Lock else Icons.Rounded.Folder,
          contentDescription = null,
          tint = if (folder.isLocked) primaryColor else secondaryColor,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = folder.name,
          style = MaterialTheme.typography.titleSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        VaultStatusBadge(isLocked = folder.isLocked)
        if (!folder.isLocked && folder.fileCount > 0) {
          Spacer(modifier = Modifier.width(6.dp))
          FilledTonalIconButton(
            onClick = onQuickLock,
            modifier = Modifier
              .size(48.dp)
              .testTag("analytics_quick_lock_${folder.id}"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Lock,
              contentDescription = "Lock folder",
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }

      Canvas(
        modifier = Modifier
          .fillMaxWidth()
          .height(12.dp),
      ) {
        val barHeight = size.height
        val radius = CornerRadius(barHeight / 2f, barHeight / 2f)
        drawRoundRect(color = trackColor, size = size, cornerRadius = radius)

        val filledWidth = (size.width * animatedTotalBar).let {
          if (folder.totalBytes > 0L) it.coerceAtLeast(barHeight) else 0f
        }
        if (filledWidth > 0f) {
          // Unencrypted base segment (amber)
          drawRoundRect(
            color = secondaryColor,
            size = Size(filledWidth, barHeight),
            cornerRadius = radius,
          )
          // Encrypted segment overlaid from left (emerald)
          val encWidth = filledWidth * encShareOfBar
          if (encWidth > 0f) {
            drawRoundRect(
              color = primaryColor,
              size = Size(encWidth, barHeight),
              cornerRadius = radius,
            )
          }
        }
      }

      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = "Enc: ${formatByteSize(encBytes)} • Plain: ${formatByteSize(plainBytes)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = "${formatByteSize(folder.totalBytes)} (${folder.fileCount} files)",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

@Composable
private fun PostureBreakdownCard(state: VaultUiState) {
  val totalFiles = (state.totalEncryptedFiles + state.totalUnencryptedFiles).coerceAtLeast(1)
  val encFileFrac = state.totalEncryptedFiles.toFloat() / totalFiles.toFloat()
  val internalCount = state.folders.count { it.isInternalVault }
  val safCount = state.folders.count { !it.isInternalVault }

  val primaryColor = MaterialTheme.colorScheme.primary
  val secondaryColor = MaterialTheme.colorScheme.secondary
  val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.padding(16.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Shield, contentDescription = null, tint = primaryColor)
        Spacer(modifier = Modifier.width(8.dp))
        Text("File & Folder Composition", style = MaterialTheme.typography.titleSmall)
      }

      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = "Encrypted Files (${state.totalEncryptedFiles})",
            style = MaterialTheme.typography.labelSmall,
            color = primaryColor,
          )
          Text(
            text = "Plaintext Files (${state.totalUnencryptedFiles})",
            style = MaterialTheme.typography.labelSmall,
            color = secondaryColor,
          )
        }
        Canvas(
          modifier = Modifier
            .fillMaxWidth()
            .height(10.dp),
        ) {
          val r = CornerRadius(size.height / 2f, size.height / 2f)
          drawRoundRect(color = secondaryColor, size = size, cornerRadius = r)
          if (state.totalEncryptedFiles + state.totalUnencryptedFiles == 0) {
            drawRoundRect(color = trackColor, size = size, cornerRadius = r)
          } else if (encFileFrac > 0f) {
            drawRoundRect(
              color = primaryColor,
              size = Size(size.width * encFileFrac, size.height),
              cornerRadius = r,
            )
          }
        }
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.weight(1f),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp),
          ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = primaryColor, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "Private Vault: $internalCount",
              style = MaterialTheme.typography.labelSmall,
            )
          }
        }
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.weight(1f),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp),
          ) {
            Icon(Icons.Rounded.LockOpen, contentDescription = null, tint = secondaryColor, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "Device SAF: $safCount",
              style = MaterialTheme.typography.labelSmall,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun StorageHygieneAndExitCard(
  orphanCount: Int,
  orphanBytes: Long,
  isScanning: Boolean,
  isScanningIntegrity: Boolean,
  integrityReport: IntegrityDiagnosticReport?,
  hasFolders: Boolean,
  onScanOrphans: () -> Unit,
  onOpenOrphanCleanup: () -> Unit,
  onOpenMigrateOut: () -> Unit,
  onOpenIntegrityDiagnostic: () -> Unit,
) {
  val hasOrphans = orphanCount > 0
  val corruptedCount = integrityReport?.corruptedFiles?.size ?: 0
  Card(
    colors = CardDefaults.cardColors(
      containerColor = when {
        corruptedCount > 0 -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        hasOrphans -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surfaceContainer
      },
    ),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("analytics_hygiene_card"),
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.padding(16.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(
          imageVector = if (hasOrphans) Icons.Rounded.CleaningServices else Icons.Rounded.CheckCircle,
          contentDescription = null,
          tint = if (hasOrphans) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = if (isScanning) {
              "Scanning Temporary Files..."
            } else if (hasOrphans) {
              "$orphanCount Orphaned Temp Item(s) Detected"
            } else {
              "Storage Hygiene Clean"
            },
            style = MaterialTheme.typography.titleSmall,
          )
          Text(
            text = if (hasOrphans) {
              "${formatByteSize(orphanBytes)} reclaimable from interrupted re-encryption or cloud sync"
            } else {
              "0 leftover re-encryption or interrupted cloud sync artifacts found"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (hasOrphans) {
          Button(
            onClick = onOpenOrphanCleanup,
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.secondary,
              contentColor = MaterialTheme.colorScheme.onSecondary,
            ),
            modifier = Modifier
              .weight(1f)
              .testTag("analytics_clear_orphans_button"),
          ) {
            Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Review & Clean")
          }
        } else {
          OutlinedButton(
            onClick = onScanOrphans,
            enabled = !isScanning,
            modifier = Modifier
              .weight(1f)
              .testTag("analytics_scan_orphans_button"),
          ) {
            Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (isScanning) "Scanning..." else "Scan Temp Files")
          }
        }

        if (hasFolders) {
          FilledTonalButton(
            onClick = onOpenMigrateOut,
            modifier = Modifier
              .weight(1f)
              .testTag("analytics_migrate_out_button"),
          ) {
            Icon(Icons.Rounded.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Exit & Export")
          }
        }
      }

      if (hasFolders) {
        OutlinedButton(
          onClick = onOpenIntegrityDiagnostic,
          enabled = !isScanningIntegrity,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("analytics_run_integrity_scan_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.VerifiedUser,
            contentDescription = null,
            tint = if (corruptedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = when {
              isScanningIntegrity -> "Verifying Encrypted Files..."
              corruptedCount > 0 -> "Integrity Alert: $corruptedCount Corrupted File(s) — View Report"
              integrityReport != null -> "Integrity Verified (${integrityReport.healthyFiles}/${integrityReport.totalEncryptedFiles}) — Re-Scan"
              else -> "Run Encrypted File Integrity Diagnostic"
            },
          )
        }
      }
    }
  }
}
