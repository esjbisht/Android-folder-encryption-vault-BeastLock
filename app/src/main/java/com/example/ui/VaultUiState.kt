package com.example.ui

import android.net.Uri
import com.example.crypto.BiometricAvailability
import com.example.data.audit.AuditLogSnapshot
import com.example.data.backup.CloudBackupProgress
import com.example.data.backup.DriveVaultBackupItem
import com.example.data.local.BackupRecordEntity
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

fun matchesFolderSearchQuery(folder: VaultFolderEntity, rawQuery: String): Boolean {
  val trimmed = rawQuery.trim().lowercase()
  if (trimmed.isEmpty()) return true
  val folderNameLower = folder.name.lowercase()
  if (folderNameLower.contains(trimmed)) return true

  val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
  if (tokens.isEmpty()) return true

  return tokens.all { token ->
    when {
      token == "status:locked" || token == "is:locked" ||
        token == "locked" || token == "sealed" || token == "encrypted" -> folder.isLocked
      token == "status:unlocked" || token == "is:unlocked" ||
        token == "unlocked" || token == "open" || token == "decrypted" || token == "unencrypted" -> !folder.isLocked
      token.length >= 3 && (
        "locked".startsWith(token) || "sealed".startsWith(token) || "encrypted".startsWith(token)
        ) -> folder.isLocked || folderNameLower.contains(token)
      token.length >= 3 && (
        "unlocked".startsWith(token) || "decrypted".startsWith(token) || "unencrypted".startsWith(token)
        ) -> !folder.isLocked || folderNameLower.contains(token)
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
  val cloudBackupProgress: CloudBackupProgress? = null,
  val isAutoLockReencrypting: Boolean = false,
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
  val authError: String? = null,
  val recoveryError: String? = null,
  val statusMessage: String? = null,
  val errorMessage: String? = null,
) {
  val filteredFolders: List<VaultFolderEntity>
    get() = folders.filter { folder ->
      val matchesFilter = when (folderFilter) {
        FolderFilter.ALL -> true
        FolderFilter.LOCKED -> folder.isLocked
        FolderFilter.UNLOCKED -> !folder.isLocked
      }
      matchesFilter && matchesFolderSearchQuery(folder, searchQuery)
    }

  val lockedFoldersCount: Int get() = folders.count { it.isLocked }
  val unlockedFoldersCount: Int get() = folders.count { !it.isLocked }
  val totalEncryptedFiles: Int get() = folders.sumOf { it.encryptedFileCount }
  val totalUnencryptedFiles: Int get() = folders.sumOf { (it.fileCount - it.encryptedFileCount).coerceAtLeast(0) }
  val totalProtectedBytes: Long get() = folders.sumOf { it.totalBytes }
  val totalOrphanBytes: Long get() = orphanCandidates.sumOf { it.sizeBytes }
  val selectedOrphanBytes: Long
    get() = orphanCandidates.filter { it.id in selectedOrphanIds }.sumOf { it.sizeBytes }
  val isBatterySaverBlockingTasks: Boolean
    get() = securitySettings.batterySaverModeEnabled && devicePowerState.isBatterySaverPaused

  val totalEncryptedBytes: Long
    get() = folders.sumOf { folder ->
      when {
        folder.encryptedBytes > 0L || folder.unencryptedBytes > 0L -> folder.encryptedBytes
        folder.isLocked -> folder.totalBytes
        else -> 0L
      }
    }

  val totalUnencryptedBytes: Long
    get() = folders.sumOf { folder ->
      when {
        folder.encryptedBytes > 0L || folder.unencryptedBytes > 0L -> folder.unencryptedBytes
        !folder.isLocked -> folder.totalBytes
        else -> 0L
      }
    }
}
