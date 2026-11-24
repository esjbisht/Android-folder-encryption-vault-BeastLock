package com.example.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.example.crypto.AesFolderCipher
import com.example.crypto.DualKeyVaultManager
import com.example.crypto.EncryptedFileMetadata
import com.example.crypto.EncryptionManager
import com.example.crypto.UriPermissionStatus
import com.example.data.audit.AuditCategory
import com.example.data.audit.EncryptedAuditLogRepository
import com.example.data.local.AuditLogEntity
import com.example.data.local.VaultDao
import com.example.data.local.VaultFolderEntity
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class VaultFileItem(
  val id: String,
  val displayName: String,
  val rawFileName: String,
  val sizeBytes: Long,
  val mimeType: String,
  val isEncrypted: Boolean,
  val uriString: String,
  val lastModified: Long,
  val relativeSubPath: String = "",
  val folderId: String = "",
)

data class EncryptionProgress(
  val folderName: String,
  val isLocking: Boolean,
  val currentFileName: String,
  val processedFiles: Int,
  val totalFiles: Int,
  val processedBytes: Long,
  val totalBytes: Long,
  val folderId: String = "",
  val currentFileId: String = "",
  val currentFileProcessedBytes: Long = 0L,
  val currentFileTotalBytes: Long = 0L,
  val isStealthMasked: Boolean = false,
) {
  val currentFileFraction: Float
    get() = when {
      currentFileTotalBytes > 0L ->
        (currentFileProcessedBytes.toFloat() / currentFileTotalBytes.toFloat()).coerceIn(0f, 1f)
      totalFiles > 0 && processedFiles >= totalFiles -> 1f
      else -> 0f
    }

  val overallFraction: Float
    get() = when {
      totalBytes > 0L ->
        ((processedBytes + currentFileProcessedBytes).coerceAtMost(totalBytes).toFloat() / totalBytes.toFloat())
          .coerceIn(0f, 1f)
      totalFiles > 0 ->
        (processedFiles.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
      else -> 0f
    }
}

data class BatchFolderResult(
  val succeededCount: Int,
  val failedCount: Int,
  val totalFiles: Int,
  val totalBytes: Long,
  val firstError: String? = null,
  val skippedCount: Int = 0,
)

data class ImportFilesResult(
  val importedCount: Int,
  val movedWithoutCopyCount: Int,
  val readOnlySourceCount: Int,
)

data class PickedFolderInspection(
  val treeUri: Uri,
  val displayName: String,
  val permissionStatus: UriPermissionStatus,
  val plaintextCount: Int,
  val encryptedCount: Int,
  val totalBytes: Long,
  val existingFolderId: String?,
)

data class DecryptAnyFolderResult(
  val folderName: String,
  val decryptedFiles: Int,
  val decryptedBytes: Long,
  val alreadyPlainFiles: Int,
  val wasRegisteredInVault: Boolean,
)

sealed interface RotateFolderOutcome {
  data class Rotated(
    val folder: VaultFolderEntity,
    val rotatedFileCount: Int,
    val rotatedBytes: Long,
  ) : RotateFolderOutcome

  data object SkippedUnlocked : RotateFolderOutcome
}

sealed interface SecurePreviewContent {
  data class ImagePreview(
    val fileName: String,
    val bytes: ByteArray,
    val mimeType: String,
    val isEncryptedOnDisk: Boolean,
  ) : SecurePreviewContent

  data class TextPreview(
    val fileName: String,
    val text: String,
    val isEncryptedOnDisk: Boolean,
  ) : SecurePreviewContent

  data class BinarySummary(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256Prefix: String,
    val isEncryptedOnDisk: Boolean,
  ) : SecurePreviewContent
}

class FolderLockerRepository(
  private val context: Context,
  private val vaultDao: VaultDao,
  private val auditLogRepo: EncryptedAuditLogRepository? = null,
  private val dualKeyVaultManager: DualKeyVaultManager? = null,
  private val externalTreeUriProvider: () -> String = { "" },
  private val encryptionManager: EncryptionManager = EncryptionManager(
    context = context,
    dualKeyVaultManager = dualKeyVaultManager,
    externalTreeUriProvider = externalTreeUriProvider,
  ),
) {
  companion object {
    private const val INTERNAL_URI_PREFIX = "app-internal://"
    private const val NOMEDIA_FILE = ".nomedia"
    private const val STEALTH_SANDBOX_DIR = ".beastlock_private"
    private const val LEGACY_SANDBOX_DIR = "vaults"
    private const val STEALTH_AUDIT_ALIAS = "Stealth Private Vault"
  }

  private class ProgressThrottle(
    private val onProgress: (EncryptionProgress) -> Unit,
  ) {
    private var lastEmitAt = 0L
    private var lastFileKey = ""

    fun emit(progress: EncryptionProgress, force: Boolean = false) {
      val now = System.currentTimeMillis()
      val fileKey = "${progress.folderId}|${progress.currentFileId}|${progress.currentFileName}|${progress.processedFiles}"
      if (force || fileKey != lastFileKey || now - lastEmitAt >= 75L) {
        lastEmitAt = now
        lastFileKey = fileKey
        onProgress(progress)
      }
    }
  }

  private fun safeAuditName(folder: VaultFolderEntity): String =
    if (folder.isStealthHidden) STEALTH_AUDIT_ALIAS else folder.name

  private fun safeAuditId(folder: VaultFolderEntity): String? =
    if (folder.isStealthHidden) null else folder.id

  fun observeFolders(): Flow<List<VaultFolderEntity>> = vaultDao.observeFolders()

  fun observeFolder(folderId: String): Flow<VaultFolderEntity?> = vaultDao.observeFolderById(folderId)

  fun observeAuditLogs(): Flow<List<AuditLogEntity>> = vaultDao.observeAuditLogs()

  suspend fun logEvent(
    folderId: String? = null,
    folderName: String,
    action: String,
    details: String,
    category: AuditCategory? = null,
    isWarning: Boolean = false,
  ) {
    if (auditLogRepo != null) {
      auditLogRepo.recordEvent(
        action = action,
        folderName = folderName,
        details = details,
        folderId = folderId,
        category = category ?: when {
          isWarning || action.contains("FAILED") -> AuditCategory.FAILED_ATTEMPT
          action.startsWith("CLOUD_") -> AuditCategory.CLOUD_SYNC
          action in setOf(
            "LOCKED",
            "UNLOCKED",
            "AUTO_LOCK_REENCRYPT",
            "FILE_IMPORTED",
            "PIN_CHANGED",
            "RECOVERY_UPDATED",
            "FOLDER_KEY_ROTATED",
            "BATCH_KEY_ROTATED",
            "FILES_BATCH_RENAMED",
            "FOLDERS_BATCH_RENAMED",
            "FILES_ENCRYPTED",
            "FILE_DECRYPTED",
            "ANY_FOLDER_DECRYPTED",
            "FILES_REMOVED",
            "FOLDERS_BATCH_REMOVED",
          ) ->
            AuditCategory.CRYPTO_OPERATION
          else -> AuditCategory.ACCESS_EVENT
        },
        isWarning = isWarning,
      )
    } else {
      vaultDao.insertAuditLog(
        AuditLogEntity(
          folderId = folderId,
          folderName = folderName,
          action = action,
          details = details,
          isWarning = isWarning,
        ),
      )
    }
  }

  suspend fun clearAuditLogs() {
    if (auditLogRepo != null) {
      auditLogRepo.clearLogs()
    } else {
      vaultDao.clearAuditLogs()
    }
  }

  fun resolveFolderKey(folder: VaultFolderEntity, masterDek: SecretKey): SecretKey {
    val wrapped = folder.wrappedFolderKey
    return if (wrapped.isNullOrBlank()) {
      masterDek
    } else {
      encryptionManager.unwrapFolderKey(wrapped, masterDek)
    }
  }

  suspend fun resolveFolderKeyWithRecovery(folder: VaultFolderEntity, masterDek: SecretKey): SecretKey {
    return encryptionManager.resolveFolderKey(folder, masterDek) { key, rec ->
      val fp = rec.keyFingerprint.ifBlank { encryptionManager.computeKeyFingerprint(key) }
      vaultDao.upsertFolder(
        folder.copy(
          wrappedFolderKey = rec.wrappedFolderKey,
          keyVersion = rec.keyVersion,
          keyFingerprint = fp,
        ),
      )
      logEvent(
        folderId = safeAuditId(folder),
        folderName = safeAuditName(folder),
        action = "FOLDER_KEY_RECOVERED",
        details = "Recovered missing or damaged folder key (v${rec.keyVersion}) from Dual Key Vault",
        category = AuditCategory.CRYPTO_OPERATION,
      )
    }
  }

  suspend fun inspectPickedSafFolder(treeUri: Uri): Result<PickedFolderInspection> = withContext(Dispatchers.IO) {
    runCatching {
      encryptionManager.takePersistableFolderPermission(treeUri)
      val permStatus = encryptionManager.checkTreeUriPermission(treeUri, allowTransientFallback = true)
      val docTree = resolveTreeDocumentFile(treeUri)
        ?: error("Unable to open selected folder")
      val displayName = docTree.name?.takeIf { it.isNotBlank() } ?: "Protected Folder"
      val stats = inspectSafStats(docTree)
      val existing = vaultDao.getFolderByUri(treeUri.toString())
      PickedFolderInspection(
        treeUri = treeUri,
        displayName = displayName,
        permissionStatus = permStatus,
        plaintextCount = stats.plainCount,
        encryptedCount = stats.encryptedCount,
        totalBytes = stats.totalBytes,
        existingFolderId = existing?.id,
      )
    }
  }

  suspend fun auditFolderPermissions(folders: List<VaultFolderEntity>): Map<String, UriPermissionStatus> =
    withContext(Dispatchers.IO) {
      folders.associate { folder ->
        folder.id to encryptionManager.checkFolderUriPermission(folder)
      }
    }

  suspend fun regrantSafFolderPermission(
    folderId: String,
    treeUri: Uri,
  ): Result<VaultFolderEntity> = withContext(Dispatchers.IO) {
    runCatching {
      val folder = vaultDao.getFolderById(folderId) ?: error("Folder not found")
      require(!folder.isInternalVault) { "Private sandbox folders do not require SAF re-grant" }
      encryptionManager.takePersistableFolderPermission(treeUri)
      val permStatus = encryptionManager.checkTreeUriPermission(treeUri, allowTransientFallback = true)
      if (!permStatus.isWritable) {
        error("Selected folder only granted read-only or unavailable access. Please allow full folder access.")
      }
      val newUriString = treeUri.toString()
      val existingConflict = vaultDao.getFolderByUri(newUriString)
      if (existingConflict != null && existingConflict.id != folder.id) {
        error("That folder is already registered as \"${existingConflict.name}\"")
      }
      val docTree = resolveTreeDocumentFile(treeUri) ?: error("Unable to open selected folder")
      val resolvedName = if (newUriString != folder.treeUri) {
        docTree.name?.takeIf { it.isNotBlank() } ?: folder.name
      } else {
        folder.name
      }
      val updatedBase = folder.copy(
        name = resolvedName,
        treeUri = newUriString,
        lastActionTimestamp = System.currentTimeMillis(),
      )
      val refreshed = refreshFolderMetricsInternal(updatedBase, dek = null)
      logEvent(
        folderId = safeAuditId(refreshed),
        folderName = safeAuditName(refreshed),
        action = "PERMISSION_REGRANTED",
        details = "Re-acquired ${permStatus.label} SAF folder access permission",
        category = AuditCategory.ACCESS_EVENT,
      )
      refreshed
    }
  }

  suspend fun registerSafFolder(
    treeUri: Uri,
    obfuscateDefault: Boolean,
    requireBioDefault: Boolean = true,
  ): Result<VaultFolderEntity> = withContext(Dispatchers.IO) {
    runCatching {
      encryptionManager.takePersistableFolderPermission(treeUri)

      val uriString = treeUri.toString()
      val existing = vaultDao.getFolderByUri(uriString)
      if (existing != null) {
        return@runCatching refreshFolderMetricsInternal(existing, dek = null)
      }

      val docTree = resolveTreeDocumentFile(treeUri)
        ?: error("Unable to open selected folder")
      val displayName = docTree.name?.takeIf { it.isNotBlank() } ?: "Protected Folder"
      val stats = inspectSafStats(docTree)
      val now = System.currentTimeMillis()
      val entity = VaultFolderEntity(
        id = UUID.randomUUID().toString(),
        name = displayName,
        treeUri = uriString,
        isInternalVault = false,
        isLocked = stats.encryptedCount > 0 && stats.plainCount == 0,
        fileCount = stats.plainCount + stats.encryptedCount,
        encryptedFileCount = stats.encryptedCount,
        totalBytes = stats.totalBytes,
        encryptedBytes = stats.encryptedBytes,
        unencryptedBytes = stats.unencryptedBytes,
        lastActionTimestamp = now,
        createdAt = now,
        obfuscateFilenames = obfuscateDefault,
        requireBiometricPerUnlock = requireBioDefault,
      )
      vaultDao.upsertFolder(entity)
      logEvent(
        folderId = entity.id,
        folderName = entity.name,
        action = "FOLDER_ADDED",
        details = "Registered device folder (${entity.fileCount} files detected)",
      )
      entity
    }
  }

  suspend fun createInternalVaultFolder(
    folderName: String,
    obfuscateDefault: Boolean,
    requireBioDefault: Boolean = true,
    dek: SecretKey? = null,
    stealthHidden: Boolean = true,
  ): Result<VaultFolderEntity> = withContext(Dispatchers.IO) {
    runCatching {
      val cleanName = folderName.trim().ifBlank { "Private Vault" }
      val id = UUID.randomUUID().toString()
      val sandboxRoot = File(context.filesDir, STEALTH_SANDBOX_DIR)
      if (!sandboxRoot.exists()) sandboxRoot.mkdirs()
      File(sandboxRoot, NOMEDIA_FILE).apply { if (!exists()) createNewFile() }
      val dir = File(sandboxRoot, id)
      if (!dir.exists() && !dir.mkdirs()) {
        error("Could not create private vault directory")
      }
      File(dir, NOMEDIA_FILE).apply { if (!exists()) createNewFile() }

      val folderKey = if (dek != null) encryptionManager.generateFolderKey() else null
      val wrappedKey = if (dek != null && folderKey != null) {
        encryptionManager.wrapFolderKey(folderKey, dek)
      } else null
      val keyFp = if (folderKey != null) encryptionManager.computeKeyFingerprint(folderKey) else ""

      val now = System.currentTimeMillis()
      val entity = VaultFolderEntity(
        id = id,
        name = cleanName,
        treeUri = "$INTERNAL_URI_PREFIX$id",
        isInternalVault = true,
        isLocked = true,
        fileCount = 0,
        encryptedFileCount = 0,
        totalBytes = 0L,
        lastActionTimestamp = now,
        createdAt = now,
        obfuscateFilenames = if (stealthHidden) true else obfuscateDefault,
        requireBiometricPerUnlock = if (stealthHidden) true else requireBioDefault,
        wrappedFolderKey = wrappedKey,
        keyVersion = 1,
        keyFingerprint = keyFp,
        requireAuthOnOpen = true,
        isStealthHidden = stealthHidden,
      )
      vaultDao.upsertFolder(entity)
      logEvent(
        folderId = safeAuditId(entity),
        folderName = safeAuditName(entity),
        action = "FOLDER_ADDED",
        details = if (stealthHidden) {
          "Created always-encrypted hidden stealth private folder"
        } else {
          "Created sandboxed private vault folder"
        },
      )
      entity
    }
  }

  suspend fun migrateLegacyPrivateVaultStorage(dek: SecretKey? = null): Int = withContext(Dispatchers.IO) {
    val sandboxRoot = File(context.filesDir, STEALTH_SANDBOX_DIR)
    if (!sandboxRoot.exists()) sandboxRoot.mkdirs()
    File(sandboxRoot, NOMEDIA_FILE).apply { if (!exists()) createNewFile() }

    val legacyRoot = File(context.filesDir, LEGACY_SANDBOX_DIR)
    if (legacyRoot.exists()) {
      legacyRoot.listFiles().orEmpty().forEach { child ->
        if (child.isDirectory) {
          val dest = File(sandboxRoot, child.name)
          if (!dest.exists()) {
            if (!child.renameTo(dest)) {
              child.copyRecursively(dest, overwrite = true)
              child.deleteRecursively()
            }
          } else {
            child.listFiles().orEmpty().forEach { f ->
              val destFile = File(dest, f.name)
              if (!destFile.exists()) {
                if (!f.renameTo(destFile)) {
                  f.copyTo(destFile, overwrite = true)
                  f.delete()
                }
              }
            }
            child.deleteRecursively()
          }
          File(dest, NOMEDIA_FILE).apply { if (!exists()) createNewFile() }
        }
      }
      if (legacyRoot.listFiles().orEmpty().isEmpty()) {
        legacyRoot.delete()
      }
    }

    var sealedCount = 0
    val allFolders = vaultDao.getAllFoldersOnce()
    for (folder in allFolders) {
      if (folder.isInternalVault) {
        val dir = internalFolderDir(folder.id)
        if (!dir.exists()) dir.mkdirs()
        File(dir, NOMEDIA_FILE).apply { if (!exists()) createNewFile() }

        val upgraded = if (!folder.isStealthHidden || !folder.obfuscateFilenames || !folder.requireAuthOnOpen) {
          folder.copy(
            isStealthHidden = true,
            obfuscateFilenames = true,
            requireAuthOnOpen = true,
          ).also { vaultDao.upsertFolder(it) }
        } else {
          folder
        }
        if (dek != null) {
          val stats = inspectInternalStats(dir)
          if (stats.plainCount > 0 || !upgraded.isLocked) {
            lockFolder(upgraded.id, dek).onSuccess { sealedCount++ }
          }
        }
      }
    }
    sealedCount
  }

  suspend fun concealAndResealPrivateFolders(dek: SecretKey?): Int = withContext(Dispatchers.IO) {
    if (dek == null) return@withContext 0
    var sealed = 0
    val privateFolders = vaultDao.getAllFoldersOnce().filter { it.isStealthHidden || it.isInternalVault }
    for (folder in privateFolders) {
      val dir = if (folder.isInternalVault) internalFolderDir(folder.id) else null
      val hasPlaintextOnDisk = if (dir != null && dir.exists()) {
        inspectInternalStats(dir).plainCount > 0
      } else {
        folder.encryptedFileCount < folder.fileCount
      }
      if (!folder.isLocked || hasPlaintextOnDisk) {
        lockFolder(folder.id, dek).onSuccess { sealed++ }
      }
    }
    sealed
  }

  suspend fun importFilesIntoFolder(
    folderId: String,
    uris: List<Uri>,
    dek: SecretKey?,
  ): Result<Int> = importFilesWithReport(
    folderId = folderId,
    uris = uris,
    dek = dek,
    moveOriginals = false,
  ).map { it.importedCount }

  suspend fun importFilesWithReport(
    folderId: String,
    uris: List<Uri>,
    dek: SecretKey?,
    moveOriginals: Boolean = true,
  ): Result<ImportFilesResult> = withContext(Dispatchers.IO) {
    runCatching {
      val folder = vaultDao.getFolderById(folderId) ?: error("Folder not found")
      val folderKey = dek?.let { resolveFolderKeyWithRecovery(folder, it) }
      var imported = 0
      var movedCount = 0
      var readOnlyCount = 0
      for (sourceUri in uris) {
        val (fileName, mimeType, knownSize) = queryUriMetadata(sourceUri)
        val inputStream = context.contentResolver.openInputStream(sourceUri) ?: continue
        var writeCompleted = false
        inputStream.use { input ->
          if (folder.isInternalVault) {
            val dir = internalFolderDir(folder.id)
            if (!dir.exists()) dir.mkdirs()
            if ((folder.isLocked || folder.isStealthHidden) && folderKey != null) {
              val targetName = if (folder.obfuscateFilenames || folder.isStealthHidden) {
                encryptionManager.generateObfuscatedCipherName()
              } else {
                uniqueLocalFile(dir, "$fileName${AesFolderCipher.ENCRYPTED_EXTENSION}").name
              }
              val targetFile = File(dir, targetName)
              val tempOut = File(dir, ".tmp_${UUID.randomUUID()}")
              if (knownSize != null && knownSize >= 0L) {
                // Direct single-pass stream into encrypted container without staging copy
                try {
                  tempOut.outputStream().use { out ->
                    encryptionManager.encryptStream(
                      input = input,
                      output = out,
                      key = folderKey,
                      metadata = EncryptedFileMetadata(
                        originalName = fileName,
                        mimeType = mimeType,
                        originalSize = knownSize,
                        encryptedAt = System.currentTimeMillis(),
                      ),
                    )
                  }
                  if (!tempOut.renameTo(targetFile)) {
                    tempOut.copyTo(targetFile, overwrite = true)
                    tempOut.delete()
                  }
                  writeCompleted = true
                } catch (t: Throwable) {
                  tempOut.delete()
                  throw t
                }
              } else {
                val stagingFile = File(context.cacheDir, ".tmp_import_${UUID.randomUUID()}")
                try {
                  val plainSize = stagingFile.outputStream().use { stagingOut ->
                    input.copyTo(stagingOut, bufferSize = 64 * 1024)
                  }
                  try {
                    stagingFile.inputStream().use { stagedIn ->
                      tempOut.outputStream().use { out ->
                        encryptionManager.encryptStream(
                          input = stagedIn,
                          output = out,
                          key = folderKey,
                          metadata = EncryptedFileMetadata(
                            originalName = fileName,
                            mimeType = mimeType,
                            originalSize = plainSize,
                            encryptedAt = System.currentTimeMillis(),
                          ),
                        )
                      }
                    }
                    if (!tempOut.renameTo(targetFile)) {
                      tempOut.copyTo(targetFile, overwrite = true)
                      tempOut.delete()
                    }
                    writeCompleted = true
                  } catch (t: Throwable) {
                    tempOut.delete()
                    throw t
                  }
                } finally {
                  stagingFile.delete()
                }
              }
            } else {
              val targetFile = uniqueLocalFile(dir, fileName)
              targetFile.outputStream().use { out -> input.copyTo(out, bufferSize = 64 * 1024) }
              writeCompleted = true
            }
            imported++
          } else {
            val tree = resolveTreeDocumentFile(Uri.parse(folder.treeUri))
              ?: error("Folder access permission lost")
            if (folder.isLocked && folderKey != null) {
              val targetName = if (folder.obfuscateFilenames) {
                encryptionManager.generateObfuscatedCipherName()
              } else {
                uniqueSafName(tree, "$fileName${AesFolderCipher.ENCRYPTED_EXTENSION}")
              }
              val created = createSafFile(tree, "application/octet-stream", targetName)
                ?: error("Failed to create encrypted file")
              if (knownSize != null && knownSize >= 0L) {
                try {
                  val outStream = context.contentResolver.openOutputStream(created.uri)
                    ?: error("Unable to open output stream for $targetName")
                  outStream.use { out ->
                    encryptionManager.encryptStream(
                      input = input,
                      output = out,
                      key = folderKey,
                      metadata = EncryptedFileMetadata(
                        originalName = fileName,
                        mimeType = mimeType,
                        originalSize = knownSize,
                        encryptedAt = System.currentTimeMillis(),
                      ),
                    )
                  }
                  writeCompleted = true
                } catch (t: Throwable) {
                  created.delete()
                  throw t
                }
              } else {
                val stagingFile = File(context.cacheDir, ".tmp_import_${UUID.randomUUID()}")
                try {
                  val plainSize = stagingFile.outputStream().use { stagingOut ->
                    input.copyTo(stagingOut, bufferSize = 64 * 1024)
                  }
                  try {
                    val outStream = context.contentResolver.openOutputStream(created.uri)
                      ?: error("Unable to open output stream for $targetName")
                    outStream.use { out ->
                      stagingFile.inputStream().use { stagedIn ->
                        encryptionManager.encryptStream(
                          input = stagedIn,
                          output = out,
                          key = folderKey,
                          metadata = EncryptedFileMetadata(
                            originalName = fileName,
                            mimeType = mimeType,
                            originalSize = plainSize,
                            encryptedAt = System.currentTimeMillis(),
                          ),
                        )
                      }
                    }
                    writeCompleted = true
                  } catch (t: Throwable) {
                    created.delete()
                    throw t
                  }
                } finally {
                  stagingFile.delete()
                }
              }
            } else {
              val created = createSafFile(tree, mimeType, uniqueSafName(tree, fileName))
                ?: error("Failed to import file into folder")
              try {
                val outStream = context.contentResolver.openOutputStream(created.uri)
                  ?: error("Unable to open output stream for $fileName")
                outStream.use { out ->
                  input.copyTo(out, bufferSize = 64 * 1024)
                }
                writeCompleted = true
              } catch (t: Throwable) {
                created.delete()
                throw t
              }
            }
            imported++
          }
        }
        if (writeCompleted && moveOriginals) {
          if (tryDeleteExternalSourceUri(sourceUri)) {
            movedCount++
          } else {
            readOnlyCount++
          }
        }
      }
      refreshFolderMetricsInternal(folder, dek)
      if (imported > 0) {
        val moveNote = when {
          moveOriginals && movedCount == imported -> " (moved without duplicate copies)"
          moveOriginals && movedCount > 0 -> " ($movedCount moved, $readOnlyCount from read-only picker)"
          else -> ""
        }
        logEvent(
          folderId = safeAuditId(folder),
          folderName = safeAuditName(folder),
          action = "FILE_IMPORTED",
          details = "Imported $imported file(s)${if (folder.isLocked || folder.isStealthHidden) " and encrypted immediately" else ""}$moveNote",
        )
      }
      ImportFilesResult(
        importedCount = imported,
        movedWithoutCopyCount = movedCount,
        readOnlySourceCount = readOnlyCount,
      )
    }
  }

  suspend fun listFolderFiles(folderId: String, dek: SecretKey?): Result<List<VaultFileItem>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val folder = vaultDao.getFolderById(folderId) ?: error("Folder not found")
        val folderKey = dek?.let { resolveFolderKeyWithRecovery(folder, it) }
        if (folder.isInternalVault) {
          val root = internalFolderDir(folder.id)
          if (!root.exists()) return@runCatching emptyList()
          val results = mutableListOf<VaultFileItem>()
          collectInternalFiles(folder.id, root, root, folderKey, results)
          results.sortedWith(compareByDescending<VaultFileItem> { it.isEncrypted }.thenBy { it.displayName.lowercase() })
        } else {
          val tree = resolveTreeDocumentFile(Uri.parse(folder.treeUri))
            ?: error("Permission to access folder was revoked")
          val results = mutableListOf<VaultFileItem>()
          collectSafFiles(folder.id, tree, "", folderKey, results)
          results.sortedWith(compareByDescending<VaultFileItem> { it.isEncrypted }.thenBy { it.displayName.lowercase() })
        }
      }
    }

  suspend fun lockFolder(
    folderId: String,
    dek: SecretKey,
    selectedFileIds: Set<String>? = null,
    onProgress: (EncryptionProgress) -> Unit = {},
  ): Result<VaultFolderEntity> = withContext(Dispatchers.IO) {
    runCatching {
      val folder = vaultDao.getFolderById(folderId) ?: error("Folder not found")
      val folderKey = resolveFolderKeyWithRecovery(folder, dek)
      val throttle = ProgressThrottle(onProgress)
      var processed = 0
      var processedBytes = 0L

      if (folder.isInternalVault) {
        val root = internalFolderDir(folder.id)
        if (!root.exists()) root.mkdirs()
        val plainFiles = root.walkTopDown()
          .filter {
            it.isFile &&
              !it.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION) &&
              !isInternalScratchFile(it.name) &&
              (selectedFileIds == null || it.absolutePath in selectedFileIds)
          }
          .toList()
        val totalBytes = plainFiles.sumOf { it.length() }

        for (file in plainFiles) {
          val origBytes = file.length()
          val baseProgress = EncryptionProgress(
            folderName = folder.name,
            isLocking = true,
            currentFileName = file.name,
            processedFiles = processed,
            totalFiles = plainFiles.size,
            processedBytes = processedBytes,
            totalBytes = totalBytes,
            folderId = folder.id,
            currentFileId = file.absolutePath,
            currentFileProcessedBytes = 0L,
            currentFileTotalBytes = origBytes,
            isStealthMasked = folder.isStealthHidden,
          )
          throttle.emit(baseProgress, force = true)
          val encryptedName = if (folder.obfuscateFilenames) {
            encryptionManager.generateObfuscatedCipherName()
          } else {
            "${file.name}${AesFolderCipher.ENCRYPTED_EXTENSION}"
          }
          val targetFile = uniqueLocalFile(file.parentFile ?: root, encryptedName)
          val tempFile = File(file.parentFile ?: root, ".tmp_${UUID.randomUUID()}")
          val mime = guessMimeType(file.name)
          try {
            file.inputStream().use { input ->
              tempFile.outputStream().use { output ->
                encryptionManager.encryptStream(
                  input = input,
                  output = output,
                  key = folderKey,
                  metadata = EncryptedFileMetadata(
                    originalName = file.name,
                    mimeType = mime,
                    originalSize = origBytes,
                    encryptedAt = System.currentTimeMillis(),
                  ),
                  onProgressBytes = { fileBytes ->
                    throttle.emit(
                      baseProgress.copy(
                        currentFileProcessedBytes = fileBytes.coerceAtMost(origBytes),
                      ),
                    )
                  },
                )
              }
            }
            if (!tempFile.renameTo(targetFile)) {
              tempFile.copyTo(targetFile, overwrite = true)
              tempFile.delete()
            }
            if (!file.delete() && file.exists()) {
              targetFile.delete()
              error("Could not remove original file '${file.name}' without leaving a duplicate copy")
            }
          } catch (t: Throwable) {
            tempFile.delete()
            throw t
          }
          processedBytes += origBytes
          processed++
          throttle.emit(
            baseProgress.copy(
              processedFiles = processed,
              processedBytes = processedBytes,
              currentFileProcessedBytes = origBytes,
            ),
            force = true,
          )
        }
        val hasEncrypted = processed > 0 || root.walkTopDown()
          .any { it.isFile && it.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION) }
        if (selectedFileIds == null || hasEncrypted) {
          val nomedia = File(root, NOMEDIA_FILE)
          if (!nomedia.exists()) nomedia.createNewFile()
        }
      } else {
        val tree = resolveTreeDocumentFile(Uri.parse(folder.treeUri))
          ?: error("Folder access permission unavailable")
        val allPlainDocs = mutableListOf<Pair<DocumentFile, DocumentFile>>()
        gatherPlainSafDocs(tree, allPlainDocs)
        val plainDocs = if (selectedFileIds == null) {
          allPlainDocs
        } else {
          allPlainDocs.filter { (doc, _) -> doc.uri.toString() in selectedFileIds }
        }
        val totalBytes = plainDocs.sumOf { it.first.length() }

        for ((doc, parent) in plainDocs) {
          val origName = doc.name ?: "file"
          val origSize = doc.length()
          val baseProgress = EncryptionProgress(
            folderName = folder.name,
            isLocking = true,
            currentFileName = origName,
            processedFiles = processed,
            totalFiles = plainDocs.size,
            processedBytes = processedBytes,
            totalBytes = totalBytes,
            folderId = folder.id,
            currentFileId = doc.uri.toString(),
            currentFileProcessedBytes = 0L,
            currentFileTotalBytes = origSize,
            isStealthMasked = folder.isStealthHidden,
          )
          throttle.emit(baseProgress, force = true)
          val targetName = if (folder.obfuscateFilenames) {
            encryptionManager.generateObfuscatedCipherName()
          } else {
            uniqueSafName(parent, "$origName${AesFolderCipher.ENCRYPTED_EXTENSION}")
          }
          val outDoc = createSafFile(parent, "application/octet-stream", targetName)
            ?: error("Could not create encrypted file in folder")
          val mime = doc.type ?: guessMimeType(origName)
          var encryptedOk = false
          try {
            val inStream = context.contentResolver.openInputStream(doc.uri)
              ?: error("Cannot read source file $origName")
            inStream.use { input ->
              val outStream = context.contentResolver.openOutputStream(outDoc.uri)
                ?: error("Cannot write encrypted file $targetName")
              outStream.use { output ->
                encryptionManager.encryptStream(
                  input = input,
                  output = output,
                  key = folderKey,
                  metadata = EncryptedFileMetadata(
                    originalName = origName,
                    mimeType = mime,
                    originalSize = origSize,
                    encryptedAt = System.currentTimeMillis(),
                  ),
                  onProgressBytes = { fileBytes ->
                    throttle.emit(
                      baseProgress.copy(
                        currentFileProcessedBytes = fileBytes.coerceAtMost(origSize),
                      ),
                    )
                  },
                )
                encryptedOk = true
              }
            }
            if (encryptedOk && !deleteSafDocStrict(doc)) {
              error("Could not remove original file '$origName' — aborted to prevent duplicate copy")
            }
          } catch (t: Throwable) {
            deleteSafDocStrict(outDoc)
            throw t
          }
          processedBytes += origSize
          processed++
          throttle.emit(
            baseProgress.copy(
              processedFiles = processed,
              processedBytes = processedBytes,
              currentFileProcessedBytes = origSize,
            ),
            force = true,
          )
        }
        val remainingEnc = mutableListOf<Pair<DocumentFile, DocumentFile>>()
        gatherEncryptedSafDocs(tree, remainingEnc)
        if ((selectedFileIds == null || remainingEnc.isNotEmpty()) && tree.findFile(NOMEDIA_FILE) == null) {
          createSafFile(tree, "application/octet-stream", NOMEDIA_FILE)
        }
      }

      val refreshed = refreshFolderMetricsInternal(folder.copy(isLocked = true), dek)
      val keepLocked = refreshed.encryptedFileCount > 0 || selectedFileIds == null
      val updated = refreshed.copy(
        isLocked = keepLocked,
        lastActionTimestamp = System.currentTimeMillis(),
      )
      vaultDao.upsertFolder(updated)
      val remainingPlain = (updated.fileCount - updated.encryptedFileCount).coerceAtLeast(0)
      val exclusionNote = if (remainingPlain > 0) "; $remainingPlain kept unencrypted" else ""
      logEvent(
        folderId = safeAuditId(updated),
        folderName = safeAuditName(updated),
        action = if (folder.isLocked && selectedFileIds != null) "FILES_ENCRYPTED" else "LOCKED",
        details = "AES-256-GCM encrypted $processed file(s) (${formatSize(processedBytes)})$exclusionNote",
      )
      updated
    }
  }

  suspend fun decryptFilesInFolder(
    folderId: String,
    fileIds: Set<String>,
    dek: SecretKey,
    onProgress: (EncryptionProgress) -> Unit = {},
  ): Result<VaultFolderEntity> = withContext(Dispatchers.IO) {
    runCatching {
      require(fileIds.isNotEmpty()) { "Select at least one encrypted file to decrypt" }
      val folder = vaultDao.getFolderById(folderId) ?: error("Folder not found")
      val folderKey = resolveFolderKeyWithRecovery(folder, dek)
      val throttle = ProgressThrottle(onProgress)
      var processed = 0
      var processedBytes = 0L

      if (folder.isInternalVault) {
        val root = internalFolderDir(folder.id)
        if (!root.exists()) root.mkdirs()
        val encryptedFiles = root.walkTopDown()
          .filter {
            it.isFile &&
              it.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION) &&
              it.absolutePath in fileIds
          }
          .toList()
        val totalBytes = encryptedFiles.sumOf { it.length() }

        for (encFile in encryptedFiles) {
          val parent = encFile.parentFile ?: root
          val encLen = encFile.length()
          val headerMeta = encFile.inputStream().use { input ->
            encryptionManager.readMetadata(input, folderKey)
          }
          val fileTotalForBar = headerMeta.originalSize.takeIf { it > 0L } ?: encLen
          val baseProgress = EncryptionProgress(
            folderName = folder.name,
            isLocking = false,
            currentFileName = headerMeta.originalName,
            processedFiles = processed,
            totalFiles = encryptedFiles.size,
            processedBytes = processedBytes,
            totalBytes = totalBytes,
            folderId = folder.id,
            currentFileId = encFile.absolutePath,
            currentFileProcessedBytes = 0L,
            currentFileTotalBytes = fileTotalForBar,
            isStealthMasked = folder.isStealthHidden,
          )
          throttle.emit(baseProgress, force = true)
          val tempPlain = File(parent, ".dec_${UUID.randomUUID()}")
          var meta: EncryptedFileMetadata? = null
          try {
            encFile.inputStream().use { input ->
              tempPlain.outputStream().use { output ->
                meta = encryptionManager.decryptStream(
                  input = input,
                  output = output,
                  key = folderKey,
                  onProgressBytes = { plainBytes ->
                    throttle.emit(
                      baseProgress.copy(
                        currentFileProcessedBytes = plainBytes.coerceAtMost(fileTotalForBar),
                      ),
                    )
                  },
                )
              }
            }
          } catch (t: Throwable) {
            tempPlain.delete()
            throw t
          }
          val resolvedMeta = meta ?: error("Missing metadata after decryption")
          val restoredFile = uniqueLocalFile(parent, sanitizeFileName(resolvedMeta.originalName))
          if (!tempPlain.renameTo(restoredFile)) {
            tempPlain.copyTo(restoredFile, overwrite = true)
            tempPlain.delete()
          }
          if (!encFile.delete() && encFile.exists()) {
            restoredFile.delete()
            error("Could not remove encrypted source '${encFile.name}' without leaving a duplicate copy")
          }
          processedBytes += encLen
          processed++
          throttle.emit(
            baseProgress.copy(
              processedFiles = processed,
              processedBytes = processedBytes,
              currentFileProcessedBytes = fileTotalForBar,
            ),
            force = true,
          )
        }
        val anyEncryptedRemaining = root.walkTopDown()
          .any { it.isFile && it.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION) }
        if (!anyEncryptedRemaining) {
          File(root, NOMEDIA_FILE).takeIf { it.exists() }?.delete()
        }
      } else {
        val tree = resolveTreeDocumentFile(Uri.parse(folder.treeUri))
          ?: error("Folder access permission unavailable")
        val allEncryptedDocs = mutableListOf<Pair<DocumentFile, DocumentFile>>()
        gatherEncryptedSafDocs(tree, allEncryptedDocs)
        val encryptedDocs = allEncryptedDocs.filter { (doc, _) -> doc.uri.toString() in fileIds }
        val totalBytes = encryptedDocs.sumOf { it.first.length() }

        for ((encDoc, parent) in encryptedDocs) {
          val meta = context.contentResolver.openInputStream(encDoc.uri)?.use { input ->
            encryptionManager.readMetadata(input, folderKey)
          } ?: error("Cannot read encrypted header")

          val encLen = encDoc.length()
          val fileTotalForBar = meta.originalSize.takeIf { it > 0L } ?: encLen
          val baseProgress = EncryptionProgress(
            folderName = folder.name,
            isLocking = false,
            currentFileName = meta.originalName,
            processedFiles = processed,
            totalFiles = encryptedDocs.size,
            processedBytes = processedBytes,
            totalBytes = totalBytes,
            folderId = folder.id,
            currentFileId = encDoc.uri.toString(),
            currentFileProcessedBytes = 0L,
            currentFileTotalBytes = fileTotalForBar,
            isStealthMasked = folder.isStealthHidden,
          )
          throttle.emit(baseProgress, force = true)

          val safeName = uniqueSafName(parent, sanitizeFileName(meta.originalName))
          val targetDoc = createSafFile(parent, meta.mimeType.ifBlank { "application/octet-stream" }, safeName)
            ?: error("Could not restore decrypted file")

          var decryptedOk = false
          try {
            val inStream = context.contentResolver.openInputStream(encDoc.uri)
              ?: error("Cannot read encrypted file ${encDoc.name ?: safeName}")
            inStream.use { input ->
              val outStream = context.contentResolver.openOutputStream(targetDoc.uri)
                ?: error("Cannot write decrypted file $safeName")
              outStream.use { output ->
                encryptionManager.decryptStream(
                  input = input,
                  output = output,
                  key = folderKey,
                  onProgressBytes = { plainBytes ->
                    throttle.emit(
                      baseProgress.copy(
                        currentFileProcessedBytes = plainBytes.coerceAtMost(fileTotalForBar),
                      ),
                    )
                  },
                )
                decryptedOk = true
              }
            }
            if (decryptedOk && !deleteSafDocStrict(encDoc)) {
              error("Could not remove encrypted file '${encDoc.name ?: safeName}' — aborted to prevent duplicate copy")
            }
          } catch (t: Throwable) {
            deleteSafDocStrict(targetDoc)
            throw t
          }
          processedBytes += encLen
          processed++
          throttle.emit(
            baseProgress.copy(
              processedFiles = processed,
              processedBytes = processedBytes,
              currentFileProcessedBytes = fileTotalForBar,
            ),
            force = true,
          )
        }
        val remainingEncrypted = mutableListOf<Pair<DocumentFile, DocumentFile>>()
        gatherEncryptedSafDocs(tree, remainingEncrypted)
        if (remainingEncrypted.isEmpty()) {
          tree.findFile(NOMEDIA_FILE)?.let { deleteSafDocStrict(it) }
        }
      }

      val updated = refreshFolderMetricsInternal(folder, dek)
        .copy(lastActionTimestamp = System.currentTimeMillis())
      vaultDao.upsertFolder(updated)
      logEvent(
        folderId = safeAuditId(updated),
        folderName = safeAuditName(updated),
        action = "FILE_DECRYPTED",
        details = "Verified GCM tags & decrypted $processed selected file(s) (${formatSize(processedBytes)})",
      )
      updated
    }
  }

  suspend fun unlockFolder(
    folderId: String,
    dek: SecretKey,
    onProgress: (EncryptionProgress) -> Unit = {},
  ): Result<VaultFolderEntity> = withContext(Dispatchers.IO) {
    runCatching {
      val folder = vaultDao.getFolderById(folderId) ?: error("Folder not found")
      val folderKey = resolveFolderKeyWithRecovery(folder, dek)
      val throttle = ProgressThrottle(onProgress)
      var processed = 0
      var processedBytes = 0L

      if (folder.isInternalVault) {
        val root = internalFolderDir(folder.id)
        if (!root.exists()) root.mkdirs()
        val encryptedFiles = root.walkTopDown()
          .filter { it.isFile && it.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION) }
          .toList()
        val totalBytes = encryptedFiles.sumOf { it.length() }

        for (encFile in encryptedFiles) {
          val parent = encFile.parentFile ?: root
          val encLen = encFile.length()
          val headerMeta = encFile.inputStream().use { input ->
            encryptionManager.readMetadata(input, folderKey)
          }
          val fileTotalForBar = headerMeta.originalSize.takeIf { it > 0L } ?: encLen
          val baseProgress = EncryptionProgress(
            folderName = folder.name,
            isLocking = false,
            currentFileName = headerMeta.originalName,
            processedFiles = processed,
            totalFiles = encryptedFiles.size,
            processedBytes = processedBytes,
            totalBytes = totalBytes,
            folderId = folder.id,
            currentFileId = encFile.absolutePath,
            currentFileProcessedBytes = 0L,
            currentFileTotalBytes = fileTotalForBar,
            isStealthMasked = folder.isStealthHidden,
          )
          throttle.emit(baseProgress, force = true)
          val tempPlain = File(parent, ".dec_${UUID.randomUUID()}")
          var meta: EncryptedFileMetadata? = null
          try {
            encFile.inputStream().use { input ->
              tempPlain.outputStream().use { output ->
                meta = encryptionManager.decryptStream(
                  input = input,
                  output = output,
                  key = folderKey,
                  onProgressBytes = { plainBytes ->
                    throttle.emit(
                      baseProgress.copy(
                        currentFileProcessedBytes = plainBytes.coerceAtMost(fileTotalForBar),
                      ),
                    )
                  },
                )
              }
            }
          } catch (t: Throwable) {
            tempPlain.delete()
            throw t
          }
          val resolvedMeta = meta ?: error("Missing metadata after decryption")
          val restoredFile = uniqueLocalFile(parent, sanitizeFileName(resolvedMeta.originalName))
          if (!tempPlain.renameTo(restoredFile)) {
            tempPlain.copyTo(restoredFile, overwrite = true)
            tempPlain.delete()
          }
          if (!encFile.delete() && encFile.exists()) {
            restoredFile.delete()
            error("Could not remove encrypted source '${encFile.name}' without leaving a duplicate copy")
          }
          processedBytes += encLen
          processed++
          throttle.emit(
            baseProgress.copy(
              processedFiles = processed,
              processedBytes = processedBytes,
              currentFileProcessedBytes = fileTotalForBar,
            ),
            force = true,
          )
        }
        File(root, NOMEDIA_FILE).takeIf { it.exists() }?.delete()
      } else {
        val tree = resolveTreeDocumentFile(Uri.parse(folder.treeUri))
          ?: error("Folder access permission unavailable")
        val encryptedDocs = mutableListOf<Pair<DocumentFile, DocumentFile>>()
        gatherEncryptedSafDocs(tree, encryptedDocs)
        val totalBytes = encryptedDocs.sumOf { it.first.length() }

        for ((encDoc, parent) in encryptedDocs) {
          val meta = context.contentResolver.openInputStream(encDoc.uri)?.use { input ->
            encryptionManager.readMetadata(input, folderKey)
          } ?: error("Cannot read encrypted header")

          val encLen = encDoc.length()
          val fileTotalForBar = meta.originalSize.takeIf { it > 0L } ?: encLen
          val baseProgress = EncryptionProgress(
            folderName = folder.name,
            isLocking = false,
            currentFileName = meta.originalName,
            processedFiles = processed,
            totalFiles = encryptedDocs.size,
            processedBytes = processedBytes,
            totalBytes = totalBytes,
            folderId = folder.id,
            currentFileId = encDoc.uri.toString(),
            currentFileProcessedBytes = 0L,
            currentFileTotalBytes = fileTotalForBar,
            isStealthMasked = folder.isStealthHidden,
          )
          throttle.emit(baseProgress, force = true)

          val safeName = uniqueSafName(parent, sanitizeFileName(meta.originalName))
          val targetDoc = createSafFile(parent, meta.mimeType.ifBlank { "application/octet-stream" }, safeName)
            ?: error("Could not restore decrypted file")

          var decryptedOk = false
          try {
            val inStream = context.contentResolver.openInputStream(encDoc.uri)
              ?: error("Cannot read encrypted file ${encDoc.name ?: safeName}")
            inStream.use { input ->
              val outStream = context.contentResolver.openOutputStream(targetDoc.uri)
                ?: error("Cannot write decrypted file $safeName")
              outStream.use { output ->
                encryptionManager.decryptStream(
                  input = input,
                  output = output,
                  key = folderKey,
                  onProgressBytes = { plainBytes ->
                    throttle.emit(
                      baseProgress.copy(
                        currentFileProcessedBytes = plainBytes.coerceAtMost(fileTotalForBar),
                      ),
                    )
                  },
                )
                decryptedOk = true
              }
            }
            if (decryptedOk && !deleteSafDocStrict(encDoc)) {
              error("Could not remove encrypted file '${encDoc.name ?: safeName}' — aborted to prevent duplicate copy")
            }
          } catch (t: Throwable) {
            deleteSafDocStrict(targetDoc)
            throw t
          }
          processedBytes += encLen
          processed++
          throttle.emit(
            baseProgress.copy(
              processedFiles = processed,
              processedBytes = processedBytes,
              currentFileProcessedBytes = fileTotalForBar,
            ),
            force = true,
          )
        }
        tree.findFile(NOMEDIA_FILE)?.let { deleteSafDocStrict(it) }
      }

      val updated = refreshFolderMetricsInternal(folder.copy(isLocked = false), dek)
        .copy(isLocked = false, lastActionTimestamp = System.currentTimeMillis())
      vaultDao.upsertFolder(updated)
      logEvent(
        folderId = safeAuditId(updated),
        folderName = safeAuditName(updated),
        action = "UNLOCKED",
        details = "Verified GCM tags & decrypted $processed file(s)",
      )
      updated
    }
  }

  suspend fun decryptAnySafFolder(
    treeUri: Uri,
    dek: SecretKey,
    onProgress: (EncryptionProgress) -> Unit = {},
  ): Result<DecryptAnyFolderResult> = withContext(Dispatchers.IO) {
    runCatching {
      encryptionManager.takePersistableFolderPermission(treeUri)

      val existing = vaultDao.getFolderByUri(treeUri.toString())
      val folderKey = if (existing != null) resolveFolderKeyWithRecovery(existing, dek) else dek
      val tree = resolveTreeDocumentFile(treeUri)
        ?: error("Unable to open selected device folder")
      val displayName = existing?.name ?: tree.name?.takeIf { it.isNotBlank() } ?: "Device Folder"
      val throttle = ProgressThrottle(onProgress)

      val encryptedDocs = mutableListOf<Pair<DocumentFile, DocumentFile>>()
      val plainDocs = mutableListOf<Pair<DocumentFile, DocumentFile>>()
      gatherEncryptedSafDocs(tree, encryptedDocs)
      gatherPlainSafDocs(tree, plainDocs)

      val totalBytes = encryptedDocs.sumOf { it.first.length() }
      var processed = 0
      var processedBytes = 0L

      for ((encDoc, parent) in encryptedDocs) {
        val meta = context.contentResolver.openInputStream(encDoc.uri)?.use { input ->
          encryptionManager.readMetadata(input, folderKey)
        } ?: error("Cannot read encrypted header in \"${encDoc.name ?: "file"}\" (wrong key or tampered file)")

        val encLen = encDoc.length()
        val fileTotalForBar = meta.originalSize.takeIf { it > 0L } ?: encLen
        val baseProgress = EncryptionProgress(
          folderName = displayName,
          isLocking = false,
          currentFileName = meta.originalName,
          processedFiles = processed,
          totalFiles = encryptedDocs.size,
          processedBytes = processedBytes,
          totalBytes = totalBytes,
          folderId = existing?.id ?: "",
          currentFileId = encDoc.uri.toString(),
          currentFileProcessedBytes = 0L,
          currentFileTotalBytes = fileTotalForBar,
          isStealthMasked = existing?.isStealthHidden == true,
        )
        throttle.emit(baseProgress, force = true)

        val safeName = uniqueSafName(parent, sanitizeFileName(meta.originalName))
        val targetDoc = createSafFile(parent, meta.mimeType.ifBlank { "application/octet-stream" }, safeName)
          ?: error("Could not write decrypted file $safeName")

        var decryptedOk = false
        try {
          val inStream = context.contentResolver.openInputStream(encDoc.uri)
            ?: error("Cannot read encrypted file ${encDoc.name ?: safeName}")
          inStream.use { input ->
            val outStream = context.contentResolver.openOutputStream(targetDoc.uri)
              ?: error("Cannot write decrypted file $safeName")
            outStream.use { output ->
              encryptionManager.decryptStream(
                input = input,
                output = output,
                key = folderKey,
                onProgressBytes = { plainBytes ->
                  throttle.emit(
                    baseProgress.copy(
                      currentFileProcessedBytes = plainBytes.coerceAtMost(fileTotalForBar),
                    ),
                  )
                },
              )
              decryptedOk = true
            }
          }
          if (decryptedOk && !deleteSafDocStrict(encDoc)) {
            error("Could not delete encrypted file '${encDoc.name ?: safeName}' — aborted to avoid duplicate copy")
          }
        } catch (t: Throwable) {
          deleteSafDocStrict(targetDoc)
          throw t
        }
        processedBytes += encLen
        processed++
        throttle.emit(
          baseProgress.copy(
            processedFiles = processed,
            processedBytes = processedBytes,
            currentFileProcessedBytes = fileTotalForBar,
          ),
          force = true,
        )
      }

      if (processed > 0) {
        tree.findFile(NOMEDIA_FILE)?.let { deleteSafDocStrict(it) }
      }
      if (existing != null) {
        refreshFolderMetricsInternal(existing.copy(isLocked = false), dek)
          .copy(isLocked = false, lastActionTimestamp = System.currentTimeMillis())
          .let { vaultDao.upsertFolder(it) }
      }
      if (processed > 0) {
        logEvent(
          folderId = existing?.id,
          folderName = displayName,
          action = "ANY_FOLDER_DECRYPTED",
          details = "In-place decrypted $processed encrypted file(s) (${formatSize(processedBytes)}) in device folder",
        )
      }

      DecryptAnyFolderResult(
        folderName = displayName,
        decryptedFiles = processed,
        decryptedBytes = processedBytes,
        alreadyPlainFiles = plainDocs.size,
        wasRegisteredInVault = existing != null,
      )
    }
  }

  suspend fun lockFoldersBatch(
    folderIds: List<String>,
    dek: SecretKey,
    onProgress: (EncryptionProgress) -> Unit = {},
  ): BatchFolderResult {
    var succeeded = 0
    var failed = 0
    var totalFiles = 0
    var totalBytes = 0L
    var firstError: String? = null

    val targets = folderIds.mapNotNull { vaultDao.getFolderById(it) }
      .filter { !it.isLocked || it.encryptedFileCount < it.fileCount }
    targets.forEachIndexed { index, folder ->
      val prefix = "[${index + 1}/${targets.size}] ${folder.name}"
      onProgress(
        EncryptionProgress(
          folderName = prefix,
          isLocking = true,
          currentFileName = "Preparing folder...",
          processedFiles = 0,
          totalFiles = folder.fileCount.coerceAtLeast(1),
          processedBytes = 0L,
          totalBytes = folder.totalBytes,
          folderId = folder.id,
          isStealthMasked = folder.isStealthHidden,
        ),
      )
      lockFolder(folder.id, dek) { progress ->
        onProgress(progress.copy(folderName = prefix))
      }.onSuccess { updated ->
        succeeded++
        totalFiles += updated.encryptedFileCount
        totalBytes += updated.encryptedBytes
      }.onFailure { err ->
        failed++
        if (firstError == null) firstError = err.message ?: "Failed to lock ${folder.name}"
      }
    }

    if (succeeded > 0 || failed > 0) {
      logEvent(
        folderName = "Batch Operation",
        action = "BATCH_LOCKED",
        details = "Encrypted $succeeded folder(s) ($totalFiles file(s))${if (failed > 0) "; $failed failed" else ""}",
        category = AuditCategory.CRYPTO_OPERATION,
        isWarning = failed > 0,
      )
    }

    return BatchFolderResult(
      succeededCount = succeeded,
      failedCount = failed,
      totalFiles = totalFiles,
      totalBytes = totalBytes,
      firstError = firstError,
    )
  }

  suspend fun unlockFoldersBatch(
    folderIds: List<String>,
    dek: SecretKey,
    onProgress: (EncryptionProgress) -> Unit = {},
  ): BatchFolderResult {
    var succeeded = 0
    var failed = 0
    var totalFiles = 0
    var totalBytes = 0L
    var firstError: String? = null

    val targets = folderIds.mapNotNull { vaultDao.getFolderById(it) }
      .filter { it.isLocked || it.encryptedFileCount > 0 }
    targets.forEachIndexed { index, folder ->
      val prefix = "[${index + 1}/${targets.size}] ${folder.name}"
      onProgress(
        EncryptionProgress(
          folderName = prefix,
          isLocking = false,
          currentFileName = "Preparing folder...",
          processedFiles = 0,
          totalFiles = folder.encryptedFileCount.coerceAtLeast(1),
          processedBytes = 0L,
          totalBytes = folder.totalBytes,
          folderId = folder.id,
          isStealthMasked = folder.isStealthHidden,
        ),
      )
      unlockFolder(folder.id, dek) { progress ->
        onProgress(progress.copy(folderName = prefix))
      }.onSuccess { updated ->
        succeeded++
        totalFiles += updated.fileCount
        totalBytes += updated.unencryptedBytes
      }.onFailure { err ->
        failed++
        if (firstError == null) firstError = err.message ?: "Failed to unlock ${folder.name}"
      }
    }

    if (succeeded > 0 || failed > 0) {
      logEvent(
        folderName = "Batch Operation",
        action = "BATCH_UNLOCKED",
        details = "Decrypted $succeeded folder(s) ($totalFiles file(s))${if (failed > 0) "; $failed failed" else ""}",
        category = AuditCategory.CRYPTO_OPERATION,
        isWarning = failed > 0,
      )
    }

    return BatchFolderResult(
      succeededCount = succeeded,
      failedCount = failed,
      totalFiles = totalFiles,
      totalBytes = totalBytes,
      firstError = firstError,
    )
  }

  suspend fun rotateFolderKey(
    folderId: String,
    masterDek: SecretKey,
    encryptUnlockedFiles: Boolean = true,
    onProgress: (EncryptionProgress) -> Unit = {},
  ): Result<RotateFolderOutcome> = withContext(Dispatchers.IO) {
    runCatching {
      val folder = vaultDao.getFolderById(folderId) ?: error("Folder not found")
      val oldFolderKey = resolveFolderKeyWithRecovery(folder, masterDek)
      val newFolderKey = encryptionManager.generateFolderKey()
      val wrappedNewKey = encryptionManager.wrapFolderKey(newFolderKey, masterDek)
      val newFingerprint = encryptionManager.computeKeyFingerprint(newFolderKey)

      var processed = 0
      var processedBytes = 0L

      if (folder.isInternalVault) {
        val root = internalFolderDir(folder.id)
        if (!root.exists()) root.mkdirs()

        val encryptedFiles = root.walkTopDown()
          .filter { it.isFile && it.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION) }
          .toList()
        val plainFiles = root.walkTopDown()
          .filter {
            it.isFile &&
              !it.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION) &&
              !isInternalScratchFile(it.name)
          }
          .toList()

        if (!folder.isLocked && encryptedFiles.isEmpty() && plainFiles.isNotEmpty() && !encryptUnlockedFiles) {
          return@runCatching RotateFolderOutcome.SkippedUnlocked
        }

        val targetPlainFiles = if (encryptUnlockedFiles) plainFiles else emptyList()
        val totalFiles = encryptedFiles.size + targetPlainFiles.size
        val totalBytes = encryptedFiles.sumOf { it.length() } + targetPlainFiles.sumOf { it.length() }

        val stagedEncryptedSwaps = mutableListOf<Pair<File, File>>()
        val stagedPlainAdditions = mutableListOf<Triple<File, File, String>>()

        try {
          for (encFile in encryptedFiles) {
            val parent = encFile.parentFile ?: root
            val tempPlain = File(context.cacheDir, ".reenc_plain_${UUID.randomUUID()}")
            val stagedCipher = File(parent, ".reenc_cipher_${UUID.randomUUID()}")
            val encLen = encFile.length()
            try {
              val recoveredMeta = encFile.inputStream().use { input ->
                tempPlain.outputStream().use { plainOut ->
                  encryptionManager.decryptStream(input, plainOut, oldFolderKey)
                }
              }
              val fileSize = recoveredMeta.originalSize.takeIf { it > 0L } ?: encLen
              onProgress(
                EncryptionProgress(
                  folderName = folder.name,
                  isLocking = true,
                  currentFileName = recoveredMeta.originalName,
                  processedFiles = processed,
                  totalFiles = totalFiles,
                  processedBytes = processedBytes,
                  totalBytes = totalBytes,
                  folderId = folder.id,
                  currentFileId = encFile.absolutePath,
                  currentFileProcessedBytes = 0L,
                  currentFileTotalBytes = fileSize,
                  isStealthMasked = folder.isStealthHidden,
                ),
              )
              tempPlain.inputStream().use { plainIn ->
                stagedCipher.outputStream().use { cipherOut ->
                  encryptionManager.encryptStream(
                    input = plainIn,
                    output = cipherOut,
                    key = newFolderKey,
                    metadata = recoveredMeta.copy(encryptedAt = System.currentTimeMillis()),
                  )
                }
              }
              stagedEncryptedSwaps.add(encFile to stagedCipher)
              processedBytes += encLen
              processed++
            } finally {
              if (tempPlain.exists()) {
                tempPlain.delete()
              }
            }
          }

          for (plainFile in targetPlainFiles) {
            val parent = plainFile.parentFile ?: root
            val stagedCipher = File(parent, ".reenc_cipher_${UUID.randomUUID()}")
            val plainLen = plainFile.length()
            onProgress(
              EncryptionProgress(
                folderName = folder.name,
                isLocking = true,
                currentFileName = plainFile.name,
                processedFiles = processed,
                totalFiles = totalFiles,
                processedBytes = processedBytes,
                totalBytes = totalBytes,
                folderId = folder.id,
                currentFileId = plainFile.absolutePath,
                currentFileProcessedBytes = 0L,
                currentFileTotalBytes = plainLen,
                isStealthMasked = folder.isStealthHidden,
              ),
            )
            val targetName = if (folder.obfuscateFilenames) {
              encryptionManager.generateObfuscatedCipherName()
            } else {
              "${plainFile.name}${AesFolderCipher.ENCRYPTED_EXTENSION}"
            }
            plainFile.inputStream().use { input ->
              stagedCipher.outputStream().use { output ->
                encryptionManager.encryptStream(
                  input = input,
                  output = output,
                  key = newFolderKey,
                  metadata = EncryptedFileMetadata(
                    originalName = plainFile.name,
                    mimeType = guessMimeType(plainFile.name),
                    originalSize = plainLen,
                    encryptedAt = System.currentTimeMillis(),
                  ),
                )
              }
            }
            stagedPlainAdditions.add(Triple(plainFile, stagedCipher, targetName))
            processedBytes += plainLen
            processed++
          }
        } catch (t: Throwable) {
          stagedEncryptedSwaps.forEach { (_, staged) -> staged.delete() }
          stagedPlainAdditions.forEach { (_, staged, _) -> staged.delete() }
          root.walkTopDown()
            .filter { it.isFile && it.name.startsWith(".reenc_") }
            .forEach { it.delete() }
          throw t
        }

        // Phase 2: All staged files verified & encrypted under newFolderKey -> swap & commit
        for ((origEnc, stagedCipher) in stagedEncryptedSwaps) {
          if (origEnc.exists()) origEnc.delete()
          if (!stagedCipher.renameTo(origEnc)) {
            stagedCipher.copyTo(origEnc, overwrite = true)
            stagedCipher.delete()
          }
        }
        for ((plainFile, stagedCipher, desiredName) in stagedPlainAdditions) {
          val parent = plainFile.parentFile ?: root
          val dest = uniqueLocalFile(parent, desiredName)
          if (!stagedCipher.renameTo(dest)) {
            stagedCipher.copyTo(dest, overwrite = true)
            stagedCipher.delete()
          }
          plainFile.delete()
        }
        if (totalFiles > 0) {
          val nomedia = File(root, NOMEDIA_FILE)
          if (!nomedia.exists()) nomedia.createNewFile()
        }
      } else {
        val tree = resolveTreeDocumentFile(Uri.parse(folder.treeUri))
          ?: error("Folder access permission unavailable")
        val encryptedDocs = mutableListOf<Pair<DocumentFile, DocumentFile>>()
        val plainDocs = mutableListOf<Pair<DocumentFile, DocumentFile>>()
        gatherEncryptedSafDocs(tree, encryptedDocs)
        gatherPlainSafDocs(tree, plainDocs)

        if (!folder.isLocked && encryptedDocs.isEmpty() && plainDocs.isNotEmpty() && !encryptUnlockedFiles) {
          return@runCatching RotateFolderOutcome.SkippedUnlocked
        }

        val targetPlainDocs = if (encryptUnlockedFiles) plainDocs else emptyList()
        val totalFiles = encryptedDocs.size + targetPlainDocs.size
        val totalBytes = encryptedDocs.sumOf { it.first.length() } + targetPlainDocs.sumOf { it.first.length() }

        data class StagedSafSwap(
          val oldDoc: DocumentFile,
          val stagedDoc: DocumentFile,
          val finalName: String,
        )
        val stagedSwaps = mutableListOf<StagedSafSwap>()

        try {
          for ((encDoc, parent) in encryptedDocs) {
            val tempPlain = File(context.cacheDir, ".reenc_plain_${UUID.randomUUID()}")
            val stagedName = ".reenc_tmp_${UUID.randomUUID()}"
            val stagedDoc = parent.createFile("application/octet-stream", stagedName)
              ?: error("Could not stage re-encrypted SAF file")
            val encLen = encDoc.length()
            try {
              val recoveredMeta = context.contentResolver.openInputStream(encDoc.uri)?.use { input ->
                tempPlain.outputStream().use { plainOut ->
                  encryptionManager.decryptStream(input, plainOut, oldFolderKey)
                }
              } ?: error("Cannot read encrypted SAF file")

              val fileSize = recoveredMeta.originalSize.takeIf { it > 0L } ?: encLen
              onProgress(
                EncryptionProgress(
                  folderName = folder.name,
                  isLocking = true,
                  currentFileName = recoveredMeta.originalName,
                  processedFiles = processed,
                  totalFiles = totalFiles,
                  processedBytes = processedBytes,
                  totalBytes = totalBytes,
                  folderId = folder.id,
                  currentFileId = encDoc.uri.toString(),
                  currentFileProcessedBytes = 0L,
                  currentFileTotalBytes = fileSize,
                  isStealthMasked = folder.isStealthHidden,
                ),
              )

              tempPlain.inputStream().use { plainIn ->
                context.contentResolver.openOutputStream(stagedDoc.uri)?.use { cipherOut ->
                  encryptionManager.encryptStream(
                    input = plainIn,
                    output = cipherOut,
                    key = newFolderKey,
                    metadata = recoveredMeta.copy(encryptedAt = System.currentTimeMillis()),
                  )
                } ?: error("Cannot write staged SAF file")
              }

              val desiredFinalName = encDoc.name ?: encryptionManager.generateObfuscatedCipherName()
              stagedSwaps.add(StagedSafSwap(encDoc, stagedDoc, desiredFinalName))
              processedBytes += encLen
              processed++
            } catch (t: Throwable) {
              stagedDoc.delete()
              throw t
            } finally {
              if (tempPlain.exists()) {
                tempPlain.delete()
              }
            }
          }

          for ((plainDoc, parent) in targetPlainDocs) {
            val origName = plainDoc.name ?: "file"
            val origSize = plainDoc.length()
            val stagedDoc = parent.createFile("application/octet-stream", ".reenc_tmp_${UUID.randomUUID()}")
              ?: error("Could not stage encrypted SAF file")
            onProgress(
              EncryptionProgress(
                folderName = folder.name,
                isLocking = true,
                currentFileName = origName,
                processedFiles = processed,
                totalFiles = totalFiles,
                processedBytes = processedBytes,
                totalBytes = totalBytes,
                folderId = folder.id,
                currentFileId = plainDoc.uri.toString(),
                currentFileProcessedBytes = 0L,
                currentFileTotalBytes = origSize,
                isStealthMasked = folder.isStealthHidden,
              ),
            )
            try {
              context.contentResolver.openInputStream(plainDoc.uri)?.use { input ->
                context.contentResolver.openOutputStream(stagedDoc.uri)?.use { output ->
                  encryptionManager.encryptStream(
                    input = input,
                    output = output,
                    key = newFolderKey,
                    metadata = EncryptedFileMetadata(
                      originalName = origName,
                      mimeType = plainDoc.type ?: guessMimeType(origName),
                      originalSize = origSize,
                      encryptedAt = System.currentTimeMillis(),
                    ),
                  )
                } ?: error("Cannot write staged SAF file")
              } ?: error("Cannot read plain SAF file")

              val desiredFinalName = if (folder.obfuscateFilenames) {
                encryptionManager.generateObfuscatedCipherName()
              } else {
                "$origName${AesFolderCipher.ENCRYPTED_EXTENSION}"
              }
              stagedSwaps.add(StagedSafSwap(plainDoc, stagedDoc, desiredFinalName))
              processedBytes += origSize
              processed++
            } catch (t: Throwable) {
              stagedDoc.delete()
              throw t
            }
          }
        } catch (t: Throwable) {
          stagedSwaps.forEach { it.stagedDoc.delete() }
          throw t
        }

        for (swap in stagedSwaps) {
          val oldSize = swap.oldDoc.length()
          swap.oldDoc.delete()
          if (!swap.stagedDoc.renameTo(swap.finalName)) {
            swap.stagedDoc.renameTo(encryptionManager.generateObfuscatedCipherName())
          }
          if (processedBytes == 0L) processedBytes += oldSize
        }
        if (totalFiles > 0 && tree.findFile(NOMEDIA_FILE) == null) {
          tree.createFile("application/octet-stream", NOMEDIA_FILE)
        }
      }

      val now = System.currentTimeMillis()
      val nextVersion = folder.keyVersion + 1
      val updatedBase = folder.copy(
        isLocked = if (processed > 0) true else folder.isLocked,
        lastActionTimestamp = now,
        wrappedFolderKey = wrappedNewKey,
        keyVersion = nextVersion,
        keyFingerprint = newFingerprint,
        lastKeyRotatedAt = now,
      )
      val refreshed = refreshFolderMetricsInternal(updatedBase, masterDek).copy(
        wrappedFolderKey = wrappedNewKey,
        keyVersion = nextVersion,
        keyFingerprint = newFingerprint,
        lastKeyRotatedAt = now,
        lastActionTimestamp = now,
        isLocked = if (processed > 0) true else updatedBase.isLocked,
      )
      vaultDao.upsertFolder(refreshed)
      logEvent(
        folderId = safeAuditId(refreshed),
        folderName = safeAuditName(refreshed),
        action = "FOLDER_KEY_ROTATED",
        details = "Rotated 256-bit folder key (v${folder.keyVersion} -> v$nextVersion, $newFingerprint) across $processed file(s) (${formatSize(processedBytes)})",
        category = AuditCategory.CRYPTO_OPERATION,
      )
      RotateFolderOutcome.Rotated(
        folder = refreshed,
        rotatedFileCount = processed,
        rotatedBytes = processedBytes,
      )
    }
  }

  suspend fun reencryptFoldersBatch(
    folderIds: List<String>,
    masterDek: SecretKey,
    encryptUnlockedFolders: Boolean = true,
    onProgress: (EncryptionProgress) -> Unit = {},
  ): BatchFolderResult {
    var succeeded = 0
    var failed = 0
    var skipped = 0
    var totalFiles = 0
    var totalBytes = 0L
    var firstError: String? = null

    val targets = folderIds.mapNotNull { vaultDao.getFolderById(it) }
    targets.forEachIndexed { index, folder ->
      val prefix = "[${index + 1}/${targets.size}] ${folder.name}"
      onProgress(
        EncryptionProgress(
          folderName = prefix,
          isLocking = true,
          currentFileName = "Rotating encryption key...",
          processedFiles = 0,
          totalFiles = folder.fileCount.coerceAtLeast(1),
          processedBytes = 0L,
          totalBytes = folder.totalBytes,
          folderId = folder.id,
          isStealthMasked = folder.isStealthHidden,
        ),
      )
      rotateFolderKey(folder.id, masterDek, encryptUnlockedFolders) { progress ->
        onProgress(progress.copy(folderName = prefix))
      }.onSuccess { outcome ->
        when (outcome) {
          is RotateFolderOutcome.Rotated -> {
            succeeded++
            totalFiles += outcome.rotatedFileCount
            totalBytes += outcome.rotatedBytes
          }
          RotateFolderOutcome.SkippedUnlocked -> skipped++
        }
      }.onFailure { err ->
        failed++
        if (firstError == null) firstError = err.message ?: "Failed to rotate key for ${folder.name}"
      }
    }

    if (succeeded > 0 || failed > 0) {
      logEvent(
        folderName = "Batch Key Rotation",
        action = "BATCH_KEY_ROTATED",
        details = "Rotated AES-256 keys for $succeeded folder(s) ($totalFiles file(s))${if (skipped > 0) ", skipped $skipped unlocked" else ""}${if (failed > 0) "; $failed failed" else ""}",
        category = AuditCategory.CRYPTO_OPERATION,
        isWarning = failed > 0,
      )
    }

    return BatchFolderResult(
      succeededCount = succeeded,
      failedCount = failed,
      totalFiles = totalFiles,
      totalBytes = totalBytes,
      firstError = firstError,
      skippedCount = skipped,
    )
  }

  suspend fun batchRenameFolderFiles(
    folderId: String,
    renameMap: Map<String, String>,
    dek: SecretKey?,
    onProgress: (EncryptionProgress) -> Unit = {},
  ): Result<BatchRenameResult> = withContext(Dispatchers.IO) {
    runCatching {
      val folder = vaultDao.getFolderById(folderId) ?: error("Folder not found")
      val currentItems = listFolderFiles(folderId, dek).getOrThrow()
      val targets = currentItems.filter { it.id in renameMap.keys }
      if (targets.isEmpty()) {
        return@runCatching BatchRenameResult(renamedCount = 0, skippedCount = renameMap.size, failedCount = 0)
      }

      val needsKey = targets.any { it.isEncrypted }
      val folderKey = if (needsKey) {
        val masterDek = dek ?: error("Vault must be unlocked to rename encrypted files")
        resolveFolderKeyWithRecovery(folder, masterDek)
      } else {
        null
      }

      var renamed = 0
      var skipped = 0
      var failed = 0
      var firstError: String? = null
      val totalBytes = targets.sumOf { it.sizeBytes }
      var processedBytes = 0L

      if (folder.isInternalVault) {
        data class StagedLocalRename(
          val stagedFile: File,
          val parentDir: File,
          val finalDiskName: String,
          val originalNameForObfuscated: File,
          val isEncrypted: Boolean,
        )

        val stagedList = mutableListOf<StagedLocalRename>()
        val originalsToDelete = mutableListOf<File>()

        targets.forEachIndexed { index, item ->
          val requestedName = renameMap[item.id] ?: ""
          val cleanNewName = BatchRenameEngine.sanitizeBaseName(
            raw = requestedName,
            sanitizeIllegalChars = true,
            replaceSpacesWithUnderscores = false,
            collapseSeparators = false,
            trimWhitespaceAndDots = true,
            fallback = "file",
          )
          if (cleanNewName == item.displayName) {
            skipped++
            processedBytes += item.sizeBytes
            return@forEachIndexed
          }

          onProgress(
            EncryptionProgress(
              folderName = folder.name,
              isLocking = true,
              currentFileName = "${item.displayName} → $cleanNewName",
              processedFiles = index,
              totalFiles = targets.size,
              processedBytes = processedBytes,
              totalBytes = totalBytes,
              folderId = folder.id,
              currentFileId = item.id,
              currentFileProcessedBytes = 0L,
              currentFileTotalBytes = item.sizeBytes,
              isStealthMasked = folder.isStealthHidden,
            ),
          )

          val sourceFile = File(Uri.parse(item.uriString).path ?: item.id)
          if (!sourceFile.exists()) {
            failed++
            if (firstError == null) firstError = "File missing: ${item.displayName}"
            return@forEachIndexed
          }
          val parentDir = sourceFile.parentFile ?: internalFolderDir(folder.id)

          runCatching {
            if (item.isEncrypted) {
              val key = folderKey ?: error("Encryption key unavailable")
              val stagedFile = File(parentDir, ".tmp_ren_${UUID.randomUUID()}")
              try {
                sourceFile.inputStream().use { input ->
                  stagedFile.outputStream().use { output ->
                    encryptionManager.rewriteEncryptedMetadata(input, output, key) { oldMeta ->
                      val guessedMime = guessMimeType(cleanNewName)
                      val nextMime = if (guessedMime != "application/octet-stream") guessedMime else oldMeta.mimeType
                      oldMeta.copy(
                        originalName = cleanNewName,
                        mimeType = nextMime,
                      )
                    }
                  }
                }
                val finalDiskName = if (folder.obfuscateFilenames) {
                  sourceFile.name
                } else {
                  "$cleanNewName${AesFolderCipher.ENCRYPTED_EXTENSION}"
                }
                originalsToDelete.add(sourceFile)
                stagedList.add(
                  StagedLocalRename(
                    stagedFile = stagedFile,
                    parentDir = parentDir,
                    finalDiskName = finalDiskName,
                    originalNameForObfuscated = sourceFile,
                    isEncrypted = true,
                  ),
                )
              } catch (t: Throwable) {
                if (stagedFile.exists()) stagedFile.delete()
                throw t
              }
            } else {
              val stagedPlain = File(parentDir, ".tmp_ren_${UUID.randomUUID()}")
              if (!sourceFile.renameTo(stagedPlain)) {
                sourceFile.copyTo(stagedPlain, overwrite = true)
                sourceFile.delete()
              }
              stagedList.add(
                StagedLocalRename(
                  stagedFile = stagedPlain,
                  parentDir = parentDir,
                  finalDiskName = cleanNewName,
                  originalNameForObfuscated = sourceFile,
                  isEncrypted = false,
                ),
              )
            }
            renamed++
          }.onFailure { err ->
            failed++
            if (firstError == null) firstError = err.message ?: "Failed to rename ${item.displayName}"
          }
          processedBytes += item.sizeBytes
        }

        for (orig in originalsToDelete) {
          if (orig.exists()) orig.delete()
        }
        for (entry in stagedList) {
          val destFile = if (entry.isEncrypted && folder.obfuscateFilenames) {
            entry.originalNameForObfuscated
          } else {
            uniqueLocalFile(entry.parentDir, entry.finalDiskName)
          }
          if (!entry.stagedFile.renameTo(destFile)) {
            entry.stagedFile.copyTo(destFile, overwrite = true)
            entry.stagedFile.delete()
          }
        }
      } else {
        val tree = resolveTreeDocumentFile(Uri.parse(folder.treeUri))
          ?: error("Folder access permission unavailable")
        val allSafDocs = mutableListOf<Pair<DocumentFile, DocumentFile>>()
        gatherAllSafDocs(tree, allSafDocs)
        val docByUri = allSafDocs.associateBy { it.first.uri.toString() }

        targets.forEachIndexed { index, item ->
          val requestedName = renameMap[item.id] ?: ""
          val cleanNewName = BatchRenameEngine.sanitizeBaseName(
            raw = requestedName,
            sanitizeIllegalChars = true,
            replaceSpacesWithUnderscores = false,
            collapseSeparators = false,
            trimWhitespaceAndDots = true,
            fallback = "file",
          )
          if (cleanNewName == item.displayName) {
            skipped++
            processedBytes += item.sizeBytes
            return@forEachIndexed
          }

          onProgress(
            EncryptionProgress(
              folderName = folder.name,
              isLocking = true,
              currentFileName = "${item.displayName} → $cleanNewName",
              processedFiles = index,
              totalFiles = targets.size,
              processedBytes = processedBytes,
              totalBytes = totalBytes,
              folderId = folder.id,
              currentFileId = item.id,
              currentFileProcessedBytes = 0L,
              currentFileTotalBytes = item.sizeBytes,
              isStealthMasked = folder.isStealthHidden,
            ),
          )

          val pair = docByUri[item.uriString]
          if (pair == null) {
            failed++
            if (firstError == null) firstError = "Document unavailable: ${item.displayName}"
            return@forEachIndexed
          }
          val (doc, parent) = pair

          runCatching {
            if (item.isEncrypted) {
              val key = folderKey ?: error("Encryption key unavailable")
              val stagedDoc = parent.createFile("application/octet-stream", ".tmp_ren_${UUID.randomUUID()}")
                ?: error("Could not stage renamed SAF file")
              try {
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                  context.contentResolver.openOutputStream(stagedDoc.uri)?.use { output ->
                    encryptionManager.rewriteEncryptedMetadata(input, output, key) { oldMeta ->
                      val guessedMime = guessMimeType(cleanNewName)
                      val nextMime = if (guessedMime != "application/octet-stream") guessedMime else oldMeta.mimeType
                      oldMeta.copy(
                        originalName = cleanNewName,
                        mimeType = nextMime,
                      )
                    }
                  } ?: error("Cannot write staged SAF file")
                } ?: error("Cannot read encrypted SAF file")

                val desiredDiskName = if (folder.obfuscateFilenames) {
                  doc.name ?: encryptionManager.generateObfuscatedCipherName()
                } else {
                  "$cleanNewName${AesFolderCipher.ENCRYPTED_EXTENSION}"
                }
                doc.delete()
                if (!stagedDoc.renameTo(desiredDiskName)) {
                  stagedDoc.renameTo(encryptionManager.generateObfuscatedCipherName())
                }
              } catch (t: Throwable) {
                stagedDoc.delete()
                throw t
              }
            } else {
              if (!doc.renameTo(cleanNewName)) {
                val fallbackMime = doc.type ?: guessMimeType(cleanNewName)
                val replacement = parent.createFile(fallbackMime, cleanNewName)
                  ?: error("Could not rename SAF file ${item.displayName}")
                try {
                  context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    context.contentResolver.openOutputStream(replacement.uri)?.use { output ->
                      input.copyTo(output)
                    } ?: error("Cannot write renamed SAF file")
                  } ?: error("Cannot read SAF file")
                  doc.delete()
                } catch (t: Throwable) {
                  replacement.delete()
                  throw t
                }
              }
            }
            renamed++
          }.onFailure { err ->
            failed++
            if (firstError == null) firstError = err.message ?: "Failed to rename ${item.displayName}"
          }
          processedBytes += item.sizeBytes
        }
      }

      if (renamed > 0) {
        val refreshed = refreshFolderMetricsInternal(folder, dek)
          .copy(lastActionTimestamp = System.currentTimeMillis())
        vaultDao.upsertFolder(refreshed)
      }
      if (renamed > 0 || failed > 0) {
        logEvent(
          folderId = folder.id,
          folderName = folder.name,
          action = "FILES_BATCH_RENAMED",
          details = "Batch-renamed $renamed file(s) without decrypting payloads${if (skipped > 0) " ($skipped unchanged)" else ""}${if (failed > 0) "; $failed failed" else ""}",
          category = AuditCategory.CRYPTO_OPERATION,
          isWarning = failed > 0,
        )
      }

      BatchRenameResult(
        renamedCount = renamed,
        skippedCount = skipped,
        failedCount = failed,
        firstError = firstError,
      )
    }
  }

  suspend fun batchRenameFolders(
    renameMap: Map<String, String>,
  ): Result<BatchRenameResult> = withContext(Dispatchers.IO) {
    runCatching {
      var renamed = 0
      var skipped = 0
      var failed = 0
      var firstError: String? = null
      val now = System.currentTimeMillis()

      for ((folderId, proposedName) in renameMap) {
        val folder = vaultDao.getFolderById(folderId)
        if (folder == null) {
          failed++
          if (firstError == null) firstError = "Folder not found"
          continue
        }
        val cleanName = BatchRenameEngine.sanitizeFolderName(proposedName)
        if (cleanName == folder.name) {
          skipped++
          continue
        }
        vaultDao.upsertFolder(
          folder.copy(
            name = cleanName,
            lastActionTimestamp = now,
          ),
        )
        renamed++
      }

      if (renamed > 0) {
        logEvent(
          folderName = "Batch Folder Rename",
          action = "FOLDERS_BATCH_RENAMED",
          details = "Sanitized/renamed $renamed vault folder label(s) without decrypting encrypted contents",
          category = AuditCategory.CRYPTO_OPERATION,
        )
      }

      BatchRenameResult(
        renamedCount = renamed,
        skippedCount = skipped,
        failedCount = failed,
        firstError = firstError,
      )
    }
  }

  suspend fun peekFile(item: VaultFileItem, dek: SecretKey?): Result<SecurePreviewContent> =
    withContext(Dispatchers.IO) {
      runCatching {
        val uri = Uri.parse(item.uriString)
        val rawBytes: ByteArray
        val resolvedName: String
        val resolvedMime: String

        val inputStream = if (item.uriString.startsWith("file://")) {
          File(uri.path!!).inputStream()
        } else {
          context.contentResolver.openInputStream(uri) ?: error("Unable to open file stream")
        }

        inputStream.use { stream ->
          if (item.isEncrypted) {
            val masterKey = dek ?: error("Vault must be authenticated to peek encrypted files")
            val folderKey = item.folderId.takeIf { it.isNotBlank() }
              ?.let { vaultDao.getFolderById(it) }
              ?.let { resolveFolderKeyWithRecovery(it, masterKey) }
              ?: masterKey
            val (meta, bytes) = encryptionManager.decryptToByteArray(stream, folderKey)
            rawBytes = bytes
            resolvedName = meta.originalName
            resolvedMime = meta.mimeType
          } else {
            val limited = stream.readNBytes(8 * 1024 * 1024)
            rawBytes = limited
            resolvedName = item.displayName
            resolvedMime = item.mimeType
          }
        }

        val lowerName = resolvedName.lowercase()
        when {
          resolvedMime.startsWith("image/") ||
            lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".png") || lowerName.endsWith(".webp") -> {
            SecurePreviewContent.ImagePreview(
              fileName = resolvedName,
              bytes = rawBytes,
              mimeType = resolvedMime,
              isEncryptedOnDisk = item.isEncrypted,
            )
          }
          resolvedMime.startsWith("text/") ||
            resolvedMime == "application/json" ||
            lowerName.endsWith(".txt") || lowerName.endsWith(".md") ||
            lowerName.endsWith(".json") || lowerName.endsWith(".csv") ||
            lowerName.endsWith(".log") || lowerName.endsWith(".xml") -> {
            SecurePreviewContent.TextPreview(
              fileName = resolvedName,
              text = rawBytes.toString(Charsets.UTF_8).take(16_000),
              isEncryptedOnDisk = item.isEncrypted,
            )
          }
          else -> {
            val sha = MessageDigest.getInstance("SHA-256").digest(rawBytes)
              .take(12).joinToString("") { "%02x".format(it) }
            SecurePreviewContent.BinarySummary(
              fileName = resolvedName,
              mimeType = resolvedMime,
              sizeBytes = item.sizeBytes,
              sha256Prefix = sha,
              isEncryptedOnDisk = item.isEncrypted,
            )
          }
        }
      }
    }

  suspend fun updateFolderSecurityOptions(
    folderId: String,
    requireBiometricPerUnlock: Boolean,
    obfuscateFilenames: Boolean,
    requireAuthOnOpen: Boolean? = null,
  ) {
    val folder = vaultDao.getFolderById(folderId) ?: return
    vaultDao.updateFolder(
      folder.copy(
        requireBiometricPerUnlock = requireBiometricPerUnlock,
        obfuscateFilenames = obfuscateFilenames,
        requireAuthOnOpen = requireAuthOnOpen ?: folder.requireAuthOnOpen,
      ),
    )
  }

  suspend fun setFolderAutoCloudBackupSync(
    folderId: String,
    enabled: Boolean,
  ) {
    val folder = vaultDao.getFolderById(folderId) ?: return
    if (folder.autoCloudBackupSync == enabled) return
    vaultDao.updateFolder(folder.copy(autoCloudBackupSync = enabled))
    logEvent(
      folderId = folder.id,
      folderName = folder.name,
      action = "CLOUD_SYNC_POLICY",
      details = if (enabled) {
        "Enabled automatic cloud backup synchronization"
      } else {
        "Disabled automatic cloud backup synchronization"
      },
      category = AuditCategory.CLOUD_SYNC,
    )
  }

  suspend fun setFoldersAutoCloudBackupSyncBatch(
    folderIds: List<String>,
    enabled: Boolean,
  ): Int {
    var updatedCount = 0
    for (id in folderIds) {
      val folder = vaultDao.getFolderById(id) ?: continue
      if (folder.autoCloudBackupSync != enabled) {
        vaultDao.updateFolder(folder.copy(autoCloudBackupSync = enabled))
        updatedCount++
      }
    }
    if (updatedCount > 0) {
      logEvent(
        folderName = "Batch Cloud Sync Policy",
        action = "CLOUD_SYNC_POLICY",
        details = "${if (enabled) "Enabled" else "Disabled"} automatic cloud backup synchronization for $updatedCount folder(s)",
        category = AuditCategory.CLOUD_SYNC,
      )
    }
    return updatedCount
  }

  suspend fun removeFilesInFolder(
    folderId: String,
    fileIds: Set<String>,
  ): Result<VaultFolderEntity> = withContext(Dispatchers.IO) {
    runCatching {
      require(fileIds.isNotEmpty()) { "Select at least one file to remove" }
      val folder = vaultDao.getFolderById(folderId) ?: error("Folder not found")
      var removed = 0
      var removedBytes = 0L

      if (folder.isInternalVault) {
        val root = internalFolderDir(folder.id)
        if (!root.exists()) root.mkdirs()
        val matching = root.walkTopDown()
          .filter { it.isFile && !isInternalScratchFile(it.name) && it.absolutePath in fileIds }
          .toList()
        for (file in matching) {
          val size = file.length()
          if (!file.delete() && file.exists()) {
            error("Unable to delete '${file.name}'")
          }
          removedBytes += size
          removed++
        }
        val anyEncRemaining = root.walkTopDown()
          .any { it.isFile && it.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION) }
        if (!anyEncRemaining) {
          File(root, NOMEDIA_FILE).takeIf { it.exists() }?.delete()
        }
      } else {
        val tree = resolveTreeDocumentFile(Uri.parse(folder.treeUri))
          ?: error("Folder access permission unavailable")
        val allDocs = mutableListOf<Pair<DocumentFile, DocumentFile>>()
        gatherAllSafDocs(tree, allDocs)
        val matching = allDocs.filter { (doc, _) -> doc.uri.toString() in fileIds }
        for ((doc, _) in matching) {
          val size = doc.length()
          if (!deleteSafDocStrict(doc)) {
            error("Unable to delete '${doc.name ?: "file"}'")
          }
          removedBytes += size
          removed++
        }
        val remainingEnc = mutableListOf<Pair<DocumentFile, DocumentFile>>()
        gatherEncryptedSafDocs(tree, remainingEnc)
        if (remainingEnc.isEmpty()) {
          tree.findFile(NOMEDIA_FILE)?.let { deleteSafDocStrict(it) }
        }
      }

      val refreshed = refreshFolderMetricsInternal(folder, dek = null)
      val finalEntity = refreshed.copy(
        isLocked = if (refreshed.encryptedFileCount == 0) false else refreshed.isLocked,
        lastActionTimestamp = System.currentTimeMillis(),
      )
      vaultDao.upsertFolder(finalEntity)
      if (removed > 0) {
        logEvent(
          folderId = finalEntity.id,
          folderName = finalEntity.name,
          action = "FILES_REMOVED",
          details = "Removed $removed file(s) (${formatSize(removedBytes)}) from folder list",
        )
      }
      finalEntity
    }
  }

  suspend fun removeFolderFromVault(
    folderId: String,
    dek: SecretKey?,
    decryptLockedSafFirst: Boolean = true,
    onProgress: (EncryptionProgress) -> Unit = {},
  ) {
    val folder = vaultDao.getFolderById(folderId) ?: return
    if (folder.isInternalVault) {
      internalFolderDir(folder.id).deleteRecursively()
    } else if (decryptLockedSafFirst && (folder.isLocked || folder.encryptedFileCount > 0) && dek != null) {
      unlockFolder(folderId, dek, onProgress = onProgress).getOrThrow()
    }
    vaultDao.deleteFolderById(folderId)
    val note = when {
      folder.isInternalVault -> "Deleted sandboxed private vault folder and contents"
      decryptLockedSafFirst && folder.encryptedFileCount > 0 ->
        "Decrypted device folder in-place and removed from BeastLock list"
      else -> "Unlinked device folder from BeastLock list"
    }
    logEvent(
      folderId = null,
      folderName = safeAuditName(folder),
      action = "FOLDER_REMOVED",
      details = note,
    )
  }

  suspend fun removeFoldersBatch(
    folderIds: List<String>,
    dek: SecretKey?,
    decryptLockedSafFirst: Boolean = true,
    onProgress: (EncryptionProgress) -> Unit = {},
  ): BatchFolderResult {
    var succeeded = 0
    var failed = 0
    var totalFiles = 0
    var totalBytes = 0L
    var firstError: String? = null

    val targets = folderIds.mapNotNull { vaultDao.getFolderById(it) }
    targets.forEachIndexed { index, folder ->
      onProgress(
        EncryptionProgress(
          folderName = "[${index + 1}/${targets.size}] ${folder.name}",
          isLocking = false,
          currentFileName = if (!folder.isInternalVault && decryptLockedSafFirst && folder.isLocked) {
            "Decrypting before removing..."
          } else {
            "Removing from vault..."
          },
          processedFiles = index,
          totalFiles = targets.size,
          processedBytes = totalBytes,
          totalBytes = targets.sumOf { it.totalBytes },
          folderId = folder.id,
          isStealthMasked = folder.isStealthHidden,
        ),
      )
      runCatching {
        removeFolderFromVault(
          folderId = folder.id,
          dek = dek,
          decryptLockedSafFirst = decryptLockedSafFirst,
          onProgress = onProgress,
        )
      }.onSuccess {
        succeeded++
        totalFiles += folder.fileCount
        totalBytes += folder.totalBytes
      }.onFailure { err ->
        failed++
        if (firstError == null) firstError = err.message ?: "Failed to remove ${folder.name}"
      }
    }

    if (succeeded > 0 || failed > 0) {
      logEvent(
        folderName = "Batch Remove",
        action = "FOLDERS_BATCH_REMOVED",
        details = "Removed $succeeded folder(s) from vault list${if (failed > 0) "; $failed failed" else ""}",
        category = AuditCategory.CRYPTO_OPERATION,
        isWarning = failed > 0,
      )
    }

    return BatchFolderResult(
      succeededCount = succeeded,
      failedCount = failed,
      totalFiles = totalFiles,
      totalBytes = totalBytes,
      firstError = firstError,
    )
  }

  suspend fun refreshFolderMetrics(folderId: String, dek: SecretKey?) {
    val folder = vaultDao.getFolderById(folderId) ?: return
    refreshFolderMetricsInternal(folder, dek)
  }

  private suspend fun refreshFolderMetricsInternal(
    folder: VaultFolderEntity,
    dek: SecretKey?,
  ): VaultFolderEntity = withContext(Dispatchers.IO) {
    val stats = if (folder.isInternalVault) {
      inspectInternalStats(internalFolderDir(folder.id))
    } else {
      val tree = resolveTreeDocumentFile(Uri.parse(folder.treeUri))
      if (tree != null) inspectSafStats(tree) else FolderStats(0, 0, 0L, 0L, 0L)
    }
    val totalFiles = stats.plainCount + stats.encryptedCount
    val lockedState = when {
      stats.encryptedCount > 0 && stats.plainCount == 0 -> true
      stats.plainCount > 0 && stats.encryptedCount == 0 -> false
      stats.encryptedCount == 0 && stats.plainCount == 0 -> folder.isStealthHidden
      else -> folder.isLocked
    }
    val updated = folder.copy(
      fileCount = totalFiles,
      encryptedFileCount = stats.encryptedCount,
      totalBytes = stats.totalBytes,
      encryptedBytes = stats.encryptedBytes,
      unencryptedBytes = stats.unencryptedBytes,
      isLocked = lockedState,
    )
    vaultDao.upsertFolder(updated)
    updated
  }

  private fun internalFolderDir(folderId: String): File {
    val stealthDir = File(File(context.filesDir, STEALTH_SANDBOX_DIR), folderId)
    val legacyDir = File(File(context.filesDir, LEGACY_SANDBOX_DIR), folderId)
    if (!stealthDir.exists() && legacyDir.exists()) {
      return legacyDir
    }
    return stealthDir
  }

  private data class FolderStats(
    val plainCount: Int,
    val encryptedCount: Int,
    val totalBytes: Long,
    val encryptedBytes: Long,
    val unencryptedBytes: Long,
  )

  private fun isInternalScratchFile(name: String): Boolean {
    return name == NOMEDIA_FILE ||
      name.startsWith(".tmp_") ||
      name.startsWith(".dec_") ||
      name.startsWith(".reenc_")
  }

  private fun inspectInternalStats(dir: File): FolderStats {
    if (!dir.exists()) return FolderStats(0, 0, 0L, 0L, 0L)
    var plain = 0
    var enc = 0
    var encBytes = 0L
    var plainBytes = 0L
    dir.walkTopDown().filter { it.isFile && !isInternalScratchFile(it.name) }.forEach { f ->
      val len = f.length()
      if (f.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION)) {
        enc++
        encBytes += len
      } else {
        plain++
        plainBytes += len
      }
    }
    return FolderStats(plain, enc, encBytes + plainBytes, encBytes, plainBytes)
  }

  private fun inspectSafStats(dir: DocumentFile): FolderStats {
    var plain = 0
    var enc = 0
    var encBytes = 0L
    var plainBytes = 0L
    for (child in dir.listFiles()) {
      val name = child.name ?: continue
      if (isInternalScratchFile(name)) continue
      if (child.isDirectory) {
        val sub = inspectSafStats(child)
        plain += sub.plainCount
        enc += sub.encryptedCount
        encBytes += sub.encryptedBytes
        plainBytes += sub.unencryptedBytes
      } else if (child.isFile) {
        val len = child.length()
        if (name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION)) {
          enc++
          encBytes += len
        } else {
          plain++
          plainBytes += len
        }
      }
    }
    return FolderStats(plain, enc, encBytes + plainBytes, encBytes, plainBytes)
  }

  private fun collectInternalFiles(
    folderId: String,
    root: File,
    current: File,
    dek: SecretKey?,
    out: MutableList<VaultFileItem>,
  ) {
    val children = current.listFiles() ?: return
    for (child in children) {
      if (isInternalScratchFile(child.name)) {
        continue
      }
      if (child.isDirectory) {
        collectInternalFiles(folderId, root, child, dek, out)
      } else if (child.isFile) {
        val isEnc = child.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION)
        val rel = child.parentFile?.relativeTo(root)?.path?.takeIf { it != "." && it.isNotEmpty() } ?: ""
        var displayName = child.name
        var mime = guessMimeType(child.name)
        var size = child.length()
        if (isEnc && dek != null) {
          runCatching {
            child.inputStream().use { input ->
              val meta = encryptionManager.readMetadata(input, dek)
              displayName = meta.originalName
              mime = meta.mimeType
              size = meta.originalSize
            }
          }
        }
        out.add(
          VaultFileItem(
            id = child.absolutePath,
            displayName = displayName,
            rawFileName = child.name,
            sizeBytes = size,
            mimeType = mime,
            isEncrypted = isEnc,
            uriString = Uri.fromFile(child).toString(),
            lastModified = child.lastModified(),
            relativeSubPath = rel,
            folderId = folderId,
          ),
        )
      }
    }
  }

  private fun collectSafFiles(
    folderId: String,
    dir: DocumentFile,
    relPath: String,
    dek: SecretKey?,
    out: MutableList<VaultFileItem>,
  ) {
    for (child in dir.listFiles()) {
      val name = child.name ?: continue
      if (isInternalScratchFile(name)) continue
      if (child.isDirectory) {
        val nextPath = if (relPath.isEmpty()) name else "$relPath/$name"
        collectSafFiles(folderId, child, nextPath, dek, out)
      } else if (child.isFile) {
        val isEnc = name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION)
        var displayName = name
        var mime = child.type ?: guessMimeType(name)
        var size = child.length()
        if (isEnc && dek != null) {
          runCatching {
            context.contentResolver.openInputStream(child.uri)?.use { input ->
              val meta = encryptionManager.readMetadata(input, dek)
              displayName = meta.originalName
              mime = meta.mimeType
              size = meta.originalSize
            }
          }
        }
        out.add(
          VaultFileItem(
            id = child.uri.toString(),
            displayName = displayName,
            rawFileName = name,
            sizeBytes = size,
            mimeType = mime,
            isEncrypted = isEnc,
            uriString = child.uri.toString(),
            lastModified = child.lastModified(),
            relativeSubPath = relPath,
            folderId = folderId,
          ),
        )
      }
    }
  }

  private fun gatherPlainSafDocs(
    dir: DocumentFile,
    out: MutableList<Pair<DocumentFile, DocumentFile>>,
  ) {
    for (child in dir.listFiles()) {
      val name = child.name ?: continue
      if (isInternalScratchFile(name)) continue
      if (child.isDirectory) {
        gatherPlainSafDocs(child, out)
      } else if (child.isFile && !name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION)) {
        out.add(child to dir)
      }
    }
  }

  private fun gatherEncryptedSafDocs(
    dir: DocumentFile,
    out: MutableList<Pair<DocumentFile, DocumentFile>>,
  ) {
    for (child in dir.listFiles()) {
      val name = child.name ?: continue
      if (isInternalScratchFile(name)) continue
      if (child.isDirectory) {
        gatherEncryptedSafDocs(child, out)
      } else if (child.isFile && name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION)) {
        out.add(child to dir)
      }
    }
  }

  private fun gatherAllSafDocs(
    dir: DocumentFile,
    out: MutableList<Pair<DocumentFile, DocumentFile>>,
  ) {
    for (child in dir.listFiles()) {
      val name = child.name ?: continue
      if (isInternalScratchFile(name)) continue
      if (child.isDirectory) {
        gatherAllSafDocs(child, out)
      } else if (child.isFile) {
        out.add(child to dir)
      }
    }
  }

  private fun resolveTreeDocumentFile(treeUri: Uri): DocumentFile? {
    return if (treeUri.scheme == "file") {
      treeUri.path?.let { DocumentFile.fromFile(File(it)) }
    } else {
      DocumentFile.fromTreeUri(context, treeUri)
    }
  }

  private fun createSafFile(
    parent: DocumentFile,
    mimeType: String,
    exactName: String,
  ): DocumentFile? {
    if (parent.uri.scheme == "file") {
      val dir = parent.uri.path?.let { File(it) } ?: return null
      if (!dir.exists()) dir.mkdirs()
      val target = File(dir, exactName)
      return if (target.createNewFile() || target.exists()) DocumentFile.fromFile(target) else null
    }
    return parent.createFile(mimeType, exactName)
  }

  private fun deleteSafDocStrict(doc: DocumentFile): Boolean {
    if (!doc.exists()) return true
    if (runCatching { doc.delete() }.getOrDefault(false) && !doc.exists()) return true
    val contractDeleted = runCatching {
      DocumentsContract.deleteDocument(context.contentResolver, doc.uri)
    }.getOrDefault(false)
    return contractDeleted || !doc.exists()
  }

  private fun tryDeleteExternalSourceUri(uri: Uri): Boolean {
    return when (uri.scheme) {
      "file" -> uri.path?.let { path ->
        val file = File(path)
        file.delete() || !file.exists()
      } ?: false
      else -> {
        val docDeleted = runCatching {
          DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrDefault(false)
        if (docDeleted) {
          true
        } else {
          runCatching {
            DocumentFile.fromSingleUri(context, uri)?.delete() == true
          }.getOrDefault(false)
        }
      }
    }
  }

  private fun uniqueSafName(parent: DocumentFile, desiredName: String): String {
    if (parent.findFile(desiredName) == null) return desiredName
    val dot = desiredName.lastIndexOf('.')
    val base = if (dot > 0) desiredName.substring(0, dot) else desiredName
    val ext = if (dot > 0) desiredName.substring(dot) else ""
    var counter = 1
    var candidate = "${base}_$counter$ext"
    while (parent.findFile(candidate) != null) {
      counter++
      candidate = "${base}_$counter$ext"
    }
    return candidate
  }

  private fun queryUriMetadata(uri: Uri): Triple<String, String, Long?> {
    var name = "imported_file_${System.currentTimeMillis()}"
    var size: Long? = null
    if (uri.scheme == "file") {
      uri.path?.let { path ->
        val f = File(path)
        if (f.name.isNotBlank()) name = f.name
        if (f.exists()) size = f.length()
      }
    } else {
      context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
      )?.use { cursor ->
        if (cursor.moveToFirst()) {
          val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (nameIdx >= 0) {
            cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
          }
          val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
          if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
            val s = cursor.getLong(sizeIdx)
            if (s >= 0L) size = s
          }
        }
      }
    }
    val mime = context.contentResolver.getType(uri) ?: guessMimeType(name)
    return Triple(sanitizeFileName(name), mime, size)
  }

  private fun uniqueLocalFile(dir: File, desiredName: String): File {
    var candidate = File(dir, desiredName)
    if (!candidate.exists()) return candidate
    val dot = desiredName.lastIndexOf('.')
    val base = if (dot > 0) desiredName.substring(0, dot) else desiredName
    val ext = if (dot > 0) desiredName.substring(dot) else ""
    var counter = 1
    while (candidate.exists()) {
      candidate = File(dir, "${base}_$counter$ext")
      counter++
    }
    return candidate
  }

  private fun sanitizeFileName(name: String): String {
    return name.replace('/', '_').replace('\\', '_').replace("..", "_").ifBlank { "file" }
  }

  private fun guessMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
  }

  private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
  }
}
