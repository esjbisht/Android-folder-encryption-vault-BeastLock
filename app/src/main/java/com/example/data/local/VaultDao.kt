package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
  @Query("SELECT * FROM vault_folders ORDER BY isLocked DESC, lastActionTimestamp DESC")
  fun observeFolders(): Flow<List<VaultFolderEntity>>

  @Query("SELECT * FROM vault_folders WHERE id = :folderId LIMIT 1")
  fun observeFolderById(folderId: String): Flow<VaultFolderEntity?>

  @Query("SELECT * FROM vault_folders WHERE id = :folderId LIMIT 1")
  suspend fun getFolderById(folderId: String): VaultFolderEntity?

  @Query("SELECT * FROM vault_folders WHERE treeUri = :treeUri LIMIT 1")
  suspend fun getFolderByUri(treeUri: String): VaultFolderEntity?

  @Query("SELECT * FROM vault_folders")
  suspend fun getAllFoldersOnce(): List<VaultFolderEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertFolder(folder: VaultFolderEntity)

  @Update
  suspend fun updateFolder(folder: VaultFolderEntity)

  @Query("DELETE FROM vault_folders WHERE id = :folderId")
  suspend fun deleteFolderById(folderId: String)

  @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT 200")
  fun observeAuditLogs(): Flow<List<AuditLogEntity>>

  @Insert
  suspend fun insertAuditLog(log: AuditLogEntity)

  @Query("DELETE FROM audit_logs")
  suspend fun clearAuditLogs()

  @Query("SELECT * FROM backup_records ORDER BY createdAt DESC LIMIT 50")
  fun observeBackupRecords(): Flow<List<BackupRecordEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertBackupRecord(record: BackupRecordEntity)
}
