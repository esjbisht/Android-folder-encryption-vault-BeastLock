package com.example.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.example.crypto.AesFolderCipher
import com.example.crypto.EncryptedFileMetadata
import com.example.data.audit.AuditCategory
import com.example.data.audit.EncryptedAuditLogRepository
import com.example.data.local.BackupRecordEntity
import com.example.data.local.VaultDao
import com.example.data.local.VaultFolderEntity
import com.example.data.preferences.SecurityPreferencesRepository
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CloudBackupVaultState(
  val deviceModel: String = "",
  val obfuscateFilenamesDefault: Boolean = true,
  val autoLockEnabled: Boolean = true,
  val autoLockTimeoutSeconds: Int = 30,
  val panicModeEnabled: Boolean = true,
  val batterySaverModeEnabled: Boolean = true,
)

@Serializable
data class CloudBackupManifest(
  val version: Int = 1,
  val createdAt: Long,
  val keyFingerprint: String,
  val pinSaltBase64: String,
  val pinVerifier: String,
  val pinWrappedDekBase64: String,
  val folders: List<CloudBackupFolderEntry>,
  val vaultState: CloudBackupVaultState? = null,
)

@Serializable
data class CloudBackupFolderEntry(
  val id: String,
  val name: String,
  val obfuscateFilenames: Boolean,
  val requireBiometricPerUnlock: Boolean,
  val files: List<CloudBackupFileEntry>,
  val wrappedFolderKey: String? = null,
  val keyVersion: Int = 1,
  val autoCloudBackupSync: Boolean = true,
)

@Serializable
data class CloudBackupFileEntry(
  val zipEntryPath: String,
  val originalFileName: String,
  val originalSize: Long,
)

data class CloudBackupProgress(
  val phase: String,
  val currentFile: String,
  val processedFiles: Int,
  val totalFiles: Int,
)

data class CloudBackupResult(
  val record: BackupRecordEntity,
  val skippedMissingFolders: Int,
)

data class CloudRestoreResult(
  val restoredFolders: Int,
  val restoredFiles: Int,
  val requiresBackupPin: Boolean = false,
)

data class DriveVaultBackupItem(
  val id: String,
  val name: String,
  val sizeBytes: Long,
  val modifiedTimeIso: String,
  val folderCount: Int,
  val fileCount: Int,
  val sha256Checksum: String,
  val deviceModel: String,
  val isAutoSyncArchive: Boolean,
)

/**
 * Creates and restores Zero-Knowledge Encrypted Cloud Backups (`.vlkbak`).
 *
 * Every file archived inside `.vlkbak` is AES-256-GCM encrypted (`VLK1` format)
 * prior to streaming to Google Drive API (`appDataFolder`), Dropbox, or SAF providers.
 */
class CloudBackupManager(
  private val context: Context,
  private val vaultDao: VaultDao,
  private val prefsRepo: SecurityPreferencesRepository,
  private val auditLogRepo: EncryptedAuditLogRepository,
  private val driveClient: GoogleDriveApiClient = GoogleDriveApiClient(),
) {
  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }

  private data class BackupSourceFile(
    val displayName: String,
    val isEncrypted: Boolean,
    val size: Long,
    val mimeType: String,
    val openStream: () -> InputStream?,
  )

  companion object {
    const val AUTO_SYNC_ARCHIVE_NAME = "BeastLock_AutoSync.vlkbak"
  }

  fun observeBackupRecords(): Flow<List<BackupRecordEntity>> = vaultDao.observeBackupRecords()

  suspend fun syncAutoBackupToTree(
    treeUri: Uri,
    providerLabel: String,
    dek: SecretKey,
    onProgress: (CloudBackupProgress) -> Unit = {},
  ): Result<CloudBackupResult?> = withContext(Dispatchers.IO) {
    runCatching {
      val syncFolders = vaultDao.getAllFoldersOnce().filter { it.autoCloudBackupSync }
      if (syncFolders.isEmpty()) {
        return@runCatching null
      }

      val targetUri: Uri = if (treeUri.scheme == "file" && !treeUri.path.isNullOrBlank()) {
        val dir = File(treeUri.path!!)
        if (!dir.exists()) dir.mkdirs()
        val outFile = File(dir, AUTO_SYNC_ARCHIVE_NAME)
        if (outFile.exists()) outFile.delete()
        outFile.createNewFile()
        Uri.fromFile(outFile)
      } else {
        val targetDirDoc = DocumentFile.fromTreeUri(context, treeUri)
          ?: error("Auto-sync cloud folder access unavailable")
        targetDirDoc.findFile(AUTO_SYNC_ARCHIVE_NAME)?.delete()
        val targetFileDoc = targetDirDoc.createFile("application/octet-stream", AUTO_SYNC_ARCHIVE_NAME)
          ?: error("Unable to create auto-sync backup archive")
        targetFileDoc.uri
      }

      val stagingFile = File(context.cacheDir, ".tmp_sync_${UUID.randomUUID()}.vlkbak")
      val result = try {
        val stagedResult = exportEncryptedBackup(
          destinationUri = Uri.fromFile(stagingFile),
          providerLabel = "$providerLabel Auto-Sync",
          dek = dek,
          folderIds = syncFolders.map { it.id }.toSet(),
          onProgress = onProgress,
        ).getOrThrow()

        val finalOut = if (targetUri.scheme == "file" && !targetUri.path.isNullOrBlank()) {
          File(targetUri.path!!).outputStream()
        } else {
          context.contentResolver.openOutputStream(targetUri)
            ?: error("Unable to open final auto-sync destination stream")
        }
        finalOut.use { output ->
          stagingFile.inputStream().buffered().use { input ->
            input.copyTo(output)
          }
        }
        val finalRecord = stagedResult.record.copy(
          fileName = AUTO_SYNC_ARCHIVE_NAME,
          destinationUri = targetUri.toString(),
        )
        vaultDao.insertBackupRecord(finalRecord)
        stagedResult.copy(record = finalRecord)
      } finally {
        if (stagingFile.exists()) stagingFile.delete()
      }

      prefsRepo.recordAutoBackupTimestamp(System.currentTimeMillis())
      result
    }
  }

  suspend fun fetchGoogleDriveAccountEmail(accessToken: String): String? = runCatching {
    driveClient.getUserInfo(accessToken)?.emailAddress
  }.getOrNull()

  suspend fun listGoogleDriveBackups(accessToken: String): Result<List<DriveVaultBackupItem>> =
    withContext(Dispatchers.IO) {
      runCatching {
        driveClient.listAppDataBackups(accessToken)
          .filter { it.name.endsWith(".vlkbak", ignoreCase = true) || it.appProperties["beastlock"] == "true" }
          .map { meta ->
            DriveVaultBackupItem(
              id = meta.id,
              name = meta.name,
              sizeBytes = meta.size?.toLongOrNull() ?: 0L,
              modifiedTimeIso = meta.modifiedTime.orEmpty(),
              folderCount = meta.appProperties["folderCount"]?.toIntOrNull() ?: 0,
              fileCount = meta.appProperties["fileCount"]?.toIntOrNull() ?: 0,
              sha256Checksum = meta.appProperties["sha256"].orEmpty(),
              deviceModel = meta.appProperties["deviceModel"].orEmpty(),
              isAutoSyncArchive = meta.name == AUTO_SYNC_ARCHIVE_NAME,
            )
          }
      }
    }

  suspend fun syncVaultToGoogleDrive(
    accessToken: String,
    dek: SecretKey,
    folderIds: Set<String>? = null,
    onlyAutoSyncEnabled: Boolean = false,
    archiveFileName: String = AUTO_SYNC_ARCHIVE_NAME,
    updateExistingAutoSync: Boolean = true,
    onProgress: (CloudBackupProgress) -> Unit = {},
  ): Result<CloudBackupResult?> = withContext(Dispatchers.IO) {
    runCatching {
      val allFolders = vaultDao.getAllFoldersOnce()
      val targetFolders = when {
        !folderIds.isNullOrEmpty() -> allFolders.filter { it.id in folderIds }
        onlyAutoSyncEnabled -> allFolders.filter { it.autoCloudBackupSync }
        else -> allFolders
      }
      if (targetFolders.isEmpty()) {
        return@runCatching null
      }

      val stagingFile = File(context.cacheDir, ".tmp_gdrive_sync_${UUID.randomUUID()}.vlkbak")
      try {
        val stagedResult = exportEncryptedBackup(
          destinationUri = Uri.fromFile(stagingFile),
          providerLabel = "Google Drive API",
          dek = dek,
          folderIds = targetFolders.map { it.id }.toSet(),
          recordAuditAndHistory = false,
          onProgress = onProgress,
        ).getOrThrow()

        onProgress(
          CloudBackupProgress(
            phase = "Uploading encrypted vault to Google Drive",
            currentFile = archiveFileName,
            processedFiles = stagedResult.record.fileCount,
            totalFiles = stagedResult.record.fileCount.coerceAtLeast(1),
          ),
        )

        val existingId = if (updateExistingAutoSync) {
          driveClient.listAppDataBackups(accessToken)
            .firstOrNull { it.name == archiveFileName }
            ?.id
        } else {
          null
        }

        val deviceLabel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        val uploadedMeta = driveClient.uploadEncryptedArchive(
          accessToken = accessToken,
          fileName = archiveFileName,
          archiveFile = stagingFile,
          existingFileId = existingId,
          appProperties = mapOf(
            "beastlock" to "true",
            "folderCount" to stagedResult.record.folderCount.toString(),
            "fileCount" to stagedResult.record.fileCount.toString(),
            "sha256" to stagedResult.record.sha256Checksum,
            "deviceModel" to deviceLabel.take(64),
          ),
        )

        val now = System.currentTimeMillis()
        val finalRecord = stagedResult.record.copy(
          providerName = "Google Drive API",
          fileName = uploadedMeta.name,
          destinationUri = "gdrive://appDataFolder/${uploadedMeta.id}",
          createdAt = now,
        )
        vaultDao.insertBackupRecord(finalRecord)
        prefsRepo.recordGoogleDriveSyncTimestamp(now)
        auditLogRepo.recordEvent(
          action = "GOOGLE_DRIVE_SYNC",
          folderName = "Google Drive",
          details = "Synced encrypted vault (${finalRecord.folderCount} folders, ${finalRecord.fileCount} files, SHA-256:${finalRecord.sha256Checksum}) to Drive appDataFolder",
          category = AuditCategory.CLOUD_SYNC,
        )
        stagedResult.copy(record = finalRecord)
      } finally {
        if (stagingFile.exists()) stagingFile.delete()
      }
    }
  }

  suspend fun restoreFromGoogleDrive(
    accessToken: String,
    driveFileId: String,
    fileName: String,
    currentDek: SecretKey,
    backupPinIfDifferentKey: String? = null,
    onProgress: (CloudBackupProgress) -> Unit = {},
  ): Result<CloudRestoreResult> = withContext(Dispatchers.IO) {
    runCatching {
      onProgress(
        CloudBackupProgress(
          phase = "Downloading encrypted archive from Google Drive",
          currentFile = fileName,
          processedFiles = 0,
          totalFiles = 1,
        ),
      )
      val tempArchive = File(context.cacheDir, ".tmp_gdrive_restore_${UUID.randomUUID()}.vlkbak")
      try {
        driveClient.downloadFileTo(accessToken, driveFileId, tempArchive)
        val result = restoreEncryptedBackup(
          backupUri = Uri.fromFile(tempArchive),
          currentDek = currentDek,
          backupPinIfDifferentKey = backupPinIfDifferentKey,
          onProgress = onProgress,
        ).getOrThrow()
        if (!result.requiresBackupPin) {
          auditLogRepo.recordEvent(
            action = "GOOGLE_DRIVE_RESTORE",
            folderName = "Google Drive",
            details = "Restored ${result.restoredFolders} encrypted folder(s) (${result.restoredFiles} files) from Google Drive backup \"$fileName\"",
            category = AuditCategory.CLOUD_SYNC,
          )
        }
        result
      } finally {
        if (tempArchive.exists()) tempArchive.delete()
      }
    }
  }

  suspend fun deleteGoogleDriveBackup(
    accessToken: String,
    driveFileId: String,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      driveClient.deleteFile(accessToken, driveFileId)
      auditLogRepo.recordEvent(
        action = "GOOGLE_DRIVE_DELETE",
        folderName = "Google Drive",
        details = "Removed encrypted cloud snapshot from Drive appDataFolder",
        category = AuditCategory.CLOUD_SYNC,
      )
    }
  }

  suspend fun exportEncryptedBackup(
    destinationUri: Uri,
    providerLabel: String,
    dek: SecretKey,
    folderIds: Set<String>? = null,
    recordAuditAndHistory: Boolean = true,
    onProgress: (CloudBackupProgress) -> Unit = {},
  ): Result<CloudBackupResult> = withContext(Dispatchers.IO) {
    runCatching {
      val allFolders = vaultDao.getAllFoldersOnce()
      val selectedFolders = if (folderIds.isNullOrEmpty()) {
        allFolders
      } else {
        allFolders.filter { it.id in folderIds }
      }
      require(selectedFolders.isNotEmpty()) { "Add at least one folder before creating a cloud backup" }

      val (pinSalt, pinVerifier, pinWrappedDek) = prefsRepo.getPinWrappedBackupCredentials()
        ?: error("Vault credentials not initialized")
      val fingerprint = AesFolderCipher.computeKeyFingerprint(dek)

      val folderSources = mutableMapOf<VaultFolderEntity, List<BackupSourceFile>>()
      var skippedFolders = 0
      for (folder in selectedFolders) {
        val sources = mutableListOf<BackupSourceFile>()
        if (folder.isInternalVault) {
          val dir = File(File(context.filesDir, "vaults"), folder.id)
          if (dir.exists()) {
            dir.walkTopDown()
              .filter { it.isFile && it.name != ".nomedia" && !it.name.startsWith(".tmp_") }
              .forEach { f ->
                sources.add(
                  BackupSourceFile(
                    displayName = f.name,
                    isEncrypted = f.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION),
                    size = f.length(),
                    mimeType = "application/octet-stream",
                    openStream = { f.inputStream() },
                  ),
                )
              }
          }
        } else {
          val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
          if (tree == null) {
            skippedFolders++
            continue
          }
          collectSafSources(tree, sources)
        }
        folderSources[folder] = sources
      }

      val totalFiles = folderSources.values.sumOf { it.size }
      var processedFiles = 0
      var totalArchivedBytes = 0L
      val manifestFolders = mutableListOf<CloudBackupFolderEntry>()

      val digest = MessageDigest.getInstance("SHA-256")
      val rawOut = openOutputStreamCompat(destinationUri)
        ?: error("Unable to open destination stream for cloud backup")

      rawOut.use { stream ->
        val digestOut = DigestOutputStream(BufferedOutputStream(stream), digest)
        ZipOutputStream(digestOut).use { zip ->
          for ((folder, files) in folderSources) {
            val folderKey = folder.wrappedFolderKey?.let {
              AesFolderCipher.unwrapDekWithKey(it, dek)
            } ?: dek
            val fileEntries = mutableListOf<CloudBackupFileEntry>()
            for (src in files) {
              onProgress(
                CloudBackupProgress(
                  phase = "Encrypting & uploading to $providerLabel",
                  currentFile = src.displayName,
                  processedFiles = processedFiles,
                  totalFiles = totalFiles,
                ),
              )
              val entryName = if (src.isEncrypted) {
                src.displayName
              } else {
                "${UUID.randomUUID()}${AesFolderCipher.ENCRYPTED_EXTENSION}"
              }
              val zipPath = "folders/${folder.id}/$entryName"
              zip.putNextEntry(ZipEntry(zipPath))
              src.openStream()?.use { input ->
                if (src.isEncrypted) {
                  val copied = input.copyTo(zip)
                  totalArchivedBytes += copied
                } else {
                  val plainBytes = AesFolderCipher.encryptStream(
                    input = input,
                    output = zip,
                    key = folderKey,
                    metadata = EncryptedFileMetadata(
                      originalName = src.displayName,
                      mimeType = src.mimeType,
                      originalSize = src.size,
                      encryptedAt = System.currentTimeMillis(),
                    ),
                  )
                  totalArchivedBytes += plainBytes
                }
              }
              zip.closeEntry()
              fileEntries.add(
                CloudBackupFileEntry(
                  zipEntryPath = zipPath,
                  originalFileName = src.displayName,
                  originalSize = src.size,
                ),
              )
              processedFiles++
            }
            manifestFolders.add(
              CloudBackupFolderEntry(
                id = folder.id,
                name = folder.name,
                obfuscateFilenames = folder.obfuscateFilenames,
                requireBiometricPerUnlock = folder.requireBiometricPerUnlock,
                files = fileEntries,
                wrappedFolderKey = folder.wrappedFolderKey,
                keyVersion = folder.keyVersion,
                autoCloudBackupSync = folder.autoCloudBackupSync,
              ),
            )
          }

          val currentSettings = runCatching { prefsRepo.securitySettings.first() }.getOrNull()
          val manifest = CloudBackupManifest(
            createdAt = System.currentTimeMillis(),
            keyFingerprint = fingerprint,
            pinSaltBase64 = pinSalt,
            pinVerifier = pinVerifier,
            pinWrappedDekBase64 = pinWrappedDek,
            folders = manifestFolders,
            vaultState = currentSettings?.let {
              CloudBackupVaultState(
                deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
                obfuscateFilenamesDefault = it.obfuscateFilenamesDefault,
                autoLockEnabled = it.autoLockEnabled,
                autoLockTimeoutSeconds = it.autoLockTimeoutSeconds,
                panicModeEnabled = it.panicModeEnabled,
                batterySaverModeEnabled = it.batterySaverModeEnabled,
              )
            },
          )
          zip.putNextEntry(ZipEntry("manifest.json"))
          zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
          zip.closeEntry()
          zip.finish()
        }
      }

      val checksum = digest.digest().take(12).joinToString("") { "%02x".format(it) }
      val destFileName = resolveDisplayName(destinationUri) ?: "BeastLock_Backup.vlkbak"
      val record = BackupRecordEntity(
        id = UUID.randomUUID().toString(),
        providerName = providerLabel,
        fileName = destFileName,
        destinationUri = destinationUri.toString(),
        folderCount = manifestFolders.size,
        fileCount = processedFiles,
        totalBytes = totalArchivedBytes,
        sha256Checksum = checksum,
      )
      if (recordAuditAndHistory) {
        vaultDao.insertBackupRecord(record)
        auditLogRepo.recordEvent(
          action = "CLOUD_BACKUP",
          folderName = providerLabel,
          details = "Uploaded encrypted backup (${manifestFolders.size} folders, $processedFiles files, SHA-256:$checksum)",
          category = AuditCategory.CLOUD_SYNC,
        )
      }
      CloudBackupResult(record = record, skippedMissingFolders = skippedFolders)
    }
  }

  suspend fun restoreEncryptedBackup(
    backupUri: Uri,
    currentDek: SecretKey,
    backupPinIfDifferentKey: String? = null,
    onProgress: (CloudBackupProgress) -> Unit = {},
  ): Result<CloudRestoreResult> = withContext(Dispatchers.IO) {
    runCatching {
      var manifest: CloudBackupManifest? = null
      openInputStreamCompat(backupUri)?.use { rawIn ->
        ZipInputStream(BufferedInputStream(rawIn)).use { zip ->
          var entry = zip.nextEntry
          while (entry != null) {
            if (entry.name == "manifest.json") {
              val baos = ByteArrayOutputStream()
              zip.copyTo(baos)
              manifest = json.decodeFromString<CloudBackupManifest>(baos.toString("UTF-8"))
              break
            }
            entry = zip.nextEntry
          }
        }
      } ?: error("Cannot open backup file from cloud provider")

      val resolvedManifest = manifest ?: error("Invalid .vlkbak archive: manifest.json missing")
      val currentFingerprint = AesFolderCipher.computeKeyFingerprint(currentDek)

      val backupDek: SecretKey = if (resolvedManifest.keyFingerprint == currentFingerprint) {
        currentDek
      } else {
        if (backupPinIfDifferentKey.isNullOrBlank()) {
          return@runCatching CloudRestoreResult(
            restoredFolders = 0,
            restoredFiles = 0,
            requiresBackupPin = true,
          )
        }
        val salt = Base64.decode(resolvedManifest.pinSaltBase64, Base64.NO_WRAP)
        val verifier = AesFolderCipher.computePinVerifier(backupPinIfDifferentKey, salt)
        if (verifier != resolvedManifest.pinVerifier) {
          auditLogRepo.recordEvent(
            action = "CLOUD_RESTORE_AUTH_FAILED",
            folderName = resolveDisplayName(backupUri) ?: "Cloud Backup",
            details = "Incorrect backup Master PIN entered during cloud restore",
            category = AuditCategory.FAILED_ATTEMPT,
            isWarning = true,
          )
          error("Backup Master PIN did not match archive")
        }
        val pinKey = AesFolderCipher.derivePinKey(backupPinIfDifferentKey, salt)
        AesFolderCipher.unwrapDekWithKey(resolvedManifest.pinWrappedDekBase64, pinKey)
      }

      val folderLookup = resolvedManifest.folders.associateBy { it.id }
      val totalFiles = resolvedManifest.folders.sumOf { it.files.size }
      var restoredFilesCount = 0
      val localFolderMap = mutableMapOf<String, String>()
      val folderBytesMap = mutableMapOf<String, Long>()
      val folderCountMap = mutableMapOf<String, Int>()
      val targetFolderKeys = mutableMapOf<String, SecretKey>()
      val restoredWrappedKeys = mutableMapOf<String, String?>()
      val restoredFingerprints = mutableMapOf<String, String>()

      for (folderEntry in resolvedManifest.folders) {
        val newId = UUID.randomUUID().toString()
        localFolderMap[folderEntry.id] = newId
        val dir = File(File(context.filesDir, "vaults"), newId)
        dir.mkdirs()
        File(dir, ".nomedia").createNewFile()

        if (backupDek == currentDek) {
          restoredWrappedKeys[newId] = folderEntry.wrappedFolderKey
          if (!folderEntry.wrappedFolderKey.isNullOrBlank()) {
            val fk = AesFolderCipher.unwrapDekWithKey(folderEntry.wrappedFolderKey, currentDek)
            targetFolderKeys[newId] = fk
            restoredFingerprints[newId] = AesFolderCipher.computeKeyFingerprint(fk)
          } else {
            targetFolderKeys[newId] = currentDek
            restoredFingerprints[newId] = ""
          }
        } else if (!folderEntry.wrappedFolderKey.isNullOrBlank()) {
          val newFolderKey = AesFolderCipher.generateAes256Key()
          targetFolderKeys[newId] = newFolderKey
          restoredWrappedKeys[newId] = AesFolderCipher.wrapDekWithKey(newFolderKey, currentDek)
          restoredFingerprints[newId] = AesFolderCipher.computeKeyFingerprint(newFolderKey)
        } else {
          targetFolderKeys[newId] = currentDek
          restoredWrappedKeys[newId] = null
          restoredFingerprints[newId] = ""
        }
      }

      openInputStreamCompat(backupUri)?.use { rawIn ->
        ZipInputStream(BufferedInputStream(rawIn)).use { zip ->
          var entry = zip.nextEntry
          while (entry != null) {
            val name = entry.name
            if (name.startsWith("folders/") && !entry.isDirectory) {
              val parts = name.removePrefix("folders/").split("/", limit = 2)
              if (parts.size == 2) {
                val backupFolderId = parts[0]
                val fileName = parts[1].substringAfterLast("/")
                val newFolderId = localFolderMap[backupFolderId]
                val metaEntry = folderLookup[backupFolderId]
                if (newFolderId != null && metaEntry != null) {
                  onProgress(
                    CloudBackupProgress(
                      phase = "Restoring encrypted vault",
                      currentFile = fileName,
                      processedFiles = restoredFilesCount,
                      totalFiles = totalFiles,
                    ),
                  )
                  val targetDir = File(File(context.filesDir, "vaults"), newFolderId)
                  val targetFile = File(targetDir, fileName)

                  if (backupDek == currentDek) {
                    targetFile.outputStream().use { out -> zip.copyTo(out) }
                  } else {
                    val sourceFolderKey = metaEntry.wrappedFolderKey?.let {
                      AesFolderCipher.unwrapDekWithKey(it, backupDek)
                    } ?: backupDek
                    val destFolderKey = targetFolderKeys[newFolderId] ?: currentDek
                    val cipherBytes = zip.readBytes()
                    val plainBaos = ByteArrayOutputStream()
                    val fileMeta = AesFolderCipher.decryptStream(
                      cipherBytes.inputStream(),
                      plainBaos,
                      sourceFolderKey,
                    )
                    targetFile.outputStream().use { out ->
                      AesFolderCipher.encryptStream(
                        plainBaos.toByteArray().inputStream(),
                        out,
                        destFolderKey,
                        fileMeta,
                      )
                    }
                  }
                  folderBytesMap[newFolderId] = (folderBytesMap[newFolderId] ?: 0L) + targetFile.length()
                  folderCountMap[newFolderId] = (folderCountMap[newFolderId] ?: 0) + 1
                  restoredFilesCount++
                }
              }
            }
            entry = zip.nextEntry
          }
        }
      }

      val now = System.currentTimeMillis()
      for (folderEntry in resolvedManifest.folders) {
        val newId = localFolderMap[folderEntry.id] ?: continue
        val count = folderCountMap[newId] ?: 0
        val bytes = folderBytesMap[newId] ?: 0L
        val restoredEntity = VaultFolderEntity(
          id = newId,
          name = "${folderEntry.name} (Restored)",
          treeUri = "app-internal://$newId",
          isInternalVault = true,
          isLocked = count > 0,
          fileCount = count,
          encryptedFileCount = count,
          totalBytes = bytes,
          encryptedBytes = bytes,
          unencryptedBytes = 0L,
          lastActionTimestamp = now,
          createdAt = now,
          obfuscateFilenames = folderEntry.obfuscateFilenames,
          requireBiometricPerUnlock = folderEntry.requireBiometricPerUnlock,
          wrappedFolderKey = restoredWrappedKeys[newId],
          keyVersion = folderEntry.keyVersion,
          keyFingerprint = restoredFingerprints[newId] ?: "",
          lastKeyRotatedAt = if (!restoredWrappedKeys[newId].isNullOrBlank()) now else 0L,
          autoCloudBackupSync = folderEntry.autoCloudBackupSync,
        )
        vaultDao.upsertFolder(restoredEntity)
      }

      auditLogRepo.recordEvent(
        action = "CLOUD_RESTORE",
        folderName = resolveDisplayName(backupUri) ?: "Cloud Backup",
        details = "Restored ${resolvedManifest.folders.size} encrypted folder(s) ($restoredFilesCount files)",
        category = AuditCategory.CLOUD_SYNC,
      )

      CloudRestoreResult(
        restoredFolders = resolvedManifest.folders.size,
        restoredFiles = restoredFilesCount,
        requiresBackupPin = false,
      )
    }
  }

  private fun collectSafSources(
    dir: DocumentFile,
    out: MutableList<BackupSourceFile>,
  ) {
    for (child in dir.listFiles()) {
      val name = child.name ?: continue
      if (name == ".nomedia") continue
      if (child.isDirectory) {
        collectSafSources(child, out)
      } else if (child.isFile) {
        val uri = child.uri
        out.add(
          BackupSourceFile(
            displayName = name,
            isEncrypted = name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION),
            size = child.length(),
            mimeType = child.type ?: "application/octet-stream",
            openStream = { context.contentResolver.openInputStream(uri) },
          ),
        )
      }
    }
  }

  private fun openInputStreamCompat(uri: Uri) =
    if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
      File(uri.path!!).inputStream()
    } else {
      context.contentResolver.openInputStream(uri)
    }

  private fun openOutputStreamCompat(uri: Uri) =
    if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
      File(uri.path!!).apply { parentFile?.mkdirs() }.outputStream()
    } else {
      context.contentResolver.openOutputStream(uri)
    }

  private fun resolveDisplayName(uri: Uri): String? {
    if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
      return File(uri.path!!).name
    }
    return runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx) else null
          } else null
        }
    }.getOrNull()
  }
}
