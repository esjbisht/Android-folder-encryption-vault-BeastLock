package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToDrive
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.BackupRecordEntity
import com.example.data.local.VaultFolderEntity
import com.example.data.preferences.SecuritySettings
import com.example.security.DevicePowerState
import com.example.ui.components.formatByteSize
import com.example.ui.components.formatTimestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CloudBackupScreen(
  folders: List<VaultFolderEntity>,
  backupRecords: List<BackupRecordEntity>,
  securitySettings: SecuritySettings,
  devicePowerState: DevicePowerState,
  isBatterySaverBlockingTasks: Boolean,
  pendingRestoreUriRequiringPin: Uri?,
  onExportBackup: (destinationUri: Uri, providerLabel: String, selectedFolderIds: Set<String>?) -> Unit,
  onRestoreBackup: (backupUri: Uri, backupPin: String?) -> Unit,
  onConfigureAutoBackupTree: (treeUri: Uri, providerLabel: String) -> Unit,
  onClearAutoBackupTree: () -> Unit,
  onSyncAutoBackupNow: () -> Unit,
  onDismissPendingRestorePin: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var selectedProviderLabel by remember { mutableStateOf(securitySettings.autoBackupProviderLabel.ifBlank { "Google Drive" }) }
  var selectedFolderIds by remember(folders) {
    mutableStateOf(folders.filter { it.autoCloudBackupSync }.map { it.id }.toSet())
  }

  val autoSyncTreeLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree(),
  ) { uri ->
    if (uri != null) {
      onConfigureAutoBackupTree(uri, selectedProviderLabel)
    }
  }

  val createBackupLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
  ) { uri ->
    if (uri != null) {
      val subset = if (selectedFolderIds.size == folders.size) null else selectedFolderIds
      onExportBackup(uri, selectedProviderLabel, subset)
    }
  }

  val restoreBackupLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) {
      onRestoreBackup(uri, null)
    }
  }

  val defaultArchiveName = remember(folders.size) {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
    "BeastLock_Backup_$stamp.vlkbak"
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
      // Zero-Knowledge Cloud Architecture Card
      item(key = "cloud_header") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(16.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.VerifiedUser,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
              Text(
                text = "Zero-Knowledge Cloud Backup",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Backups bundle only AES-256-GCM encrypted payloads (.vlkbak). Google Drive, Dropbox, or any cloud provider never sees plaintext files or filenames.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
              )
            }
          }
        }
      }

      // Cloud Provider Selection Cards (Google Drive / Dropbox / Any Cloud SAF Provider)
      item(key = "provider_cards") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "CHOOSE CLOUD DESTINATION",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            CloudProviderCard(
              title = "Google Drive",
              subtitle = "Upload encrypted .vlkbak via Drive",
              icon = Icons.Rounded.AddToDrive,
              selected = selectedProviderLabel == "Google Drive",
              onClick = { selectedProviderLabel = "Google Drive" },
              testTag = "provider_google_drive",
              modifier = Modifier.weight(1f),
            )
            CloudProviderCard(
              title = "Dropbox / Cloud",
              subtitle = "Save to Dropbox or cloud storage",
              icon = Icons.Rounded.CloudSync,
              selected = selectedProviderLabel == "Dropbox",
              onClick = { selectedProviderLabel = "Dropbox" },
              testTag = "provider_dropbox",
              modifier = Modifier.weight(1f),
            )
          }
        }
      }

      // Folder Selection Checklist
      if (folders.isNotEmpty()) {
        item(key = "folder_selection") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = "Folders to Include (${selectedFolderIds.size}/${folders.size})",
                  style = MaterialTheme.typography.titleSmall,
                )
                TextButton(
                  onClick = {
                    selectedFolderIds = if (selectedFolderIds.size == folders.size) {
                      emptySet()
                    } else {
                      folders.map { it.id }.toSet()
                    }
                  },
                ) {
                  Text(if (selectedFolderIds.size == folders.size) "Clear" else "Select All")
                }
              }

              folders.forEach { folder ->
                val checked = folder.id in selectedFolderIds
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                      selectedFolderIds = if (checked) {
                        selectedFolderIds - folder.id
                      } else {
                        selectedFolderIds + folder.id
                      }
                    }
                    .padding(vertical = 4.dp),
                ) {
                  Checkbox(
                    checked = checked,
                    onCheckedChange = { isChecked ->
                      selectedFolderIds = if (isChecked) {
                        selectedFolderIds + folder.id
                      } else {
                        selectedFolderIds - folder.id
                      }
                    },
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Column(modifier = Modifier.weight(1f)) {
                    Text(folder.name, style = MaterialTheme.typography.bodyMedium)
                    val syncNote = if (folder.autoCloudBackupSync) "Auto-Sync On" else "Auto-Sync Off"
                    Text(
                      text = "${folder.fileCount} files • ${formatByteSize(folder.totalBytes)} • $syncNote",
                      style = MaterialTheme.typography.labelSmall,
                      color = if (folder.autoCloudBackupSync) {
                        MaterialTheme.colorScheme.primary
                      } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                      },
                    )
                  }
                }
              }
            }
          }
        }
      }

      // Automatic Cloud Backup Synchronization Card
      item(key = "auto_sync_card") {
        val autoSyncCount = remember(folders) { folders.count { it.autoCloudBackupSync } }
        val isLinked = securitySettings.autoBackupTreeUri.isNotBlank()
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(14.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Rounded.CloudSync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
              Spacer(modifier = Modifier.width(10.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Automatic Cloud Sync ($autoSyncCount/${folders.size} folders)",
                  style = MaterialTheme.typography.titleSmall,
                )
                Text(
                  text = if (isLinked) {
                    val lastSync = if (securitySettings.lastAutoBackupAtMillis > 0L) {
                      " • Last sync ${formatTimestamp(securitySettings.lastAutoBackupAtMillis)}"
                    } else ""
                    "Syncing to ${securitySettings.autoBackupProviderLabel}$lastSync"
                  } else {
                    "Pick a Drive/Dropbox folder to automatically sync enabled folders when encrypted"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            if (isBatterySaverBlockingTasks || securitySettings.pendingBatteryPausedAutoSync) {
              Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("cloud_battery_saver_banner"),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.padding(10.dp),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.BatteryAlert,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = if (isBatterySaverBlockingTasks) {
                      val reason = if (devicePowerState.isSystemPowerSaveMode) {
                        "System Power Save (${devicePowerState.batteryPercent}%)"
                      } else {
                        "Battery ${devicePowerState.batteryPercent}%"
                      }
                      "Auto Cloud Sync paused for Battery Saver ($reason). Will resume automatically once charged above 20%."
                    } else {
                      "Queued auto cloud sync is resuming..."
                    },
                    style = MaterialTheme.typography.bodySmall,
                  )
                }
              }
            }

            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              FilledTonalButton(
                onClick = { autoSyncTreeLauncher.launch(null) },
                modifier = Modifier
                  .weight(1f)
                  .testTag("configure_auto_sync_button"),
              ) {
                Text(if (isLinked) "Change Sync Folder" else "Link Sync Folder")
              }
              if (isLinked) {
                Button(
                  onClick = onSyncAutoBackupNow,
                  enabled = autoSyncCount > 0,
                  modifier = Modifier
                    .weight(1f)
                    .testTag("sync_auto_backup_now_button"),
                ) {
                  Text("Sync Now")
                }
                TextButton(
                  onClick = onClearAutoBackupTree,
                  modifier = Modifier.testTag("unlink_auto_sync_button"),
                ) {
                  Text("Unlink")
                }
              }
            }
          }
        }
      }

      // Backup & Restore Action Buttons
      item(key = "backup_actions") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Button(
            onClick = { createBackupLauncher.launch(defaultArchiveName) },
            enabled = folders.isNotEmpty() && selectedFolderIds.isNotEmpty(),
            modifier = Modifier
              .fillMaxWidth()
              .height(52.dp)
              .testTag("export_cloud_backup_button"),
          ) {
            Icon(Icons.Rounded.CloudUpload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Back Up Encrypted Vault to $selectedProviderLabel")
          }

          FilledTonalButton(
            onClick = { restoreBackupLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier
              .fillMaxWidth()
              .height(50.dp)
              .testTag("restore_cloud_backup_button"),
          ) {
            Icon(Icons.Rounded.CloudDownload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Restore .vlkbak from Drive / Dropbox")
          }
        }
      }

      // Backup History
      item(key = "history_header") {
        Text(
          text = "CLOUD BACKUP HISTORY (${backupRecords.size})",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }

      if (backupRecords.isEmpty()) {
        item(key = "no_backups") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = "No cloud backups recorded yet. Pick Google Drive or Dropbox above to save an encrypted .vlkbak archive.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(20.dp),
            )
          }
        }
      } else {
        items(backupRecords, key = { it.id }) { record ->
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            ) {
              Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(42.dp),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(Icons.Rounded.CloudDone, contentDescription = null)
                }
              }
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "${record.providerName} • ${record.fileName}",
                  style = MaterialTheme.typography.titleSmall,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = "${record.folderCount} folders • ${record.fileCount} files • ${formatByteSize(record.totalBytes)}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text = "SHA-256 ${record.sha256Checksum} • ${formatTimestamp(record.createdAt)}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }

  if (pendingRestoreUriRequiringPin != null) {
    RestoreArchivePinDialog(
      onDismiss = onDismissPendingRestorePin,
      onConfirmPin = { pin ->
        onRestoreBackup(pendingRestoreUriRequiringPin, pin)
      },
    )
  }
}

@Composable
private fun CloudProviderCard(
  title: String,
  subtitle: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  selected: Boolean,
  onClick: () -> Unit,
  testTag: String,
  modifier: Modifier = Modifier,
) {
  Card(
    colors = CardDefaults.cardColors(
      containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceContainer
      },
    ),
    modifier = modifier
      .clickable(onClick = onClick)
      .testTag(testTag),
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(6.dp),
      modifier = Modifier.padding(14.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (selected) {
          MaterialTheme.colorScheme.onPrimaryContainer
        } else {
          MaterialTheme.colorScheme.primary
        },
      )
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) {
          MaterialTheme.colorScheme.onPrimaryContainer
        } else {
          MaterialTheme.colorScheme.onSurface
        },
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) {
          MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      )
    }
  }
}

@Composable
private fun RestoreArchivePinDialog(
  onDismiss: () -> Unit,
  onConfirmPin: (String) -> Unit,
) {
  var pin by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Enter Archive Master PIN") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "This .vlkbak archive was encrypted with a different vault key. Enter the Master PIN used when creating the backup to unwrap and re-encrypt it into your current vault.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = pin,
          onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
          label = { Text("Backup 6-digit PIN") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("restore_archive_pin_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirmPin(pin) },
        enabled = pin.length == 6,
        modifier = Modifier.testTag("restore_archive_pin_confirm"),
      ) {
        Text("Decrypt & Restore")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}
