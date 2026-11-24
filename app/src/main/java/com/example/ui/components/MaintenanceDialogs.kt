package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.VaultFolderEntity
import com.example.data.storage.IntegrityDiagnosticReport
import com.example.data.storage.IntegrityScanProgress
import com.example.data.storage.MigrationExportFormat
import com.example.data.storage.MigrationProgress
import com.example.data.storage.MigrationResult
import com.example.data.storage.OrphanCategory
import com.example.data.storage.OrphanedFileCandidate
import com.example.data.storage.VaultFileItem
import com.example.ui.SelectiveLockPromptState
import com.example.ui.theme.BeastLockTheme
import com.example.ui.theme.VaultProgressTone

@Composable
fun OrphanCleanupDialog(
  candidates: List<OrphanedFileCandidate>,
  selectedIds: Set<String>,
  isScanning: Boolean,
  isCleaning: Boolean,
  onRescan: () -> Unit,
  onToggleSelect: (String) -> Unit,
  onSelectAll: (Boolean) -> Unit,
  onConfirmDelete: () -> Unit,
  onDismiss: () -> Unit,
) {
  val selectedBytes = remember(candidates, selectedIds) {
    candidates.filter { it.id in selectedIds }.sumOf { it.sizeBytes }
  }
  val allSelected = candidates.isNotEmpty() && candidates.all { it.id in selectedIds }

  AlertDialog(
    onDismissRequest = { if (!isCleaning) onDismiss() },
    icon = {
      Icon(
        imageVector = Icons.Rounded.CleaningServices,
        contentDescription = null,
        tint = if (candidates.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
      )
    },
    title = { Text("Clear Orphaned Files") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = "Detects temporary scratch files left behind by interrupted re-encryption, lock/unlock, or cloud sync operations.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
          isScanning || isCleaning -> {
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            ) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                VaultCircularProgressIndicator()
                Text(
                  text = if (isCleaning) "Removing orphaned temporary files..." else "Scanning vault & cloud staging...",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          candidates.isEmpty() -> {
            Surface(
              color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              shape = MaterialTheme.shapes.medium,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(16.dp),
              ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(28.dp))
                Text(
                  text = "Vault Hygiene Clean",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.SemiBold,
                )
                Text(
                  text = "0 orphaned temporary files or stale sync artifacts found.",
                  style = MaterialTheme.typography.bodySmall,
                )
              }
            }
          }

          else -> {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = "${selectedIds.size}/${candidates.size} selected • ${formatByteSize(selectedBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
              )
              TextButton(
                onClick = { onSelectAll(!allSelected) },
                modifier = Modifier.testTag("orphan_select_all_button"),
              ) {
                Text(if (allSelected) "Deselect All" else "Select All")
              }
            }

            LazyColumn(
              verticalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp),
            ) {
              items(candidates, key = { it.id }) { item ->
                val checked = item.id in selectedIds
                val badgeBg = when (item.category) {
                  OrphanCategory.INTERRUPTED_CLOUD_SYNC -> MaterialTheme.colorScheme.tertiaryContainer
                  OrphanCategory.INCOMPLETE_REENCRYPTION -> MaterialTheme.colorScheme.secondaryContainer
                  OrphanCategory.ORPHANED_VAULT_DIR -> MaterialTheme.colorScheme.errorContainer
                }
                val badgeFg = when (item.category) {
                  OrphanCategory.INTERRUPTED_CLOUD_SYNC -> MaterialTheme.colorScheme.onTertiaryContainer
                  OrphanCategory.INCOMPLETE_REENCRYPTION -> MaterialTheme.colorScheme.onSecondaryContainer
                  OrphanCategory.ORPHANED_VAULT_DIR -> MaterialTheme.colorScheme.onErrorContainer
                }
                Surface(
                  color = MaterialTheme.colorScheme.surfaceContainerHigh,
                  shape = MaterialTheme.shapes.small,
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onToggleSelect(item.id) }
                    .testTag("orphan_item_${item.id}"),
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                  ) {
                    Checkbox(
                      checked = checked,
                      onCheckedChange = { onToggleSelect(item.id) },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(
                      verticalArrangement = Arrangement.spacedBy(3.dp),
                      modifier = Modifier.weight(1f),
                    ) {
                      Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                      Text(
                        text = "${item.locationLabel} • ${formatByteSize(item.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                      Surface(
                        color = badgeBg,
                        contentColor = badgeFg,
                        shape = RoundedCornerShape(6.dp),
                      ) {
                        Text(
                          text = item.category.label,
                          style = MaterialTheme.typography.labelSmall,
                          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      if (candidates.isEmpty()) {
        OutlinedButton(
          onClick = onRescan,
          enabled = !isScanning,
          modifier = Modifier.testTag("orphan_rescan_button"),
        ) {
          Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Re-Scan")
        }
      } else {
        Button(
          onClick = onConfirmDelete,
          enabled = selectedIds.isNotEmpty() && !isCleaning && !isScanning,
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
          ),
          modifier = Modifier.testTag("orphan_confirm_delete_button"),
        ) {
          Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Clear (${selectedIds.size})")
        }
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        enabled = !isCleaning,
      ) {
        Text("Close")
      }
    },
    modifier = Modifier.testTag("orphan_cleanup_dialog"),
  )
}

@Composable
fun MigrationWizardDialog(
  folders: List<VaultFolderEntity>,
  onDismiss: () -> Unit,
  onConfirmExport: (
    selectedFolderIds: List<String>,
    format: MigrationExportFormat,
    removeOriginalsAfterSuccess: Boolean,
  ) -> Unit,
) {
  var selectedIds by remember(folders) {
    mutableStateOf(folders.map { it.id }.toSet())
  }
  var selectedFormat by remember { mutableStateOf(MigrationExportFormat.FOLDER_TREE) }
  var removeOriginals by remember { mutableStateOf(false) }

  val totalSelectedBytes = remember(folders, selectedIds) {
    folders.filter { it.id in selectedIds }.sumOf { it.totalBytes }
  }
  val totalSelectedFiles = remember(folders, selectedIds) {
    folders.filter { it.id in selectedIds }.sumOf { it.fileCount }
  }
  val allSelected = folders.isNotEmpty() && folders.all { it.id in selectedIds }

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = Icons.Rounded.DriveFileMove,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    title = { Text("Decrypt & Migrate Out") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 460.dp)
          .verticalScroll(rememberScrollState()),
      ) {
        Text(
          text = "Your BeastLock exit strategy: decrypt and export your files cleanly to standard device storage or a portable ZIP archive.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
          text = "Export Format",
          style = MaterialTheme.typography.labelLarge,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          val formats = listOf(
            Triple(MigrationExportFormat.FOLDER_TREE, "Folder Tree", Icons.Rounded.DriveFileMove),
            Triple(MigrationExportFormat.PLAIN_ZIP, "Plain ZIP", Icons.Rounded.FolderZip),
          )
          formats.forEachIndexed { index, (format, label, icon) ->
            SegmentedButton(
              selected = selectedFormat == format,
              onClick = { selectedFormat = format },
              shape = SegmentedButtonDefaults.itemShape(index = index, count = formats.size),
              icon = {
                SegmentedButtonDefaults.Icon(active = selectedFormat == format) {
                  Icon(icon, contentDescription = null)
                }
              },
              modifier = Modifier.testTag("migration_format_${format.name}"),
            ) {
              Text(label)
            }
          }
        }
        Text(
          text = if (selectedFormat == MigrationExportFormat.FOLDER_TREE) {
            "Creates plain subfolders with original filenames inside a device directory you pick."
          } else {
            "Bundles all decrypted folders into one unencrypted .zip archive readable on any OS."
          },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = "Folders (${selectedIds.size}/${folders.size})",
            style = MaterialTheme.typography.labelLarge,
          )
          TextButton(
            onClick = {
              selectedIds = if (allSelected) emptySet() else folders.map { it.id }.toSet()
            },
            modifier = Modifier.testTag("migration_select_all_button"),
          ) {
            Text(if (allSelected) "Clear All" else "Select All")
          }
        }

        if (folders.isEmpty()) {
          Text(
            text = "No folders in BeastLock to export.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            folders.forEach { folder ->
              val checked = folder.id in selectedIds
              Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(MaterialTheme.shapes.small)
                  .clickable {
                    selectedIds = if (checked) selectedIds - folder.id else selectedIds + folder.id
                  }
                  .testTag("migration_folder_${folder.id}"),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                  Checkbox(
                    checked = checked,
                    onCheckedChange = {
                      selectedIds = if (checked) selectedIds - folder.id else selectedIds + folder.id
                    },
                  )
                  Icon(
                    imageVector = if (folder.isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    contentDescription = null,
                    tint = if (folder.isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = folder.name,
                      style = MaterialTheme.typography.bodyMedium,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                      text = "${folder.fileCount} files • ${formatByteSize(folder.totalBytes)}",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
          }
        }

        HorizontalDivider()

        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Remove migrated folders from BeastLock",
              style = MaterialTheme.typography.titleSmall,
            )
            Text(
              text = "Keep originals by default. Turn on to wipe exported folders after 0-error verification.",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          Switch(
            checked = removeOriginals,
            onCheckedChange = { removeOriginals = it },
            modifier = Modifier.testTag("migration_remove_originals_switch"),
          )
        }

        if (removeOriginals) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(10.dp),
            ) {
              Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Internal vault copies will be permanently removed and linked folders unlinked once every file in that folder exports cleanly.",
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
        }

        Text(
          text = "Estimated export: $totalSelectedFiles file(s) • ${formatByteSize(totalSelectedBytes)}",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onConfirmExport(
            folders.filter { it.id in selectedIds }.map { it.id },
            selectedFormat,
            removeOriginals,
          )
        },
        enabled = selectedIds.isNotEmpty(),
        modifier = Modifier.testTag("migration_start_button"),
      ) {
        Text("Verify & Export")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
    modifier = Modifier.testTag("migration_wizard_dialog"),
  )
}

@Composable
fun MigrationProgressDialog(
  progress: MigrationProgress,
  onCancel: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = {},
    icon = {
      Icon(
        imageVector = Icons.Rounded.DriveFileMove,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    title = { Text("Migrating Out of BeastLock") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = "${progress.format.label} • ${progress.currentFolderName}",
          style = MaterialTheme.typography.titleSmall,
        )
        Text(
          text = "Decrypting: ${progress.currentFileName}",
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
          tone = VaultProgressTone.DECRYPT,
          height = 8.dp,
        )
        Text(
          text = "${progress.processedFiles} / ${progress.totalFiles} files • ${formatByteSize(progress.exportedBytes)} written",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = onCancel,
        modifier = Modifier.testTag("migration_cancel_button"),
      ) {
        Text("Cancel")
      }
    },
  )
}

@Composable
fun MigrationResultDialog(
  result: MigrationResult,
  onDismiss: () -> Unit,
) {
  val hasErrors = result.failedFiles > 0
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = if (hasErrors) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
        contentDescription = null,
        tint = if (hasErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
      )
    },
    title = {
      Text(if (hasErrors) "Migration Finished With Warnings" else "Migration Complete")
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "Destination: ${result.destinationLabel}",
          style = MaterialTheme.typography.titleSmall,
        )
        Text(
          text = "• Format: ${result.format.label}",
          style = MaterialTheme.typography.bodySmall,
        )
        Text(
          text = "• Exported: ${result.exportedFiles} file(s) from ${result.exportedFolders} folder(s) (${formatByteSize(result.exportedBytes)})",
          style = MaterialTheme.typography.bodySmall,
        )
        Text(
          text = if (result.removedFoldersCount > 0) {
            "• Vault Cleanup: Removed ${result.removedFoldersCount} migrated folder(s) from BeastLock"
          } else {
            "• Vault Cleanup: Originals retained inside BeastLock"
          },
          style = MaterialTheme.typography.bodySmall,
        )
        if (hasErrors) {
          Text(
            text = "• ${result.failedFiles} file(s) failed (${result.firstError.orEmpty()})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = onDismiss,
        modifier = Modifier.testTag("migration_result_done_button"),
      ) {
        Text("Done")
      }
    },
    modifier = Modifier.testTag("migration_result_dialog"),
  )
}

@Composable
fun IntegrityDiagnosticDialog(
  isScanning: Boolean,
  progress: IntegrityScanProgress?,
  report: IntegrityDiagnosticReport?,
  onRescan: () -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  var copiedNames by remember(report) { mutableStateOf(false) }

  val hasCorruption = (report?.corruptedFiles?.isNotEmpty() == true)
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = when {
          isScanning -> Icons.Rounded.VerifiedUser
          hasCorruption -> Icons.Rounded.Warning
          else -> Icons.Rounded.CheckCircle
        },
        contentDescription = null,
        tint = when {
          isScanning -> MaterialTheme.colorScheme.primary
          hasCorruption -> MaterialTheme.colorScheme.error
          else -> MaterialTheme.colorScheme.primary
        },
      )
    },
    title = {
      Text(
        when {
          isScanning -> "Scanning Vault Integrity"
          hasCorruption -> "Corrupted Files Detected"
          else -> "Vault Integrity Diagnostic"
        },
      )
    },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = "Verifies VLK1 headers, AES-256-GCM chunk authentication tags, and EOF sentinels across every encrypted file without writing plaintext.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
          isScanning -> {
            Column(
              verticalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            ) {
              val total = progress?.totalFiles ?: 0
              val scanned = progress?.scannedFiles ?: 0
              if (total > 0) {
                VaultLinearProgressBar(
                  progress = (scanned.toFloat() / total.toFloat()).coerceIn(0f, 1f),
                  tone = VaultProgressTone.NEUTRAL,
                  height = 8.dp,
                )
                Text(
                  text = "$scanned / $total encrypted file(s) checked • ${progress?.corruptedSoFar ?: 0} corrupted",
                  style = MaterialTheme.typography.labelSmall,
                  color = BeastLockTheme.colors.infoAccent,
                )
              } else {
                VaultLinearProgressBar(
                  progress = null,
                  tone = VaultProgressTone.NEUTRAL,
                  height = 8.dp,
                )
                Text(
                  text = "Indexing encrypted vault folders...",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (progress != null && progress.currentFileName.isNotBlank()) {
                Text(
                  text = "${progress.currentFolderName} • ${progress.currentFileName}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }

          report == null -> {
            Surface(
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
              shape = MaterialTheme.shapes.medium,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = "Tap Scan Now to check every .vlock file in your vault for header corruption, bit rot, or truncation.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(14.dp),
              )
            }
          }

          else -> {
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              DiagnosticStatPill(
                label = "Checked",
                value = "${report.totalEncryptedFiles}",
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
              )
              DiagnosticStatPill(
                label = "Healthy",
                value = "${report.healthyFiles}",
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
              )
              DiagnosticStatPill(
                label = "Corrupted",
                value = "${report.corruptedFiles.size}",
                containerColor = if (hasCorruption) {
                  MaterialTheme.colorScheme.errorContainer
                } else {
                  MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (hasCorruption) {
                  MaterialTheme.colorScheme.onErrorContainer
                } else {
                  MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
              )
            }

            if (report.skippedFolders.isNotEmpty()) {
              Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = "Skipped ${report.skippedFolders.size} folder(s) with revoked SAF access: ${report.skippedFolders.joinToString(", ")}",
                  style = MaterialTheme.typography.labelSmall,
                  modifier = Modifier.padding(10.dp),
                )
              }
            }

            if (report.corruptedFiles.isEmpty()) {
              Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                  modifier = Modifier.padding(14.dp),
                ) {
                  Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(26.dp))
                  Text(
                    text = if (report.totalEncryptedFiles == 0) {
                      "No Encrypted Files to Scan"
                    } else {
                      "All Encrypted Files Verified"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                  )
                  Text(
                    text = if (report.totalEncryptedFiles == 0) {
                      "Lock a folder or import files to verify cryptographic integrity."
                    } else {
                      "Scanned ${report.totalEncryptedFiles} file(s) across ${report.scannedFolders} folder(s) with 0 integrity failures."
                    },
                    style = MaterialTheme.typography.bodySmall,
                  )
                }
              }
            } else {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = "Affected Files (${report.corruptedFiles.size})",
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                  onClick = {
                    val text = report.corruptedFiles.joinToString("\n") { item ->
                      val sub = if (item.relativeSubPath.isBlank()) "" else "/${item.relativeSubPath}"
                      "${item.affectedName} (${item.folderName}$sub • ${item.category.label})"
                    }
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("Corrupted Vault Files", text))
                    copiedNames = true
                  },
                  modifier = Modifier.testTag("integrity_copy_names_button"),
                ) {
                  Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(if (copiedNames) "Copied" else "Copy Names")
                }
              }

              LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(max = 260.dp)
                  .testTag("integrity_corrupted_list"),
              ) {
                itemsIndexed(
                  items = report.corruptedFiles,
                  key = { _, item -> item.id },
                ) { index, item ->
                  Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                      .fillMaxWidth()
                      .testTag("integrity_corrupted_item_$index"),
                  ) {
                    Column(
                      verticalArrangement = Arrangement.spacedBy(4.dp),
                      modifier = Modifier.padding(10.dp),
                    ) {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                      ) {
                        Text(
                          text = item.affectedName,
                          style = MaterialTheme.typography.bodyMedium,
                          fontWeight = FontWeight.SemiBold,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                          modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                          text = formatByteSize(item.sizeBytes),
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                      val pathInfo = if (item.relativeSubPath.isBlank()) {
                        item.folderName
                      } else {
                        "${item.folderName}/${item.relativeSubPath}"
                      }
                      Text(
                        text = if (item.affectedName != item.diskFileName) {
                          "$pathInfo • ${item.diskFileName}"
                        } else {
                          pathInfo
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                      Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(6.dp),
                      ) {
                        Text(
                          text = item.category.label,
                          style = MaterialTheme.typography.labelSmall,
                          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                      }
                      Text(
                        text = item.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = onRescan,
        enabled = !isScanning,
        modifier = Modifier.testTag("integrity_rescan_button"),
      ) {
        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(if (report == null) "Scan Now" else "Re-Scan")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("integrity_close_button"),
      ) {
        Text(if (isScanning) "Cancel" else "Close")
      }
    },
    modifier = Modifier.testTag("integrity_diagnostic_dialog"),
  )
}

@Composable
private fun DiagnosticStatPill(
  label: String,
  value: String,
  containerColor: androidx.compose.ui.graphics.Color,
  contentColor: androidx.compose.ui.graphics.Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = containerColor,
    contentColor = contentColor,
    shape = MaterialTheme.shapes.small,
    modifier = modifier,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(2.dp),
      modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
      Text(
        text = value,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }
}

@Composable
fun SelectiveLockFilesDialog(
  prompt: SelectiveLockPromptState,
  onConfirm: (selectedFileIds: Set<String>) -> Unit,
  onDismiss: () -> Unit,
) {
  var selectedIds by remember(prompt.folder.id, prompt.plaintextFiles) {
    mutableStateOf(prompt.plaintextFiles.map { it.id }.toSet())
  }
  val allSelected = prompt.plaintextFiles.isNotEmpty() &&
    prompt.plaintextFiles.all { it.id in selectedIds }
  val selectedBytes = remember(prompt.plaintextFiles, selectedIds) {
    prompt.plaintextFiles.filter { it.id in selectedIds }.sumOf { it.sizeBytes }
  }
  val excludedCount = (prompt.plaintextFiles.size - selectedIds.size).coerceAtLeast(0)

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = Icons.Rounded.Lock,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    title = {
      Text(
        text = if (prompt.folder.isLocked) {
          "Encrypt Files in \"${prompt.folder.name}\""
        } else {
          "Lock \"${prompt.folder.name}\""
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = "Choose which files to encrypt with AES-256-GCM. Unchecked files stay unencrypted inside the folder.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (prompt.isLoading) {
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 24.dp),
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              VaultCircularProgressIndicator()
              Text(
                text = "Reading folder files...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          if (prompt.alreadyEncryptedCount > 0) {
            Surface(
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
              shape = MaterialTheme.shapes.small,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = "${prompt.alreadyEncryptedCount} file(s) already encrypted in this folder",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              )
            }
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "${selectedIds.size}/${prompt.plaintextFiles.size} to encrypt • ${formatByteSize(selectedBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
              )
              if (excludedCount > 0) {
                Text(
                  text = "$excludedCount file(s) will stay unencrypted",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.secondary,
                )
              }
            }
            TextButton(
              onClick = {
                selectedIds = if (allSelected) {
                  emptySet()
                } else {
                  prompt.plaintextFiles.map { it.id }.toSet()
                }
              },
              modifier = Modifier.testTag("selective_lock_select_all_button"),
            ) {
              Text(if (allSelected) "Deselect All" else "Select All")
            }
          }

          LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 260.dp)
              .testTag("selective_lock_list"),
          ) {
            items(prompt.plaintextFiles, key = { it.id }) { file ->
              val checked = file.id in selectedIds
              val toggle = {
                selectedIds = if (checked) selectedIds - file.id else selectedIds + file.id
              }
              Surface(
                color = if (checked) {
                  MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                } else {
                  MaterialTheme.colorScheme.surfaceContainerHigh
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(MaterialTheme.shapes.small)
                  .clickable(onClick = toggle)
                  .testTag("selective_lock_item_${file.displayName}"),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                  Checkbox(
                    checked = checked,
                    onCheckedChange = { toggle() },
                  )
                  Icon(
                    imageVector = Icons.Rounded.Description,
                    contentDescription = null,
                    tint = if (checked) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = file.displayName,
                      style = MaterialTheme.typography.bodyMedium,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                      text = buildString {
                        append(formatByteSize(file.sizeBytes))
                        if (file.relativeSubPath.isNotEmpty()) {
                          append(" • /${file.relativeSubPath}")
                        }
                        append(if (checked) " • Encrypt" else " • Keep plain")
                      },
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(selectedIds) },
        enabled = !prompt.isLoading && selectedIds.isNotEmpty(),
        modifier = Modifier.testTag("selective_lock_confirm_button"),
      ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Encrypt (${selectedIds.size})")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("selective_lock_cancel_button"),
      ) {
        Text("Cancel")
      }
    },
    modifier = Modifier.testTag("selective_lock_dialog"),
  )
}

@Composable
fun ConfirmRemoveFoldersDialog(
  folders: List<VaultFolderEntity>,
  onDismiss: () -> Unit,
  onConfirm: (decryptLockedSafFirst: Boolean) -> Unit,
) {
  val hasLockedSaf = remember(folders) {
    folders.any { !it.isInternalVault && (it.isLocked || it.encryptedFileCount > 0) }
  }
  val hasPrivate = remember(folders) { folders.any { it.isInternalVault } }
  val hasSaf = remember(folders) { folders.any { !it.isInternalVault } }
  var decryptSafFirst by remember(folders) { mutableStateOf(true) }

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = Icons.Rounded.DeleteOutline,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
      )
    },
    title = {
      Text(
        text = if (folders.size == 1) {
          "Remove \"${folders.first().name}\"?"
        } else {
          "Remove ${folders.size} Folders from Vault?"
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = buildString {
            if (hasSaf) {
              append("Device SAF folders will be unlinked from BeastLock's list. ")
            }
            if (hasPrivate) {
              append("Private Vault folders and their sandboxed files will be permanently deleted.")
            }
          }.trim(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (folders.size > 1) {
          Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
          ) {
            LazyColumn(
              verticalArrangement = Arrangement.spacedBy(4.dp),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp)
                .padding(10.dp),
            ) {
              items(folders, key = { it.id }) { folder ->
                val kind = if (folder.isInternalVault) "Private Vault" else "Device SAF"
                val lockLabel = if (folder.isLocked) "${folder.encryptedFileCount} encrypted" else "Unlocked"
                Text(
                  text = "• ${folder.name} ($kind • $lockLabel)",
                  style = MaterialTheme.typography.labelMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }

        if (hasLockedSaf) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .fillMaxWidth()
              .clip(MaterialTheme.shapes.small)
              .clickable { decryptSafFirst = !decryptSafFirst }
              .padding(vertical = 4.dp),
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Decrypt locked device folder(s) first",
                style = MaterialTheme.typography.titleSmall,
              )
              Text(
                text = if (decryptSafFirst) {
                  "Restores .vlock files to original files in-place before unlinking"
                } else {
                  "Unlinks from list only; .vlock files remain encrypted on device"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = decryptSafFirst,
              onCheckedChange = { decryptSafFirst = it },
              modifier = Modifier.testTag("remove_folders_decrypt_switch"),
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(decryptSafFirst) },
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.error,
          contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = Modifier.testTag("confirm_remove_folders_button"),
      ) {
        Text(if (folders.size == 1) "Remove" else "Remove (${folders.size})")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("cancel_remove_folders_button"),
      ) {
        Text("Cancel")
      }
    },
    modifier = Modifier.testTag("confirm_remove_folders_dialog"),
  )
}

@Composable
fun ConfirmRemoveFilesDialog(
  files: List<VaultFileItem>,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = Icons.Rounded.DeleteOutline,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
      )
    },
    title = {
      Text(
        text = if (files.size == 1) {
          "Remove \"${files.first().displayName}\"?"
        } else {
          "Remove ${files.size} Files?"
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = "Removes the selected file(s) from this folder and device storage without leaving copies.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth(),
        ) {
          LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 160.dp)
              .padding(10.dp),
          ) {
            items(files, key = { it.id }) { file ->
              Text(
                text = "• ${file.displayName} (${formatByteSize(file.sizeBytes)})",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = onConfirm,
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.error,
          contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = Modifier.testTag("confirm_remove_files_button"),
      ) {
        Text(if (files.size == 1) "Remove" else "Remove (${files.size})")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("cancel_remove_files_button"),
      ) {
        Text("Cancel")
      }
    },
    modifier = Modifier.testTag("confirm_remove_files_dialog"),
  )
}

