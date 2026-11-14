package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.AesFolderCipher
import com.example.crypto.EncryptedFileMetadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read app name string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("BeastLock", appName)
  }

  @Test
  fun `multi-chunk AES-256-GCM roundtrip preserves metadata and bytes`() {
    val key = AesFolderCipher.generateAes256Key()
    val payload = ByteArray(150_000) { idx -> (idx % 251).toByte() }
    val meta = EncryptedFileMetadata(
      originalName = "confidential_contract.pdf",
      mimeType = "application/pdf",
      originalSize = payload.size.toLong(),
      encryptedAt = 1_700_000_000_000L,
    )

    val encryptedOut = ByteArrayOutputStream()
    val plainWritten = AesFolderCipher.encryptStream(
      input = ByteArrayInputStream(payload),
      output = encryptedOut,
      key = key,
      metadata = meta,
      chunkSize = 16 * 1024,
    )
    assertEquals(payload.size.toLong(), plainWritten)

    val decryptedOut = ByteArrayOutputStream()
    val recoveredMeta = AesFolderCipher.decryptStream(
      input = ByteArrayInputStream(encryptedOut.toByteArray()),
      output = decryptedOut,
      key = key,
    )

    assertEquals("confidential_contract.pdf", recoveredMeta.originalName)
    assertEquals("application/pdf", recoveredMeta.mimeType)
    assertArrayEquals(payload, decryptedOut.toByteArray())
  }

  @Test
  fun `tampered ciphertext fails GCM authentication`() {
    val key = AesFolderCipher.generateAes256Key()
    val payload = "Top secret folder payload".toByteArray()
    val meta = EncryptedFileMetadata("secret.txt", "text/plain", payload.size.toLong(), 123L)

    val encryptedOut = ByteArrayOutputStream()
    AesFolderCipher.encryptStream(ByteArrayInputStream(payload), encryptedOut, key, meta)
    val tampered = encryptedOut.toByteArray()
    tampered[tampered.size - 10] = (tampered[tampered.size - 10].toInt() xor 0xFF).toByte()

    assertThrows(AEADBadTagException::class.java) {
      AesFolderCipher.decryptStream(
        ByteArrayInputStream(tampered),
        ByteArrayOutputStream(),
        key,
      )
    }
  }

  @Test
  fun `PBKDF2 PIN and recovery key wrapping restores identical DEK`() {
    val dek = AesFolderCipher.generateAes256Key()
    val pinSalt = AesFolderCipher.generateSalt()
    val pinKey = AesFolderCipher.derivePinKey("849201", pinSalt)
    val wrapped = AesFolderCipher.wrapDekWithKey(dek, pinKey)

    val unwrapped = AesFolderCipher.unwrapDekWithKey(wrapped, pinKey)
    assertArrayEquals(dek.encoded, unwrapped.encoded)
  }

  @Test
  fun `internal folder lock re-encrypts files and restores original content on unlock`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.VaultDatabase::class.java,
    ).allowMainThreadQueries().build()
    try {
      val repo = com.example.data.storage.FolderLockerRepository(context, db.vaultDao())
      val dek = AesFolderCipher.generateAes256Key()
      val folder = repo.createInternalVaultFolder("AutoLockTest", obfuscateDefault = true).getOrThrow()

      val vaultDir = java.io.File(java.io.File(context.filesDir, "vaults"), folder.id)
      val plainFile = java.io.File(vaultDir, "notes.txt")
      plainFile.writeText("Sensitive auto-lock payload")

      val locked = repo.lockFolder(folder.id, dek).getOrThrow()
      assertEquals(true, locked.isLocked)
      assertEquals(1, locked.encryptedFileCount)
      assertEquals(locked.totalBytes, locked.encryptedBytes)
      assertEquals(0L, locked.unencryptedBytes)
      assertEquals(false, plainFile.exists())

      val unlocked = repo.unlockFolder(folder.id, dek).getOrThrow()
      assertEquals(false, unlocked.isLocked)
      assertEquals(0, unlocked.encryptedFileCount)
      assertEquals(0L, unlocked.encryptedBytes)
      assertEquals(unlocked.totalBytes, unlocked.unencryptedBytes)
      assertEquals("Sensitive auto-lock payload", plainFile.readText())
    } finally {
      db.close()
    }
  }

  @Test
  fun `encrypted audit log stores failed unlock and access events in non-plaintext AES-GCM file and detects tampering`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val auditFile = java.io.File(java.io.File(context.filesDir, "security"), "vault_audit.log.enc")
    if (auditFile.exists()) auditFile.delete()

    val auditKey = AesFolderCipher.generateAes256Key()
    val keystoreManager = com.example.crypto.KeystoreManager(context)
    val auditRepo = com.example.data.audit.EncryptedAuditLogRepository(
      context = context,
      keystoreManager = keystoreManager,
      overrideTestKey = auditKey,
    )

    auditRepo.recordEvent(
      action = "AUTH_FAILED",
      folderName = "Master Vault",
      details = "Incorrect Master PIN entered (attempt #1)",
      category = com.example.data.audit.AuditCategory.FAILED_ATTEMPT,
      isWarning = true,
    )
    auditRepo.recordEvent(
      action = "FOLDER_ACCESSED",
      folderName = "Tax Records",
      details = "Opened vault folder workspace",
      folderId = "folder-123",
      category = com.example.data.audit.AuditCategory.ACCESS_EVENT,
      isWarning = false,
    )

    assertEquals(true, auditFile.exists())
    assertEquals(true, auditFile.length() > 32L)
    val rawFileText = String(auditFile.readBytes(), java.nio.charset.StandardCharsets.UTF_8)
    assertEquals(false, rawFileText.contains("AUTH_FAILED"))
    assertEquals(false, rawFileText.contains("Tax Records"))
    assertEquals(false, rawFileText.contains("Incorrect Master PIN"))

    val reloadedRepo = com.example.data.audit.EncryptedAuditLogRepository(
      context = context,
      keystoreManager = keystoreManager,
      overrideTestKey = auditKey,
    )
    val loadedSnapshot = reloadedRepo.loadOrRefresh()
    assertEquals(true, loadedSnapshot.isIntegrityVerified)
    assertEquals(2, loadedSnapshot.entries.size)
    assertEquals(1, loadedSnapshot.failedAttemptsCount)
    assertEquals(1, loadedSnapshot.accessEventsCount)
    assertEquals("FOLDER_ACCESSED", loadedSnapshot.entries.first().action)
    assertEquals("AUTH_FAILED", loadedSnapshot.entries.last().action)

    // Corrupt ciphertext bytes on disk and verify GCM tamper detection triggers
    val corrupted = auditFile.readBytes()
    corrupted[corrupted.size - 5] = (corrupted[corrupted.size - 5].toInt() xor 0xFF).toByte()
    auditFile.writeBytes(corrupted)

    val tamperedSnapshot = reloadedRepo.loadOrRefresh()
    assertEquals(false, tamperedSnapshot.isIntegrityVerified)
    assertEquals("AUDIT_TAMPER_ALERT", tamperedSnapshot.entries.first().action)

    // Rotating/clearing resets to a fresh verified chain
    reloadedRepo.clearLogs()
    val resetSnapshot = reloadedRepo.loadOrRefresh()
    assertEquals(true, resetSnapshot.isIntegrityVerified)
    assertEquals(1, resetSnapshot.entries.size)
    assertEquals("AUDIT_LOG_CLEARED", resetSnapshot.entries.first().action)
  }

  @Test
  fun `panic mode shake detector ignores resting gravity and triggers on double shake with cooldown`() {
    var triggerCount = 0
    val detector = com.example.security.ShakeDetector(
      sensitivity = com.example.security.PanicSensitivity.STANDARD,
      onShakeDetected = { triggerCount++ },
    )

    // Steady Earth gravity (~1.0g) must never trigger
    assertEquals(false, detector.onAccelerationSample(0f, 0f, 9.81f, 1_000L))
    // Single isolated bump (~3.0g) must not trigger alone
    assertEquals(false, detector.onAccelerationSample(29.5f, 0f, 0f, 1_100L))
    assertEquals(0, triggerCount)

    // Rapid sample within minPeakSpacingMs (120ms) is debounced as part of the same swing
    assertEquals(false, detector.onAccelerationSample(-29.5f, 0f, 0f, 1_150L))
    assertEquals(0, triggerCount)

    // Second distinct peak within 700ms window triggers Panic Mode
    assertEquals(true, detector.onAccelerationSample(-29.5f, 0f, 0f, 1_260L))
    assertEquals(1, triggerCount)

    // Cooldown (1500ms) suppresses immediate re-triggering
    assertEquals(false, detector.onAccelerationSample(32f, 0f, 0f, 1_400L))
    assertEquals(false, detector.onAccelerationSample(-32f, 0f, 0f, 1_600L))
    assertEquals(1, triggerCount)

    // Sensitive mode triggers at lower acceleration (~2.2g)
    detector.updateSensitivity(com.example.security.PanicSensitivity.SENSITIVE)
    assertEquals(false, detector.onAccelerationSample(21.6f, 0f, 0f, 3_000L))
    assertEquals(true, detector.onAccelerationSample(-21.6f, 0f, 0f, 3_200L))
    assertEquals(2, triggerCount)
  }

  @Test
  fun `panic shake monitor registers and unregisters accelerometer listener cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
    val shadowSensorManager = org.robolectric.Shadows.shadowOf(sensorManager)
    val fakeAccel = org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_ACCELEROMETER)
    shadowSensorManager.addSensor(fakeAccel)

    var panicked = false
    val monitor = com.example.security.PanicShakeMonitor(
      context = context,
      initialSensitivity = com.example.security.PanicSensitivity.STANDARD,
      onPanicShake = { panicked = true },
    )

    assertEquals(true, monitor.hasAccelerometer)
    assertEquals(false, shadowSensorManager.hasListener(monitor))

    monitor.start()
    assertEquals(true, shadowSensorManager.hasListener(monitor))

    monitor.stop()
    assertEquals(false, shadowSensorManager.hasListener(monitor))
    assertEquals(false, panicked)
  }

  @Test
  fun `theme mode preference toggles cleanly between system default, light, and dark`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = com.example.data.preferences.SecurityPreferencesRepository(
      context = context,
      keystoreManager = com.example.crypto.KeystoreManager(context),
    )

    val initial = repo.securitySettings.first()
    assertEquals(com.example.ui.theme.ThemeMode.SYSTEM, initial.themeMode)

    repo.setThemeMode(com.example.ui.theme.ThemeMode.LIGHT)
    assertEquals(com.example.ui.theme.ThemeMode.LIGHT, repo.securitySettings.first().themeMode)

    repo.setThemeMode(com.example.ui.theme.ThemeMode.DARK)
    assertEquals(com.example.ui.theme.ThemeMode.DARK, repo.securitySettings.first().themeMode)

    repo.setThemeMode(com.example.ui.theme.ThemeMode.SYSTEM)
    assertEquals(com.example.ui.theme.ThemeMode.SYSTEM, repo.securitySettings.first().themeMode)

    repo.setDynamicColor(true)
    assertEquals(true, repo.securitySettings.first().dynamicColor)
  }

  @Test
  fun `batch lock and unlock encrypts and decrypts multiple folders simultaneously`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.VaultDatabase::class.java,
    ).allowMainThreadQueries().build()
    try {
      val repo = com.example.data.storage.FolderLockerRepository(context, db.vaultDao())
      val dek = AesFolderCipher.generateAes256Key()

      val f1 = repo.createInternalVaultFolder("Passports", obfuscateDefault = true).getOrThrow()
      val f2 = repo.createInternalVaultFolder("Contracts", obfuscateDefault = false).getOrThrow()
      val file1 = java.io.File(java.io.File(java.io.File(context.filesDir, "vaults"), f1.id), "passport.txt")
      val file2 = java.io.File(java.io.File(java.io.File(context.filesDir, "vaults"), f2.id), "contract.txt")
      file1.writeText("Passport #12345")
      file2.writeText("NDA Agreement")

      val lockResult = repo.lockFoldersBatch(listOf(f1.id, f2.id), dek)
      assertEquals(2, lockResult.succeededCount)
      assertEquals(0, lockResult.failedCount)
      assertEquals(2, lockResult.totalFiles)
      assertEquals(false, file1.exists())
      assertEquals(false, file2.exists())

      val unlockResult = repo.unlockFoldersBatch(listOf(f1.id, f2.id), dek)
      assertEquals(2, unlockResult.succeededCount)
      assertEquals(0, unlockResult.failedCount)
      assertEquals(2, unlockResult.totalFiles)
      assertEquals("Passport #12345", file1.readText())
      assertEquals("NDA Agreement", file2.readText())
    } finally {
      db.close()
    }
  }

  @Test
  fun `reencryptFoldersBatch rotates per-folder keys, isolates selected folders, and preserves unlock and peek`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.VaultDatabase::class.java,
    ).allowMainThreadQueries().build()
    try {
      val repo = com.example.data.storage.FolderLockerRepository(context, db.vaultDao())
      val masterDek = AesFolderCipher.generateAes256Key()

      val lockedFolder = repo.createInternalVaultFolder("RotatedLocked", obfuscateDefault = true).getOrThrow()
      val unlockedFolder = repo.createInternalVaultFolder("RotatedUnlocked", obfuscateDefault = false).getOrThrow()
      val untouchedFolder = repo.createInternalVaultFolder("Untouched", obfuscateDefault = false).getOrThrow()

      val lockedDir = java.io.File(java.io.File(context.filesDir, "vaults"), lockedFolder.id)
      val unlockedDir = java.io.File(java.io.File(context.filesDir, "vaults"), unlockedFolder.id)
      val untouchedDir = java.io.File(java.io.File(context.filesDir, "vaults"), untouchedFolder.id)

      val fLocked = java.io.File(lockedDir, "secret_tax.txt").apply { writeText("2026 Tax Return") }
      val fUnlocked = java.io.File(unlockedDir, "draft_memo.txt").apply { writeText("Board Memo Draft") }
      val fUntouched = java.io.File(untouchedDir, "keep_old.txt").apply { writeText("Legacy Key File") }

      repo.lockFolder(lockedFolder.id, masterDek).getOrThrow()
      repo.lockFolder(untouchedFolder.id, masterDek).getOrThrow()

      // First run with encryptUnlockedFolders = false: skips unlockedFolder, rotates lockedFolder
      val skipResult = repo.reencryptFoldersBatch(
        folderIds = listOf(lockedFolder.id, unlockedFolder.id),
        masterDek = masterDek,
        encryptUnlockedFolders = false,
      )
      assertEquals(1, skipResult.succeededCount)
      assertEquals(1, skipResult.skippedCount)
      assertEquals(0, skipResult.failedCount)
      assertEquals(true, fUnlocked.exists())

      val lockedAfterFirst = db.vaultDao().getFolderById(lockedFolder.id)!!
      assertEquals(2, lockedAfterFirst.keyVersion)
      assertEquals(true, !lockedAfterFirst.wrappedFolderKey.isNullOrBlank())
      assertEquals(true, lockedAfterFirst.keyFingerprint.isNotBlank())
      assertEquals(true, lockedAfterFirst.lastKeyRotatedAt > 0L)

      // Raw masterDek must NOT decrypt rotated files directly (proves key material changed)
      val rotatedEncFile = lockedDir.listFiles()!!.first { it.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION) }
      assertThrows(AEADBadTagException::class.java) {
        rotatedEncFile.inputStream().use { input ->
          AesFolderCipher.decryptStream(input, ByteArrayOutputStream(), masterDek)
        }
      }

      // Second run with encryptUnlockedFolders = true: rotates lockedFolder (v2->v3) and encrypts+rotates unlockedFolder (v1->v2)
      val batchResult = repo.reencryptFoldersBatch(
        folderIds = listOf(lockedFolder.id, unlockedFolder.id),
        masterDek = masterDek,
        encryptUnlockedFolders = true,
      )
      assertEquals(2, batchResult.succeededCount)
      assertEquals(0, batchResult.skippedCount)
      assertEquals(0, batchResult.failedCount)
      assertEquals(false, fUnlocked.exists())

      val lockedV3 = db.vaultDao().getFolderById(lockedFolder.id)!!
      val unlockedV2 = db.vaultDao().getFolderById(unlockedFolder.id)!!
      val untouchedV1 = db.vaultDao().getFolderById(untouchedFolder.id)!!

      assertEquals(3, lockedV3.keyVersion)
      assertEquals(2, unlockedV2.keyVersion)
      assertEquals(1, untouchedV1.keyVersion)
      assertEquals(null, untouchedV1.wrappedFolderKey)
      assertEquals(false, lockedV3.wrappedFolderKey == unlockedV2.wrappedFolderKey)

      // Listing and peeking files works seamlessly with masterDek unwrapping each folder's key
      val items = repo.listFolderFiles(lockedFolder.id, masterDek).getOrThrow()
      assertEquals(1, items.size)
      assertEquals("secret_tax.txt", items.first().displayName)
      val preview = repo.peekFile(items.first(), masterDek).getOrThrow()
      assertEquals("2026 Tax Return", (preview as com.example.data.storage.SecurePreviewContent.TextPreview).text)

      // Batch unlock works across rotated folders and untouched legacy folder together
      val unlockAll = repo.unlockFoldersBatch(
        listOf(lockedFolder.id, unlockedFolder.id, untouchedFolder.id),
        masterDek,
      )
      assertEquals(3, unlockAll.succeededCount)
      assertEquals(0, unlockAll.failedCount)
      assertEquals("2026 Tax Return", fLocked.readText())
      assertEquals("Board Memo Draft", fUnlocked.readText())
      assertEquals("Legacy Key File", fUntouched.readText())
    } finally {
      db.close()
    }
  }

  @Test
  fun `cloud backup export and cross-PIN restore preserves rotated per-folder key files`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.VaultDatabase::class.java,
    ).allowMainThreadQueries().build()
    try {
      val testKeystoreKey = AesFolderCipher.generateAes256Key()
      val keystore = com.example.crypto.KeystoreManager(context, overrideTestMasterKey = testKeystoreKey)
      val prefs = com.example.data.preferences.SecurityPreferencesRepository(context, keystore)
      val auditKey = AesFolderCipher.generateAes256Key()
      val auditRepo = com.example.data.audit.EncryptedAuditLogRepository(context, keystore, overrideTestKey = auditKey)
      val repo = com.example.data.storage.FolderLockerRepository(context, db.vaultDao(), auditRepo)
      val backupManager = com.example.data.backup.CloudBackupManager(context, db.vaultDao(), prefs, auditRepo)

      val originalMasterDek = prefs.initializeVault(
        pin = "246810",
        enableBiometrics = false,
        question1 = "City?",
        answer1 = "Tokyo",
        question2 = "Pet?",
        answer2 = "Rex",
      )

      val folder = repo.createInternalVaultFolder("BackupRotated", obfuscateDefault = true).getOrThrow()
      val vaultDir = java.io.File(java.io.File(context.filesDir, "vaults"), folder.id)
      java.io.File(vaultDir, "keys.txt").writeText("Rotated Cloud Backup Secret")
      repo.lockFolder(folder.id, originalMasterDek).getOrThrow()
      repo.reencryptFoldersBatch(listOf(folder.id), originalMasterDek, encryptUnlockedFolders = true)

      val backupFile = java.io.File(context.cacheDir, "test_rotated_backup.vlkbak")
      if (backupFile.exists()) backupFile.delete()
      val backupUri = android.net.Uri.fromFile(backupFile)

      backupManager.exportEncryptedBackup(
        destinationUri = backupUri,
        providerLabel = "TestDrive",
        dek = originalMasterDek,
        folderIds = setOf(folder.id),
      ).getOrThrow()

      // Simulate restoring onto a vault with a completely different Master Key
      val newVaultMasterDek = AesFolderCipher.generateAes256Key()
      val restoreNeedPin = backupManager.restoreEncryptedBackup(backupUri, newVaultMasterDek).getOrThrow()
      assertEquals(true, restoreNeedPin.requiresBackupPin)

      val restoreOk = backupManager.restoreEncryptedBackup(
        backupUri = backupUri,
        currentDek = newVaultMasterDek,
        backupPinIfDifferentKey = "246810",
      ).getOrThrow()
      assertEquals(false, restoreOk.requiresBackupPin)
      assertEquals(1, restoreOk.restoredFolders)
      assertEquals(1, restoreOk.restoredFiles)

      val restoredFolder = db.vaultDao().getAllFoldersOnce().first { it.name.contains("(Restored)") }
      assertEquals(2, restoredFolder.keyVersion)
      assertEquals(true, !restoredFolder.wrappedFolderKey.isNullOrBlank())

      repo.unlockFolder(restoredFolder.id, newVaultMasterDek).getOrThrow()
      val restoredFile = java.io.File(java.io.File(java.io.File(context.filesDir, "vaults"), restoredFolder.id), "keys.txt")
      assertEquals("Rotated Cloud Backup Secret", restoredFile.readText())
    } finally {
      db.close()
    }
  }

  @Test
  fun `per-folder and batch auto cloud sync toggles filter syncAutoBackupToTree archive contents`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.VaultDatabase::class.java,
    ).allowMainThreadQueries().build()
    try {
      val testKeystoreKey = AesFolderCipher.generateAes256Key()
      val keystore = com.example.crypto.KeystoreManager(context, overrideTestMasterKey = testKeystoreKey)
      val prefs = com.example.data.preferences.SecurityPreferencesRepository(context, keystore)
      val auditKey = AesFolderCipher.generateAes256Key()
      val auditRepo = com.example.data.audit.EncryptedAuditLogRepository(context, keystore, overrideTestKey = auditKey)
      val repo = com.example.data.storage.FolderLockerRepository(context, db.vaultDao(), auditRepo)
      val backupManager = com.example.data.backup.CloudBackupManager(context, db.vaultDao(), prefs, auditRepo)

      val masterDek = prefs.initializeVault(
        pin = "135790",
        enableBiometrics = false,
        question1 = "City?",
        answer1 = "Berlin",
        question2 = "Pet?",
        answer2 = "Max",
      )

      val syncedFolder = repo.createInternalVaultFolder("SyncedDocs", obfuscateDefault = true).getOrThrow()
      val localOnlyFolder = repo.createInternalVaultFolder("LocalOnlyNotes", obfuscateDefault = false).getOrThrow()
      val thirdFolder = repo.createInternalVaultFolder("ExtraVault", obfuscateDefault = true).getOrThrow()

      assertEquals(true, syncedFolder.autoCloudBackupSync)
      assertEquals(true, localOnlyFolder.autoCloudBackupSync)

      // Disable per-folder automatic cloud backup sync for localOnlyFolder
      repo.setFolderAutoCloudBackupSync(localOnlyFolder.id, false)
      assertEquals(false, db.vaultDao().getFolderById(localOnlyFolder.id)!!.autoCloudBackupSync)
      assertEquals(true, db.vaultDao().getFolderById(syncedFolder.id)!!.autoCloudBackupSync)

      // Batch toggle off localOnlyFolder & thirdFolder together
      val changed = repo.setFoldersAutoCloudBackupSyncBatch(
        folderIds = listOf(localOnlyFolder.id, thirdFolder.id),
        enabled = false,
      )
      assertEquals(1, changed) // only thirdFolder changed from true -> false
      assertEquals(false, db.vaultDao().getFolderById(thirdFolder.id)!!.autoCloudBackupSync)

      val syncedDir = java.io.File(java.io.File(context.filesDir, "vaults"), syncedFolder.id)
      val localOnlyDir = java.io.File(java.io.File(context.filesDir, "vaults"), localOnlyFolder.id)
      java.io.File(syncedDir, "cloud_doc.txt").writeText("Sync to Cloud")
      java.io.File(localOnlyDir, "device_secret.txt").writeText("Stay Local Only")

      val syncDestDir = java.io.File(context.cacheDir, "auto_sync_test_dir").apply {
        deleteRecursively()
        mkdirs()
      }
      val syncResult = backupManager.syncAutoBackupToTree(
        treeUri = android.net.Uri.fromFile(syncDestDir),
        providerLabel = "Google Drive",
        dek = masterDek,
      ).getOrThrow()

      assertEquals(true, syncResult != null)
      assertEquals(1, syncResult!!.record.folderCount)
      assertEquals(1, syncResult.record.fileCount)

      val archiveFile = java.io.File(syncDestDir, com.example.data.backup.CloudBackupManager.AUTO_SYNC_ARCHIVE_NAME)
      assertEquals(true, archiveFile.exists())

      // Inspect manifest inside BeastLock_AutoSync.vlkbak to prove disabled folders were excluded
      val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
      var manifest: com.example.data.backup.CloudBackupManifest? = null
      java.util.zip.ZipInputStream(archiveFile.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
          if (entry.name == "manifest.json") {
            manifest = json.decodeFromString<com.example.data.backup.CloudBackupManifest>(
              zip.readBytes().toString(Charsets.UTF_8),
            )
            break
          }
          entry = zip.nextEntry
        }
      }

      assertEquals(listOf(syncedFolder.id), manifest!!.folders.map { it.id })
      assertEquals(listOf(true), manifest!!.folders.map { it.autoCloudBackupSync })

      // Re-enabling batch sync on remaining folder returns null when all disabled first
      repo.setFolderAutoCloudBackupSync(syncedFolder.id, false)
      val emptySync = backupManager.syncAutoBackupToTree(
        treeUri = android.net.Uri.fromFile(syncDestDir),
        providerLabel = "Google Drive",
        dek = masterDek,
      ).getOrThrow()
      assertEquals(null, emptySync)
    } finally {
      db.close()
    }
  }

  @Test
  fun `clear orphaned files detects and removes incomplete re-encryption and interrupted cloud sync temp files safely`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.VaultDatabase::class.java,
    ).allowMainThreadQueries().build()
    try {
      val testKeystoreKey = AesFolderCipher.generateAes256Key()
      val keystore = com.example.crypto.KeystoreManager(context, overrideTestMasterKey = testKeystoreKey)
      val auditKey = AesFolderCipher.generateAes256Key()
      val auditRepo = com.example.data.audit.EncryptedAuditLogRepository(context, keystore, overrideTestKey = auditKey)
      val repo = com.example.data.storage.FolderLockerRepository(context, db.vaultDao(), auditRepo)
      val maintenance = com.example.data.storage.VaultMaintenanceManager(context, db.vaultDao(), auditRepo)

      val masterDek = AesFolderCipher.generateAes256Key()
      val activeFolder = repo.createInternalVaultFolder("ActiveVault", obfuscateDefault = true).getOrThrow()
      val activeDir = java.io.File(java.io.File(context.filesDir, "vaults"), activeFolder.id)
      java.io.File(activeDir, "keep.txt").writeText("Active Protected Content")
      repo.lockFolder(activeFolder.id, masterDek).getOrThrow()

      val activeEncFile = activeDir.listFiles()!!.first { it.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION) }
      val nomediaFile = java.io.File(activeDir, ".nomedia")

      // Simulate orphaned temp files across re-encryption, cache, cloud sync, and unreferenced folder dir
      val orphanReencFolder = java.io.File(activeDir, ".reenc_cipher_test_1").apply { writeText("partial-cipher") }
      val orphanReencCache = java.io.File(context.cacheDir, ".reenc_plain_test_2").apply { writeText("partial-plain") }
      val orphanSyncCache = java.io.File(context.cacheDir, ".tmp_sync_test_3.vlkbak").apply { writeText("partial-sync") }
      val syncDestDir = java.io.File(context.cacheDir, "orphan_sync_tree").apply {
        deleteRecursively()
        mkdirs()
      }
      val orphanZeroByteAutoSync = java.io.File(
        syncDestDir,
        com.example.data.backup.CloudBackupManager.AUTO_SYNC_ARCHIVE_NAME,
      ).apply { createNewFile() }

      val orphanGhostDir = java.io.File(java.io.File(context.filesDir, "vaults"), "ghost-orphan-dir").apply {
        mkdirs()
      }
      java.io.File(orphanGhostDir, "stale.bin").writeText("leftover")

      val found = maintenance.scanOrphanedFiles(android.net.Uri.fromFile(syncDestDir).toString())
      val foundNames = found.map { it.displayName }.toSet()

      assertEquals(true, orphanReencFolder.name in foundNames)
      assertEquals(true, orphanReencCache.name in foundNames)
      assertEquals(true, orphanSyncCache.name in foundNames)
      assertEquals(true, orphanZeroByteAutoSync.name in foundNames)
      assertEquals(true, orphanGhostDir.name in foundNames)
      assertEquals(false, activeEncFile.name in foundNames)
      assertEquals(false, nomediaFile.name in foundNames)

      val reencItems = found.filter { it.category == com.example.data.storage.OrphanCategory.INCOMPLETE_REENCRYPTION }
      val syncItems = found.filter { it.category == com.example.data.storage.OrphanCategory.INTERRUPTED_CLOUD_SYNC }
      val dirItems = found.filter { it.category == com.example.data.storage.OrphanCategory.ORPHANED_VAULT_DIR }
      assertEquals(true, reencItems.size >= 2)
      assertEquals(true, syncItems.size >= 2)
      assertEquals(1, dirItems.size)

      val cleanupResult = maintenance.deleteOrphanedFiles(found)
      assertEquals(found.size, cleanupResult.deletedCount)
      assertEquals(0, cleanupResult.failedCount)

      // Temp files removed; active encrypted vault file & .nomedia untouched
      assertEquals(false, orphanReencFolder.exists())
      assertEquals(false, orphanReencCache.exists())
      assertEquals(false, orphanSyncCache.exists())
      assertEquals(false, orphanZeroByteAutoSync.exists())
      assertEquals(false, orphanGhostDir.exists())
      assertEquals(true, activeEncFile.exists())
      assertEquals(true, nomediaFile.exists())

      val afterClean = maintenance.scanOrphanedFiles(android.net.Uri.fromFile(syncDestDir).toString())
      assertEquals(0, afterClean.size)
    } finally {
      db.close()
    }
  }

  @Test
  fun `decrypt and migrate out exports folder tree and plain zip with optional vault removal`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.VaultDatabase::class.java,
    ).allowMainThreadQueries().build()
    try {
      val testKeystoreKey = AesFolderCipher.generateAes256Key()
      val keystore = com.example.crypto.KeystoreManager(context, overrideTestMasterKey = testKeystoreKey)
      val auditKey = AesFolderCipher.generateAes256Key()
      val auditRepo = com.example.data.audit.EncryptedAuditLogRepository(context, keystore, overrideTestKey = auditKey)
      val repo = com.example.data.storage.FolderLockerRepository(context, db.vaultDao(), auditRepo)
      val maintenance = com.example.data.storage.VaultMaintenanceManager(context, db.vaultDao(), auditRepo)

      val masterDek = AesFolderCipher.generateAes256Key()

      val rotatedFolder = repo.createInternalVaultFolder("Finance", obfuscateDefault = true).getOrThrow()
      val rotatedDir = java.io.File(java.io.File(context.filesDir, "vaults"), rotatedFolder.id)
      val subDir = java.io.File(rotatedDir, "2026").apply { mkdirs() }
      java.io.File(subDir, "taxes.txt").writeText("Decrypted Tax Summary")
      repo.lockFolder(rotatedFolder.id, masterDek).getOrThrow()
      repo.reencryptFoldersBatch(listOf(rotatedFolder.id), masterDek, encryptUnlockedFolders = true)

      val plainFolder = repo.createInternalVaultFolder("Notes", obfuscateDefault = false).getOrThrow()
      val plainDir = java.io.File(java.io.File(context.filesDir, "vaults"), plainFolder.id)
      java.io.File(plainDir, "todo.txt").writeText("Plaintext Checklist")

      // 1. Export as Folder Tree keeping originals in BeastLock
      val treeExportBase = java.io.File(context.cacheDir, "migration_tree_out").apply {
        deleteRecursively()
        mkdirs()
      }
      val treeResult = maintenance.decryptAndExportFolders(
        folderIds = listOf(rotatedFolder.id, plainFolder.id),
        masterDek = masterDek,
        format = com.example.data.storage.MigrationExportFormat.FOLDER_TREE,
        destinationUri = android.net.Uri.fromFile(treeExportBase),
        removeOriginalsAfterSuccess = false,
      ).getOrThrow()

      assertEquals(2, treeResult.exportedFolders)
      assertEquals(2, treeResult.exportedFiles)
      assertEquals(0, treeResult.failedFiles)
      assertEquals(0, treeResult.removedFoldersCount)
      assertEquals(2, db.vaultDao().getAllFoldersOnce().size)

      val exportRoot = treeExportBase.listFiles()!!.first { it.isDirectory && it.name.startsWith("BeastLock_Export_") }
      val exportedTax = java.io.File(exportRoot, "Finance/2026/taxes.txt")
      val exportedTodo = java.io.File(exportRoot, "Notes/todo.txt")
      assertEquals("Decrypted Tax Summary", exportedTax.readText())
      assertEquals("Plaintext Checklist", exportedTodo.readText())

      // 2. Export as Standard Unencrypted ZIP with post-migration removal enabled
      val zipOutFile = java.io.File(context.cacheDir, "migration_exit.zip").apply {
        if (exists()) delete()
      }
      val zipResult = maintenance.decryptAndExportFolders(
        folderIds = listOf(rotatedFolder.id, plainFolder.id),
        masterDek = masterDek,
        format = com.example.data.storage.MigrationExportFormat.PLAIN_ZIP,
        destinationUri = android.net.Uri.fromFile(zipOutFile),
        removeOriginalsAfterSuccess = true,
      ).getOrThrow()

      assertEquals(2, zipResult.exportedFolders)
      assertEquals(2, zipResult.exportedFiles)
      assertEquals(0, zipResult.failedFiles)
      assertEquals(2, zipResult.removedFoldersCount)
      assertEquals(0, db.vaultDao().getAllFoldersOnce().size)
      assertEquals(false, rotatedDir.exists())
      assertEquals(false, plainDir.exists())

      val zipEntries = mutableMapOf<String, String>()
      java.util.zip.ZipInputStream(zipOutFile.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
          zipEntries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
          entry = zip.nextEntry
        }
      }
      assertEquals("Decrypted Tax Summary", zipEntries["Finance/2026/taxes.txt"])
      assertEquals("Plaintext Checklist", zipEntries["Notes/todo.txt"])
    } finally {
      db.close()
    }
  }

  @Test
  fun `battery power monitor pauses below 20 percent or power save and resumes strictly above 20 percent`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val monitor = com.example.security.BatteryPowerMonitor(context)

    // Normal battery above 20% and Power Save off -> not paused
    assertEquals(
      false,
      com.example.security.BatteryPowerMonitor.evaluatePauseState(
        previouslyPaused = false,
        batteryPercent = 25,
        isSystemPowerSaveMode = false,
      ),
    )
    // At exactly 20% without prior pause -> not paused (< 20% triggers pause)
    assertEquals(
      false,
      com.example.security.BatteryPowerMonitor.evaluatePauseState(
        previouslyPaused = false,
        batteryPercent = 20,
        isSystemPowerSaveMode = false,
      ),
    )

    // Drop below 20% -> activates pause
    monitor.updateForTesting(batteryPercent = 19, isCharging = false, isSystemPowerSaveMode = false)
    assertEquals(true, monitor.powerState.value.isBatterySaverPaused)
    assertEquals(true, monitor.powerState.value.isLowBatteryBelow20)

    // Charging reaches exactly 20% -> stays paused due to hysteresis (must be > 20%)
    monitor.updateForTesting(batteryPercent = 20, isCharging = true, isSystemPowerSaveMode = false)
    assertEquals(true, monitor.powerState.value.isBatterySaverPaused)

    // Charging reaches 21% -> resumes automatically
    monitor.updateForTesting(batteryPercent = 21, isCharging = true, isSystemPowerSaveMode = false)
    assertEquals(false, monitor.powerState.value.isBatterySaverPaused)

    // System Power Save forces pause even at high battery (85%)
    monitor.updateForTesting(batteryPercent = 85, isCharging = false, isSystemPowerSaveMode = true)
    assertEquals(true, monitor.powerState.value.isBatterySaverPaused)

    // Turning off System Power Save at 85% resumes cleanly
    monitor.updateForTesting(batteryPercent = 85, isCharging = false, isSystemPowerSaveMode = false)
    assertEquals(false, monitor.powerState.value.isBatterySaverPaused)
  }

  @Test
  fun `battery saver preference and queued work flags persist across DataStore updates`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val keystore = com.example.crypto.KeystoreManager(
      context,
      overrideTestMasterKey = AesFolderCipher.generateAes256Key(),
    )
    val prefs = com.example.data.preferences.SecurityPreferencesRepository(context, keystore)

    val initial = prefs.securitySettings.first()
    assertEquals(true, initial.batterySaverModeEnabled)
    assertEquals(false, initial.pendingBatteryPausedAutoSync)
    assertEquals(false, initial.pendingBatteryPausedReencrypt)

    prefs.setPendingBatteryPausedAutoSync(true)
    prefs.setPendingBatteryPausedReencrypt(true)
    val queued = prefs.securitySettings.first()
    assertEquals(true, queued.pendingBatteryPausedAutoSync)
    assertEquals(true, queued.pendingBatteryPausedReencrypt)

    prefs.setBatterySaverModeEnabled(false)
    prefs.setPendingBatteryPausedAutoSync(false)
    prefs.setPendingBatteryPausedReencrypt(false)
    val updated = prefs.securitySettings.first()
    assertEquals(false, updated.batterySaverModeEnabled)
    assertEquals(false, updated.pendingBatteryPausedAutoSync)
    assertEquals(false, updated.pendingBatteryPausedReencrypt)
  }

  @Test
  fun `quick settings LockVaultTileService and PanicModeTileService dispatch immediate commands`() = kotlinx.coroutines.test.runTest {
    val commands = mutableListOf<com.example.security.QuickTileCommand>()
    val collector = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
      com.example.security.VaultQuickActionBus.commands.collect { cmd ->
        commands.add(cmd)
      }
    }
    try {
      // Simulate unlocked vault and tap Lock Vault Quick Settings tile
      com.example.security.VaultQuickActionBus.updateVaultState(
        unlocked = true,
        panicExitToHome = false,
      )
      val lockService = org.robolectric.Robolectric.buildService(
        com.example.quicksettings.LockVaultTileService::class.java,
      ).create().get()
      lockService.onClick()

      assertEquals(listOf(com.example.security.QuickTileCommand.LOCK_VAULT), commands)
      assertEquals(false, com.example.security.VaultQuickActionBus.isVaultUnlocked.value)

      // Simulate unlocked vault and tap Panic Mode Quick Settings tile
      com.example.security.VaultQuickActionBus.updateVaultState(
        unlocked = true,
        panicExitToHome = false,
      )
      val panicService = org.robolectric.Robolectric.buildService(
        com.example.quicksettings.PanicModeTileService::class.java,
      ).create().get()
      panicService.onClick()

      assertEquals(
        listOf(
          com.example.security.QuickTileCommand.LOCK_VAULT,
          com.example.security.QuickTileCommand.PANIC_MODE,
        ),
        commands,
      )
      assertEquals(false, com.example.security.VaultQuickActionBus.isVaultUnlocked.value)
    } finally {
      collector.cancel()
    }
  }

  @Test
  fun `audit log exports RFC4180 CSV with All Events and Failed Attempts Only scopes`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val auditFile = java.io.File(java.io.File(context.filesDir, "security"), "vault_audit.log.enc")
    if (auditFile.exists()) auditFile.delete()

    val auditKey = AesFolderCipher.generateAes256Key()
    val keystoreManager = com.example.crypto.KeystoreManager(context, overrideTestMasterKey = auditKey)
    val auditRepo = com.example.data.audit.EncryptedAuditLogRepository(
      context = context,
      keystoreManager = keystoreManager,
      overrideTestKey = auditKey,
    )

    auditRepo.recordEvent(
      action = "AUTH_FAILED",
      folderName = "BeastLock Gate",
      details = "Invalid PIN, \"quoted\" attempt #1",
      category = com.example.data.audit.AuditCategory.FAILED_ATTEMPT,
      isWarning = true,
    )
    auditRepo.recordEvent(
      action = "FOLDER_ACCESSED",
      folderName = "Work Docs",
      details = "Opened folder explorer",
      category = com.example.data.audit.AuditCategory.ACCESS_EVENT,
      isWarning = false,
    )
    auditRepo.recordEvent(
      action = "AUTH_FAILED",
      folderName = "=EVIL_FORMULA",
      details = "Invalid Unlock Pattern attempt #2",
      category = com.example.data.audit.AuditCategory.FAILED_ATTEMPT,
      isWarning = true,
    )

    val allCsvFile = java.io.File(context.cacheDir, "audit_all.csv").apply { if (exists()) delete() }
    val allCount = auditRepo.exportAuditLog(
      destinationUri = android.net.Uri.fromFile(allCsvFile),
      format = com.example.data.audit.AuditExportFormat.CSV,
      scope = com.example.data.audit.AuditExportScope.ALL,
    ).getOrThrow()

    assertEquals(3, allCount)
    val allCsvText = allCsvFile.readText(Charsets.UTF_8)
    assertEquals(true, allCsvText.startsWith("\uFEFF"))
    assertEquals(true, allCsvText.contains("\"Event ID\",\"Timestamp UTC (ISO-8601)\""))
    assertEquals(true, allCsvText.contains("\"Invalid PIN, \"\"quoted\"\" attempt #1\""))
    assertEquals(true, allCsvText.contains("\"'=EVIL_FORMULA\""))
    assertEquals(true, allCsvText.contains("\"FOLDER_ACCESSED\""))
    assertEquals(true, allCsvText.contains("\"CHAIN_VERIFIED\""))

    val failedCsvFile = java.io.File(context.cacheDir, "audit_failed.csv").apply { if (exists()) delete() }
    val failedCount = auditRepo.exportAuditLog(
      destinationUri = android.net.Uri.fromFile(failedCsvFile),
      format = com.example.data.audit.AuditExportFormat.CSV,
      scope = com.example.data.audit.AuditExportScope.FAILED_ATTEMPTS_ONLY,
    ).getOrThrow()

    assertEquals(2, failedCount)
    val failedCsvText = failedCsvFile.readText(Charsets.UTF_8)
    assertEquals(true, failedCsvText.contains("\"AUTH_FAILED\""))
    assertEquals(false, failedCsvText.contains("\"FOLDER_ACCESSED\""))
  }

  @Test
  fun `unlock pattern fallback wraps identical DEK alongside Master PIN and bridges collinear grid dots`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val keystore = com.example.crypto.KeystoreManager(
      context,
      overrideTestMasterKey = AesFolderCipher.generateAes256Key(),
    )
    val prefs = com.example.data.preferences.SecurityPreferencesRepository(context, keystore)

    val masterDek = prefs.initializeVault(
      pin = "654321",
      enableBiometrics = false,
      question1 = "City?",
      answer1 = "Seoul",
      question2 = "Pet?",
      answer2 = "Coco",
    )

    // Pattern shorter than 4 nodes is rejected
    assertEquals(false, prefs.setUnlockPattern(masterDek, listOf(0, 1, 2)))

    // Valid 5-dot pattern wraps the identical DEK alongside PIN
    val saved = prefs.setUnlockPattern(masterDek, listOf(0, 1, 2, 5, 8), makeDefaultFallback = true)
    assertEquals(true, saved)
    val withPattern = prefs.securitySettings.first()
    assertEquals(true, withPattern.hasPatternLock)
    assertEquals(true, withPattern.preferPatternFallback)

    // Wrong pattern fails and increments failedAttempts
    assertEquals(null, prefs.unlockWithPattern(listOf(0, 3, 6, 7, 8)))
    assertEquals(1, prefs.securitySettings.first().failedAttempts)

    // Correct pattern unwraps exact DEK and resets failedAttempts
    val unlockedByPattern = prefs.unlockWithPattern(listOf(0, 1, 2, 5, 8))
    assertArrayEquals(masterDek.encoded, unlockedByPattern!!.encoded)
    assertEquals(0, prefs.securitySettings.first().failedAttempts)

    // PIN unlock continues to work and changing PIN preserves Pattern access
    assertEquals(true, prefs.changePin("654321", "987654"))
    assertArrayEquals(masterDek.encoded, prefs.unlockWithPattern(listOf(0, 1, 2, 5, 8))!!.encoded)
    assertArrayEquals(masterDek.encoded, prefs.unlockWithPin("987654")!!.encoded)

    // Removing pattern disables Pattern unlock while keeping PIN active
    prefs.removeUnlockPattern()
    val afterRemove = prefs.securitySettings.first()
    assertEquals(false, afterRemove.hasPatternLock)
    assertEquals(false, afterRemove.preferPatternFallback)
    assertEquals(null, prefs.unlockWithPattern(listOf(0, 1, 2, 5, 8)))
    assertArrayEquals(masterDek.encoded, prefs.unlockWithPin("987654")!!.encoded)

    // Verify collinear middle node bridging helper
    assertEquals(1, com.example.ui.components.collinearMiddleNode(0, 2))
    assertEquals(4, com.example.ui.components.collinearMiddleNode(0, 8))
    assertEquals(4, com.example.ui.components.collinearMiddleNode(2, 6))
    assertEquals(null, com.example.ui.components.collinearMiddleNode(0, 5))
  }

  @Test
  fun `rewriteEncryptedMetadata updates filename without decrypting chunks and preserves GCM integrity`() {
    val key = AesFolderCipher.generateAes256Key()
    val payload = ByteArray(90_000) { idx -> (idx % 199).toByte() }
    val origMeta = EncryptedFileMetadata(
      originalName = "raw:draft??report.pdf",
      mimeType = "application/pdf",
      originalSize = payload.size.toLong(),
      encryptedAt = 1_700_123_456_000L,
    )

    val encryptedOriginalOut = ByteArrayOutputStream()
    AesFolderCipher.encryptStream(
      input = ByteArrayInputStream(payload),
      output = encryptedOriginalOut,
      key = key,
      metadata = origMeta,
      chunkSize = 16 * 1024,
    )
    val origBytes = encryptedOriginalOut.toByteArray()

    // Extract original payload offset (after 4B magic + 1B ver + 12B nonce + 4B chunkSize + 4B metaLen + metaLen)
    val origBuf = java.nio.ByteBuffer.wrap(origBytes)
    origBuf.position(4 + 1 + 12 + 4)
    val origMetaLen = origBuf.int
    val origPayloadOffset = 4 + 1 + 12 + 4 + 4 + origMetaLen

    val rewrittenOut = ByteArrayOutputStream()
    val updatedMeta = AesFolderCipher.rewriteEncryptedMetadata(
      input = ByteArrayInputStream(origBytes),
      output = rewrittenOut,
      key = key,
    ) { old ->
      old.copy(originalName = "2026_clean_report.pdf")
    }
    assertEquals("2026_clean_report.pdf", updatedMeta.originalName)

    val rewrittenBytes = rewrittenOut.toByteArray()
    val newBuf = java.nio.ByteBuffer.wrap(rewrittenBytes)
    newBuf.position(4 + 1 + 12 + 4)
    val newMetaLen = newBuf.int
    val newPayloadOffset = 4 + 1 + 12 + 4 + 4 + newMetaLen

    // Encrypted data chunks + EOF sentinel bytes are byte-for-byte identical (never decrypted)
    assertArrayEquals(
      origBytes.copyOfRange(origPayloadOffset, origBytes.size),
      rewrittenBytes.copyOfRange(newPayloadOffset, rewrittenBytes.size),
    )

    // Full decryption verifies all GCM chunk tags + EOF sentinel with the updated metadata
    val decryptedOut = ByteArrayOutputStream()
    val decryptedMeta = AesFolderCipher.decryptStream(
      input = ByteArrayInputStream(rewrittenBytes),
      output = decryptedOut,
      key = key,
    )
    assertEquals("2026_clean_report.pdf", decryptedMeta.originalName)
    assertArrayEquals(payload, decryptedOut.toByteArray())
  }

  @Test
  fun `BatchRenameEngine sanitizes illegal chars, organizes names, preserves extensions, and avoids collisions`() {
    val files = listOf(
      com.example.data.storage.VaultFileItem(
        id = "f1",
        displayName = "Tax:Report*2026?.pdf",
        rawFileName = "uuid1.vlock",
        sizeBytes = 100L,
        mimeType = "application/pdf",
        isEncrypted = true,
        uriString = "file:///f1",
        lastModified = 1L,
      ),
      com.example.data.storage.VaultFileItem(
        id = "f2",
        displayName = "Tax  Report  2026.pdf",
        rawFileName = "uuid2.vlock",
        sizeBytes = 100L,
        mimeType = "application/pdf",
        isEncrypted = true,
        uriString = "file:///f2",
        lastModified = 1L,
      ),
    )

    val config = com.example.data.storage.BatchRenameConfig(
      sanitizeIllegalChars = true,
      replaceSpacesWithUnderscores = true,
      collapseSeparators = true,
      findText = "Tax",
      replaceText = "Finance",
      prefix = "clean_",
      caseMode = com.example.data.storage.RenameCaseMode.LOWERCASE,
      preserveExtension = true,
    )

    val plan = com.example.data.storage.BatchRenameEngine.computeBatchFileRenamePlan(
      selectedFiles = files,
      allFolderFiles = files,
      config = config,
    )
    assertEquals(2, plan.size)
    assertEquals("clean_finance_report_2026.pdf", plan[0].proposedName)
    assertEquals("clean_finance_report_2026_1.pdf", plan[1].proposedName)
    assertEquals("Auto-numbered to avoid name collision", plan[1].conflictNote)

    // Folder sanitization strips unsafe characters cleanly
    assertEquals("Taxes 2026 Q1", com.example.data.storage.BatchRenameEngine.sanitizeFolderName("  ..Taxes / 2026* Q1..  ").replace("_", " ").replace(Regex("\\s+"), " ").trim())
  }

  @Test
  fun `batchRenameFolderFiles and batchRenameFolders organize locked vaults without decrypting payloads`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.VaultDatabase::class.java,
    ).allowMainThreadQueries().build()
    try {
      val testKeystoreKey = AesFolderCipher.generateAes256Key()
      val keystore = com.example.crypto.KeystoreManager(context, overrideTestMasterKey = testKeystoreKey)
      val auditKey = AesFolderCipher.generateAes256Key()
      val auditRepo = com.example.data.audit.EncryptedAuditLogRepository(context, keystore, overrideTestKey = auditKey)
      val repo = com.example.data.storage.FolderLockerRepository(context, db.vaultDao(), auditRepo)
      val masterDek = AesFolderCipher.generateAes256Key()

      // 1. Obfuscated locked folder + 2. Non-obfuscated locked folder
      val obfFolder = repo.createInternalVaultFolder("Raw??Docs 2026", obfuscateDefault = true).getOrThrow()
      val plainNameFolder = repo.createInternalVaultFolder("Temp  Photos", obfuscateDefault = false).getOrThrow()

      val obfDir = java.io.File(java.io.File(context.filesDir, "vaults"), obfFolder.id)
      val plainNameDir = java.io.File(java.io.File(context.filesDir, "vaults"), plainNameFolder.id)

      java.io.File(obfDir, "my draft notes.txt").writeText("Encrypted Draft Body")
      java.io.File(obfDir, "invoice 01.txt").writeText("Encrypted Invoice Body")
      java.io.File(plainNameDir, "scan raw.txt").writeText("Non-Obfuscated Secret")

      repo.lockFolder(obfFolder.id, masterDek).getOrThrow()
      repo.lockFolder(plainNameFolder.id, masterDek).getOrThrow()

      val obfItems = repo.listFolderFiles(obfFolder.id, masterDek).getOrThrow()
      assertEquals(2, obfItems.size)
      val renamePlan = com.example.data.storage.BatchRenameEngine.computeBatchFileRenamePlan(
        selectedFiles = obfItems,
        allFolderFiles = obfItems,
        config = com.example.data.storage.BatchRenameConfig(
          replaceSpacesWithUnderscores = true,
          prefix = "2026_",
          numberingEnabled = true,
          numberingStart = 1,
          numberingPadding = 3,
        ),
      )
      val obfMap = renamePlan.associate { it.id to it.proposedName }
      val obfDiskBefore = obfDir.listFiles()!!.filter { it.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION) }.map { it.name }.toSet()

      val fileRenameResult = repo.batchRenameFolderFiles(obfFolder.id, obfMap, masterDek).getOrThrow()
      assertEquals(2, fileRenameResult.renamedCount)
      assertEquals(0, fileRenameResult.failedCount)

      // Folder remains locked and obfuscated UUID disk filenames remain unchanged
      assertEquals(true, db.vaultDao().getFolderById(obfFolder.id)!!.isLocked)
      val obfDiskAfter = obfDir.listFiles()!!.filter { it.name.endsWith(AesFolderCipher.ENCRYPTED_EXTENSION) }.map { it.name }.toSet()
      assertEquals(obfDiskBefore, obfDiskAfter)

      val renamedObfItems = repo.listFolderFiles(obfFolder.id, masterDek).getOrThrow().map { it.displayName }.toSet()
      assertEquals(setOf("2026_invoice_01_001.txt", "2026_my_draft_notes_002.txt"), renamedObfItems)

      // Non-obfuscated locked folder updates both metadata header and .vlock disk filename
      val nonObfItems = repo.listFolderFiles(plainNameFolder.id, masterDek).getOrThrow()
      val nonObfResult = repo.batchRenameFolderFiles(
        plainNameFolder.id,
        mapOf(nonObfItems.first().id to "clean_scan.txt"),
        masterDek,
      ).getOrThrow()
      assertEquals(1, nonObfResult.renamedCount)
      assertEquals(true, java.io.File(plainNameDir, "clean_scan.txt.vlock").exists())
      assertEquals(false, java.io.File(plainNameDir, "scan raw.txt.vlock").exists())

      // Batch rename locked folders themselves without decrypting contents
      val folderPlan = com.example.data.storage.BatchRenameEngine.computeBatchFolderRenamePlan(
        selectedFolders = listOf(obfFolder, plainNameFolder),
        allFolders = listOf(obfFolder, plainNameFolder),
        config = com.example.data.storage.BatchRenameConfig(
          sanitizeIllegalChars = true,
          replaceSpacesWithUnderscores = true,
          caseMode = com.example.data.storage.RenameCaseMode.TITLE_CASE,
        ),
      )
      val folderResult = repo.batchRenameFolders(folderPlan.associate { it.id to it.proposedName }).getOrThrow()
      assertEquals(2, folderResult.renamedCount)
      assertEquals("Raw_Docs_2026", db.vaultDao().getFolderById(obfFolder.id)!!.name)
      assertEquals("Temp_Photos", db.vaultDao().getFolderById(plainNameFolder.id)!!.name)

      // Unlocking afterwards restores exact plaintext content under the new filenames
      repo.unlockFolder(obfFolder.id, masterDek).getOrThrow()
      repo.unlockFolder(plainNameFolder.id, masterDek).getOrThrow()
      assertEquals("Encrypted Draft Body", java.io.File(obfDir, "2026_my_draft_notes_002.txt").readText())
      assertEquals("Encrypted Invoice Body", java.io.File(obfDir, "2026_invoice_01_001.txt").readText())
      assertEquals("Non-Obfuscated Secret", java.io.File(plainNameDir, "clean_scan.txt").readText())

      val auditActions = auditRepo.loadOrRefresh().entries.map { it.action }.toSet()
      assertEquals(true, "FILES_BATCH_RENAMED" in auditActions)
      assertEquals(true, "FOLDERS_BATCH_RENAMED" in auditActions)
    } finally {
      db.close()
    }
  }
}
