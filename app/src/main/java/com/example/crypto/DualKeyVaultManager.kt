package com.example.crypto

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.example.data.local.VaultFolderEntity
import com.example.data.preferences.DualKeyCredentialSnapshot
import com.example.data.preferences.PrimaryLockType
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DualKeyFolderRecord(
  val folderId: String,
  val folderName: String,
  val treeUri: String,
  val isInternalVault: Boolean,
  val obfuscateFilenames: Boolean,
  val requireAuthOnOpen: Boolean = true,
  val wrappedFolderKey: String,
  val keyVersion: Int = 1,
  val keyFingerprint: String = "",
)

@Serializable
data class DualKeyVaultBundle(
  val schemaVersion: Int = 1,
  val updatedAtMillis: Long = System.currentTimeMillis(),
  val masterKeyFingerprint: String = "",
  val primaryLockType: String = PrimaryLockType.PIN_6.name,
  val pinLength: Int = 6,
  val pinSaltB64: String? = null,
  val pinWrappedDek: String? = null,
  val pinVerifier: String? = null,
  val patternSaltB64: String? = null,
  val patternWrappedDek: String? = null,
  val patternVerifier: String? = null,
  val recoveryQ1: String = "",
  val recoveryQ2: String = "",
  val recoverySaltB64: String? = null,
  val recoveryWrappedDek: String? = null,
  val recoveryVerifier: String? = null,
  val folders: List<DualKeyFolderRecord> = emptyList(),
)

data class DualKeyMirrorStatus(
  val internalHealthy: Boolean,
  val internalReadOnlyLocked: Boolean,
  val externalLinked: Boolean,
  val externalHealthy: Boolean,
  val checksumValid: Boolean,
  val syncedAtMillis: Long,
  val tamperAlert: String? = null,
  val autoRepairedMirror: String? = null,
  val bundleFolderCount: Int = 0,
)

/**
 * Maintains dual hidden, SHA-256-verified encrypted key recovery mirrors in both:
 * 1) Internal app vault storage (`filesDir/.beastlock_keystore`, kept OS read-only when idle)
 * 2) Linked External SAF folder (`.BeastLock_Keys` + `.nomedia` + SHA-256 verification & auto-repair)
 *
 * Security invariant: Plaintext AES keys are NEVER written to disk. Only PBKDF2-wrapped
 * DEK slots (PIN, 3x3 Pattern, Recovery Questions) and DEK-wrapped per-folder keys are persisted.
 */
class DualKeyVaultManager(private val context: Context) {
  companion object {
    private const val INTERNAL_KEY_DIR = ".beastlock_keystore"
    private const val EXTERNAL_KEY_DIR = ".BeastLock_Keys"
    private const val BUNDLE_FILE_NAME = "beastlock_keys.vkey"
    private const val CHECKSUM_FILE_NAME = "beastlock_keys.sha256"
    private const val READ_ONLY_MARKER = ".beastlock_readonly.lock"
    private const val NOMEDIA_FILE = ".nomedia"
  }

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
  }

  private fun internalDir(): File = File(context.filesDir, INTERNAL_KEY_DIR)

  private data class MirrorReadResult(
    val exists: Boolean,
    val valid: Boolean,
    val rawPayload: String? = null,
    val sha256Hex: String? = null,
    val bundle: DualKeyVaultBundle? = null,
    val readOnlyEnforced: Boolean = false,
    val errorReason: String? = null,
  )

  suspend fun syncDualVault(
    credentials: DualKeyCredentialSnapshot,
    folders: List<VaultFolderEntity>,
    masterDek: SecretKey?,
    externalTreeUri: String,
  ): DualKeyMirrorStatus = withContext(Dispatchers.IO) {
    val existingBest = readBestValidBundle(externalTreeUri)
    val mergedRecords = buildMergedFolderRecords(
      folders = folders,
      masterDek = masterDek,
      previousRecords = existingBest?.folders.orEmpty(),
    )
    val now = System.currentTimeMillis()
    val bundle = DualKeyVaultBundle(
      schemaVersion = 1,
      updatedAtMillis = now,
      masterKeyFingerprint = credentials.keyFingerprint,
      primaryLockType = credentials.primaryLockType.name,
      pinLength = credentials.pinLength,
      pinSaltB64 = credentials.pinSaltB64,
      pinWrappedDek = credentials.pinWrappedDek,
      pinVerifier = credentials.pinVerifier,
      patternSaltB64 = credentials.patternSaltB64,
      patternWrappedDek = credentials.patternWrappedDek,
      patternVerifier = credentials.patternVerifier,
      recoveryQ1 = credentials.recoveryQ1,
      recoveryQ2 = credentials.recoveryQ2,
      recoverySaltB64 = credentials.recoverySaltB64,
      recoveryWrappedDek = credentials.recoveryWrappedDek,
      recoveryVerifier = credentials.recoveryVerifier,
      folders = mergedRecords,
    )

    val rawPayload = json.encodeToString(bundle)
    val sha256 = sha256Hex(rawPayload.toByteArray(StandardCharsets.UTF_8))

    val internalOk = writeInternalMirror(rawPayload, sha256)
    val externalLinked = externalTreeUri.isNotBlank()
    val externalOk = if (externalLinked) {
      writeExternalMirror(Uri.parse(externalTreeUri), rawPayload, sha256)
    } else {
      false
    }

    val readBackInternal = readInternalMirror()
    DualKeyMirrorStatus(
      internalHealthy = internalOk && readBackInternal.valid,
      internalReadOnlyLocked = readBackInternal.readOnlyEnforced,
      externalLinked = externalLinked,
      externalHealthy = externalOk,
      checksumValid = internalOk && (!externalLinked || externalOk),
      syncedAtMillis = now,
      tamperAlert = when {
        externalLinked && !externalOk ->
          "External key recovery folder is unreachable or read-only locked by system. Internal mirror remains intact."
        !internalOk -> "Failed to update internal read-only key vault."
        else -> null
      },
      bundleFolderCount = mergedRecords.size,
    )
  }

  suspend fun verifyAndAutoHeal(externalTreeUri: String): DualKeyMirrorStatus =
    withContext(Dispatchers.IO) {
      val internal = readInternalMirror()
      val externalLinked = externalTreeUri.isNotBlank()
      val external = if (externalLinked) readExternalMirror(Uri.parse(externalTreeUri)) else null
      val now = System.currentTimeMillis()

      var tamperAlert: String? = null
      var repairedMirror: String? = null

      if (internal.exists && !internal.valid) {
        tamperAlert = "Internal key vault checksum mismatch (${internal.errorReason ?: "corrupted"})."
        if (external != null && external.valid && external.rawPayload != null && external.sha256Hex != null) {
          if (writeInternalMirror(external.rawPayload, external.sha256Hex)) {
            repairedMirror = "INTERNAL_REPAIRED"
          }
        }
      }

      if (externalLinked && external != null) {
        val extNeedsHeal = !external.valid ||
          (internal.valid && internal.sha256Hex != null && external.sha256Hex != internal.sha256Hex)
        if (external.exists && !external.valid) {
          tamperAlert = listOfNotNull(
            tamperAlert,
            "External key mirror tamper or checksum mismatch detected (${external.errorReason ?: "modified"}).",
          ).joinToString(" ")
        }
        if (extNeedsHeal && internal.valid && internal.rawPayload != null && internal.sha256Hex != null) {
          if (writeExternalMirror(Uri.parse(externalTreeUri), internal.rawPayload, internal.sha256Hex)) {
            repairedMirror = if (repairedMirror == null) "EXTERNAL_REPAIRED" else "BOTH_SYNCED"
          }
        }
      }

      val postInternal = readInternalMirror()
      val postExternal = if (externalLinked) readExternalMirror(Uri.parse(externalTreeUri)) else null
      val bestBundle = postInternal.bundle ?: postExternal?.bundle

      DualKeyMirrorStatus(
        internalHealthy = postInternal.valid,
        internalReadOnlyLocked = postInternal.readOnlyEnforced,
        externalLinked = externalLinked,
        externalHealthy = postExternal?.valid == true,
        checksumValid = postInternal.valid && (!externalLinked || postExternal?.valid == true),
        syncedAtMillis = bestBundle?.updatedAtMillis ?: now,
        tamperAlert = tamperAlert,
        autoRepairedMirror = repairedMirror,
        bundleFolderCount = bestBundle?.folders?.size ?: 0,
      )
    }

  suspend fun recoverFolderKey(
    folderId: String,
    expectedKeyFingerprint: String,
    masterDek: SecretKey,
    externalTreeUri: String,
  ): Pair<SecretKey, DualKeyFolderRecord>? = withContext(Dispatchers.IO) {
    val bundle = readBestValidBundle(externalTreeUri) ?: return@withContext null
    val candidates = bundle.folders.filter { rec ->
      rec.folderId == folderId ||
        (expectedKeyFingerprint.isNotBlank() && rec.keyFingerprint == expectedKeyFingerprint)
    }
    for (candidate in candidates) {
      val key = runCatching {
        AesFolderCipher.unwrapDekWithKey(candidate.wrappedFolderKey, masterDek)
      }.getOrNull() ?: continue
      if (expectedKeyFingerprint.isBlank() ||
        AesFolderCipher.computeKeyFingerprint(key) == expectedKeyFingerprint ||
        candidate.keyFingerprint == expectedKeyFingerprint
      ) {
        return@withContext key to candidate
      }
    }
    null
  }

  suspend fun readLatestBundle(externalTreeUri: String): DualKeyVaultBundle? =
    withContext(Dispatchers.IO) { readBestValidBundle(externalTreeUri) }

  suspend fun unwrapMasterDekWithPin(
    pin: String,
    externalTreeUri: String,
  ): Pair<SecretKey, DualKeyVaultBundle>? = withContext(Dispatchers.IO) {
    val bundle = readBestValidBundle(externalTreeUri) ?: return@withContext null
    val saltB64 = bundle.pinSaltB64 ?: return@withContext null
    val verifier = bundle.pinVerifier ?: return@withContext null
    val wrapped = bundle.pinWrappedDek ?: return@withContext null
    val salt = Base64.decode(saltB64, Base64.NO_WRAP)
    if (AesFolderCipher.computePinVerifier(pin, salt) != verifier) return@withContext null
    val pinKey = AesFolderCipher.derivePinKey(pin, salt)
    val dek = runCatching { AesFolderCipher.unwrapDekWithKey(wrapped, pinKey) }.getOrNull()
      ?: return@withContext null
    dek to bundle
  }

  suspend fun unwrapMasterDekWithPattern(
    canonicalPattern: String,
    externalTreeUri: String,
  ): Pair<SecretKey, DualKeyVaultBundle>? = withContext(Dispatchers.IO) {
    val bundle = readBestValidBundle(externalTreeUri) ?: return@withContext null
    val saltB64 = bundle.patternSaltB64 ?: return@withContext null
    val verifier = bundle.patternVerifier ?: return@withContext null
    val wrapped = bundle.patternWrappedDek ?: return@withContext null
    val salt = Base64.decode(saltB64, Base64.NO_WRAP)
    if (AesFolderCipher.computePinVerifier(canonicalPattern, salt) != verifier) return@withContext null
    val patternKey = AesFolderCipher.derivePinKey(canonicalPattern, salt)
    val dek = runCatching { AesFolderCipher.unwrapDekWithKey(wrapped, patternKey) }.getOrNull()
      ?: return@withContext null
    dek to bundle
  }

  suspend fun unwrapMasterDekWithRecoveryAnswers(
    normalizedAnswers: String,
    externalTreeUri: String,
  ): Pair<SecretKey, DualKeyVaultBundle>? = withContext(Dispatchers.IO) {
    val bundle = readBestValidBundle(externalTreeUri) ?: return@withContext null
    val saltB64 = bundle.recoverySaltB64 ?: return@withContext null
    val verifier = bundle.recoveryVerifier ?: return@withContext null
    val wrapped = bundle.recoveryWrappedDek ?: return@withContext null
    val salt = Base64.decode(saltB64, Base64.NO_WRAP)
    if (AesFolderCipher.computePinVerifier(normalizedAnswers, salt) != verifier) return@withContext null
    val recKey = AesFolderCipher.derivePinKey(normalizedAnswers, salt)
    val dek = runCatching { AesFolderCipher.unwrapDekWithKey(wrapped, recKey) }.getOrNull()
      ?: return@withContext null
    dek to bundle
  }

  private fun buildMergedFolderRecords(
    folders: List<VaultFolderEntity>,
    masterDek: SecretKey?,
    previousRecords: List<DualKeyFolderRecord>,
  ): List<DualKeyFolderRecord> {
    val prevById = previousRecords.associateBy { it.folderId }
    val out = mutableListOf<DualKeyFolderRecord>()
    for (folder in folders) {
      val existingWrapped = folder.wrappedFolderKey?.takeIf { it.isNotBlank() }
      val fallbackWrapped = prevById[folder.id]?.wrappedFolderKey?.takeIf { it.isNotBlank() }
      val wrapped = existingWrapped ?: fallbackWrapped ?: masterDek?.let {
        AesFolderCipher.wrapDekWithKey(it, it)
      }
      if (!wrapped.isNullOrBlank()) {
        val fp = folder.keyFingerprint.ifBlank {
          masterDek?.let { dek ->
            runCatching {
              AesFolderCipher.computeKeyFingerprint(AesFolderCipher.unwrapDekWithKey(wrapped, dek))
            }.getOrNull()
          } ?: prevById[folder.id]?.keyFingerprint.orEmpty()
        }
        out += DualKeyFolderRecord(
          folderId = folder.id,
          folderName = folder.name,
          treeUri = folder.treeUri,
          isInternalVault = folder.isInternalVault,
          obfuscateFilenames = folder.obfuscateFilenames,
          requireAuthOnOpen = folder.requireAuthOnOpen,
          wrappedFolderKey = wrapped,
          keyVersion = folder.keyVersion,
          keyFingerprint = fp,
        )
      }
    }
    return out
  }

  private fun readBestValidBundle(externalTreeUri: String): DualKeyVaultBundle? {
    val internal = readInternalMirror()
    val external = if (externalTreeUri.isNotBlank()) readExternalMirror(Uri.parse(externalTreeUri)) else null
    return when {
      internal.valid && external?.valid == true -> {
        val iTime = internal.bundle?.updatedAtMillis ?: 0L
        val eTime = external.bundle?.updatedAtMillis ?: 0L
        if (eTime > iTime) external.bundle else internal.bundle
      }
      internal.valid -> internal.bundle
      external?.valid == true -> external.bundle
      else -> null
    }
  }

  private fun writeInternalMirror(rawPayload: String, sha256Hex: String): Boolean {
    return runCatching {
      val dir = internalDir()
      if (!dir.exists()) {
        dir.mkdirs()
      }
      dir.setWritable(true, true)

      val nomedia = File(dir, NOMEDIA_FILE)
      if (!nomedia.exists()) {
        nomedia.writeBytes(ByteArray(0))
      }
      nomedia.setReadOnly()

      val bundleFile = File(dir, BUNDLE_FILE_NAME)
      val tmpBundle = File(dir, "$BUNDLE_FILE_NAME.tmp")
      bundleFile.setWritable(true, true)
      tmpBundle.writeText(rawPayload, StandardCharsets.UTF_8)
      if (bundleFile.exists()) bundleFile.delete()
      tmpBundle.renameTo(bundleFile)
      bundleFile.setReadOnly()

      val checksumFile = File(dir, CHECKSUM_FILE_NAME)
      val tmpChecksum = File(dir, "$CHECKSUM_FILE_NAME.tmp")
      checksumFile.setWritable(true, true)
      tmpChecksum.writeText(sha256Hex, StandardCharsets.UTF_8)
      if (checksumFile.exists()) checksumFile.delete()
      tmpChecksum.renameTo(checksumFile)
      checksumFile.setReadOnly()

      val lockMarker = File(dir, READ_ONLY_MARKER)
      lockMarker.setWritable(true, true)
      lockMarker.writeText("READ_ONLY=1\nSHA256=$sha256Hex\n", StandardCharsets.UTF_8)
      lockMarker.setReadOnly()

      true
    }.getOrDefault(false)
  }

  private fun readInternalMirror(): MirrorReadResult {
    val dir = internalDir()
    val bundleFile = File(dir, BUNDLE_FILE_NAME)
    val checksumFile = File(dir, CHECKSUM_FILE_NAME)
    if (!bundleFile.exists()) {
      return MirrorReadResult(exists = false, valid = false)
    }
    if (!checksumFile.exists()) {
      return MirrorReadResult(exists = true, valid = false, errorReason = "Missing SHA-256 digest file")
    }
    return runCatching {
      val raw = bundleFile.readText(StandardCharsets.UTF_8)
      val expectedSha = checksumFile.readText(StandardCharsets.UTF_8).trim().lowercase()
      val actualSha = sha256Hex(raw.toByteArray(StandardCharsets.UTF_8))
      if (actualSha != expectedSha) {
        return MirrorReadResult(
          exists = true,
          valid = false,
          errorReason = "SHA-256 digest mismatch",
          readOnlyEnforced = !bundleFile.canWrite(),
        )
      }
      val parsed = json.decodeFromString<DualKeyVaultBundle>(raw)
      MirrorReadResult(
        exists = true,
        valid = true,
        rawPayload = raw,
        sha256Hex = actualSha,
        bundle = parsed,
        readOnlyEnforced = !bundleFile.canWrite() && !checksumFile.canWrite(),
      )
    }.getOrElse { err ->
      MirrorReadResult(exists = true, valid = false, errorReason = err.message ?: "Decode failed")
    }
  }

  private fun writeExternalMirror(treeUri: Uri, rawPayload: String, sha256Hex: String): Boolean {
    return runCatching {
      val root = resolveTreeDocumentFile(treeUri) ?: return false
      val keyDir = root.findFile(EXTERNAL_KEY_DIR)?.takeIf { it.isDirectory }
        ?: root.createDirectory(EXTERNAL_KEY_DIR)
        ?: return false

      if (keyDir.findFile(NOMEDIA_FILE) == null) {
        keyDir.createFile("application/octet-stream", NOMEDIA_FILE)?.let { nomedia ->
          context.contentResolver.openOutputStream(nomedia.uri, "wt")?.use { out ->
            out.write(ByteArray(0))
          }
        }
      }

      writeSafTextFile(keyDir, BUNDLE_FILE_NAME, "application/octet-stream", rawPayload) &&
        writeSafTextFile(keyDir, CHECKSUM_FILE_NAME, "text/plain", sha256Hex) &&
        writeSafTextFile(
          keyDir,
          READ_ONLY_MARKER,
          "text/plain",
          "BEASTLOCK_EXTERNAL_KEY_VAULT=LOCKED\nDO_NOT_EDIT=1\nSHA256=$sha256Hex\n",
        )
    }.getOrDefault(false)
  }

  private fun readExternalMirror(treeUri: Uri): MirrorReadResult {
    val root = resolveTreeDocumentFile(treeUri)
      ?: return MirrorReadResult(exists = false, valid = false, errorReason = "External tree unavailable")
    val keyDir = root.findFile(EXTERNAL_KEY_DIR)?.takeIf { it.isDirectory }
      ?: return MirrorReadResult(exists = false, valid = false)
    val bundleDoc = keyDir.findFile(BUNDLE_FILE_NAME)
      ?: return MirrorReadResult(exists = false, valid = false)
    val checksumDoc = keyDir.findFile(CHECKSUM_FILE_NAME)
      ?: return MirrorReadResult(exists = true, valid = false, errorReason = "Missing external SHA-256")

    return runCatching {
      val raw = context.contentResolver.openInputStream(bundleDoc.uri)?.bufferedReader(StandardCharsets.UTF_8)
        ?.use { it.readText() } ?: return MirrorReadResult(exists = true, valid = false, errorReason = "Unreadable bundle")
      val expectedSha = context.contentResolver.openInputStream(checksumDoc.uri)?.bufferedReader(StandardCharsets.UTF_8)
        ?.use { it.readText() }?.trim()?.lowercase()
        ?: return MirrorReadResult(exists = true, valid = false, errorReason = "Unreadable checksum")
      val actualSha = sha256Hex(raw.toByteArray(StandardCharsets.UTF_8))
      if (actualSha != expectedSha) {
        return MirrorReadResult(exists = true, valid = false, errorReason = "External SHA-256 mismatch")
      }
      val parsed = json.decodeFromString<DualKeyVaultBundle>(raw)
      MirrorReadResult(
        exists = true,
        valid = true,
        rawPayload = raw,
        sha256Hex = actualSha,
        bundle = parsed,
        readOnlyEnforced = keyDir.findFile(READ_ONLY_MARKER) != null,
      )
    }.getOrElse { err ->
      MirrorReadResult(exists = true, valid = false, errorReason = err.message ?: "External decode failed")
    }
  }

  private fun writeSafTextFile(
    dir: DocumentFile,
    name: String,
    mime: String,
    content: String,
  ): Boolean {
    val target = dir.findFile(name) ?: dir.createFile(mime, name) ?: return false
    return runCatching {
      context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
        out.write(content.toByteArray(StandardCharsets.UTF_8))
        out.flush()
      } != null
    }.getOrDefault(false)
  }

  private fun resolveTreeDocumentFile(treeUri: Uri): DocumentFile? {
    if (treeUri.scheme == "file") {
      val file = File(treeUri.path ?: return null)
      return if (file.exists() && file.isDirectory) DocumentFile.fromFile(file) else null
    }
    return DocumentFile.fromTreeUri(context, treeUri)
  }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
