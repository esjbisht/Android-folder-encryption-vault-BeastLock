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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.VaultFolderEntity
import com.example.ui.theme.AmberVault
import com.example.ui.theme.AmberVaultContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StealthVaultDrawerSheet(
  stealthFolders: List<VaultFolderEntity>,
  onOpenFolder: (String) -> Unit,
  onCreateStealthFolder: (String) -> Unit,
  onRemoveFolder: (folderId: String) -> Unit,
  onConcealVault: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var showCreateDialog by remember { mutableStateOf(false) }
  var pendingRemoveFolder by remember { mutableStateOf<VaultFolderEntity?>(null) }

  ModalBottomSheet(
    onDismissRequest = onConcealVault,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.testTag("stealth_drawer_sheet"),
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 18.dp)
        .padding(bottom = 18.dp),
    ) {
      // Stealth Vault Security Header
      Surface(
        color = AmberVaultContainer.copy(alpha = 0.72f),
        contentColor = AmberVault,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, AmberVault.copy(alpha = 0.45f)),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.VisibilityOff,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "AES-256-GCM • SCREENSHOTS BLOCKED • .NOMEDIA",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
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
            text = "Stealth Private Vault",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = "${stealthFolders.size} hidden folder(s) • Re-encrypts & vanishes on exit",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Button(
          onClick = { showCreateDialog = true },
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
          modifier = Modifier
            .weight(1f)
            .testTag("stealth_create_folder_button"),
        ) {
          Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("New Hidden Folder", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledTonalButton(
          onClick = onConcealVault,
          colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          ),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
          modifier = Modifier.testTag("stealth_conceal_button"),
        ) {
          Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Conceal & Lock", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }

      if (stealthFolders.isEmpty()) {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
              .fillMaxWidth()
              .padding(24.dp),
          ) {
            Surface(
              color = AmberVaultContainer,
              contentColor = AmberVault,
              shape = RoundedCornerShape(16.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                modifier = Modifier
                  .padding(14.dp)
                  .size(28.dp),
              )
            }
            Text(
              text = "No Hidden Stealth Folders Yet",
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              text = "Folders created here are stored inside a hidden .nomedia sandbox, always AES-256-GCM encrypted, omitted from Dashboard & Analytics, and protected against screenshots.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
          }
        }
      } else {
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
        ) {
          items(stealthFolders, key = { it.id }) { folder ->
            Card(
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable { onOpenFolder(folder.id) }
                .testTag("stealth_folder_card_${folder.id}"),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(14.dp),
              ) {
                Surface(
                  color = AmberVaultContainer,
                  contentColor = AmberVault,
                  shape = MaterialTheme.shapes.small,
                  modifier = Modifier.size(44.dp),
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector = Icons.Rounded.VerifiedUser,
                      contentDescription = null,
                      modifier = Modifier.size(22.dp),
                    )
                  }
                }
                Spacer(modifier = Modifier.width(12.dp))
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
                  Spacer(modifier = Modifier.heightIn(min = 2.dp))
                  Text(
                    text = "${folder.fileCount} files • ${formatByteSize(folder.totalBytes)} • Always-Encrypted (.nomedia)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    text = "UUID Obfuscation • Key v${folder.keyVersion} • Screenshots Blocked",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                IconButton(
                  onClick = { pendingRemoveFolder = folder },
                  modifier = Modifier.testTag("stealth_folder_remove_${folder.id}"),
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
        }
      }
    }
  }

  if (showCreateDialog) {
    CreateStealthFolderDialog(
      onDismiss = { showCreateDialog = false },
      onCreate = { folderName ->
        showCreateDialog = false
        onCreateStealthFolder(folderName)
      },
    )
  }

  pendingRemoveFolder?.let { folder ->
    ConfirmRemoveFoldersDialog(
      folders = listOf(folder),
      onDismiss = { pendingRemoveFolder = null },
      onConfirm = { _ ->
        pendingRemoveFolder = null
        onRemoveFolder(folder.id)
      },
    )
  }
}

@Composable
private fun CreateStealthFolderDialog(
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
) {
  var name by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = Icons.Rounded.VisibilityOff,
        contentDescription = null,
        tint = AmberVault,
      )
    },
    title = { Text("New Stealth Hidden Folder") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "Creates an always-encrypted hidden folder inside .beastlock_private with .nomedia shielding, UUID filenames, screenshot blocking, and immediate auto-re-encryption on exit.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Hidden Folder Name") },
          placeholder = { Text("e.g. Private Documents") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("stealth_create_folder_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onCreate(name.ifBlank { "Private Vault" }) },
        modifier = Modifier.testTag("stealth_create_folder_confirm"),
      ) {
        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Create Hidden")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}
