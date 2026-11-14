package com.example.data.storage

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.example.crypto.AesFolderCipher
import com.example.data.audit.AuditCategory
import com.example.data.audit.EncryptedAuditLogRepository
import com.example.data.backup.CloudBackupManager
import com.example.data.local.VaultDao
import com.example.data.local.VaultFolderEntity
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class OrphanCategory(val label: String) {
  INCOMPLETE_REENCRYPTION("Incomplete Re-Encryption"),
  INTERRUPTED_CLOUD_SYNC("Interrupted Cloud Sync"),
  ORPHANED_VAULT_DIR("Unreferenced Vault Directory"),
}

data class OrphanedFileCandidate(
  val id: String,
  val displayName: String,
  val category: OrphanCategory,
  val locationLabel: String,
  val sizeBytes: Long,
  val lastModifiedMillis: Long,
  val localPath: String? = null,
  val documentUriString: String? = null,
  val isDirectory: Boolean = false,
)

data class OrphanCleanupResult(
  val deletedCount: Int,
  val failedCount: Int,
  val freedBytes: Long,
)

enum class MigrationExportFormat(val label: String) {
  FOLDER_TREE("Device Folder"),
  PLAIN_ZIP("Standard ZIP"),
}

data class MigrationProgress(
  val currentFolderName: String,
  val currentFileName: String,
  val processedFiles: Int,
  val totalFiles: Int,
  val exportedBytes: Long,
  val format: MigrationExportFormat,
)

data class MigrationResult(
  val format: MigrationExportFormat,
  val destinationLabel: String,
  val exportedFolders: Int,
  val exportedFiles: Int,
  val exportedBytes: Long,
  val failedFiles: Int,
  val removedFoldersCount: Int,
  val firstError: String? = null,
)

/**
 * Handles BeastLock storage hygiene (scanning and clearing orphaned/temporary files from
 * incomplete re-encryption or interrupted cloud sync) and exit-strategy plaintext migration.
 */
class VaultMaintenanceManager(
  private val context: Context,
  private val vaultDao: VaultDao,
  private val auditLogRepo: EncryptedAuditLogRepository? = null,
) {

  private data class ExportFileSpec(
    val relativeSubPath: String,
    val rawName: String,
    val isEncrypted: Boolean,
    val sizeBytes: Long,
    val openInput: () -> InputStream?,
  )

  suspend fun scanOrphanedFiles(
    autoBackupTreeUri: String = "",
  ): List<OrphanedFileCandidate> = withContext(Dispatchers.IO) {
    val candidates = mutableListOf<OrphanedFileCandidate>()
    val seenIds = mutableSetOf<String>()
    val folders = vaultDao.getAllFoldersOnce()
    val validFolderIds = folders.map { it.id }.toSet()
    val folderNamesById = folders.associate { it.id to it.name }

    // 1. Scan internal vaults root for leftover scratch files & unreferenced directories
    val vaultsRoot = File(context.filesDir, "vaults")
    if (vaultsRoot.exists()) {
      vaultsRoot.listFiles().orEmpty().forEach { child ->
        if (child.isDirectory && child.name !in validFolderIds) {
          val dirSize = child.walkTopDown().filter { it.isFile }.sumOf { it.length() }
          val id = "dir:${child.absolutePath}"
          if (seenIds.add(id)) {
            candidates.add(
              OrphanedFileCandidate(
                id = id,
                displayName = child.name,
                category = OrphanCategory.ORPHANED_VAULT_DIR,
                locationLabel = "Unreferenced internal vault directory",
                sizeBytes = dirSize,
                lastModifiedMillis = child.lastModified(),
                localPath = child.absolutePath,
                isDirectory = true,
              ),
            )
          }
        } else if (child.isDirectory) {
          val folderName = folderNamesById[child.name] ?: child.name
          child.walkTopDown()
            .filter { it.isFile && isTempOrphanName(it.name) }
            .forEach { file ->
              val id = "file:${file.absolutePath}"
              if (seenIds.add(id)) {
                candidates.add(
                  OrphanedFileCandidate(
                    id = id,
                    displayName = file.name,
                    category = classifyTempFileName(file.name),
                    locationLabel = "Vault folder: $folderName",
                    sizeBytes = file.length(),
                    lastModifiedMillis = file.lastModified(),
                    localPath = file.absolutePath,
                  ),
                )
              }
            }
        }
      }
    }

    // 2. Scan app cacheDir for incomplete re-encryption or cloud sync staging files
    val cacheDir = context.cacheDir
    if (cacheDir.exists()) {
      cacheDir.walkTopDown()
        .filter { it.isFile && isCacheOrphanFile(it) }
        .forEach { file ->
          val id = "file:${file.absolutePath}"
          if (seenIds.add(id)) {
            val cat = classifyTempFileName(file.name)
            val loc = if (cat == OrphanCategory.INTERRUPTED_CLOUD_SYNC) {
              "App Cache • Cloud Sync Staging"
            } else {
              "App Cache • Crypto Staging"
            }
            candidates.add(
              OrphanedFileCandidate(
                id = id,
                displayName = file.name,
                category = cat,
                locationLabel = loc,
                sizeBytes = file.length(),
                lastModifiedMillis = file.lastModified(),
                localPath = file.absolutePath,
              ),
            )
          }
        }
    }

    // 3. Scan linked external SAF folders for leftover temp files
    folders.filter { !it.isInternalVault }.forEach { folder ->
      runCatching {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri)) ?: return@runCatching
        scanSafDirForTempFiles(
          dir = tree,
          locationLabel = "SAF folder: ${folder.name}",
          includeZeroByteAutoSync = false,
          seenIds = seenIds,
          out = candidates,
        )
      }
    }

    // 4. Scan configured Auto-Sync cloud destination for partial/interrupted sync artifacts
    if (autoBackupTreeUri.isNotBlank()) {
      runCatching {
        val uri = Uri.parse(autoBackupTreeUri)
        if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
          val syncDir = File(uri.path!!)
          if (syncDir.exists() && syncDir.isDirectory) {
            syncDir.listFiles().orEmpty()
              .filter { it.isFile && isInterruptedCloudSyncFile(it.name, it.length()) }
              .forEach { file ->
                val id = "file:${file.absolutePath}"
                if (seenIds.add(id)) {
                  candidates.add(
                    OrphanedFileCandidate(
                      id = id,
                      displayName = file.name,
                      category = OrphanCategory.INTERRUPTED_CLOUD_SYNC,
                      locationLabel = "Cloud Auto-Sync Folder",
                      sizeBytes = file.length(),
                      lastModifiedMillis = file.lastModified(),
                      localPath = file.absolutePath,
                    ),
                  )
                }
              }
          }
        } else {
          val syncTree = DocumentFile.fromTreeUri(context, uri)
          if (syncTree != null) {
            scanSafDirForTempFiles(
              dir = syncTree,
              locationLabel = "Cloud Auto-Sync Folder",
              includeZeroByteAutoSync = true,
              seenIds = seenIds,
              out = candidates,
            )
          }
        }
      }
    }

    candidates.sortedWith(
      compareByDescending<OrphanedFileCandidate> { it.lastModifiedMillis }
        .thenByDescending { it.sizeBytes },
    )
  }

  suspend fun deleteOrphanedFiles(
    candidates: List<OrphanedFileCandidate>,
  ): OrphanCleanupResult = withContext(Dispatchers.IO) {
    var deletedCount = 0
    var failedCount = 0
    var freedBytes = 0L

    for (candidate in candidates) {
      val deleted = runCatching {
        when {
          candidate.localPath != null -> {
            val target = File(candidate.localPath)
            if (!target.exists()) {
              true
            } else if (candidate.isDirectory) {
              target.deleteRecursively()
            } else {
              target.delete()
            }
          }
          candidate.documentUriString != null -> {
            val doc = DocumentFile.fromSingleUri(context, Uri.parse(candidate.documentUriString))
            doc == null || !doc.exists() || doc.delete()
          }
          else -> false
        }
      }.getOrDefault(false)

      if (deleted) {
        deletedCount++
        freedBytes += candidate.sizeBytes
      } else {
        failedCount++
      }
    }

    if (deletedCount > 0) {
      auditLogRepo?.recordEvent(
        action = "ORPHANS_CLEARED",
        folderName = "Storage Hygiene",
        details = "Removed $deletedCount orphaned temp item(s), reclaimed ${formatSize(freedBytes)}",
        category = AuditCategory.CRYPTO_OPERATION,
      )
    }

    OrphanCleanupResult(
      deletedCount = deletedCount,
      failedCount = failedCount,
      freedBytes = freedBytes,
    )
  }

  suspend fun decryptAndExportFolders(
    folderIds: List<String>,
    masterDek: SecretKey,
    format: MigrationExportFormat,
    destinationUri: Uri,
    removeOriginalsAfterSuccess: Boolean,
    onProgress: (MigrationProgress) -> Unit = {},
  ): Result<MigrationResult> = withContext(Dispatchers.IO) {
    runCatching {
      val selectedFolders = vaultDao.getAllFoldersOnce().filter { it.id in folderIds }
      require(selectedFolders.isNotEmpty()) { "Select at least one folder to export" }

      val folderFileMap = LinkedHashMap<VaultFolderEntity, List<ExportFileSpec>>()
      for (folder in selectedFolders) {
        currentCoroutineContext().ensureActive()
        folderFileMap[folder] = collectExportSpecs(folder)
      }

      val totalFiles = folderFileMap.values.sumOf { it.size }
      val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
      val rootExportName = "BeastLock_Export_$stamp"

      var processedFiles = 0
      var exportedFiles = 0
      var exportedBytes = 0L
      var failedFiles = 0
      var firstError: String? = null
      val succeededFolderIds = mutableListOf<String>()

      when (format) {
        MigrationExportFormat.FOLDER_TREE -> {
          val isFileScheme = destinationUri.scheme == "file" && !destinationUri.path.isNullOrBlank()
          val localExportRoot = if (isFileScheme) {
            File(destinationUri.path!!, rootExportName).apply { mkdirs() }
          } else null
          val safExportRoot = if (!isFileScheme) {
            val baseTree = DocumentFile.fromTreeUri(context, destinationUri)
              ?: error("Selected destination folder is unavailable")
            baseTree.createDirectory(rootExportName)
              ?: error("Could not create export folder in selected destination")
          } else null

          val usedRootFolderNames = mutableSetOf<String>()
          for ((folder, specs) in folderFileMap) {
            currentCoroutineContext().ensureActive()
            val folderKey = resolveFolderKey(folder, masterDek)
            val safeFolderName = uniqueEntryName(sanitizeSegment(folder.name), usedRootFolderNames)
            val localFolderRoot = localExportRoot?.let { File(it, safeFolderName).apply { mkdirs() } }
            val safFolderRoot = safExportRoot?.createDirectory(safeFolderName)

            var folderFailed = 0
            for (spec in specs) {
              currentCoroutineContext().ensureActive()
              onProgress(
                MigrationProgress(
                  currentFolderName = folder.name,
                  currentFileName = spec.rawName,
                  processedFiles = processedFiles,
                  totalFiles = totalFiles,
                  exportedBytes = exportedBytes,
                  format = format,
                ),
              )
              val res = runCatching {
                val targetName = resolvePlainFileName(spec, folderKey)
                if (localFolderRoot != null) {
                  val subDir = ensureLocalSubDir(localFolderRoot, spec.relativeSubPath)
                  val outFile = uniqueLocalFile(subDir, targetName)
                  exportSingleSpecToLocalFile(spec, folderKey, outFile)
                } else {
                  val parentDoc = ensureSafSubDir(safFolderRoot!!, spec.relativeSubPath)
                  val uniqueSafName = uniqueSafChildName(parentDoc, targetName)
                  val mime = guessMimeFromName(uniqueSafName)
                  val outDoc = parentDoc.createFile(mime, uniqueSafName)
                    ?: error("Failed to create exported file $uniqueSafName")
                  exportSingleSpecToUri(spec, folderKey, outDoc.uri)
                }
              }
              res.onSuccess { writtenBytes ->
                exportedFiles++
                exportedBytes += writtenBytes
              }.onFailure { err ->
                failedFiles++
                folderFailed++
                if (firstError == null) firstError = err.message ?: "Export failed for ${spec.rawName}"
              }
              processedFiles++
            }
            if (folderFailed == 0) {
              succeededFolderIds.add(folder.id)
            }
          }
        }

        MigrationExportFormat.PLAIN_ZIP -> {
          val rawOut = if (destinationUri.scheme == "file" && !destinationUri.path.isNullOrBlank()) {
            File(destinationUri.path!!).apply { parentFile?.mkdirs() }.outputStream()
          } else {
            context.contentResolver.openOutputStream(destinationUri)
              ?: error("Unable to open ZIP destination stream")
          }

          rawOut.use { stream ->
            ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
              val usedZipPaths = mutableSetOf<String>()
              val usedFolderRoots = mutableSetOf<String>()

              for ((folder, specs) in folderFileMap) {
                currentCoroutineContext().ensureActive()
                val folderKey = resolveFolderKey(folder, masterDek)
                val safeFolderName = uniqueEntryName(sanitizeSegment(folder.name), usedFolderRoots)
                if (specs.isEmpty()) {
                  val dirEntry = "$safeFolderName/"
                  if (usedZipPaths.add(dirEntry)) {
                    zip.putNextEntry(ZipEntry(dirEntry))
                    zip.closeEntry()
                  }
                }
                var folderFailed = 0
                for (spec in specs) {
                  currentCoroutineContext().ensureActive()
                  onProgress(
                    MigrationProgress(
                      currentFolderName = folder.name,
                      currentFileName = spec.rawName,
                      processedFiles = processedFiles,
                      totalFiles = totalFiles,
                      exportedBytes = exportedBytes,
                      format = format,
                    ),
                  )
                  val res = runCatching {
                    val plainName = resolvePlainFileName(spec, folderKey)
                    val cleanRel = spec.relativeSubPath.trim('/')
                    val rawPath = if (cleanRel.isEmpty()) {
                      "$safeFolderName/$plainName"
                    } else {
                      "$safeFolderName/$cleanRel/$plainName"
                    }
                    val entryPath = uniqueZipEntryPath(rawPath, usedZipPaths)
                    zip.putNextEntry(ZipEntry(entryPath))
                    val written = spec.openInput()?.use { input ->
                      if (spec.isEncrypted) {
                        AesFolderCipher.decryptStream(input, zip, folderKey).originalSize
                      } else {
                        input.copyTo(zip)
                      }
                    } ?: error("Cannot read source file ${spec.rawName}")
                    zip.closeEntry()
                    written
                  }
                  res.onSuccess { bytesWritten ->
                    exportedFiles++
                    exportedBytes += bytesWritten
                  }.onFailure { err ->
                    runCatching { zip.closeEntry() }
                    failedFiles++
                    folderFailed++
                    if (firstError == null) firstError = err.message ?: "ZIP export failed for ${spec.rawName}"
                  }
                  processedFiles++
                }
                if (folderFailed == 0) {
                  succeededFolderIds.add(folder.id)
                }
              }
              zip.finish()
            }
          }
        }
      }

      var removedFoldersCount = 0
      if (removeOriginalsAfterSuccess && succeededFolderIds.isNotEmpty()) {
        for (folderId in succeededFolderIds) {
          val folder = selectedFolders.find { it.id == folderId } ?: continue
          if (folder.isInternalVault) {
            File(File(context.filesDir, "vaults"), folder.id).deleteRecursively()
          }
          vaultDao.deleteFolderById(folder.id)
          removedFoldersCount++
        }
      }

      val destLabel = if (format == MigrationExportFormat.FOLDER_TREE) {
        rootExportName
      } else {
        "$rootExportName.zip"
      }

      auditLogRepo?.recordEvent(
        action = "VAULT_MIGRATED_OUT",
        folderName = format.label,
        details = "Decrypted & exported $exportedFiles file(s) from ${selectedFolders.size} folder(s) (${formatSize(exportedBytes)})${if (removedFoldersCount > 0) "; removed $removedFoldersCount folder(s) from BeastLock" else ""}",
        category = AuditCategory.CRYPTO_OPERATION,
        isWarning = removedFoldersCount > 0,
      )

      MigrationResult(
        format = format,
        destinationLabel = destLabel,
        exportedFolders = selectedFolders.size,
        exportedFiles = exportedFiles,
        exportedBytes = exportedBytes,
        failedFiles = failedFiles,
        removedFoldersCount = removedFoldersCount,
        firstError = firstError,
      )
    }
  }

  private fun resolveFolderKey(folder: VaultFolderEntity, masterDek: SecretKey): SecretKey {
    return folder.wrappedFolderKey?.let { wrapped ->
      AesFolderCipher.unwrapDekWithKey(wrapped, masterDek)
    } ?: masterDek
  }

  private fun resolvePlainFileName(spec: ExportFileSpec, folderKey: SecretKey): String {
    if (!spec.isEncrypted) return sanitizeSegment(spec.rawName)
    val metaName = runCatching {
      spec.openInput()?.use { input ->
        AesFolderCipher.readMetadata(input, folderKey).originalName
      }
    }.getOrNull()
    val fallback = spec.rawName.removeSuffix(AesFolderCipher.ENCRYPTED_EXTENSION).ifBlank { "decrypted_file" }
    return sanitizeSegment(metaName?.takeIf { it.isNotBlank() } ?: fallback)
  }

  private fun exportSingleSpecToLocalFile(
    spec: ExportFileSpec,
    folderKey: SecretKey,
    outFile: File,
  ): Long {
    return spec.openInput()?.use { input ->
      outFile.outputStream().buffered().use { output ->
        if (spec.isEncrypted) {
          AesFolderCipher.decryptStream(input, output, folderKey)
          outFile.length()
        } else {
          input.copyTo(output)
        }
      }
    } ?: error("Cannot open source file ${spec.rawName}")
  }

  private fun exportSingleSpecToUri(
    spec: ExportFileSpec,
    folderKey: SecretKey,
    destUri: Uri,
  ): Long {
    return spec.openInput()?.use { input ->
      val outStream = context.contentResolver.openOutputStream(destUri)
        ?: error("Unable to open destination file stream")
      outStream.buffered().use { output ->
        if (spec.isEncrypted) {
          AesFolderCipher.decryptStream(input, output, folderKey).originalSize
        } else {
          input.copyTo(output)
        }
      }
    } ?: error("Cannot open source file ${spec.rawName}")
  }

  private fun collectExportSpecs(folder: VaultFolderEntity): List<ExportFileSpec> {
    val specs = mutableListOf<ExportFileSpec>()
    if (folder.isInternalVault) {
      val root = File(File(context.filesDir, "vaults"), folder.id)
      if (!root.exists()) return emptyList()
      root.walkTopDown()
        .filter { it.isFile && !isScratchOrMarker(it.name) }
        .forEach { file ->
          val rel = file.parentFile?.relativeToOrSelf(root)?.path
            ?.takeUnless { it == "." || it.isBlank() }
            ?: ""
          specs.add(
            ExportFileSpec(
              relativeSubPath = rel,
              rawName = file.name,
              isEncrypted = file.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION),
              sizeBytes = file.length(),
              openInput = { file.inputStream().buffered() },
            ),
          )
        }
    } else {
      val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
        ?: error("SAF access unavailable for ${folder.name}")
      collectSafExportSpecs(tree, "", specs)
    }
    return specs
  }

  private fun collectSafExportSpecs(
    dir: DocumentFile,
    relPath: String,
    out: MutableList<ExportFileSpec>,
  ) {
    for (child in dir.listFiles()) {
      val name = child.name ?: continue
      if (isScratchOrMarker(name)) continue
      if (child.isDirectory) {
        val nextRel = if (relPath.isEmpty()) sanitizeSegment(name) else "$relPath/${sanitizeSegment(name)}"
        collectSafExportSpecs(child, nextRel, out)
      } else if (child.isFile) {
        val uri = child.uri
        out.add(
          ExportFileSpec(
            relativeSubPath = relPath,
            rawName = name,
            isEncrypted = name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION),
            sizeBytes = child.length(),
            openInput = { context.contentResolver.openInputStream(uri)?.buffered() },
          ),
        )
      }
    }
  }

  private fun scanSafDirForTempFiles(
    dir: DocumentFile,
    locationLabel: String,
    includeZeroByteAutoSync: Boolean,
    seenIds: MutableSet<String>,
    out: MutableList<OrphanedFileCandidate>,
  ) {
    for (child in dir.listFiles()) {
      val name = child.name ?: continue
      if (child.isDirectory) {
        scanSafDirForTempFiles(child, locationLabel, includeZeroByteAutoSync, seenIds, out)
      } else if (child.isFile) {
        val len = child.length()
        val match = isTempOrphanName(name) ||
          (includeZeroByteAutoSync && isInterruptedCloudSyncFile(name, len))
        if (match) {
          val id = "saf:${child.uri}"
          if (seenIds.add(id)) {
            out.add(
              OrphanedFileCandidate(
                id = id,
                displayName = name,
                category = if (includeZeroByteAutoSync) {
                  OrphanCategory.INTERRUPTED_CLOUD_SYNC
                } else {
                  classifyTempFileName(name)
                },
                locationLabel = locationLabel,
                sizeBytes = len,
                lastModifiedMillis = child.lastModified(),
                documentUriString = child.uri.toString(),
              ),
            )
          }
        }
      }
    }
  }

  private fun isScratchOrMarker(name: String): Boolean {
    return name == ".nomedia" ||
      name.startsWith(".tmp_") ||
      name.startsWith(".dec_") ||
      name.startsWith(".reenc_")
  }

  private fun isTempOrphanName(name: String): Boolean {
    return name.startsWith(".reenc_") ||
      name.startsWith(".dec_") ||
      name.startsWith(".tmp_") ||
      name.endsWith(".vlkbak.tmp") ||
      name.endsWith(".vlkbak.part")
  }

  private fun isCacheOrphanFile(file: File): Boolean {
    val name = file.name
    return name.startsWith(".reenc_") ||
      name.startsWith(".dec_") ||
      name.startsWith(".tmp_sync_") ||
      name.startsWith(".tmp_") ||
      name.endsWith(".vlkbak.tmp") ||
      name.endsWith(".vlkbak.part")
  }

  private fun isInterruptedCloudSyncFile(name: String, lengthBytes: Long): Boolean {
    return name.startsWith(".tmp_sync_") ||
      name.startsWith(".tmp_") ||
      name.endsWith(".vlkbak.tmp") ||
      name.endsWith(".part") ||
      (name == CloudBackupManager.AUTO_SYNC_ARCHIVE_NAME && lengthBytes == 0L)
  }

  private fun classifyTempFileName(name: String): OrphanCategory {
    return if (
      name.startsWith(".tmp_sync_") ||
      name.endsWith(".vlkbak.tmp") ||
      name.endsWith(".vlkbak.part") ||
      name == CloudBackupManager.AUTO_SYNC_ARCHIVE_NAME
    ) {
      OrphanCategory.INTERRUPTED_CLOUD_SYNC
    } else {
      OrphanCategory.INCOMPLETE_REENCRYPTION
    }
  }

  private fun ensureLocalSubDir(root: File, relativeSubPath: String): File {
    if (relativeSubPath.isBlank()) return root
    var current = root
    for (seg in relativeSubPath.split('/').filter { it.isNotBlank() }) {
      current = File(current, sanitizeSegment(seg)).apply { mkdirs() }
    }
    return current
  }

  private fun ensureSafSubDir(root: DocumentFile, relativeSubPath: String): DocumentFile {
    if (relativeSubPath.isBlank()) return root
    var current = root
    for (seg in relativeSubPath.split('/').filter { it.isNotBlank() }) {
      val clean = sanitizeSegment(seg)
      current = current.findFile(clean)?.takeIf { it.isDirectory }
        ?: current.createDirectory(clean)
        ?: error("Unable to create subfolder $clean")
    }
    return current
  }

  private fun uniqueLocalFile(dir: File, desiredName: String): File {
    var candidate = File(dir, desiredName)
    if (!candidate.exists()) return candidate
    val dot = desiredName.lastIndexOf('.')
    val base = if (dot > 0) desiredName.substring(0, dot) else desiredName
    val ext = if (dot > 0) desiredName.substring(dot) else ""
    var counter = 1
    while (candidate.exists()) {
      candidate = File(dir, "$base ($counter)$ext")
      counter++
    }
    return candidate
  }

  private fun uniqueSafChildName(dir: DocumentFile, desiredName: String): String {
    if (dir.findFile(desiredName) == null) return desiredName
    val dot = desiredName.lastIndexOf('.')
    val base = if (dot > 0) desiredName.substring(0, dot) else desiredName
    val ext = if (dot > 0) desiredName.substring(dot) else ""
    var counter = 1
    while (true) {
      val candidate = "$base ($counter)$ext"
      if (dir.findFile(candidate) == null) return candidate
      counter++
    }
  }

  private fun uniqueEntryName(desired: String, used: MutableSet<String>): String {
    if (used.add(desired)) return desired
    var counter = 1
    while (true) {
      val candidate = "$desired ($counter)"
      if (used.add(candidate)) return candidate
      counter++
    }
  }

  private fun uniqueZipEntryPath(desiredPath: String, used: MutableSet<String>): String {
    if (used.add(desiredPath)) return desiredPath
    val slash = desiredPath.lastIndexOf('/')
    val dir = if (slash >= 0) desiredPath.substring(0, slash + 1) else ""
    val file = if (slash >= 0) desiredPath.substring(slash + 1) else desiredPath
    val dot = file.lastIndexOf('.')
    val base = if (dot > 0) file.substring(0, dot) else file
    val ext = if (dot > 0) file.substring(dot) else ""
    var counter = 1
    while (true) {
      val candidate = "$dir$base ($counter)$ext"
      if (used.add(candidate)) return candidate
      counter++
    }
  }

  private fun sanitizeSegment(raw: String): String {
    return raw.replace('/', '_')
      .replace('\\', '_')
      .replace("..", "_")
      .trim()
      .ifBlank { "item" }
  }

  private fun guessMimeFromName(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
  }

  private fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
  }
}
