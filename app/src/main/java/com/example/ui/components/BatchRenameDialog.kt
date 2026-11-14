package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.VaultFolderEntity
import com.example.data.storage.BatchRenameConfig
import com.example.data.storage.BatchRenameEngine
import com.example.data.storage.RenameCaseMode
import com.example.data.storage.RenamePreviewItem
import com.example.data.storage.VaultFileItem

@Composable
fun BatchFileRenameDialog(
  selectedFiles: List<VaultFileItem>,
  allFolderFiles: List<VaultFileItem>,
  onDismiss: () -> Unit,
  onConfirm: (Map<String, String>) -> Unit,
) {
  var config by remember { mutableStateOf(BatchRenameConfig()) }
  val previewItems = remember(selectedFiles, allFolderFiles, config) {
    BatchRenameEngine.computeBatchFileRenamePlan(selectedFiles, allFolderFiles, config)
  }

  BatchRenameDialogContent(
    title = "Batch Rename Files (${selectedFiles.size})",
    trustNotice = "AES-256-GCM encrypted payloads stay locked on disk — only encrypted metadata headers are updated.",
    isFolderMode = false,
    config = config,
    onConfigChange = { config = it },
    previewItems = previewItems,
    onDismiss = onDismiss,
    onConfirm = {
      val map = previewItems
        .filter { it.isChanged }
        .associate { it.id to it.proposedName }
      onConfirm(map)
    },
  )
}

@Composable
fun BatchFolderRenameDialog(
  selectedFolders: List<VaultFolderEntity>,
  allFolders: List<VaultFolderEntity>,
  onDismiss: () -> Unit,
  onConfirm: (Map<String, String>) -> Unit,
) {
  var config by remember { mutableStateOf(BatchRenameConfig(preserveExtension = false)) }
  val previewItems = remember(selectedFolders, allFolders, config) {
    BatchRenameEngine.computeBatchFolderRenamePlan(selectedFolders, allFolders, config)
  }

  BatchRenameDialogContent(
    title = "Organize Folder Names (${selectedFolders.size})",
    trustNotice = "Sanitizes and organizes locked or unlocked vault folder labels without decrypting any stored files.",
    isFolderMode = true,
    config = config,
    onConfigChange = { config = it },
    previewItems = previewItems,
    onDismiss = onDismiss,
    onConfirm = {
      val map = previewItems
        .filter { it.isChanged }
        .associate { it.id to it.proposedName }
      onConfirm(map)
    },
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchRenameDialogContent(
  title: String,
  trustNotice: String,
  isFolderMode: Boolean,
  config: BatchRenameConfig,
  onConfigChange: (BatchRenameConfig) -> Unit,
  previewItems: List<RenamePreviewItem>,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  val changedCount = remember(previewItems) { previewItems.count { it.isChanged } }

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = Icons.Rounded.DriveFileRenameOutline,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    title = { Text(title) },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 460.dp)
          .verticalScroll(rememberScrollState()),
      ) {
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.Lock,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = trustNotice,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }

        // Quick Sanitization Chips
        Text(
          text = "SANITIZE & CASE",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          FilterChip(
            selected = config.sanitizeIllegalChars,
            onClick = { onConfigChange(config.copy(sanitizeIllegalChars = !config.sanitizeIllegalChars)) },
            leadingIcon = {
              Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
            },
            label = { Text("Strip Unsafe Chars") },
            modifier = Modifier.testTag("batch_rename_sanitize_chip"),
          )
          FilterChip(
            selected = config.replaceSpacesWithUnderscores,
            onClick = {
              onConfigChange(config.copy(replaceSpacesWithUnderscores = !config.replaceSpacesWithUnderscores))
            },
            label = { Text("Spaces → _") },
            modifier = Modifier.testTag("batch_rename_underscores_chip"),
          )
          if (!isFolderMode) {
            FilterChip(
              selected = config.preserveExtension,
              onClick = { onConfigChange(config.copy(preserveExtension = !config.preserveExtension)) },
              label = { Text("Keep Extension") },
              modifier = Modifier.testTag("batch_rename_keep_ext_chip"),
            )
          }
        }

        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          RenameCaseMode.entries.forEach { mode ->
            FilterChip(
              selected = config.caseMode == mode,
              onClick = { onConfigChange(config.copy(caseMode = mode)) },
              label = { Text(mode.label) },
              modifier = Modifier.testTag("batch_rename_case_${mode.name.lowercase()}"),
            )
          }
        }

        // Prefix / Suffix
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          OutlinedTextField(
            value = config.prefix,
            onValueChange = { onConfigChange(config.copy(prefix = it)) },
            label = { Text("Prefix") },
            placeholder = { Text("e.g. 2026_") },
            singleLine = true,
            modifier = Modifier
              .weight(1f)
              .testTag("batch_rename_prefix_input"),
          )
          OutlinedTextField(
            value = config.suffix,
            onValueChange = { onConfigChange(config.copy(suffix = it)) },
            label = { Text("Suffix") },
            placeholder = { Text("e.g. _vault") },
            singleLine = true,
            modifier = Modifier
              .weight(1f)
              .testTag("batch_rename_suffix_input"),
          )
        }

        // Find & Replace
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          OutlinedTextField(
            value = config.findText,
            onValueChange = { onConfigChange(config.copy(findText = it)) },
            label = { Text("Find") },
            singleLine = true,
            modifier = Modifier
              .weight(1f)
              .testTag("batch_rename_find_input"),
          )
          OutlinedTextField(
            value = config.replaceText,
            onValueChange = { onConfigChange(config.copy(replaceText = it)) },
            label = { Text("Replace") },
            singleLine = true,
            modifier = Modifier
              .weight(1f)
              .testTag("batch_rename_replace_input"),
          )
        }

        // Sequential Numbering Toggle
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(
            imageVector = Icons.Rounded.AutoFixHigh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text("Sequential Numbering (_001, _002)", style = MaterialTheme.typography.titleSmall)
            Text(
              text = "Appends padded index before extension",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = config.numberingEnabled,
            onCheckedChange = { onConfigChange(config.copy(numberingEnabled = it)) },
            modifier = Modifier.testTag("batch_rename_numbering_switch"),
          )
        }

        HorizontalDivider()

        Text(
          text = "LIVE PREVIEW ($changedCount of ${previewItems.size} changing)",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )

        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(10.dp),
          ) {
            previewItems.forEach { item ->
              Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Text(
                    text = item.currentName,
                    style = MaterialTheme.typography.labelSmall.copy(
                      textDecoration = if (item.isChanged) TextDecoration.LineThrough else TextDecoration.None,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = item.proposedName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (item.isChanged) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (item.isChanged) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                  )
                }
                if (item.conflictNote != null) {
                  Text(
                    text = item.conflictNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                  )
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = onConfirm,
        enabled = changedCount > 0,
        modifier = Modifier.testTag("batch_rename_confirm_button"),
      ) {
        Text("Apply ($changedCount)")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("batch_rename_cancel_button"),
      ) {
        Text("Cancel")
      }
    },
    modifier = Modifier.testTag("batch_rename_dialog"),
  )
}
