package com.example.data.audit

import android.content.Context
import android.net.Uri
import com.example.crypto.KeystoreManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class AuditCategory {
  FAILED_ATTEMPT,
  ACCESS_EVENT,
  CRYPTO_OPERATION,
  CLOUD_SYNC,
}

enum class AuditExportScope(val label: String) {
  ALL("All Events"),
  FAILED_ATTEMPTS_ONLY("Failed Attempts Only"),
}

enum class AuditExportFormat(val label: String, val extension: String, val mimeType: String) {
  CSV("CSV Spreadsheet (.csv)", "csv", "text/csv"),
  TEXT("Plain Text Report (.txt)", "txt", "text/plain"),
}

@Serializable
data class EncryptedAuditEntry(
  val id: Long,
  val timestamp: Long,
  val action: String,
  val category: AuditCategory = AuditCategory.ACCESS_EVENT,
  val folderId: String? = null,
  val folderName: String,
  val details: String,
  val isWarning: Boolean = false,
  val prevHash: String,
  val entryHash: String,
)

@Serializable
private data class EncryptedAuditFilePayload(
  val format: String = "VLK1-AUDIT",
  val version: Int = 1,
  val updatedAt: Long,
  val entries: List<EncryptedAuditEntry>,
)

data class AuditLogSnapshot(
  val entries: List<EncryptedAuditEntry> = emptyList(),
  val isIntegrityVerified: Boolean = true,
  val encryptedFileBytes: Long = 0L,
  val encryptedFileName: String = "vault_audit.log.enc",
  val failedAttemptsCount: Int = 0,
  val accessEventsCount: Int = 0,
)

/**
 * Stores security audit logs locally in an AES-256-GCM encrypted file
 * (`filesDir/security/vault_audit.log.enc`) using a hardware-backed Android Keystore key
 * and SHA-256 hash chaining for tamper detection.
 */
class EncryptedAuditLogRepository(
  private val context: Context,
  private val keystoreManager: KeystoreManager,
  private val overrideTestKey: SecretKey? = null,
) {
  companion object {
    private const val GENESIS_HASH = "00000000000000000000000000000000"
    private const val MAX_ENTRIES = 500
    private const val AUDIT_DIR = "security"
    private const val AUDIT_FILE_NAME = "vault_audit.log.enc"
  }

  private val mutex = Mutex()
  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
  }

  private val auditFile: File
    get() = File(File(context.filesDir, AUDIT_DIR), AUDIT_FILE_NAME)

  private val _snapshot = MutableStateFlow(AuditLogSnapshot())
  val snapshot: StateFlow<AuditLogSnapshot> = _snapshot.asStateFlow()

  suspend fun loadOrRefresh(): AuditLogSnapshot = withContext(Dispatchers.IO) {
    mutex.withLock {
      val loaded = readSnapshotLocked()
      _snapshot.value = loaded
      loaded
    }
  }

  suspend fun recordEvent(
    action: String,
    folderName: String,
    details: String,
    folderId: String? = null,
    category: AuditCategory = inferCategory(action, isWarning = false),
    isWarning: Boolean = false,
  ) = withContext(Dispatchers.IO) {
    mutex.withLock {
      val current = readSnapshotLocked()
      val existingChronological = current.entries.asReversed()
      val nextId = (existingChronological.lastOrNull()?.id ?: 0L) + 1L
      val prevHash = existingChronological.lastOrNull()?.entryHash ?: GENESIS_HASH
      val timestamp = System.currentTimeMillis()
      val entryHash = computeEntryHash(
        id = nextId,
        timestamp = timestamp,
        action = action,
        folderName = folderName,
        details = details,
        prevHash = prevHash,
      )
      val newEntry = EncryptedAuditEntry(
        id = nextId,
        timestamp = timestamp,
        action = action,
        category = category,
        folderId = folderId,
        folderName = folderName,
        details = details,
        isWarning = isWarning,
        prevHash = prevHash,
        entryHash = entryHash,
      )
      val updatedList = (existingChronological + newEntry).takeLast(MAX_ENTRIES)
      writePayloadLocked(updatedList)
      _snapshot.value = buildSnapshot(updatedList, integrityVerified = true)
    }
  }

  suspend fun clearLogs() = withContext(Dispatchers.IO) {
    mutex.withLock {
      val timestamp = System.currentTimeMillis()
      val entryHash = computeEntryHash(
        id = 1L,
        timestamp = timestamp,
        action = "AUDIT_LOG_CLEARED",
        folderName = "BeastLock",
        details = "Encrypted audit log rotated and reset by authenticated user",
        prevHash = GENESIS_HASH,
      )
      val resetEntry = EncryptedAuditEntry(
        id = 1L,
        timestamp = timestamp,
        action = "AUDIT_LOG_CLEARED",
        category = AuditCategory.ACCESS_EVENT,
        folderName = "BeastLock",
        details = "Encrypted audit log rotated and reset by authenticated user",
        isWarning = false,
        prevHash = GENESIS_HASH,
        entryHash = entryHash,
      )
      val entries = listOf(resetEntry)
      writePayloadLocked(entries)
      _snapshot.value = buildSnapshot(entries, integrityVerified = true)
    }
  }

  suspend fun exportAuditReport(destinationUri: Uri): Result<Int> =
    exportAuditLog(destinationUri, format = AuditExportFormat.TEXT, scope = AuditExportScope.ALL)

  suspend fun exportAuditLog(
    destinationUri: Uri,
    format: AuditExportFormat = AuditExportFormat.CSV,
    scope: AuditExportScope = AuditExportScope.ALL,
  ): Result<Int> = withContext(Dispatchers.IO) {
    runCatching {
      val snap = mutex.withLock { readSnapshotLocked() }
      val (content, exportedCount) = when (format) {
        AuditExportFormat.CSV -> buildCsvReport(snap, scope)
        AuditExportFormat.TEXT -> buildTextReport(snap, scope)
      }
      context.contentResolver.openOutputStream(destinationUri)?.use { out ->
        out.write(content.toByteArray(StandardCharsets.UTF_8))
        out.flush()
      } ?: error("Unable to open destination file for audit export")
      exportedCount
    }
  }

  fun buildCsvReport(
    snapshot: AuditLogSnapshot,
    scope: AuditExportScope,
  ): Pair<String, Int> {
    val entries = filterEntriesForExport(snapshot.entries, scope)
    val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
      timeZone = TimeZone.getTimeZone("UTC")
    }
    val localFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    val integrityLabel = if (snapshot.isIntegrityVerified) "CHAIN_VERIFIED" else "TAMPER_ALERT"

    val csv = buildString {
      // UTF-8 BOM for seamless Excel / desktop spreadsheet import
      append('\uFEFF')
      appendLine(
        listOf(
          "Event ID",
          "Timestamp UTC (ISO-8601)",
          "Local Date Time",
          "Action",
          "Category",
          "Warning",
          "Target",
          "Details",
          "Previous Hash",
          "Entry Hash",
          "Chain Integrity",
        ).joinToString(",") { escapeCsvField(it) },
      )
      for (entry in entries) {
        val date = Date(entry.timestamp)
        appendLine(
          listOf(
            entry.id.toString(),
            isoFormatter.format(date),
            localFormatter.format(date),
            entry.action,
            entry.category.name,
            if (entry.isWarning || entry.category == AuditCategory.FAILED_ATTEMPT) "TRUE" else "FALSE",
            entry.folderName,
            entry.details,
            entry.prevHash,
            entry.entryHash,
            integrityLabel,
          ).joinToString(",") { escapeCsvField(it) },
        )
      }
    }
    return csv to entries.size
  }

  private fun buildTextReport(
    snap: AuditLogSnapshot,
    scope: AuditExportScope,
  ): Pair<String, Int> {
    val entries = filterEntriesForExport(snap.entries, scope)
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    val report = buildString {
      appendLine("====================================================")
      appendLine("BeastLock Encrypted Security Audit Report")
      appendLine("Source File: $AUDIT_FILE_NAME (${snap.encryptedFileBytes} bytes, AES-256-GCM)")
      appendLine("Scope: ${scope.label}")
      appendLine("Hash-Chain Integrity: ${if (snap.isIntegrityVerified) "VERIFIED" else "TAMPER DETECTED"}")
      appendLine("Exported At: ${formatter.format(Date())}")
      appendLine("Exported Events: ${entries.size} (Total Failed Attempts: ${snap.failedAttemptsCount})")
      appendLine("====================================================")
      appendLine()
      for (entry in entries) {
        appendLine("[${formatter.format(Date(entry.timestamp))}] #${entry.id} ${entry.action} (${entry.category})")
        appendLine("  Target: ${entry.folderName}")
        appendLine("  Details: ${entry.details}")
        appendLine("  Hash: ${entry.entryHash}")
        appendLine()
      }
    }
    return report to entries.size
  }

  private fun filterEntriesForExport(
    entries: List<EncryptedAuditEntry>,
    scope: AuditExportScope,
  ): List<EncryptedAuditEntry> = when (scope) {
    AuditExportScope.ALL -> entries
    AuditExportScope.FAILED_ATTEMPTS_ONLY -> entries.filter {
      it.category == AuditCategory.FAILED_ATTEMPT || it.isWarning
    }
  }

  private fun escapeCsvField(raw: String): String {
    val trimmedCheck = raw.trimStart()
    val neutralized = if (
      trimmedCheck.startsWith("=") ||
      trimmedCheck.startsWith("+") ||
      trimmedCheck.startsWith("-") ||
      trimmedCheck.startsWith("@")
    ) {
      "'$raw"
    } else {
      raw
    }
    val escaped = neutralized.replace("\"", "\"\"")
    return "\"$escaped\""
  }

  private fun readSnapshotLocked(): AuditLogSnapshot {
    val file = auditFile
    if (!file.exists() || file.length() == 0L) {
      return AuditLogSnapshot(encryptedFileBytes = 0L)
    }
    return runCatching {
      val encryptedBytes = file.readBytes()
      val plainBytes = keystoreManager.decryptAuditPayload(encryptedBytes, overrideTestKey)
      val payload = json.decodeFromString<EncryptedAuditFilePayload>(
        String(plainBytes, StandardCharsets.UTF_8),
      )
      val chainOk = verifyHashChain(payload.entries)
      buildSnapshot(payload.entries, integrityVerified = chainOk)
    }.getOrElse {
      AuditLogSnapshot(
        entries = listOf(
          EncryptedAuditEntry(
            id = 0L,
            timestamp = System.currentTimeMillis(),
            action = "AUDIT_TAMPER_ALERT",
            category = AuditCategory.FAILED_ATTEMPT,
            folderName = "Encrypted Audit File",
            details = "Encrypted audit file failed AES-256-GCM authentication or decryption",
            isWarning = true,
            prevHash = GENESIS_HASH,
            entryHash = "TAMPERED",
          ),
        ),
        isIntegrityVerified = false,
        encryptedFileBytes = file.length(),
        failedAttemptsCount = 1,
      )
    }
  }

  private fun writePayloadLocked(chronologicalEntries: List<EncryptedAuditEntry>) {
    val dir = auditFile.parentFile ?: File(context.filesDir, AUDIT_DIR)
    if (!dir.exists()) dir.mkdirs()

    val payload = EncryptedAuditFilePayload(
      updatedAt = System.currentTimeMillis(),
      entries = chronologicalEntries,
    )
    val plainBytes = json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8)
    val encryptedBytes = keystoreManager.encryptAuditPayload(plainBytes, overrideTestKey)

    val tempFile = File(dir, "$AUDIT_FILE_NAME.tmp")
    tempFile.writeBytes(encryptedBytes)
    if (!tempFile.renameTo(auditFile)) {
      auditFile.writeBytes(encryptedBytes)
      tempFile.delete()
    }
  }

  private fun buildSnapshot(
    chronologicalEntries: List<EncryptedAuditEntry>,
    integrityVerified: Boolean,
  ): AuditLogSnapshot {
    val reversed = chronologicalEntries.asReversed()
    val size = if (auditFile.exists()) auditFile.length() else 0L
    return AuditLogSnapshot(
      entries = reversed,
      isIntegrityVerified = integrityVerified,
      encryptedFileBytes = size,
      encryptedFileName = AUDIT_FILE_NAME,
      failedAttemptsCount = reversed.count { it.category == AuditCategory.FAILED_ATTEMPT || it.isWarning },
      accessEventsCount = reversed.count { it.category == AuditCategory.ACCESS_EVENT },
    )
  }

  private fun verifyHashChain(chronologicalEntries: List<EncryptedAuditEntry>): Boolean {
    if (chronologicalEntries.isEmpty()) return true
    for (index in chronologicalEntries.indices) {
      val entry = chronologicalEntries[index]
      if (index > 0) {
        val expectedPrev = chronologicalEntries[index - 1].entryHash
        if (entry.prevHash != expectedPrev) return false
      }
      val expectedHash = computeEntryHash(
        id = entry.id,
        timestamp = entry.timestamp,
        action = entry.action,
        folderName = entry.folderName,
        details = entry.details,
        prevHash = entry.prevHash,
      )
      if (entry.entryHash != expectedHash) return false
    }
    return true
  }

  private fun computeEntryHash(
    id: Long,
    timestamp: Long,
    action: String,
    folderName: String,
    details: String,
    prevHash: String,
  ): String {
    val raw = "$id|$timestamp|$action|$folderName|$details|$prevHash"
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
    return digest.take(16).joinToString("") { "%02x".format(it) }
  }

  private fun inferCategory(action: String, isWarning: Boolean): AuditCategory = when {
    isWarning || action.contains("FAILED") -> AuditCategory.FAILED_ATTEMPT
    action.startsWith("CLOUD_") -> AuditCategory.CLOUD_SYNC
    action in setOf("LOCKED", "UNLOCKED", "AUTO_LOCK_REENCRYPT", "FILE_IMPORTED", "PIN_CHANGED", "RECOVERY_UPDATED") ->
      AuditCategory.CRYPTO_OPERATION
    else -> AuditCategory.ACCESS_EVENT
  }
}
