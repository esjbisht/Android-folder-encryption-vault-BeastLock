# BeastLock

Open-source Android folder encryption vault built with **Kotlin**, **Jetpack Compose (Material 3)**, **AES-256-GCM**, **Android Keystore**, and **BiometricPrompt**.

Licensed under the [MIT License](LICENSE).

---

## Security & Cryptographic Architecture

### 1. Streaming AES-256-GCM (`VLK1` Format)
Every file inside a locked folder is encrypted using **AES-256-GCM** with a unique random 96-bit base nonce per file and 64 KiB authenticated chunks:
- **Header:** `VLK1` magic bytes + version (`0x01`) + 12-byte base nonce + chunk size + encrypted JSON metadata (`originalName`, `mimeType`, `originalSize`, `encryptedAt`).
- **Chunk Nonces:** `baseNonce XOR bigEndian64(chunkIndex)` with AAD `"VLK1-DATA-<chunkIndex>"` and 128-bit GCM authentication tag.
- **Truncation Defense:** Authenticated EOF sentinel tagged with `"VLK1-EOF-<totalChunks>"` verifies the stream was never truncated or tampered with.
- **Filename Obfuscation:** Optional UUID renaming (`<uuid>.vlock`) + `.nomedia` creation hides original filenames and prevents Android MediaStore thumbnail leaks.

### 2. Key Hierarchy & Biometric Binding
- A 256-bit random **Master Data Encryption Key (DEK)** protects the vault.
- **Per-Folder Key Rotation ("Re-encrypt All"):** Multi-select **Re-encrypt All** generates an independent 256-bit AES key per selected folder (wrapped by the Master DEK), atomically re-encrypts files with fresh 96-bit nonces via two-phase staging, increments the folder's key version, and logs the rotation.
- **Biometric Unlock:** `BiometricPrompt` unlocks access to an **Android Keystore** AES-256-GCM wrapping key (`vaultlock_keystore_master_v1`), which unwraps the DEK in memory (`VaultViewModel` session only).
- **Master PIN:** Derived via **PBKDF2WithHmacSHA256** (120,000 iterations, random 128-bit salt) to wrap the DEK independently.
- **Security Questions Recovery:** Normalized answers + random 128-bit salt derive a separate PBKDF2 key that wraps the same DEK. Plaintext answers are never stored on disk; answering them correctly unwraps the DEK and lets you re-wrap it with a new Master PIN without losing any encrypted files.

### 3. Zero-Knowledge Cloud Backup (`.vlkbak`)
- Exports an archive containing only **AES-256-GCM encrypted `.vlock` payloads** plus a PIN-wrapped key manifest (`manifest.json`) to **Google Drive**, **Dropbox**, or any Android Storage Access Framework (SAF) cloud provider.
- Neither the cloud provider nor BeastLock can read backed-up files without the Master PIN.

### 4. Auto-Lock & Background Re-Encryption
- Tracks background dwell time using Android `ProcessLifecycleOwner` and configurable durations (15s, 30s, 1m, 5m, 15m, or custom seconds/minutes).
- Automatically re-encrypts any open unlocked folders, wipes the in-memory DEK, and requires Biometric / Master PIN authentication on return.

### 5. Encrypted Security Audit Log (`vault_audit.log.enc`)
- Records failed PIN/biometric/recovery attempts, vault access events, lock/unlock operations, and cloud syncs in a dedicated local file (`filesDir/security/vault_audit.log.enc`) encrypted with an **Android Keystore AES-256-GCM** key (`vaultlock_audit_log_v1`) and linked via a **SHA-256 hash chain** for tamper detection.
- Supports in-app filtering, search, log rotation, and exporting human-readable audit reports via SAF.

### 6. Panic Mode (Shake-to-Lock)
- Lifecycle-scoped `SensorManager` (`Sensor.TYPE_ACCELEROMETER`) monitor detects intentional shakes using configurable g-force sensitivity (`Sensitive`, `Standard`, `Firm`) with double-peak windowing and cooldown debouncing.
- Immediately clears the in-memory DEK, seals the session to the Auth Gate, optionally re-encrypts unlocked folders, records `PANIC_LOCK` to `vault_audit.log.enc`, and returns to the Android Home screen.

### 7. Storage Analytics Dashboard
- Visualizes encrypted vs. unencrypted folder storage consumption alongside device `StatFs` storage metrics with interactive Jetpack Compose Canvas charts.

### 8. Privacy & Play Policy Compliance
- Zero broad storage permissions (`READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` are never requested).
- Uses Android's **Storage Access Framework** (`OpenDocumentTree`, `OpenMultipleDocuments`, `CreateDocument`, `OpenDocument`) and Android **Photo Picker** (`PickVisualMedia`).
- Offline-only core encryption with zero telemetry or analytics SDKs.
