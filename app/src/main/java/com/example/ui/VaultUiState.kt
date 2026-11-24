package com.example.ui

import android.net.Uri
import com.example.crypto.BiometricAvailability
import com.example.crypto.DualKeyMirrorStatus
import com.example.crypto.UriPermissionStatus
import com.example.data.audit.AuditLogSnapshot
import com.example.data.backup.CloudBackupProgress
import com.example.data.backup.DriveVaultBackupItem
import com.example.data.local.BackupRecordEntity
import com.example.data.local.SystemIssueReportEntity
import com.example.data.local.VaultFolderEntity
import com.example.data.preferences.SecuritySettings
import com.example.data.storage.EncryptionProgress
import com.example.data.storage.IntegrityDiagnosticReport
import com.example.data.storage.IntegrityScanProgress
import com.example.data.storage.MigrationProgress
import com.example.data.storage.MigrationResult
import com.example.data.storage.OrphanCleanupResult
import com.example.data.storage.OrphanedFileCandidate
import com.example.data.storage.SecurePreviewContent
import com.example.data.storage.VaultFileItem
import com.example.data.telemetry.CloudDiagnosticReport
import com.example.security.DevicePowerState
import kotlinx.serialization.Serializable

@Serializable
data object DashboardRoute

@Serializable
data object StorageAnalyticsRoute

@Serializable
data class FolderDetailRoute(val folderId: String)

@Serializable
data object CloudBackupRoute

@Serializable
data object AuditLogRoute

@Serializable
data object SecuritySettingsRoute

@Serializable
data object DiagnosticsSupportRoute

sealed interface VaultSessionState {
  data object Loading : VaultSessionState
  data object NeedsOnboarding : VaultSessionState
  data object Locked : VaultSessionState
  data object Unlocked : VaultSessionState
}

enum class FolderFilter(val label: String) {
  ALL("All"),
  LOCKED("Locked"),
  UNLOCKED("Unlocked"),
  PARTIAL("Partial"),
  REVOKED("Revoked"),
}

enum class FolderSortOrder(val label: String) {
  RECENT("Recent"),
  NAME_AZ("Name A–Z"),
  SIZE_DESC("Largest"),
  STATUS("Status"),
}

data class DeviceStorageInfo(
  val totalDeviceBytes: Long = 0L,
  val availableDeviceBytes: Long = 0L,
)

data class SelectiveLockPromptState(
  val folder: VaultFolderEntity,
  val plaintextFiles: List<VaultFileItem> = emptyList(),
  val alreadyEncryptedCount: Int = 0,
  val isLoading: Boolean = true,
  val reloadDetailAfterLock: Boolean = false,
)

data class PendingFolderPickerPreview(
  val treeUri: Uri,
  val displayName: String,
  val permissionStatus: UriPermissionStatus,
  val plaintextFileCount: Int,
  val encryptedFileCount: Int,
  val totalBytes: Long,
  val existingFolderId: String? = null,
  val obfuscateFilenames: Boolean = true,
  val requireAuthOnOpen: Boolean = true,
  val isScanning: Boolean = false,
)

fun VaultFolderEntity.isPartiallyEncrypted(): Boolean =
  fileCount > 0 && encryptedFileCount > 0 && encryptedFileCount < fileCount

fun matchesFolderSearchQuery(
  folder: VaultFolderEntity,
  rawQuery: String,
  permissionStatus: UriPermissionStatus? = null,
): Boolean {
  val trimmed = rawQuery.trim().lowercase()
  if (trimmed.isEmpty()) return true
  val folderNameLower = folder.name.lowercase()
  if (folderNameLower.contains(trimmed)) return true

  val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
  if (tokens.isEmpty()) return true

  val isPartial = folder.isPartiallyEncrypted()
  val isRevoked = !folder.isInternalVault && permissionStatus?.needsRegrant == true

  return tokens.all { token ->
    when {
      token == "status:locked" || token == "is:locked" ||
        token == "locked" || token == "sealed" || token == "encrypted" -> folder.isLocked
      token == "status:unlocked" || token == "is:unlocked" ||
        token == "unlocked" || token == "open" || token == "decrypted" || token == "unencrypted" -> !folder.isLocked
      token == "status:partial" || token == "is:partial" ||
        token == "partial" || token == "mixed" -> isPartial
      token == "status:revoked" || token == "is:revoked" ||
        token == "revoked" || token == "permission" || token == "regrant" -> isRevoked
      token == "saf" || token == "device" || token == "external" -> !folder.isInternalVault
      token == "private" || token == "internal" || token == "sandbox" -> folder.isInternalVault
      token.length >= 3 && (
        "locked".startsWith(token) || "sealed".startsWith(token) || "encrypted".startsWith(token)
        ) -> folder.isLocked || folderNameLower.contains(token)
      token.length >= 3 && (
        "unlocked".startsWith(token) || "decrypted".startsWith(token) || "unencrypted".startsWith(token)
        ) -> !folder.isLocked || folderNameLower.contains(token)
      token.length >= 3 && "partial".startsWith(token) ->
        isPartial || folderNameLower.contains(token)
      token.length >= 3 && "revoked".startsWith(token) ->
        isRevoked || folderNameLower.contains(token)
      else -> folderNameLower.contains(token)
    }
  }
}

data class VaultUiState(
  val sessionState: VaultSessionState = VaultSessionState.Loading,
  val securitySettings: SecuritySettings = SecuritySettings(),
  val biometricAvailability: BiometricAvailability = BiometricAvailability.UNAVAILABLE,
  val devicePowerState: DevicePowerState = DevicePowerState(),
  val folders: List<VaultFolderEntity> = emptyList(),
  val folderFilter: FolderFilter = FolderFilter.ALL,
  val folderSortOrder: FolderSortOrder = FolderSortOrder.RECENT,
  val searchQuery: String = "",
  val isSelectionMode: Boolean = false,
  val selectedFolderIds: Set<String> = emptySet(),
  val activeFolderFiles: List<VaultFileItem> = emptyList(),
  val isLoadingFiles: Boolean = false,
  val auditLogSnapshot: AuditLogSnapshot = AuditLogSnapshot(),
  val backupRecords: List<BackupRecordEntity> = emptyList(),
  val driveBackups: List<DriveVaultBackupItem> = emptyList(),
  val isDriveSyncBusy: Boolean = false,
  val pendingDriveRestoreItem: DriveVaultBackupItem? = null,
  val deviceStorageInfo: DeviceStorageInfo = DeviceStorageInfo(),
  val encryptionProgress: EncryptionProgress? = null,
  val busyFolderIds: Set<String> = emptySet(),
  val cloudBackupProgress: CloudBackupProgress? = null,
  val isAutoLockReencrypting: Boolean = false,
  val deferredAutoLockPending: Boolean = false,
  val isScanningOrphans: Boolean = false,
  val hasScannedOrphans: Boolean = false,
  val orphanCandidates: List<OrphanedFileCandidate> = emptyList(),
  val selectedOrphanIds: Set<String> = emptySet(),
  val isCleaningOrphans: Boolean = false,
  val lastOrphanCleanupResult: OrphanCleanupResult? = null,
  val isScanningIntegrity: Boolean = false,
  val integrityScanProgress: IntegrityScanProgress? = null,
  val integrityDiagnosticReport: IntegrityDiagnosticReport? = null,
  val migrationProgress: MigrationProgress? = null,
  val migrationResult: MigrationResult? = null,
  val securePreview: SecurePreviewContent? = null,
  val isPreviewLoading: Boolean = false,
  val selectiveLockPrompt: SelectiveLockPromptState? = null,
  val pendingRestoreUriRequiringPin: Uri? = null,
  val systemIssues: List<SystemIssueReportEntity> = emptyList(),
  val cloudSubmittedReports: List<CloudDiagnosticReport> = emptyList(),
  val firebaseUserUid: String? = null,
  val firebaseUserEmail: String? = null,
  val isSigningInFirebase: Boolean = false,
  val isSendingDiagnosticReport: Boolean = false,
  val dualKeyStatus: DualKeyMirrorStatus? = null,
  val isStealthRevealed: Boolean = false,
  val pendingStealthTriggerLabel: String? = null,
  val isStealthPickerInFlight: Boolean = false,
  val pendingFolderPickerPreview: PendingFolderPickerPreview? = null,
  val folderPermissionStatuses: Map<String, UriPermissionStatus> = emptyMap(),
  val authError: String? = null,
  val recoveryError: String? = null,
  val statusMessage: String? = null,
  val errorMessage: String? = null,
) {
  val pendingIssuesCount: Int get() = systemIssues.count { it.syncStatus == "PENDING" }

  fun isFolderBusy(folderId: String): Boolean =
    folderId in busyFolderIds ||
      (encryptionProgress?.folderId?.isNotEmpty() == true && encryptionProgress.folderId == folderId)

  fun isFileBusy(folderId: String, fileId: String, displayName: String? = null): Boolean {
    val progress = encryptionProgress ?: return false
    if (progress.folderId != folderId) return false
    if (progress.currentFileId.isNotEmpty() && progress.currentFileId == fileId) return true
    return !displayName.isNullOrBlank() && progress.currentFileName == displayName
  }

  val publicFolders: List<VaultFolderEntity>
    get() = folders.filter { !it.isStealthHidden }

  val stealthFolders: List<VaultFolderEntity>
    get() = if (isStealthRevealed) folders.filter { it.isStealthHidden } else emptyList()

  val analyticsVisibleFolders: List<VaultFolderEntity>
    get() = if (isStealthRevealed) folders else publicFolders

  fun isFolderPermissionRevoked(folder: VaultFolderEntity): Boolean =
    !folder.isInternalVault && folderPermissionStatuses[folder.id]?.needsRegrant == true

  fun matchesFilter(folder: VaultFolderEntity, filter: FolderFilter): Boolean = when (filter) {
    FolderFilter.ALL -> true
    FolderFilter.LOCKED -> folder.isLocked
    FolderFilter.UNLOCKED -> !folder.isLocked
    FolderFilter.PARTIAL -> folder.isPartiallyEncrypted()
    FolderFilter.REVOKED -> isFolderPermissionRevoked(folder)
  }

  fun countForFilter(filter: FolderFilter): Int =
    publicFolders.count { matchesFilter(it, filter) }

  val folderFilterCounts: Map<FolderFilter, Int>
    get() = FolderFilter.entries.associateWith { countForFilter(it) }

  val filteredFolders: List<VaultFolderEntity>
    get() {
      val matching = publicFolders.filter { folder ->
        matchesFilter(folder, folderFilter) &&
          matchesFolderSearchQuery(folder, searchQuery, folderPermissionStatuses[folder.id])
      }
      return when (folderSortOrder) {
        FolderSortOrder.RECENT -> matching.sortedByDescending { it.lastActionTimestamp }
        FolderSortOrder.NAME_AZ -> matching.sortedBy { it.name.lowercase() }
        FolderSortOrder.SIZE_DESC -> matching.sortedByDescending { it.totalBytes }
        FolderSortOrder.STATUS -> matching.sortedWith(
          compareBy<VaultFolderEntity> { folder ->
            when {
              isFolderPermissionRevoked(folder) -> 0
              folder.isPartiallyEncrypted() -> 1
              !folder.isLocked -> 2
              else -> 3
            }
          }.thenBy { it.name.lowercase() },
        )
      }
    }

  val lockedFoldersCount: Int get() = publicFolders.count { it.isLocked }
  val unlockedFoldersCount: Int get() = publicFolders.count { !it.isLocked }
  val partialFoldersCount: Int get() = publicFolders.count { it.isPartiallyEncrypted() }
  val revokedFoldersCount: Int get() = publicFolders.count { isFolderPermissionRevoked(it) }
  val totalEncryptedFiles: Int get() = publicFolders.sumOf { it.encryptedFileCount }
  val totalUnencryptedFiles: Int get() = publicFolders.sumOf { (it.fileCount - it.encryptedFileCount).coerceAtLeast(0) }
  val totalProtectedBytes: Long get() = publicFolders.sumOf { it.totalBytes }
  val totalOrphanBytes: Long get() = orphanCandidates.sumOf { it.sizeBytes }
  val selectedOrphanBytes: Long
    get() = orphanCandidates.filter { it.id in selectedOrphanIds }.sumOf { it.sizeBytes }
  val isBatterySaverBlockingTasks: Boolean
    get() = securitySettings.batterySaverModeEnabled && devicePowerState.isBatterySaverPaused

  val totalEncryptedBytes: Long
    get() = analyticsVisibleFolders.sumOf { folder ->
      when {
        folder.encryptedBytes > 0L || folder.unencryptedBytes > 0L -> folder.encryptedBytes
        folder.isLocked -> folder.totalBytes
        else -> 0L
      }
    }

  val totalUnencryptedBytes: Long
    get() = analyticsVisibleFolders.sumOf { folder ->
      when {
        folder.encryptedBytes > 0L || folder.unencryptedBytes > 0L -> folder.unencryptedBytes
        !folder.isLocked -> folder.totalBytes
        else -> 0L
      }
    }
}
