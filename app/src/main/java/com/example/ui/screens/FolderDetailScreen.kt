package com.example.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.VaultFolderEntity
import com.example.data.storage.SecurePreviewContent
import com.example.data.storage.VaultFileItem
import com.example.ui.components.BatchFileRenameDialog
import com.example.ui.components.ConfirmRemoveFilesDialog
import com.example.ui.components.ConfirmRemoveFoldersDialog
import com.example.ui.components.VaultStatusBadge
import com.example.ui.components.formatByteSize
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.AmberVault
import com.example.ui.theme.AmberVaultContainer

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderDetailScreen(
  folder: VaultFolderEntity?,
  files: List<VaultFileItem>,
  isLoadingFiles: Boolean,
  securePreview: SecurePreviewContent?,
  isPreviewLoading: Boolean,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onImportDocuments: (List<Uri>) -> Unit,
  onLockFolder: () -> Unit,
  onSelectiveLockFolder: () -> Unit,
  onUnlockFolder: () -> Unit,
  onEncryptFiles: (Set<String>) -> Unit,
  onDecryptFiles: (Set<String>) -> Unit,
  onRemoveFiles: (Set<String>) -> Unit,
  onUpdateOptions: (requireBio: Boolean, obfuscateNames: Boolean) -> Unit,
  onToggleAutoCloudSync: (enabled: Boolean) -> Unit,
  onBatchRenameFiles: (Map<String, String>) -> Unit,
  onPeekFile: (VaultFileItem) -> Unit,
  onDismissPreview: () -> Unit,
  onRemoveFolder: (decryptLockedSafFirst: Boolean) -> Unit,
  onPickerLaunching: (Boolean) -> Unit = {},
) {
  var showDeleteConfirm by remember { mutableStateOf(false) }
  var isFileSelectionMode by remember { mutableStateOf(false) }
  var selectedFileIds by remember { mutableStateOf<Set<String>>(emptySet()) }
  var showBatchRenameDialog by remember { mutableStateOf(false) }
  var pendingRemoveFiles by remember { mutableStateOf<List<VaultFileItem>>(emptyList()) }
  val haptic = LocalHapticFeedback.current

  BackHandler {
    if (isFileSelectionMode) {
      isFileSelectionMode = false
      selectedFileIds = emptySet()
    } else {
      onBack()
    }
  }

  LaunchedEffect(folder?.id) {
    if (folder != null) {
      onRefresh()
    }
  }

  LaunchedEffect(files) {
    val validIds = files.map { it.id }.toSet()
    selectedFileIds = selectedFileIds.intersect(validIds)
  }

  val docImportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenMultipleDocuments(),
  ) { uris ->
    onPickerLaunching(false)
    if (uris.isNotEmpty()) onImportDocuments(uris)
  }

  val photoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickMultipleVisualMedia(),
  ) { uris ->
    onPickerLaunching(false)
    if (uris.isNotEmpty()) onImportDocuments(uris)
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = folder?.name ?: "Folder",
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (folder != null) {
              Text(
                text = "${folder.fileCount} files • ${formatByteSize(folder.totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("folder_detail_back"),
          ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(onClick = onRefresh, modifier = Modifier.testTag("folder_detail_refresh")) {
            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh files")
          }
          IconButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.testTag("folder_detail_remove"),
          ) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove folder")
          }
        },
      )
    },
  ) { innerPadding ->
    if (folder == null) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      ) {
        Text("Folder not found")
      }
      return@Scaffold
    }

    val selectedFiles = remember(files, selectedFileIds) {
      files.filter { it.id in selectedFileIds }
    }
    val allFilesSelected = remember(files, selectedFileIds) {
      files.isNotEmpty() && files.all { it.id in selectedFileIds }
    }

    Box(
      contentAlignment = Alignment.TopCenter,
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
    ) {
      LazyColumn(
        contentPadding = PaddingValues(
          start = 16.dp,
          end = 16.dp,
          top = 16.dp,
          bottom = if (isFileSelectionMode) 156.dp else 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
          .widthIn(max = 760.dp)
          .fillMaxSize(),
      ) {
        if (folder.isStealthHidden) {
          item(key = "stealth_protection_banner") {
            Surface(
              color = AmberVaultContainer.copy(alpha = 0.78f),
              contentColor = AmberVault,
              shape = MaterialTheme.shapes.medium,
              border = BorderStroke(1.dp, AmberVault.copy(alpha = 0.45f)),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("folder_stealth_protection_banner"),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.VisibilityOff,
                  contentDescription = null,
                  modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "STEALTH VAULT • SCREENSHOTS BLOCKED",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                  )
                  Text(
                    text = "Hidden .nomedia storage • UUID obfuscation • Auto-hides & re-encrypts on exit",
                    style = MaterialTheme.typography.bodySmall,
                  )
                }
              }
            }
          }
        }

        // Primary Lock / Unlock Status Banner
        item(key = "lock_banner") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              verticalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier.padding(16.dp),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
              ) {
                VaultStatusBadge(isLocked = folder.isLocked)
                Text(
                  text = "${folder.encryptedFileCount}/${folder.fileCount} encrypted",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                val hasRemainingPlaintext = (folder.fileCount - folder.encryptedFileCount) > 0 ||
                  files.any { !it.isEncrypted }
                Button(
                  onClick = if (folder.isLocked) onUnlockFolder else onLockFolder,
                  modifier = Modifier
                    .weight(1f)
                    .testTag("detail_lock_unlock_button"),
                ) {
                  Icon(
                    imageVector = if (folder.isLocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = if (folder.isLocked) {
                      if (folder.encryptedFileCount > 0) {
                        "Unlock All (${folder.encryptedFileCount})"
                      } else {
                        "Unlock Folder"
                      }
                    } else {
                      "Lock All"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                if (hasRemainingPlaintext) {
                  FilledTonalButton(
                    onClick = onSelectiveLockFolder,
                    modifier = Modifier
                      .weight(1f)
                      .testTag(
                        if (folder.isLocked) {
                          "detail_encrypt_remaining_button"
                        } else {
                          "detail_selective_lock_button"
                        },
                      ),
                  ) {
                    Icon(Icons.Rounded.Checklist, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = if (folder.isLocked) "Encrypt Plain" else "Choose Files",
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
              }

              Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                FilledTonalButton(
                  onClick = {
                    onPickerLaunching(true)
                    docImportLauncher.launch(arrayOf("*/*"))
                  },
                  modifier = Modifier
                    .weight(1f)
                    .testTag("import_files_button"),
                ) {
                  Icon(Icons.Rounded.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Add Files", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(
                  onClick = {
                    onPickerLaunching(true)
                    photoPickerLauncher.launch(
                      PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                  },
                  modifier = Modifier
                    .weight(1f)
                    .testTag("import_photos_button"),
                ) {
                  Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Add Photos", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }
            }
          }
        }

        // Per-Folder Security Options
        item(key = "security_options") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text("Require Biometric on Unlock", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "Prompt fingerprint/face before decrypting files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = if (folder.isStealthHidden) true else folder.requireBiometricPerUnlock,
                  enabled = !folder.isStealthHidden,
                  onCheckedChange = { onUpdateOptions(it, folder.obfuscateFilenames) },
                  modifier = Modifier.testTag("folder_require_bio_switch"),
                )
              }
              Spacer(modifier = Modifier.height(6.dp))
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text("Obfuscate Filenames", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = if (folder.isStealthHidden) {
                      "Always enforced on Stealth Private Vault folders (UUID.vlock)"
                    } else {
                      "Hide real filenames on disk as UUID.vlock"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = if (folder.isStealthHidden) true else folder.obfuscateFilenames,
                  enabled = !folder.isStealthHidden,
                  onCheckedChange = { onUpdateOptions(folder.requireBiometricPerUnlock, it) },
                  modifier = Modifier.testTag("folder_obfuscate_switch"),
                )
              }
              Spacer(modifier = Modifier.height(6.dp))
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text("Auto Cloud Backup Sync", style = MaterialTheme.typography.titleSmall)
                  Text(
                    text = "Include this folder in automatic .vlkbak cloud syncs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = folder.autoCloudBackupSync,
                  onCheckedChange = onToggleAutoCloudSync,
                  modifier = Modifier.testTag("folder_detail_auto_cloud_sync_switch"),
                )
              }
              Spacer(modifier = Modifier.height(6.dp))
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 6.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Key,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                  val keyScope = if (folder.wrappedFolderKey.isNullOrBlank()) {
                    "Master Vault Key"
                  } else {
                    "Dedicated Per-Folder Key"
                  }
                  Text(
                    text = "Encryption Key v${folder.keyVersion} ($keyScope)",
                    style = MaterialTheme.typography.titleSmall,
                  )
                  val detailText = buildString {
                    if (folder.keyFingerprint.isNotBlank()) {
                      append("KEY ${folder.keyFingerprint}")
                    } else {
                      append("AES-256-GCM")
                    }
                    if (folder.lastKeyRotatedAt > 0L) {
                      append(" • Rotated ${formatTimestamp(folder.lastKeyRotatedAt)}")
                    }
                  }
                  Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }

        item(key = "files_header") {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = "CONTENTS (${files.size})",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            if (files.isNotEmpty() && !isFileSelectionMode) {
              OutlinedButton(
                onClick = { isFileSelectionMode = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.testTag("folder_files_select_button"),
              ) {
                Icon(Icons.Rounded.Checklist, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Select / Batch")
              }
            }
          }
        }

        if (isFileSelectionMode && files.isNotEmpty()) {
          item(key = "file_selection_toolbar") {
            Surface(
              color = MaterialTheme.colorScheme.primaryContainer,
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              shape = MaterialTheme.shapes.medium,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("file_selection_toolbar"),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 10.dp, vertical = 6.dp),
              ) {
                IconButton(
                  onClick = {
                    isFileSelectionMode = false
                    selectedFileIds = emptySet()
                  },
                  modifier = Modifier.testTag("exit_file_selection_button"),
                ) {
                  Icon(Icons.Rounded.Close, contentDescription = "Exit file selection")
                }
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = if (selectedFileIds.isEmpty()) {
                      "Select files"
                    } else {
                      "${selectedFileIds.size} file(s) selected"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                  )
                  Text(
                    text = "Decrypt, encrypt, or rename selected files",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                  )
                }
                TextButton(
                  onClick = {
                    selectedFileIds = if (allFilesSelected) emptySet() else files.map { it.id }.toSet()
                  },
                  modifier = Modifier.testTag("file_selection_select_all_button"),
                ) {
                  Text(if (allFilesSelected) "Deselect All" else "Select All")
                }
              }
            }
          }
        }

        if (isLoadingFiles) {
          item(key = "loading") {
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            ) {
              CircularProgressIndicator()
            }
          }
        } else if (files.isEmpty()) {
          item(key = "empty_files") {
            Card(
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(24.dp),
              ) {
                Text("Folder is Empty", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = "Import photos or documents above, then tap Lock with AES-256.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        } else {
          items(files, key = { it.id }) { file ->
            val isSelected = file.id in selectedFileIds
            val toggleFileSelect = {
              isFileSelectionMode = true
              selectedFileIds = if (isSelected) {
                selectedFileIds - file.id
              } else {
                selectedFileIds + file.id
              }
            }
            Card(
              colors = CardDefaults.cardColors(
                containerColor = if (isSelected) {
                  MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                } else {
                  MaterialTheme.colorScheme.surfaceContainer
                },
              ),
              border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
              modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(
                  onClick = {
                    if (isFileSelectionMode) {
                      toggleFileSelect()
                    } else {
                      onPeekFile(file)
                    }
                  },
                  onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isFileSelectionMode = true
                    selectedFileIds = selectedFileIds + file.id
                  },
                )
                .testTag("file_row_${file.displayName}"),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(14.dp),
              ) {
                Surface(
                  color = if (file.isEncrypted) {
                    MaterialTheme.colorScheme.secondaryContainer
                  } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                  },
                  shape = MaterialTheme.shapes.small,
                  modifier = Modifier.size(42.dp),
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector = if (file.isEncrypted) Icons.Rounded.EnhancedEncryption else Icons.Rounded.Description,
                      contentDescription = null,
                      tint = if (file.isEncrypted) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                      } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                      },
                    )
                  }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    text = buildString {
                      append(formatByteSize(file.sizeBytes))
                      append(if (file.isEncrypted) " • AES-256-GCM" else " • Plaintext")
                      if (file.relativeSubPath.isNotEmpty()) {
                        append(" • /${file.relativeSubPath}")
                      }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                if (isFileSelectionMode) {
                  Checkbox(
                    checked = isSelected,
                    onCheckedChange = { toggleFileSelect() },
                    modifier = Modifier.testTag("file_checkbox_${file.displayName}"),
                  )
                } else {
                  IconButton(
                    onClick = {
                      if (file.isEncrypted) {
                        onDecryptFiles(setOf(file.id))
                      } else {
                        onEncryptFiles(setOf(file.id))
                      }
                    },
                    modifier = Modifier.testTag(
                      if (file.isEncrypted) {
                        "file_decrypt_${file.displayName}"
                      } else {
                        "file_encrypt_${file.displayName}"
                      },
                    ),
                  ) {
                    Icon(
                      imageVector = if (file.isEncrypted) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                      contentDescription = if (file.isEncrypted) {
                        "Decrypt ${file.displayName}"
                      } else {
                        "Encrypt ${file.displayName}"
                      },
                      tint = if (file.isEncrypted) {
                        MaterialTheme.colorScheme.primary
                      } else {
                        MaterialTheme.colorScheme.secondary
                      },
                    )
                  }
                  IconButton(onClick = { onPeekFile(file) }) {
                    Icon(
                      imageVector = Icons.Rounded.Visibility,
                      contentDescription = "Secure preview",
                    )
                  }
                  IconButton(
                    onClick = { pendingRemoveFiles = listOf(file) },
                    modifier = Modifier.testTag("file_remove_${file.displayName}"),
                  ) {
                    Icon(
                      imageVector = Icons.Rounded.DeleteOutline,
                      contentDescription = "Remove ${file.displayName}",
                      tint = MaterialTheme.colorScheme.error,
                    )
                  }
                }
              }
            }
          }
        }
      }

      AnimatedVisibility(
        visible = isFileSelectionMode && selectedFiles.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(horizontal = 16.dp, vertical = 14.dp),
      ) {
        val selectedEncryptedIds = remember(selectedFiles) {
          selectedFiles.filter { it.isEncrypted }.map { it.id }.toSet()
        }
        val selectedPlainIds = remember(selectedFiles) {
          selectedFiles.filter { !it.isEncrypted }.map { it.id }.toSet()
        }
        Surface(
          shape = MaterialTheme.shapes.large,
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          tonalElevation = 6.dp,
          shadowElevation = 8.dp,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .testTag("file_batch_action_bar"),
        ) {
          Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          ) {
            if (selectedEncryptedIds.isNotEmpty() || selectedPlainIds.isNotEmpty()) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                if (selectedEncryptedIds.isNotEmpty()) {
                  Button(
                    onClick = {
                      val targetIds = selectedEncryptedIds
                      isFileSelectionMode = false
                      selectedFileIds = emptySet()
                      onDecryptFiles(targetIds)
                    },
                    modifier = Modifier
                      .weight(1f)
                      .testTag("detail_batch_decrypt_button"),
                  ) {
                    Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Decrypt (${selectedEncryptedIds.size})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                  }
                }
                if (selectedPlainIds.isNotEmpty()) {
                  FilledTonalButton(
                    onClick = {
                      val targetIds = selectedPlainIds
                      isFileSelectionMode = false
                      selectedFileIds = emptySet()
                      onEncryptFiles(targetIds)
                    },
                    modifier = Modifier
                      .weight(1f)
                      .testTag("detail_batch_encrypt_button"),
                  ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Encrypt (${selectedPlainIds.size})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                  }
                }
              }
            }
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              OutlinedButton(
                onClick = { showBatchRenameDialog = true },
                modifier = Modifier
                  .weight(1f)
                  .testTag("detail_batch_rename_button"),
              ) {
                Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Rename (${selectedFiles.size})", maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
              OutlinedButton(
                onClick = { pendingRemoveFiles = selectedFiles },
                colors = ButtonDefaults.outlinedButtonColors(
                  contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier
                  .weight(1f)
                  .testTag("detail_batch_remove_button"),
              ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Remove (${selectedFiles.size})", maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }
      }
    }
  }

  if (showBatchRenameDialog && selectedFileIds.isNotEmpty()) {
    val selectedForDialog = files.filter { it.id in selectedFileIds }
    if (selectedForDialog.isNotEmpty()) {
      BatchFileRenameDialog(
        selectedFiles = selectedForDialog,
        allFolderFiles = files,
        onDismiss = { showBatchRenameDialog = false },
        onConfirm = { renameMap ->
          showBatchRenameDialog = false
          isFileSelectionMode = false
          selectedFileIds = emptySet()
          onBatchRenameFiles(renameMap)
        },
      )
    }
  }

  if (isPreviewLoading) {
    AlertDialog(
      onDismissRequest = {},
      title = { Text("Decrypting Secure Preview...") },
      text = {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
          CircularProgressIndicator()
        }
      },
      confirmButton = {},
    )
  }

  if (securePreview != null) {
    SecurePreviewDialog(
      preview = securePreview,
      onDismiss = onDismissPreview,
    )
  }

  if (pendingRemoveFiles.isNotEmpty()) {
    ConfirmRemoveFilesDialog(
      files = pendingRemoveFiles,
      onDismiss = { pendingRemoveFiles = emptyList() },
      onConfirm = {
        val targetIds = pendingRemoveFiles.map { it.id }.toSet()
        pendingRemoveFiles = emptyList()
        isFileSelectionMode = false
        selectedFileIds = emptySet()
        onRemoveFiles(targetIds)
      },
    )
  }

  if (showDeleteConfirm && folder != null) {
    ConfirmRemoveFoldersDialog(
      folders = listOf(folder),
      onDismiss = { showDeleteConfirm = false },
      onConfirm = { decryptLockedSafFirst ->
        showDeleteConfirm = false
        onRemoveFolder(decryptLockedSafFirst)
        onBack()
      },
    )
  }
}

@Composable
private fun SecurePreviewDialog(
  preview: SecurePreviewContent,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Column {
        Text(
          text = when (preview) {
            is SecurePreviewContent.ImagePreview -> preview.fileName
            is SecurePreviewContent.TextPreview -> preview.fileName
            is SecurePreviewContent.BinarySummary -> preview.fileName
          },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val encrypted = when (preview) {
          is SecurePreviewContent.ImagePreview -> preview.isEncryptedOnDisk
          is SecurePreviewContent.TextPreview -> preview.isEncryptedOnDisk
          is SecurePreviewContent.BinarySummary -> preview.isEncryptedOnDisk
        }
        Text(
          text = if (encrypted) "In-memory AES-256-GCM peek (still locked on disk)" else "Plaintext file",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    },
    text = {
      when (preview) {
        is SecurePreviewContent.ImagePreview -> {
          val bitmap = remember(preview.bytes) {
            BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size)?.asImageBitmap()
          }
          if (bitmap != null) {
            Image(
              bitmap = bitmap,
              contentDescription = preview.fileName,
              contentScale = ContentScale.Fit,
              modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            )
          } else {
            Text("Unable to decode image bytes.")
          }
        }
        is SecurePreviewContent.TextPreview -> {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .height(240.dp)
              .verticalScroll(rememberScrollState()),
          ) {
            Text(
              text = preview.text,
              style = MaterialTheme.typography.labelMedium,
            )
          }
        }
        is SecurePreviewContent.BinarySummary -> {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MIME: ${preview.mimeType}", style = MaterialTheme.typography.bodySmall)
            Text("Size: ${formatByteSize(preview.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
            Text("SHA-256 Prefix: ${preview.sha256Prefix}", style = MaterialTheme.typography.labelMedium)
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("close_secure_preview")) {
        Text("Close & Wipe Buffer")
      }
    },
  )
}
