package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.crypto.UriPermissionStatus
import com.example.ui.PendingFolderPickerPreview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerPreviewSheet(
  preview: PendingFolderPickerPreview,
  onUpdateOptions: (obfuscateFilenames: Boolean, requireAuthOnOpen: Boolean) -> Unit,
  onConfirm: (encryptNow: Boolean) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.testTag("folder_picker_preview_sheet"),
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(14.dp),
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 20.dp)
        .padding(bottom = 20.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Surface(
          color = if (preview.permissionStatus.isWritable) {
            MaterialTheme.colorScheme.primaryContainer
          } else {
            MaterialTheme.colorScheme.errorContainer
          },
          contentColor = if (preview.permissionStatus.isWritable) {
            MaterialTheme.colorScheme.onPrimaryContainer
          } else {
            MaterialTheme.colorScheme.onErrorContainer
          },
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier.size(48.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Rounded.Folder,
              contentDescription = "Selected device folder",
              modifier = Modifier.size(26.dp),
            )
          }
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = preview.displayName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = if (preview.existingFolderId != null) {
              "Already linked in BeastLock"
            } else {
              "Selected via Android Folder Picker"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        PermissionStatusPill(
          status = preview.permissionStatus,
          modifier = Modifier.testTag("picker_permission_badge"),
        )
      }

      if (preview.isScanning) {
        Column(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        ) {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          Text(
            text = "Auditing SAF URI permissions & scanning files...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        Row(
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          PreviewStatBox(
            label = "PLAINTEXT",
            value = "${preview.plaintextFileCount}",
            modifier = Modifier.weight(1f),
          )
          PreviewStatBox(
            label = "ENCRYPTED",
            value = "${preview.encryptedFileCount}",
            modifier = Modifier.weight(1f),
          )
          PreviewStatBox(
            label = "TOTAL SIZE",
            value = formatByteSize(preview.totalBytes),
            modifier = Modifier.weight(1f),
          )
        }

        if (!preview.permissionStatus.isWritable) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.padding(12.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
              )
              Text(
                text = "Read-only or revoked permission detected. Pick a writable folder to enable AES-256-GCM encryption.",
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }

        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.fillMaxWidth()) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable {
                  onUpdateOptions(!preview.obfuscateFilenames, preview.requireAuthOnOpen)
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Obfuscate Filenames (UUID)",
                  style = MaterialTheme.typography.titleSmall,
                )
                Text(
                  text = "Hides real filenames as random .vlock UUIDs on storage",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Spacer(modifier = Modifier.width(8.dp))
              Switch(
                checked = preview.obfuscateFilenames,
                onCheckedChange = { checked ->
                  onUpdateOptions(checked, preview.requireAuthOnOpen)
                },
                modifier = Modifier.testTag("picker_obfuscate_switch"),
              )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable {
                  onUpdateOptions(preview.obfuscateFilenames, !preview.requireAuthOnOpen)
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Require Biometric / Auth on Unlock",
                  style = MaterialTheme.typography.titleSmall,
                )
                Text(
                  text = "Prompts BiometricHelper or Pattern/PIN before decrypting or opening",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Spacer(modifier = Modifier.width(8.dp))
              Switch(
                checked = preview.requireAuthOnOpen,
                onCheckedChange = { checked ->
                  onUpdateOptions(preview.obfuscateFilenames, checked)
                },
                modifier = Modifier.testTag("picker_require_auth_switch"),
              )
            }
          }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = { onConfirm(true) },
            enabled = preview.permissionStatus.isWritable,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("picker_encrypt_confirm_button"),
          ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            val primaryLabel = when {
              preview.existingFolderId != null && preview.plaintextFileCount > 0 ->
                "Encrypt Remaining (${preview.plaintextFileCount})"
              preview.plaintextFileCount > 0 ->
                "Encrypt & Lock Folder (${preview.plaintextFileCount})"
              else ->
                "Protect & Lock Folder"
            }
            Text(primaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }

          if (preview.existingFolderId == null) {
            OutlinedButton(
              onClick = { onConfirm(false) },
              enabled = preview.permissionStatus.isWritable,
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("picker_register_only_button"),
            ) {
              Text("Register Without Locking", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }

          TextButton(
            onClick = onDismiss,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("picker_cancel_button"),
          ) {
            Text("Cancel")
          }
        }
      }
    }
  }
}

@Composable
fun PermissionStatusPill(
  status: UriPermissionStatus,
  modifier: Modifier = Modifier,
) {
  val (containerColor, contentColor, icon) = when (status) {
    UriPermissionStatus.PERSISTED_READ_WRITE -> Triple(
      MaterialTheme.colorScheme.primaryContainer,
      MaterialTheme.colorScheme.onPrimaryContainer,
      Icons.Rounded.VerifiedUser,
    )
    UriPermissionStatus.TRANSIENT -> Triple(
      MaterialTheme.colorScheme.tertiaryContainer,
      MaterialTheme.colorScheme.onTertiaryContainer,
      Icons.Rounded.Info,
    )
    UriPermissionStatus.READ_ONLY,
    UriPermissionStatus.REVOKED,
    -> Triple(
      MaterialTheme.colorScheme.errorContainer,
      MaterialTheme.colorScheme.onErrorContainer,
      Icons.Rounded.WarningAmber,
    )
    UriPermissionStatus.INTERNAL_SANDBOX -> Triple(
      MaterialTheme.colorScheme.secondaryContainer,
      MaterialTheme.colorScheme.onSecondaryContainer,
      Icons.Rounded.VerifiedUser,
    )
  }

  Surface(
    color = containerColor,
    contentColor = contentColor,
    shape = RoundedCornerShape(50),
    border = if (status.needsRegrant) {
      BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
    } else null,
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
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = status.label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun PreviewStatBox(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = MaterialTheme.shapes.small,
    modifier = modifier,
  ) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = value,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
