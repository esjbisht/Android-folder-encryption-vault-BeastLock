package com.example.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.example.crypto.AesFolderCipher
import com.example.crypto.EncryptedFileMetadata
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
)

data class BatchFolderResult(
  val succeededCount: Int,
  val failedCount: Int,
  val totalFiles: Int,
  val totalBytes: Long,
  val firstError: String? = null,
  val skippedCount: Int = 0,
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
) {
  companion object {
    private const val INTERNAL_URI_PREFIX = "app-internal://"
    private const val NOMEDIA_FILE = ".nomedia"
  }

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
      AesFolderCipher.unwrapDekWithKey(wrapped, masterDek)
    }
  }

  suspend fun registerSafFolder(
    treeUri: Uri,
    obfuscateDefault: Boolean,
    requireBioDefault: Boolean = true,
  ): Result<VaultFolderEntity> = withContext(Dispatchers.IO) {
    runCatching {
      try {
        context.contentResolver.takePersistableUriPermission(
          treeUri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
      } catch (_: SecurityException) {
        // Fallback when provider grants transient permission during tests or special providers
      }

      val uriString = treeUri.toString()
      val existing = vaultDao.getFolderByUri(uriString)
      if (existing != null) {
        return@runCatching refreshFolderMetricsInternal(existing, dek = null)
      }

      val docTree = DocumentFile.fromTreeUri(context, treeUri)
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
  ): Result<VaultFolderEntity> = withContext(Dispatchers.IO) {
    runCatching {
      val cleanName = folderName.trim().ifBlank { "Private Vault" }
      val id = UUID.randomUUID().toString()
      val dir = File(File(context.filesDir, "vaults"), id)
      if (!dir.exists() && !dir.mkdirs()) {
        error("Could not create private vault directory")
      }
      val now = System.currentTimeMillis()
      val entity = VaultFolderEntity(
        id = id,
        name = cleanName,
        treeUri = "$INTERNAL_URI_PREFIX$id",
        isInternalVault = true,
        isLocked = false,
        fileCount = 0,
        encryptedFileCount = 0,
        totalBytes = 0L,
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
        details = "Created sandboxed private vault folder",
      )
      entity
    }
  }

  suspend fun importFilesIntoFolder(
    folderId: String,
    uris: List<Uri>,
    dek: SecretKey?,
  ): Result<Int> = withContext(Dispatchers.IO) {
    runCatching {
      val folder = vaultDao.getFolderById(folderId) ?: error("Folder not found")
      val folderKey = dek?.let { resolveFolderKey(folder, it) }
      var imported = 0
      for (sourceUri in uris) {
        val (fileName, mimeType) = queryUriMetadata(sourceUri)
        val inputStream = context.contentResolver.openInputStream(sourceUri) ?: continue
        inputStream.use { input ->
          if (folder.isInternalVault) {
            val dir = internalFolderDir(folder.id)
            if (!dir.exists()) dir.mkdirs()
            if (folder.isLocked && folderKey != null) {
              val targetName = if (folder.obfuscateFilenames) {
                "${UUID.randomUUID()}${AesFolderCipher.ENCRYPTED_EXTENSION}"
              } else {
                uniqueLocalFile(dir, "$fileName${AesFolderCipher.ENCRYPTED_EXTENSION}").name
              }
              val targetFile = File(dir, targetName)
              val tempBytes = input.readBytes()
              targetFile.outputStream().use { out ->
                AesFolderCipher.encryptStream(
                  input = ByteArrayInputStream(tempBytes),
                  output = out,
                  key = folderKey,
                  metadata = EncryptedFileMetadata(
                    originalName = fileName,
                    mimeType = mimeType,
                    originalSize = tempBytes.size.toLong(),
                    encryptedAt = System.currentTimeMillis(),
                  ),
                )
              }
            } else {
              val targetFile = uniqueLocalFile(dir, fileName)
              targetFile.outputStream().use { out -> input.copyTo(out) }
            }
            imported++
          } else {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
              ?: error("Folder access permission lost")
            if (folder.isLocked && folderKey != null) {
              val tempBytes = input.readBytes()
              val targetName = if (folder.obfuscateFilenames) {
                "${UUID.randomUUID()}${AesFolderCipher.ENCRYPTED_EXTENSION}"
              } else {
                "$fileName${AesFolderCipher.ENCRYPTED_EXTENSION}"
              }
              val created = tree.createFile("application/octet-stream", targetName)
                ?: error("Failed to create encrypted file")
              context.contentResolver.openOutputStream(created.uri)?.use { out ->
                AesFolderCipher.encryptStream(
                  input = ByteArrayInputStream(tempBytes),
                  output = out,
                  key = folderKey,
                  metadata = EncryptedFileMetadata(
                    originalName = fileName,
                    mimeType = mimeType,
                    originalSize = tempBytes.size.toLong(),
                    encryptedAt = System.currentTimeMillis(),
                  ),
                )
              }
            } else {
              val created = tree.createFile(mimeType, fileName)
                ?: error("Failed to import file into folder")
              context.contentResolver.openOutputStream(created.uri)?.use { out ->
                input.copyTo(out)
              }
            }
            imported++
          }
        }
      }
      refreshFolderMetricsInternal(folder, dek)
      if (imported > 0) {
        logEvent(
          folderId = folder.id,
          folderName = folder.name,
          action = "FILE_IMPORTED",
          details = "Imported $imported file(s)${if (folder.isLocked) " and encrypted immediately" else ""}",
        )
      }
      imported
    }
  }

  suspend fun listFolderFiles(folderId: String, dek: SecretKey?): Result<List<VaultFileItem>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val folder = vaultDao.getFolderById(folderId) ?: error("Folder not found")
        val folderKey = dek?.let { resolveFolderKey(folder, it) }
        if (folder.isInternalVault) {
          val root = internalFolderDir(folder.id)
          if (!root.exists()) return@runCatching emptyList()
          val results = mutableListOf<VaultFileItem>()
          collectInternalFiles(folder.id, root, root, folderKey, results)
          results.sortedWith(compareByDescending<VaultFileItem> { it.isEncrypted }.thenBy { it.displayName.lowercase() })
        } else {
          val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
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
    onProgress: (EncryptionProgress) -> Unit = {},
  ): Result<VaultFolderEntity> = withContext(Dispatchers.IO) {
    runCatching {
      val folder = vaultDao.getFolderById(folderId) ?: error("Folder not found")
      val folderKey = resolveFolderKey(folder, dek)
      var processed = 0
      var processedBytes = 0L

      if (folder.isInternalVault) {
        val root = internalFolderDir(folder.id)
        if (!root.exists()) root.mkdirs()
        val plainFiles = root.walkTopDown()
          .filter {
            it.isFile &&
              !it.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION) &&
              !isInternalScratchFile(it.name)
          }
          .toList()
        val totalBytes = plainFiles.sumOf { it.length() }

        for (file in plainFiles) {
          onProgress(
            EncryptionProgress(
              folderName = folder.name,
              isLocking = true,
              currentFileName = file.name,
              processedFiles = processed,
              totalFiles = plainFiles.size,
              processedBytes = processedBytes,
              totalBytes = totalBytes,
            ),
          )
          val encryptedName = if (folder.obfuscateFilenames) {
            "${UUID.randomUUID()}${AesFolderCipher.ENCRYPTED_EXTENSION}"
          } else {
            "${file.name}${AesFolderCipher.ENCRYPTED_EXTENSION}"
          }
          val targetFile = uniqueLocalFile(file.parentFile ?: root, encryptedName)
          val tempFile = File(file.parentFile ?: root, ".tmp_${UUID.randomUUID()}")
          val mime = guessMimeType(file.name)
          file.inputStream().use { input ->
            tempFile.outputStream().use { output ->
              AesFolderCipher.encryptStream(
                input = input,
                output = output,
                key = folderKey,
                metadata = EncryptedFileMetadata(
                  originalName = file.name,
                  mimeType = mime,
                  originalSize = file.length(),
                  encryptedAt = System.currentTimeMillis(),
                ),
              )
            }
          }
          if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
          }
          processedBytes += file.length()
          file.delete()
          processed++
        }
        val nomedia = File(root, NOMEDIA_FILE)
        if (!nomedia.exists()) nomedia.createNewFile()
      } else {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
          ?: error("Folder access permission unavailable")
        val plainDocs = mutableListOf<Pair<DocumentFile, DocumentFile>>()
        gatherPlainSafDocs(tree, plainDocs)
        val totalBytes = plainDocs.sumOf { it.first.length() }

        for ((doc, parent) in plainDocs) {
          val origName = doc.name ?: "file"
          val origSize = doc.length()
          onProgress(
            EncryptionProgress(
              folderName = folder.name,
              isLocking = true,
              currentFileName = origName,
              processedFiles = processed,
              totalFiles = plainDocs.size,
              processedBytes = processedBytes,
              totalBytes = totalBytes,
            ),
          )
          val targetName = if (folder.obfuscateFilenames) {
            "${UUID.randomUUID()}${AesFolderCipher.ENCRYPTED_EXTENSION}"
          } else {
            "$origName${AesFolderCipher.ENCRYPTED_EXTENSION}"
          }
          val outDoc = parent.createFile("application/octet-stream", targetName)
            ?: error("Could not create encrypted file in folder")
          val mime = doc.type ?: guessMimeType(origName)
          var encryptedOk = false
          try {
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
              context.contentResolver.openOutputStream(outDoc.uri)?.use { output ->
                AesFolderCipher.encryptStream(
                  input = input,
                  output = output,
                  key = folderKey,
                  metadata = EncryptedFileMetadata(
                    originalName = origName,
                    mimeType = mime,
                    originalSize = origSize,
                    encryptedAt = System.currentTimeMillis(),
                  ),
                )
                encryptedOk = true
              }
            }
          } catch (t: Throwable) {
            outDoc.delete()
            throw t
          }
          if (encryptedOk) {
            doc.delete()
          }
          processedBytes += origSize
          processed++
        }
        if (tree.findFile(NOMEDIA_FILE) == null) {
          tree.createFile("application/octet-stream", NOMEDIA_FILE)
        }
      }

      val updated = refreshFolderMetricsInternal(folder.copy(isLocked = true), dek)
        .copy(isLocked = true, lastActionTimestamp = System.currentTimeMillis())
      vaultDao.upsertFolder(updated)
      logEvent(
        folderId = updated.id,
        folderName = updated.name,
        action = "LOCKED",
        details = "AES-256-GCM encrypted $processed file(s) (${formatSize(processedBytes)})",
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
      val folderKey = resolveFolderKey(folder, dek)
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
          val tempPlain = File(parent, ".dec_${UUID.randomUUID()}")
          var meta: EncryptedFileMetadata? = null
          try {
            encFile.inputStream().use { input ->
              tempPlain.outputStream().use { output ->
                meta = AesFolderCipher.decryptStream(input, output, folderKey)
              }
            }
          } catch (t: Throwable) {
            tempPlain.delete()
            throw t
          }
          val resolvedMeta = meta ?: error("Missing metadata after decryption")
          onProgress(
            EncryptionProgress(
              folderName = folder.name,
              isLocking = false,
              currentFileName = resolvedMeta.originalName,
              processedFiles = processed,
              totalFiles = encryptedFiles.size,
              processedBytes = processedBytes,
              totalBytes = totalBytes,
            ),
          )
          val restoredFile = uniqueLocalFile(parent, sanitizeFileName(resolvedMeta.originalName))
          if (!tempPlain.renameTo(restoredFile)) {
            tempPlain.copyTo(restoredFile, overwrite = true)
            tempPlain.delete()
          }
          processedBytes += encFile.length()
          encFile.delete()
          processed++
        }
        File(root, NOMEDIA_FILE).takeIf { it.exists() }?.delete()
      } else {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
          ?: error("Folder access permission unavailable")
        val encryptedDocs = mutableListOf<Pair<DocumentFile, DocumentFile>>()
        gatherEncryptedSafDocs(tree, encryptedDocs)
        val totalBytes = encryptedDocs.sumOf { it.first.length() }

        for ((encDoc, parent) in encryptedDocs) {
          val meta = context.contentResolver.openInputStream(encDoc.uri)?.use { input ->
            AesFolderCipher.readMetadata(input, folderKey)
          } ?: error("Cannot read encrypted header")

          onProgress(
            EncryptionProgress(
              folderName = folder.name,
              isLocking = false,
              currentFileName = meta.originalName,
              processedFiles = processed,
              totalFiles = encryptedDocs.size,
              processedBytes = processedBytes,
              totalBytes = totalBytes,
            ),
          )

          val safeName = sanitizeFileName(meta.originalName)
          val targetDoc = parent.createFile(meta.mimeType.ifBlank { "application/octet-stream" }, safeName)
            ?: error("Could not restore decrypted file")

          var decryptedOk = false
          try {
            context.contentResolver.openInputStream(encDoc.uri)?.use { input ->
              context.contentResolver.openOutputStream(targetDoc.uri)?.use { output ->
                AesFolderCipher.decryptStream(input, output, folderKey)
                decryptedOk = true
              }
            }
          } catch (t: Throwable) {
            targetDoc.delete()
            throw t
          }
          if (decryptedOk) {
            processedBytes += encDoc.length()
            encDoc.delete()
          }
          processed++
        }
        tree.findFile(NOMEDIA_FILE)?.delete()
      }

      val updated = refreshFolderMetricsInternal(folder.copy(isLocked = false), dek)
        .copy(isLocked = false, lastActionTimestamp = System.currentTimeMillis())
      vaultDao.upsertFolder(updated)
      logEvent(
        folderId = updated.id,
        folderName = updated.name,
        action = "UNLOCKED",
        details = "Verified GCM tags & decrypted $processed file(s)",
      )
      updated
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

    val targets = folderIds.mapNotNull { vaultDao.getFolderById(it) }.filter { !it.isLocked }
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

    val targets = folderIds.mapNotNull { vaultDao.getFolderById(it) }.filter { it.isLocked }
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
      val oldFolderKey = resolveFolderKey(folder, masterDek)
      val newFolderKey = AesFolderCipher.generateAes256Key()
      val wrappedNewKey = AesFolderCipher.wrapDekWithKey(newFolderKey, masterDek)
      val newFingerprint = AesFolderCipher.computeKeyFingerprint(newFolderKey)

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
            try {
              val recoveredMeta = encFile.inputStream().use { input ->
                tempPlain.outputStream().use { plainOut ->
                  AesFolderCipher.decryptStream(input, plainOut, oldFolderKey)
                }
              }
              onProgress(
                EncryptionProgress(
                  folderName = folder.name,
                  isLocking = true,
                  currentFileName = recoveredMeta.originalName,
                  processedFiles = processed,
                  totalFiles = totalFiles,
                  processedBytes = processedBytes,
                  totalBytes = totalBytes,
                ),
              )
              tempPlain.inputStream().use { plainIn ->
                stagedCipher.outputStream().use { cipherOut ->
                  AesFolderCipher.encryptStream(
                    input = plainIn,
                    output = cipherOut,
                    key = newFolderKey,
                    metadata = recoveredMeta.copy(encryptedAt = System.currentTimeMillis()),
                  )
                }
              }
              stagedEncryptedSwaps.add(encFile to stagedCipher)
              processedBytes += encFile.length()
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
            onProgress(
              EncryptionProgress(
                folderName = folder.name,
                isLocking = true,
                currentFileName = plainFile.name,
                processedFiles = processed,
                totalFiles = totalFiles,
                processedBytes = processedBytes,
                totalBytes = totalBytes,
              ),
            )
            val targetName = if (folder.obfuscateFilenames) {
              "${UUID.randomUUID()}${AesFolderCipher.ENCRYPTED_EXTENSION}"
            } else {
              "${plainFile.name}${AesFolderCipher.ENCRYPTED_EXTENSION}"
            }
            plainFile.inputStream().use { input ->
              stagedCipher.outputStream().use { output ->
                AesFolderCipher.encryptStream(
                  input = input,
                  output = output,
                  key = newFolderKey,
                  metadata = EncryptedFileMetadata(
                    originalName = plainFile.name,
                    mimeType = guessMimeType(plainFile.name),
                    originalSize = plainFile.length(),
                    encryptedAt = System.currentTimeMillis(),
                  ),
                )
              }
            }
            stagedPlainAdditions.add(Triple(plainFile, stagedCipher, targetName))
            processedBytes += plainFile.length()
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
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
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
            try {
              val recoveredMeta = context.contentResolver.openInputStream(encDoc.uri)?.use { input ->
                tempPlain.outputStream().use { plainOut ->
                  AesFolderCipher.decryptStream(input, plainOut, oldFolderKey)
                }
              } ?: error("Cannot read encrypted SAF file")

              onProgress(
                EncryptionProgress(
                  folderName = folder.name,
                  isLocking = true,
                  currentFileName = recoveredMeta.originalName,
                  processedFiles = processed,
                  totalFiles = totalFiles,
                  processedBytes = processedBytes,
                  totalBytes = totalBytes,
                ),
              )

              tempPlain.inputStream().use { plainIn ->
                context.contentResolver.openOutputStream(stagedDoc.uri)?.use { cipherOut ->
                  AesFolderCipher.encryptStream(
                    input = plainIn,
                    output = cipherOut,
                    key = newFolderKey,
                    metadata = recoveredMeta.copy(encryptedAt = System.currentTimeMillis()),
                  )
                } ?: error("Cannot write staged SAF file")
              }

              val desiredFinalName = encDoc.name ?: "${UUID.randomUUID()}${AesFolderCipher.ENCRYPTED_EXTENSION}"
              stagedSwaps.add(StagedSafSwap(encDoc, stagedDoc, desiredFinalName))
              processedBytes += encDoc.length()
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
              ),
            )
            try {
              context.contentResolver.openInputStream(plainDoc.uri)?.use { input ->
                context.contentResolver.openOutputStream(stagedDoc.uri)?.use { output ->
                  AesFolderCipher.encryptStream(
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
                "${UUID.randomUUID()}${AesFolderCipher.ENCRYPTED_EXTENSION}"
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
            swap.stagedDoc.renameTo("${UUID.randomUUID()}${AesFolderCipher.ENCRYPTED_EXTENSION}")
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
        folderId = refreshed.id,
        folderName = refreshed.name,
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
        resolveFolderKey(folder, masterDek)
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
                    AesFolderCipher.rewriteEncryptedMetadata(input, output, key) { oldMeta ->
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
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
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
                    AesFolderCipher.rewriteEncryptedMetadata(input, output, key) { oldMeta ->
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
                  doc.name ?: "${UUID.randomUUID()}${AesFolderCipher.ENCRYPTED_EXTENSION}"
                } else {
                  "$cleanNewName${AesFolderCipher.ENCRYPTED_EXTENSION}"
                }
                doc.delete()
                if (!stagedDoc.renameTo(desiredDiskName)) {
                  stagedDoc.renameTo("${UUID.randomUUID()}${AesFolderCipher.ENCRYPTED_EXTENSION}")
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
              ?.let { resolveFolderKey(it, masterKey) }
              ?: masterKey
            val (meta, bytes) = AesFolderCipher.decryptToByteArray(stream, folderKey)
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
  ) {
    val folder = vaultDao.getFolderById(folderId) ?: return
    vaultDao.updateFolder(
      folder.copy(
        requireBiometricPerUnlock = requireBiometricPerUnlock,
        obfuscateFilenames = obfuscateFilenames,
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

  suspend fun removeFolderFromVault(folderId: String, dek: SecretKey?) {
    val folder = vaultDao.getFolderById(folderId) ?: return
    if (folder.isLocked && dek != null) {
      unlockFolder(folderId, dek)
    }
    if (folder.isInternalVault) {
      internalFolderDir(folder.id).deleteRecursively()
    }
    vaultDao.deleteFolderById(folderId)
    logEvent(
      folderId = null,
      folderName = folder.name,
      action = "FOLDER_REMOVED",
      details = "Removed folder from BeastLock index",
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
      val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
      if (tree != null) inspectSafStats(tree) else FolderStats(0, 0, 0L, 0L, 0L)
    }
    val totalFiles = stats.plainCount + stats.encryptedCount
    val lockedState = when {
      stats.encryptedCount > 0 && stats.plainCount == 0 -> true
      stats.plainCount > 0 && stats.encryptedCount == 0 -> false
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

  private fun internalFolderDir(folderId: String): File =
    File(File(context.filesDir, "vaults"), folderId)

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
              val meta = AesFolderCipher.readMetadata(input, dek)
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
              val meta = AesFolderCipher.readMetadata(input, dek)
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

  private fun queryUriMetadata(uri: Uri): Pair<String, String> {
    var name = "imported_file_${System.currentTimeMillis()}"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        if (cursor.moveToFirst()) {
          val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) {
            cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { name = it }
          }
        }
      }
    val mime = context.contentResolver.getType(uri) ?: guessMimeType(name)
    return sanitizeFileName(name) to mime
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
