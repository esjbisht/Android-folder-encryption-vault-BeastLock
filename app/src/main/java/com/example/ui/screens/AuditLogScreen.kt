package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.audit.AuditCategory
import com.example.data.audit.AuditExportFormat
import com.example.data.audit.AuditExportScope
import com.example.data.audit.AuditLogSnapshot
import com.example.ui.components.formatByteSize
import com.example.ui.components.formatTimestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AuditFilter(val label: String) {
  ALL("All"),
  FAILED("Failed Attempts"),
  ACCESS("Access Events"),
  CRYPTO("Crypto & Cloud"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuditLogScreen(
  snapshot: AuditLogSnapshot,
  onExportReport: (uri: Uri, format: AuditExportFormat, scope: AuditExportScope) -> Unit,
  onClearLogs: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var selectedFilter by remember { mutableStateOf(AuditFilter.ALL) }
  var searchQuery by remember { mutableStateOf("") }
  var showClearConfirm by remember { mutableStateOf(false) }
  var showExportDialog by remember { mutableStateOf(false) }
  var pendingExportScope by remember { mutableStateOf(AuditExportScope.ALL) }

  val csvExportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/csv"),
  ) { uri ->
    if (uri != null) onExportReport(uri, AuditExportFormat.CSV, pendingExportScope)
  }

  val txtExportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/plain"),
  ) { uri ->
    if (uri != null) onExportReport(uri, AuditExportFormat.TEXT, pendingExportScope)
  }

  val filteredEntries = remember(snapshot.entries, selectedFilter, searchQuery) {
    snapshot.entries.filter { entry ->
      val matchesFilter = when (selectedFilter) {
        AuditFilter.ALL -> true
        AuditFilter.FAILED -> entry.category == AuditCategory.FAILED_ATTEMPT || entry.isWarning
        AuditFilter.ACCESS -> entry.category == AuditCategory.ACCESS_EVENT
        AuditFilter.CRYPTO -> entry.category == AuditCategory.CRYPTO_OPERATION || entry.category == AuditCategory.CLOUD_SYNC
      }
      val matchesQuery = searchQuery.isBlank() ||
        entry.action.contains(searchQuery, ignoreCase = true) ||
        entry.folderName.contains(searchQuery, ignoreCase = true) ||
        entry.details.contains(searchQuery, ignoreCase = true)
      matchesFilter && matchesQuery
    }
  }

  Box(
    contentAlignment = Alignment.TopCenter,
    modifier = modifier.fillMaxSize(),
  ) {
    LazyColumn(
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier
        .widthIn(max = 760.dp)
        .fillMaxSize(),
    ) {
      item(key = "audit_header") {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Security Audit Log", style = MaterialTheme.typography.headlineMedium)
            Text(
              text = "Encrypted local file • SHA-256 hash-chained",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (snapshot.entries.isNotEmpty()) {
              TextButton(
                onClick = { showExportDialog = true },
                modifier = Modifier.testTag("export_audit_logs_button"),
              ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Export CSV")
              }
              TextButton(
                onClick = { showClearConfirm = true },
                modifier = Modifier.testTag("clear_audit_logs_button"),
              ) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear")
              }
            }
          }
        }
      }

      // Encrypted Audit File Posture Card
      item(key = "encrypted_file_banner") {
        Card(
          colors = CardDefaults.cardColors(
            containerColor = if (snapshot.isIntegrityVerified) {
              MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
            } else {
              MaterialTheme.colorScheme.errorContainer
            },
            contentColor = if (snapshot.isIntegrityVerified) {
              MaterialTheme.colorScheme.onPrimaryContainer
            } else {
              MaterialTheme.colorScheme.onErrorContainer
            },
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("audit_encrypted_file_banner"),
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
                Icon(
                  imageVector = if (snapshot.isIntegrityVerified) Icons.Rounded.EnhancedEncryption else Icons.Rounded.Warning,
                  contentDescription = null,
                  tint = if (snapshot.isIntegrityVerified) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                  } else {
                    MaterialTheme.colorScheme.onErrorContainer
                  },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "${snapshot.encryptedFileName} • AES-256-GCM",
                  style = MaterialTheme.typography.labelLarge,
                  color = if (snapshot.isIntegrityVerified) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                  } else {
                    MaterialTheme.colorScheme.onErrorContainer
                  },
                )
              }
              Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = RoundedCornerShape(50),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.VerifiedUser,
                    contentDescription = null,
                    tint = if (snapshot.isIntegrityVerified) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(13.dp),
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text(
                    text = if (snapshot.isIntegrityVerified) "CHAIN VERIFIED" else "TAMPER ALERT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                  )
                }
              }
            }

            Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              AuditStatPill(
                label = "FILE SIZE",
                value = formatByteSize(snapshot.encryptedFileBytes),
                modifier = Modifier.weight(1f),
              )
              AuditStatPill(
                label = "FAILED ATTEMPTS",
                value = "${snapshot.failedAttemptsCount}",
                modifier = Modifier.weight(1f),
              )
              AuditStatPill(
                label = "ACCESS EVENTS",
                value = "${snapshot.accessEventsCount}",
                modifier = Modifier.weight(1f),
              )
            }
          }
        }
      }

      // Search & Filter Controls
      if (snapshot.entries.isNotEmpty()) {
        item(key = "audit_filters") {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              placeholder = { Text("Filter audit events...") },
              leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
              singleLine = true,
              shape = MaterialTheme.shapes.medium,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("audit_search_input"),
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              AuditFilter.entries.forEach { filter ->
                FilterChip(
                  selected = selectedFilter == filter,
                  onClick = { selectedFilter = filter },
                  label = { Text(filter.label) },
                  modifier = Modifier.testTag("audit_filter_${filter.name.lowercase()}"),
                )
              }
            }
          }
        }
      }

      if (filteredEntries.isEmpty()) {
        item(key = "empty_logs") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = if (snapshot.entries.isEmpty()) "No Security Events Logged" else "No Matching Events",
                style = MaterialTheme.typography.titleMedium,
              )
            }
          }
        }
      } else {
        items(filteredEntries, key = { "${it.id}_${it.timestamp}" }) { log ->
          Card(
            colors = CardDefaults.cardColors(
              containerColor = if (log.isWarning || log.category == AuditCategory.FAILED_ATTEMPT) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
              } else {
                MaterialTheme.colorScheme.surfaceContainer
              },
              contentColor = if (log.isWarning || log.category == AuditCategory.FAILED_ATTEMPT) {
                MaterialTheme.colorScheme.onErrorContainer
              } else {
                MaterialTheme.colorScheme.onSurface
              },
            ),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("audit_entry_${log.id}"),
          ) {
            Row(
              verticalAlignment = Alignment.Top,
              modifier = Modifier.padding(14.dp),
            ) {
              Surface(
                color = if (log.isWarning || log.category == AuditCategory.FAILED_ATTEMPT) {
                  MaterialTheme.colorScheme.errorContainer
                } else {
                  MaterialTheme.colorScheme.primaryContainer
                },
                contentColor = if (log.isWarning || log.category == AuditCategory.FAILED_ATTEMPT) {
                  MaterialTheme.colorScheme.onErrorContainer
                } else {
                  MaterialTheme.colorScheme.onPrimaryContainer
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(38.dp),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = if (log.isWarning || log.category == AuditCategory.FAILED_ATTEMPT) {
                      Icons.Rounded.Warning
                    } else {
                      Icons.Rounded.Shield
                    },
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                  )
                }
              }
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Row(
                  horizontalArrangement = Arrangement.SpaceBetween,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Text(
                    text = log.action,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (log.isWarning || log.category == AuditCategory.FAILED_ATTEMPT) {
                      MaterialTheme.colorScheme.error
                    } else {
                      MaterialTheme.colorScheme.primary
                    },
                  )
                  Text(
                    text = formatTimestamp(log.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Text(text = log.folderName, style = MaterialTheme.typography.titleSmall)
                Text(
                  text = log.details,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = "#${log.id} • SHA-256 ${log.entryHash.take(12)}…",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
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

  if (showExportDialog) {
    AuditExportOptionsDialog(
      totalCount = snapshot.entries.size,
      failedCount = snapshot.failedAttemptsCount,
      initialScope = if (selectedFilter == AuditFilter.FAILED) {
        AuditExportScope.FAILED_ATTEMPTS_ONLY
      } else {
        AuditExportScope.ALL
      },
      onDismiss = { showExportDialog = false },
      onConfirmExport = { format, scope ->
        showExportDialog = false
        pendingExportScope = scope
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val prefix = if (scope == AuditExportScope.FAILED_ATTEMPTS_ONLY) {
          "BeastLock_Failed_Logins"
        } else {
          "BeastLock_Audit"
        }
        val fileName = "${prefix}_$stamp.${format.extension}"
        when (format) {
          AuditExportFormat.CSV -> csvExportLauncher.launch(fileName)
          AuditExportFormat.TEXT -> txtExportLauncher.launch(fileName)
        }
      },
    )
  }

  if (showClearConfirm) {
    AlertDialog(
      onDismissRequest = { showClearConfirm = false },
      title = { Text("Rotate Encrypted Audit Log?") },
      text = {
        Text("This clears previous entries in vault_audit.log.enc and records a fresh AUDIT_LOG_CLEARED entry.")
      },
      confirmButton = {
        Button(
          onClick = {
            showClearConfirm = false
            onClearLogs()
          },
          modifier = Modifier.testTag("confirm_clear_audit_logs"),
        ) {
          Text("Rotate & Clear")
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
      },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuditExportOptionsDialog(
  totalCount: Int,
  failedCount: Int,
  initialScope: AuditExportScope,
  onDismiss: () -> Unit,
  onConfirmExport: (AuditExportFormat, AuditExportScope) -> Unit,
) {
  var chosenScope by remember { mutableStateOf(initialScope) }
  var chosenFormat by remember { mutableStateOf(AuditExportFormat.CSV) }
  val canExport = when (chosenScope) {
    AuditExportScope.ALL -> totalCount > 0
    AuditExportScope.FAILED_ATTEMPTS_ONLY -> failedCount > 0
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = Icons.Rounded.Download,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    title = { Text("Export Encrypted Audit Log") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = "Decrypts vault_audit.log.enc in memory and saves a computer-readable file directly to external or shared storage.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Events to Include", style = MaterialTheme.typography.labelLarge)
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            FilterChip(
              selected = chosenScope == AuditExportScope.ALL,
              onClick = { chosenScope = AuditExportScope.ALL },
              label = { Text("All Events ($totalCount)") },
              modifier = Modifier.testTag("export_scope_all"),
            )
            FilterChip(
              selected = chosenScope == AuditExportScope.FAILED_ATTEMPTS_ONLY,
              onClick = { chosenScope = AuditExportScope.FAILED_ATTEMPTS_ONLY },
              label = { Text("Failed Attempts Only ($failedCount)") },
              modifier = Modifier.testTag("export_scope_failed"),
            )
          }
          if (chosenScope == AuditExportScope.FAILED_ATTEMPTS_ONLY && failedCount == 0) {
            Text(
              text = "No failed login or tamper warning events recorded yet.",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("File Format", style = MaterialTheme.typography.labelLarge)
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            FilterChip(
              selected = chosenFormat == AuditExportFormat.CSV,
              onClick = { chosenFormat = AuditExportFormat.CSV },
              label = { Text("CSV Spreadsheet (.csv)") },
              modifier = Modifier.testTag("export_format_csv"),
            )
            FilterChip(
              selected = chosenFormat == AuditExportFormat.TEXT,
              onClick = { chosenFormat = AuditExportFormat.TEXT },
              label = { Text("Text Report (.txt)") },
              modifier = Modifier.testTag("export_format_txt"),
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirmExport(chosenFormat, chosenScope) },
        enabled = canExport,
        modifier = Modifier.testTag("audit_export_confirm_button"),
      ) {
        Text("Save ${chosenFormat.extension.uppercase()}")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
    modifier = Modifier.testTag("audit_export_dialog"),
  )
}

@Composable
private fun AuditStatPill(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
    shape = RoundedCornerShape(10.dp),
    modifier = modifier,
  ) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = value,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}
