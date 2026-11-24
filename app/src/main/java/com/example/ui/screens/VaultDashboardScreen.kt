package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.crypto.UriPermissionStatus
import com.example.data.local.VaultFolderEntity
import com.example.data.preferences.StealthGestureMode
import com.example.ui.FolderFilter
import com.example.ui.VaultUiState
import com.example.ui.components.BatchFolderRenameDialog
import com.example.ui.components.ConfirmRemoveFoldersDialog
import com.example.ui.components.FolderPickerPreviewSheet
import com.example.ui.components.PermissionStatusPill
import com.example.ui.components.ThemeModeDropdownButton
import com.example.ui.components.VaultStatusBadge
import com.example.ui.components.formatByteSize
import com.example.ui.components.formatTimestamp
import com.example.ui.theme.ThemeMode

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun VaultDashboardScreen(
  state: VaultUiState,
  onSelectFilter: (FolderFilter) -> Unit,
  onSearchQueryChange: (String) -> Unit,
  onPickDeviceFolder: (Uri) -> Unit,
  onPreviewPickedFolder: (Uri) -> Unit = onPickDeviceFolder,
  onUpdateFolderPickerOptions: (obfuscateFilenames: Boolean, requireAuthOnOpen: Boolean) -> Unit = { _, _ -> },
  onConfirmFolderPickerPreview: (encryptNow: Boolean) -> Unit = {},
  onDismissFolderPickerPreview: () -> Unit = {},
  onRegrantFolderPermission: (folderId: String, treeUri: Uri) -> Unit = { _, _ -> },
  onDecryptAnyDeviceFolder: (Uri) -> Unit,
  onCreatePrivateFolder: (String) -> Unit = {},
  onOpenFolder: (String) -> Unit,
  onToggleLockFolder: (VaultFolderEntity) -> Unit,
  onRemoveFolder: (folderId: String, decryptLockedSafFirst: Boolean) -> Unit,
  onEnterSelectionMode: (String?) -> Unit,
  onToggleFolderSelection: (String) -> Unit,
  onSelectAllFilteredFolders: () -> Unit,
  onClearSelection: () -> Unit,
  onEncryptSelectedFolders: () -> Unit,
  onDecryptSelectedFolders: () -> Unit,
  onReencryptSelectedFolders: (includeUnlocked: Boolean) -> Unit,
  onRemoveSelectedFolders: (decryptLockedSafFirst: Boolean) -> Unit,
  onToggleFolderAutoCloudSync: (folderId: String, enabled: Boolean) -> Unit,
  onSetSelectedAutoCloudSync: (enabled: Boolean) -> Unit,
  onBatchRenameFolders: (Map<String, String>) -> Unit,
  onOpenStorageCharts: () -> Unit,
  onOpenDiagnostics: () -> Unit = {},
  onTriggerStealthGesture: () -> Unit = {},
  onLockVaultNow: () -> Unit,
  onSelectThemeMode: (ThemeMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  var showReencryptConfirmDialog by remember { mutableStateOf(false) }
  var showBatchFolderRenameDialog by remember { mutableStateOf(false) }
  var pendingRemoveFolders by remember { mutableStateOf<List<VaultFolderEntity>>(emptyList()) }
  var isRemovingBatchFolders by remember { mutableStateOf(false) }
  var pendingRegrantFolderId by remember { mutableStateOf<String?>(null) }
  val haptic = LocalHapticFeedback.current
  val publicFolders = state.publicFolders

  BackHandler(enabled = state.isSelectionMode) {
    onClearSelection()
  }

  val openFolderLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree(),
  ) { uri ->
    if (uri != null) {
      onPreviewPickedFolder(uri)
    }
  }

  val regrantFolderLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree(),
  ) { uri ->
    val targetFolderId = pendingRegrantFolderId
    pendingRegrantFolderId = null
    if (uri != null && targetFolderId != null) {
      onRegrantFolderPermission(targetFolderId, uri)
    }
  }

  val decryptAnyFolderLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree(),
  ) { uri ->
    if (uri != null) {
      onDecryptAnyDeviceFolder(uri)
    }
  }

  val selectedFolders = remember(publicFolders, state.selectedFolderIds) {
    publicFolders.filter { it.id in state.selectedFolderIds }
  }
  val unlockedSelectedCount = remember(selectedFolders) {
    selectedFolders.count { !it.isLocked || it.encryptedFileCount < it.fileCount }
  }
  val lockedSelectedCount = remember(selectedFolders) {
    selectedFolders.count { it.isLocked || it.encryptedFileCount > 0 }
  }
  val autoSyncSelectedCount = remember(selectedFolders) { selectedFolders.count { it.autoCloudBackupSync } }
  val allVisibleSelected = remember(state.filteredFolders, state.selectedFolderIds) {
    state.filteredFolders.isNotEmpty() && state.filteredFolders.all { it.id in state.selectedFolderIds }
  }

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter,
  ) {
    LazyColumn(
      contentPadding = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = 14.dp,
        bottom = if (state.isSelectionMode) 316.dp else 14.dp,
      ),
      verticalArrangement = Arrangement.spacedBy(14.dp),
      modifier = Modifier
        .widthIn(max = 760.dp)
        .fillMaxSize(),
    ) {
      // Top Hero Security Posture Banner
      item(key = "hero_posture") {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large),
        ) {
          Image(
            painter = painterResource(id = R.drawable.img_vault_security_hero),
            contentDescription = "BeastLock security status",
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .matchParentSize(),
          )
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .background(
                Brush.horizontalGradient(
                  colors = listOf(
                    Color(0xEB0A0F16),
                    Color(0xC90A0F16),
                    Color(0x700A0F16),
                  ),
                ),
              )
              .padding(18.dp),
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
              ) {
                var tripleTapCount by remember { mutableIntStateOf(0) }
                var lastTapAt by remember { mutableLongStateOf(0L) }
                val stealthInteraction = remember { MutableInteractionSource() }
                val stealthModifier = if (state.securitySettings.stealthGestureMode == StealthGestureMode.LONG_PRESS_SHIELD) {
                  Modifier
                    .heightIn(min = 48.dp)
                    .combinedClickable(
                      interactionSource = stealthInteraction,
                      indication = null,
                      onClick = {},
                      onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTriggerStealthGesture()
                      },
                    )
                    .testTag("hero_stealth_trigger")
                } else {
                  Modifier
                    .heightIn(min = 48.dp)
                    .clickable(
                      interactionSource = stealthInteraction,
                      indication = null,
                    ) {
                      val now = System.currentTimeMillis()
                      tripleTapCount = if (now - lastTapAt <= 900L) tripleTapCount + 1 else 1
                      lastTapAt = now
                      if (tripleTapCount >= 3) {
                        tripleTapCount = 0
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTriggerStealthGesture()
                      }
                    }
                    .testTag("hero_stealth_trigger")
                }

                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = stealthModifier,
                ) {
                  Icon(
                    imageVector = Icons.Rounded.VerifiedUser,
                    contentDescription = "BeastLock security badge",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = if (state.securitySettings.panicModeEnabled) {
                      "AES-256-GCM • SHAKE PANIC ON"
                    } else {
                      "AES-256-GCM ACTIVE"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                  )
                }
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  ThemeModeDropdownButton(
                    currentMode = state.securitySettings.themeMode,
                    onSelectMode = onSelectThemeMode,
                    testTagPrefix = "dashboard_theme_switcher",
                  )
                  FilledTonalIconButton(
                    onClick = onLockVaultNow,
                    modifier = Modifier.testTag("seal_vault_button"),
                  ) {
                    Icon(
                      imageVector = Icons.Rounded.Lock,
                      contentDescription = "Lock BeastLock now",
                    )
                  }
                }
              }

              Text(
                text = if (publicFolders.isEmpty()) {
                  "Protect Any Android Folder"
                } else {
                  "${state.lockedFoldersCount} of ${publicFolders.size} Folders Sealed"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
              )

              Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                StatPill(
                  label = "ENCRYPTED",
                  value = "${state.totalEncryptedFiles} files",
                  modifier = Modifier.weight(1f),
                )
                StatPill(
                  label = "PROTECTED",
                  value = formatByteSize(state.totalProtectedBytes),
                  modifier = Modifier.weight(1f),
                )
                StatPill(
                  label = "AUTO-LOCK",
                  value = if (!state.securitySettings.autoLockEnabled) {
                    "Off"
                  } else if (state.securitySettings.autoLockTimeoutSeconds == 0) {
                    "Immediate"
                  } else if (state.securitySettings.autoLockTimeoutSeconds < 60) {
                    "${state.securitySettings.autoLockTimeoutSeconds}s"
                  } else {
                    "${state.securitySettings.autoLockTimeoutSeconds / 60}m"
                  },
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }
      }

      // Battery Saver Active / Queued Banner
      if (state.isBatterySaverBlockingTasks ||
        state.securitySettings.pendingBatteryPausedAutoSync ||
        state.securitySettings.pendingBatteryPausedReencrypt
      ) {
        item(key = "battery_saver_banner") {
          Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("dashboard_battery_saver_banner"),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.BatteryAlert,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
              )
              Spacer(modifier = Modifier.width(10.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = if (state.isBatterySaverBlockingTasks) {
                    "Battery Saver Active (${state.devicePowerState.batteryPercent}%)"
                  } else {
                    "Resuming Queued Background Tasks"
                  },
                  style = MaterialTheme.typography.titleSmall,
                )
                val pausedDetails = buildList {
                  if (state.securitySettings.pendingBatteryPausedAutoSync) add("auto cloud sync")
                  if (state.securitySettings.pendingBatteryPausedReencrypt) add("background re-encryption")
                }
                Text(
                  text = if (state.isBatterySaverBlockingTasks) {
                    val queuedPart = if (pausedDetails.isNotEmpty()) {
                      "Queued: ${pausedDetails.joinToString(" & ")}. "
                    } else {
                      "Auto cloud sync & background encryption paused. "
                    }
                    "${queuedPart}Resumes automatically > 20%."
                  } else {
                    "Battery recovered above 20% — resuming ${pausedDetails.joinToString(" & ")}."
                  },
                  style = MaterialTheme.typography.bodySmall,
                )
              }
            }
          }
        }
      }

      // Pending Diagnostics / Dual Key Alert Strip
      if (state.pendingIssuesCount > 0 || state.securitySettings.dualKeyTamperAlert.isNotBlank()) {
        item(key = "diagnostics_alert_banner") {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
              .fillMaxWidth()
              .clip(MaterialTheme.shapes.medium)
              .clickable(onClick = onOpenDiagnostics)
              .testTag("dashboard_diagnostics_alert_card"),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.BugReport,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
              )
              Spacer(modifier = Modifier.width(10.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = if (state.securitySettings.dualKeyTamperAlert.isNotBlank()) {
                    "Key Recovery Vault Alert"
                  } else {
                    "${state.pendingIssuesCount} System Diagnostic(s) Recorded"
                  },
                  style = MaterialTheme.typography.titleSmall,
                )
                Text(
                  text = state.securitySettings.dualKeyTamperAlert.ifBlank {
                    "Tap to review sanitized logs & send a report on your own"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }
      }

      // Primary Action Row: Pick any Android folder via SAF or Decrypt any folder
      item(key = "actions_row") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = { openFolderLauncher.launch(null) },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("pick_device_folder_button"),
          ) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Lock Device Folder (Internal / External)", maxLines = 1, overflow = TextOverflow.Ellipsis)
          }

          OutlinedButton(
            onClick = { decryptAnyFolderLauncher.launch(null) },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("decrypt_any_device_folder_button"),
          ) {
            Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Decrypt Any Device Folder (In-Place • No Copies)", maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
      }

      // Storage Split Summary Strip (links to full Visualization Dashboard)
      if (publicFolders.isNotEmpty()) {
        item(key = "storage_split_preview") {
          StorageUsagePreviewCard(
            encryptedBytes = state.totalEncryptedBytes,
            unencryptedBytes = state.totalUnencryptedBytes,
            onClick = onOpenStorageCharts,
          )
        }
      }

      // Search & Filter Controls (Search bar always remains present for quick navigation & stealth access code)
      item(key = "search_filters") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search by folder name or status (locked / unlocked)...") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
              if (state.searchQuery.isNotEmpty()) {
                IconButton(
                  onClick = { onSearchQueryChange("") },
                  modifier = Modifier.testTag("clear_folder_search_button"),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Clear search query",
                  )
                }
              }
            },
            supportingText = if (publicFolders.isNotEmpty() &&
              (state.searchQuery.isNotBlank() || state.folderFilter != FolderFilter.ALL)
            ) {
              {
                Text(
                  text = "${state.filteredFolders.size} of ${publicFolders.size} folder(s) matching",
                  modifier = Modifier.testTag("folder_search_match_count"),
                )
              }
            } else {
              {
                Text("Type a folder name or status (e.g. locked, unlocked) to filter live")
              }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("folder_search_input"),
          )

          if (publicFolders.isNotEmpty() || state.folderFilter != FolderFilter.ALL) {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              FolderFilter.entries.forEach { filter ->
                FilterChip(
                  selected = state.folderFilter == filter,
                  onClick = { onSelectFilter(filter) },
                  label = { Text(filter.label) },
                  modifier = Modifier.testTag("filter_chip_${filter.name.lowercase()}"),
                )
              }
              if (!state.isSelectionMode && state.filteredFolders.isNotEmpty()) {
                OutlinedButton(
                  onClick = { onEnterSelectionMode(null) },
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                  modifier = Modifier.testTag("enter_multiselect_button"),
                ) {
                  Icon(Icons.Rounded.Checklist, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Select")
                }
              }
            }
          }
        }
      }

      // Contextual Multi-Select Header
      if (state.isSelectionMode) {
        item(key = "multiselect_toolbar") {
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 4.dp,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("multiselect_toolbar"),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
              IconButton(
                onClick = onClearSelection,
                modifier = Modifier.testTag("exit_multiselect_button"),
              ) {
                Icon(Icons.Rounded.Close, contentDescription = "Exit multi-select mode")
              }
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = if (selectedFolders.isEmpty()) {
                    "Select folders"
                  } else {
                    "${selectedFolders.size} folder(s) selected"
                  },
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.SemiBold,
                )
                Text(
                  text = if (selectedFolders.isEmpty()) {
                    "Tap cards to select for batch encrypt or decrypt"
                  } else {
                    "$unlockedSelectedCount unlocked • $lockedSelectedCount locked"
                  },
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
              }
              TextButton(
                onClick = onSelectAllFilteredFolders,
                modifier = Modifier.testTag("multiselect_select_all_button"),
              ) {
                Text(if (allVisibleSelected) "Deselect All" else "Select All")
              }
            }
          }
        }
      }

      // Empty State
      if (state.filteredFolders.isEmpty()) {
        item(key = "empty_folders") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp),
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
                  imageVector = Icons.Rounded.Shield,
                  contentDescription = null,
                  modifier = Modifier
                    .padding(16.dp)
                    .size(34.dp),
                )
              }
              Text(
                text = if (publicFolders.isEmpty()) "No Protected Folders Yet" else "No Matching Folders",
                style = MaterialTheme.typography.titleLarge,
              )
              Text(
                text = if (publicFolders.isEmpty()) {
                  "Tap \"Lock Device Folder\" to select any folder from internal or external storage via Android's Storage Access Framework."
                } else {
                  "No folders matched \"${state.searchQuery.ifBlank { state.folderFilter.label }}\". Try another name or status (locked / unlocked)."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              if (publicFolders.isNotEmpty()) {
                OutlinedButton(
                  onClick = {
                    onSearchQueryChange("")
                    onSelectFilter(FolderFilter.ALL)
                  },
                  modifier = Modifier.testTag("clear_search_filters_button"),
                ) {
                  Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Clear Search & Filters")
                }
              }
            }
          }
        }
      }

      // Folder Cards
      items(state.filteredFolders, key = { it.id }) { folder ->
        val isSelected = folder.id in state.selectedFolderIds
        val permStatus = state.folderPermissionStatuses[folder.id]
        VaultFolderCard(
          folder = folder,
          permissionStatus = permStatus,
          isSelectionMode = state.isSelectionMode,
          isSelected = isSelected,
          onOpen = {
            if (state.isSelectionMode) {
              onToggleFolderSelection(folder.id)
            } else {
              onOpenFolder(folder.id)
            }
          },
          onLongPress = { onEnterSelectionMode(folder.id) },
          onToggleLock = { onToggleLockFolder(folder) },
          onRequestRemove = {
            isRemovingBatchFolders = false
            pendingRemoveFolders = listOf(folder)
          },
          onRegrantPermission = {
            pendingRegrantFolderId = folder.id
            val initialUri = runCatching {
              Uri.parse(folder.treeUri).takeIf { it.scheme == "content" }
            }.getOrNull()
            regrantFolderLauncher.launch(initialUri)
          },
          onToggleAutoCloudSync = { enabled ->
            onToggleFolderAutoCloudSync(folder.id, enabled)
          },
        )
      }
    }

    AnimatedVisibility(
      visible = state.isSelectionMode && selectedFolders.isNotEmpty(),
      enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
      exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
        .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
      Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
          .widthIn(max = 600.dp)
          .fillMaxWidth()
          .testTag("batch_action_bar"),
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
          val allSelectedAutoSync = autoSyncSelectedCount == selectedFolders.size && selectedFolders.isNotEmpty()
          Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
              Icon(
                imageVector = if (allSelectedAutoSync) Icons.Rounded.CloudSync else Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = if (allSelectedAutoSync) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Auto Cloud Backup Sync",
                  style = MaterialTheme.typography.labelLarge,
                )
                Text(
                  text = "$autoSyncSelectedCount of ${selectedFolders.size} selected synced",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = allSelectedAutoSync,
                onCheckedChange = { enabled -> onSetSelectedAutoCloudSync(enabled) },
                modifier = Modifier.testTag("batch_cloud_sync_toggle"),
              )
            }
          }

          Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Button(
              onClick = onEncryptSelectedFolders,
              enabled = unlockedSelectedCount > 0,
              modifier = Modifier
                .weight(1f)
                .testTag("batch_encrypt_button"),
            ) {
              Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Encrypt ($unlockedSelectedCount)",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            FilledTonalButton(
              onClick = onDecryptSelectedFolders,
              enabled = lockedSelectedCount > 0,
              modifier = Modifier
                .weight(1f)
                .testTag("batch_decrypt_button"),
            ) {
              Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Decrypt ($lockedSelectedCount)",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }

          FilledTonalButton(
            onClick = { showReencryptConfirmDialog = true },
            enabled = selectedFolders.isNotEmpty(),
            colors = ButtonDefaults.filledTonalButtonColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
              contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("batch_reencrypt_button"),
          ) {
            Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Re-encrypt All (${selectedFolders.size}) • Rotate Keys",
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }

          Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
          ) {
            OutlinedButton(
              onClick = { showBatchFolderRenameDialog = true },
              enabled = selectedFolders.isNotEmpty(),
              modifier = Modifier
                .weight(1f)
                .testTag("batch_rename_folders_button"),
            ) {
              Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Rename (${selectedFolders.size})",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            OutlinedButton(
              onClick = {
                isRemovingBatchFolders = true
                pendingRemoveFolders = selectedFolders
              },
              enabled = selectedFolders.isNotEmpty(),
              colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
              ),
              modifier = Modifier
                .weight(1f)
                .testTag("batch_remove_folders_button"),
            ) {
              Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Remove (${selectedFolders.size})",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }

  if (showReencryptConfirmDialog && selectedFolders.isNotEmpty()) {
    ReencryptConfirmDialog(
      totalSelected = selectedFolders.size,
      lockedCount = lockedSelectedCount,
      unlockedCount = unlockedSelectedCount,
      onDismiss = { showReencryptConfirmDialog = false },
      onConfirm = { includeUnlocked ->
        showReencryptConfirmDialog = false
        onReencryptSelectedFolders(includeUnlocked)
      },
    )
  }

  if (showBatchFolderRenameDialog && selectedFolders.isNotEmpty()) {
    BatchFolderRenameDialog(
      selectedFolders = selectedFolders,
      allFolders = publicFolders,
      onDismiss = { showBatchFolderRenameDialog = false },
      onConfirm = { renameMap ->
        showBatchFolderRenameDialog = false
        onBatchRenameFolders(renameMap)
      },
    )
  }

  if (pendingRemoveFolders.isNotEmpty()) {
    ConfirmRemoveFoldersDialog(
      folders = pendingRemoveFolders,
      onDismiss = { pendingRemoveFolders = emptyList() },
      onConfirm = { decryptLockedSafFirst ->
        val targets = pendingRemoveFolders
        pendingRemoveFolders = emptyList()
        if (isRemovingBatchFolders) {
          onRemoveSelectedFolders(decryptLockedSafFirst)
        } else if (targets.size == 1) {
          onRemoveFolder(targets.first().id, decryptLockedSafFirst)
        }
      },
    )
  }

  state.pendingFolderPickerPreview?.let { preview ->
    FolderPickerPreviewSheet(
      preview = preview,
      onUpdateOptions = onUpdateFolderPickerOptions,
      onConfirm = onConfirmFolderPickerPreview,
      onDismiss = onDismissFolderPickerPreview,
    )
  }
}

@Composable
private fun StatPill(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = Color(0x88111822),
    shape = RoundedCornerShape(12.dp),
    modifier = modifier,
  ) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF94A3B8),
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = value,
        style = MaterialTheme.typography.titleSmall,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultFolderCard(
  folder: VaultFolderEntity,
  permissionStatus: UriPermissionStatus?,
  isSelectionMode: Boolean,
  isSelected: Boolean,
  onOpen: () -> Unit,
  onLongPress: () -> Unit,
  onToggleLock: () -> Unit,
  onRequestRemove: () -> Unit,
  onRegrantPermission: () -> Unit,
  onToggleAutoCloudSync: (Boolean) -> Unit,
) {
  val haptic = LocalHapticFeedback.current
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
        onClick = onOpen,
        onLongClick = {
          haptic.performHapticFeedback(HapticFeedbackType.LongPress)
          onLongPress()
        },
      )
      .testTag("folder_card_${folder.id}"),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
      ) {
        Surface(
          color = if (folder.isLocked) {
            MaterialTheme.colorScheme.secondaryContainer
          } else {
            MaterialTheme.colorScheme.primaryContainer
          },
          contentColor = if (folder.isLocked) {
            MaterialTheme.colorScheme.onSecondaryContainer
          } else {
            MaterialTheme.colorScheme.onPrimaryContainer
          },
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.size(48.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = if (folder.isLocked) Icons.Rounded.Lock else Icons.Rounded.Folder,
              contentDescription = null,
              modifier = Modifier.size(24.dp),
            )
          }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = folder.name,
              style = MaterialTheme.typography.titleMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(modifier = Modifier.width(8.dp))
            VaultStatusBadge(isLocked = folder.isLocked)
          }
          Spacer(modifier = Modifier.height(4.dp))
          val fileSummary = if (folder.isLocked && folder.fileCount > 0 && folder.encryptedFileCount < folder.fileCount) {
            "${folder.encryptedFileCount}/${folder.fileCount} encrypted"
          } else {
            "${folder.fileCount} files"
          }
          Text(
            text = "$fileSummary • ${formatByteSize(folder.totalBytes)} • ${if (folder.isInternalVault) "Private Vault" else "Device SAF"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(modifier = Modifier.height(2.dp))
          val syncLabel = if (folder.autoCloudBackupSync) "Cloud Auto-Sync" else "Local Only"
          Text(
            text = "${folder.cipherAlgorithm} • Key v${folder.keyVersion} • $syncLabel",
            style = MaterialTheme.typography.labelSmall,
            color = if (folder.autoCloudBackupSync) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.tertiary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (isSelectionMode) {
          Checkbox(
            checked = isSelected,
            onCheckedChange = { onOpen() },
            modifier = Modifier.testTag("folder_checkbox_${folder.id}"),
          )
        } else {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            FilledTonalIconButton(
              onClick = onToggleLock,
              modifier = Modifier.testTag("folder_toggle_lock_${folder.id}"),
            ) {
              Icon(
                imageVector = if (folder.isLocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                contentDescription = if (folder.isLocked) "Unlock folder" else "Lock folder",
              )
            }
            IconButton(
              onClick = onRequestRemove,
              modifier = Modifier.testTag("folder_remove_${folder.id}"),
            ) {
              Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "Remove ${folder.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      AnimatedVisibility(visible = permissionStatus?.needsRegrant == true && !folder.isInternalVault) {
        permissionStatus?.let { status ->
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
              .fillMaxWidth()
              .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
              .testTag("folder_permission_warning_${folder.id}"),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = status.label,
                modifier = Modifier.size(18.dp),
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = status.label,
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                )
                Text(
                  text = if (status == UriPermissionStatus.READ_ONLY) {
                    "Write grant missing — re-grant to encrypt or decrypt"
                  } else {
                    "SAF persistent folder grant lost — tap to restore"
                  },
                  style = MaterialTheme.typography.labelSmall,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              FilledTonalButton(
                onClick = onRegrantPermission,
                colors = ButtonDefaults.filledTonalButtonColors(
                  containerColor = MaterialTheme.colorScheme.error,
                  contentColor = MaterialTheme.colorScheme.onError,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier
                  .heightIn(min = 48.dp)
                  .testTag("folder_regrant_${folder.id}"),
              ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Re-grant", maxLines = 1)
              }
            }
          }
        }
      }

      AnimatedVisibility(visible = isSelectionMode) {
        Column(modifier = Modifier.fillMaxWidth()) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onToggleAutoCloudSync(!folder.autoCloudBackupSync) }
              .padding(horizontal = 16.dp, vertical = 6.dp),
          ) {
            Icon(
              imageVector = if (folder.autoCloudBackupSync) Icons.Rounded.CloudSync else Icons.Rounded.CloudOff,
              contentDescription = null,
              tint = if (folder.autoCloudBackupSync) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Auto Cloud Backup Sync",
                style = MaterialTheme.typography.labelLarge,
              )
              Text(
                text = if (folder.autoCloudBackupSync) {
                  "Included in automatic .vlkbak sync"
                } else {
                  "Excluded from automatic cloud sync"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = folder.autoCloudBackupSync,
              onCheckedChange = onToggleAutoCloudSync,
              modifier = Modifier.testTag("folder_cloud_sync_toggle_${folder.id}"),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ReencryptConfirmDialog(
  totalSelected: Int,
  lockedCount: Int,
  unlockedCount: Int,
  onDismiss: () -> Unit,
  onConfirm: (includeUnlocked: Boolean) -> Unit,
) {
  var includeUnlocked by remember(unlockedCount) { mutableStateOf(true) }
  val canProceed = lockedCount > 0 || includeUnlocked

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = Icons.Rounded.Key,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    title = { Text("Rotate Keys & Re-encrypt All?") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "Generates a brand-new 256-bit AES key wrapped by your Master Key and re-encrypts files with fresh random nonces.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(12.dp),
          ) {
            Text(
              text = "Selected: $totalSelected folder(s) ($lockedCount locked, $unlockedCount unlocked)",
              style = MaterialTheme.typography.labelMedium,
            )
            Text(
              text = "Previous .vlkbak cloud backups retain older key material. Create a fresh backup after key rotation.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        if (unlockedCount > 0) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Also encrypt & rotate $unlockedCount unlocked folder(s)",
                style = MaterialTheme.typography.titleSmall,
              )
              Text(
                text = "Turns off to rotate keys for already-locked folders only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = includeUnlocked,
              onCheckedChange = { includeUnlocked = it },
              modifier = Modifier.testTag("reencrypt_include_unlocked_toggle"),
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(includeUnlocked) },
        enabled = canProceed,
        modifier = Modifier.testTag("confirm_reencrypt_button"),
      ) {
        Text("Verify & Re-encrypt")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
    modifier = Modifier.testTag("reencrypt_confirm_dialog"),
  )
}

@Composable
private fun StorageUsagePreviewCard(
  encryptedBytes: Long,
  unencryptedBytes: Long,
  onClick: () -> Unit,
) {
  val total = (encryptedBytes + unencryptedBytes).coerceAtLeast(1L)
  val encFrac = (encryptedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
  val primaryColor = MaterialTheme.colorScheme.primary
  val secondaryColor = MaterialTheme.colorScheme.secondary
  val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    modifier = Modifier
      .fillMaxWidth()
      .clip(MaterialTheme.shapes.medium)
      .clickable(onClick = onClick)
      .testTag("dashboard_storage_charts_card"),
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(14.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Rounded.PieChart,
            contentDescription = null,
            tint = primaryColor,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Vault Storage Split", style = MaterialTheme.typography.titleSmall)
        }
        Text(
          text = "View Charts →",
          style = MaterialTheme.typography.labelMedium,
          color = primaryColor,
        )
      }

      androidx.compose.foundation.Canvas(
        modifier = Modifier
          .fillMaxWidth()
          .height(8.dp),
      ) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f)
        if (encryptedBytes + unencryptedBytes == 0L) {
          drawRoundRect(color = trackColor, size = size, cornerRadius = r)
        } else {
          drawRoundRect(color = secondaryColor, size = size, cornerRadius = r)
          if (encFrac > 0f) {
            drawRoundRect(
              color = primaryColor,
              size = androidx.compose.ui.geometry.Size(size.width * encFrac, size.height),
              cornerRadius = r,
            )
          }
        }
      }

      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = "Encrypted: ${formatByteSize(encryptedBytes)}",
          style = MaterialTheme.typography.labelSmall,
          color = primaryColor,
        )
        Text(
          text = "Unencrypted: ${formatByteSize(unencryptedBytes)}",
          style = MaterialTheme.typography.labelSmall,
          color = secondaryColor,
        )
      }
    }
  }
}
