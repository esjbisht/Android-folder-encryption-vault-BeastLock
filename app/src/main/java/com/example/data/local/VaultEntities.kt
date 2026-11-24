package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vault_folders")
data class VaultFolderEntity(
  @PrimaryKey val id: String,
  val name: String,
  val treeUri: String,
  val isInternalVault: Boolean,
  val isLocked: Boolean,
  val fileCount: Int,
  val encryptedFileCount: Int,
  val totalBytes: Long,
  val encryptedBytes: Long = 0L,
  val unencryptedBytes: Long = 0L,
  val lastActionTimestamp: Long,
  val createdAt: Long,
  val cipherAlgorithm: String = "AES-256-GCM",
  val requireBiometricPerUnlock: Boolean = true,
  val obfuscateFilenames: Boolean = true,
  val wrappedFolderKey: String? = null,
  val keyVersion: Int = 1,
  val keyFingerprint: String = "",
  val lastKeyRotatedAt: Long = 0L,
  val autoCloudBackupSync: Boolean = true,
  val requireAuthOnOpen: Boolean = true,
  val isStealthHidden: Boolean = false,
)

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val folderId: String? = null,
  val folderName: String,
  val action: String,
  val details: String,
  val timestamp: Long = System.currentTimeMillis(),
  val isWarning: Boolean = false,
)

@Entity(tableName = "backup_records")
data class BackupRecordEntity(
  @PrimaryKey val id: String,
  val providerName: String,
  val fileName: String,
  val destinationUri: String,
  val folderCount: Int,
  val fileCount: Int,
  val totalBytes: Long,
  val sha256Checksum: String,
  val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "system_issue_reports")
data class SystemIssueReportEntity(
  @PrimaryKey val id: String,
  val occurredAtMillis: Long,
  val category: String,
  val errorCode: String,
  val sanitizedSummary: String,
  val healthSnapshot: String,
  val userFeedback: String = "",
  val appVersion: String,
  val androidSdk: Int,
  val deviceModel: String,
  val syncStatus: String = "PENDING",
  val cloudDocumentId: String? = null,
)
