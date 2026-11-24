package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.SystemIssueReportEntity
import com.example.data.telemetry.CloudDiagnosticReport
import com.example.data.telemetry.IssueCategory
import com.example.data.telemetry.SystemIssueRecorder
import com.example.ui.VaultUiState
import com.example.ui.components.VaultCircularProgressIndicator
import com.example.ui.components.formatTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSupportScreen(
  state: VaultUiState,
  onBack: () -> Unit,
  onSignInWithGoogle: () -> Unit,
  onSignOut: () -> Unit,
  onSendReport: (userFeedback: String, selectedIssueIds: Set<String>) -> Unit,
  onClearSentHistory: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var userFeedback by remember { mutableStateOf("") }
  var showSanitizedPreview by remember { mutableStateOf(false) }

  val pendingIssues = remember(state.systemIssues) {
    state.systemIssues.filter { it.syncStatus == "PENDING" }
  }
  val sentLocalIssues = remember(state.systemIssues) {
    state.systemIssues.filter { it.syncStatus == "SENT" }
  }

  var selectedIssueIds by remember { mutableStateOf<Set<String>>(emptySet()) }
  LaunchedEffect(pendingIssues) {
    val validPendingIds = pendingIssues.map { it.id }.toSet()
    selectedIssueIds = if (selectedIssueIds.isEmpty()) {
      validPendingIds
    } else {
      (selectedIssueIds intersect validPendingIds).ifEmpty { validPendingIds }
    }
  }

  val isSignedIn = !state.firebaseUserUid.isNullOrBlank()

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("Diagnostics & Feedback", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
              text = "${pendingIssues.size} pending issue(s) • User-controlled cloud submission",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("diagnostics_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = "Back",
            )
          }
        },
      )
    },
    modifier = modifier.fillMaxSize(),
  ) { innerPadding ->
    Box(
      contentAlignment = Alignment.TopCenter,
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .navigationBarsPadding(),
    ) {
      LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
          .widthIn(max = 760.dp)
          .fillMaxSize(),
      ) {
        // Privacy guarantee banner
        item(key = "privacy_guarantee") {
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              verticalAlignment = Alignment.Top,
              modifier = Modifier.padding(14.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.PrivacyTip,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text(
                text = "Nothing is sent silently. PINs, 3×3 Patterns, security answers, folder/file names, and encryption keys are NEVER included. Only sanitized error codes, device SDK info, and feedback you choose to send are saved to our database.",
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }

        // Google Account Sign-In card for authenticated cloud writes
        item(key = "google_auth_card") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.AccountCircle,
                contentDescription = null,
                tint = if (isSignedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
              )
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = if (isSignedIn) "Cloud Database Connected" else "Sign In to Send Reports",
                  style = MaterialTheme.typography.titleSmall,
                )
                Text(
                  text = state.firebaseUserEmail?.takeIf { it.isNotBlank() }
                    ?: "Google Sign-In authenticates your diagnostic submissions & custom question suggestions.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Spacer(modifier = Modifier.width(8.dp))
              if (state.isSigningInFirebase) {
                VaultCircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
              } else if (isSignedIn) {
                OutlinedButton(
                  onClick = onSignOut,
                  modifier = Modifier.testTag("diagnostics_google_signout_button"),
                ) {
                  Text("Sign Out")
                }
              } else {
                FilledTonalButton(
                  onClick = onSignInWithGoogle,
                  modifier = Modifier.testTag("diagnostics_google_signin_button"),
                ) {
                  Text("Google Sign-In")
                }
              }
            }
          }
        }

        // User feedback composer + sanitized payload preview
        item(key = "feedback_composer_card") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              verticalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.padding(16.dp),
            ) {
              Text("Express Feedback or Report a Problem", style = MaterialTheme.typography.titleSmall)
              OutlinedTextField(
                value = userFeedback,
                onValueChange = { if (it.length <= 2000) userFeedback = it },
                label = { Text("Describe any issue, suggestion, or feature request") },
                placeholder = { Text("e.g. App locked smoothly, would love quicker folder batch selection...") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("diagnostics_feedback_input"),
              )

              Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
              ) {
                TextButton(
                  onClick = { showSanitizedPreview = !showSanitizedPreview },
                  modifier = Modifier.testTag("diagnostics_preview_payload_button"),
                ) {
                  Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(if (showSanitizedPreview) "Hide Sanitized Preview" else "Preview Sanitized Payload")
                }
                Text(
                  text = "${userFeedback.length}/2000",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              AnimatedVisibility(visible = showSanitizedPreview) {
                Surface(
                  color = MaterialTheme.colorScheme.surfaceContainerHigh,
                  shape = MaterialTheme.shapes.small,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(12.dp),
                  ) {
                    Text("Sanitized Payload Preview", style = MaterialTheme.typography.labelLarge)
                    Text(
                      text = "• Lock Mode: ${state.securitySettings.primaryLockType.name} • Biometrics=${state.securitySettings.biometricEnabled}\n" +
                        "• Dual Key Checksum Valid=${state.securitySettings.dualKeyChecksumValid}\n" +
                        "• Selected Pending Failures: ${selectedIssueIds.size}\n" +
                        "• Feedback: ${SystemIssueRecorder.sanitizeDiagnosticText(userFeedback.ifBlank { "(none entered yet)" })}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }

              Button(
                onClick = { onSendReport(userFeedback, selectedIssueIds) },
                enabled = !state.isSendingDiagnosticReport && (selectedIssueIds.isNotEmpty() || userFeedback.isNotBlank()),
                modifier = Modifier
                  .fillMaxWidth()
                  .height(50.dp)
                  .testTag("diagnostics_send_report_button"),
              ) {
                if (state.isSendingDiagnosticReport) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text("Uploading to Database...")
                } else {
                  Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(8.dp))
                  val countLabel = if (selectedIssueIds.isNotEmpty()) {
                    "Send Report (${selectedIssueIds.size} failure + feedback)"
                  } else {
                    "Send Feedback to Database"
                  }
                  Text(countLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }
            }
          }
        }

        // Recorded Local System Failures Header
        item(key = "local_issues_header") {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = "Recorded System Failures (${pendingIssues.size} pending)",
              style = MaterialTheme.typography.titleMedium,
            )
            if (sentLocalIssues.isNotEmpty()) {
              TextButton(
                onClick = onClearSentHistory,
                modifier = Modifier.testTag("diagnostics_clear_sent_button"),
              ) {
                Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear Sent")
              }
            }
          }
        }

        if (state.systemIssues.isEmpty()) {
          item(key = "no_local_issues") {
            Card(
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp),
              ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                  text = "No crashes or storage failures recorded locally. You can still write feedback above anytime.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        } else {
          items(state.systemIssues, key = { it.id }) { issue ->
            LocalSystemIssueCard(
              issue = issue,
              selected = issue.id in selectedIssueIds,
              onToggleSelect = {
                if (issue.syncStatus == "PENDING") {
                  selectedIssueIds = if (issue.id in selectedIssueIds) {
                    selectedIssueIds - issue.id
                  } else {
                    selectedIssueIds + issue.id
                  }
                }
              },
            )
          }
        }

        if (state.cloudSubmittedReports.isNotEmpty()) {
          item(key = "cloud_history_header") {
            Text(
              text = "Submitted Reports in Database (${state.cloudSubmittedReports.size})",
              style = MaterialTheme.typography.titleMedium,
            )
          }
          items(state.cloudSubmittedReports, key = { it.id.ifBlank { "${it.occurredAtMillis}_${it.errorCode}" } }) { report ->
            CloudSubmittedReportCard(report = report)
          }
        }
      }
    }
  }
}

@Composable
private fun LocalSystemIssueCard(
  issue: SystemIssueReportEntity,
  selected: Boolean,
  onToggleSelect: () -> Unit,
) {
  val isPending = issue.syncStatus == "PENDING"
  val categoryLabel = IssueCategory.entries.firstOrNull { it.wireValue == issue.category }?.displayLabel
    ?: issue.category
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    border = if (isPending && selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    modifier = Modifier
      .fillMaxWidth()
      .testTag("local_issue_card_${issue.id}"),
  ) {
    Row(
      verticalAlignment = Alignment.Top,
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
    ) {
      if (isPending) {
        Checkbox(
          checked = selected,
          onCheckedChange = { onToggleSelect() },
        )
      } else {
        Icon(
          imageVector = Icons.Rounded.CloudDone,
          contentDescription = "Sent",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(10.dp),
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Surface(
            color = if (isPending) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (isPending) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(50),
          ) {
            Text(
              text = "${issue.errorCode} • ${issue.syncStatus}",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
          }
          Text(
            text = formatTimestamp(issue.occurredAtMillis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          text = categoryLabel,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = issue.sanitizedSummary,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 4,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun CloudSubmittedReportCard(report: CloudDiagnosticReport) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(4.dp),
      modifier = Modifier.padding(14.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Rounded.BugReport,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "${report.category} • ${report.errorCode}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }
        Text(
          text = "SDK ${report.androidSdk}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (report.userFeedback.isNotBlank()) {
        Text(
          text = "\"${report.userFeedback}\"",
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Medium,
        )
      }
      Text(
        text = report.sanitizedSummary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
