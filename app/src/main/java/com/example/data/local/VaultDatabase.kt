package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [
    VaultFolderEntity::class,
    AuditLogEntity::class,
    BackupRecordEntity::class,
    SystemIssueReportEntity::class,
  ],
  version = 6,
  exportSchema = false,
)
abstract class VaultDatabase : RoomDatabase() {
  abstract fun vaultDao(): VaultDao

  companion object {
    @Volatile
    private var INSTANCE: VaultDatabase? = null

    private val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vault_folders ADD COLUMN encryptedBytes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE vault_folders ADD COLUMN unencryptedBytes INTEGER NOT NULL DEFAULT 0")
      }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vault_folders ADD COLUMN wrappedFolderKey TEXT")
        db.execSQL("ALTER TABLE vault_folders ADD COLUMN keyVersion INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE vault_folders ADD COLUMN keyFingerprint TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE vault_folders ADD COLUMN lastKeyRotatedAt INTEGER NOT NULL DEFAULT 0")
      }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vault_folders ADD COLUMN autoCloudBackupSync INTEGER NOT NULL DEFAULT 1")
      }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vault_folders ADD COLUMN requireAuthOnOpen INTEGER NOT NULL DEFAULT 1")
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `system_issue_reports` (
            `id` TEXT NOT NULL,
            `occurredAtMillis` INTEGER NOT NULL,
            `category` TEXT NOT NULL,
            `errorCode` TEXT NOT NULL,
            `sanitizedSummary` TEXT NOT NULL,
            `healthSnapshot` TEXT NOT NULL,
            `userFeedback` TEXT NOT NULL,
            `appVersion` TEXT NOT NULL,
            `androidSdk` INTEGER NOT NULL,
            `deviceModel` TEXT NOT NULL,
            `syncStatus` TEXT NOT NULL,
            `cloudDocumentId` TEXT,
            PRIMARY KEY(`id`)
          )
          """.trimIndent(),
        )
      }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vault_folders ADD COLUMN isStealthHidden INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE vault_folders SET isStealthHidden = 1 WHERE isInternalVault = 1")
      }
    }

    fun getInstance(context: Context): VaultDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          VaultDatabase::class.java,
          "vaultlock_db",
        )
          .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
          .build()
          .also { INSTANCE = it }
      }
    }
  }
}
