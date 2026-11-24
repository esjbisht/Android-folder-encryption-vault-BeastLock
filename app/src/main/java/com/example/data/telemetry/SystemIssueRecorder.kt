package com.example.data.telemetry

import android.content.Context
import android.os.Build
import com.example.data.local.SystemIssueReportEntity
import com.example.data.local.VaultDao
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class IssueCategory(val wireValue: String, val displayLabel: String) {
  CRYPTO_ERROR("CRYPTO_ERROR", "Encryption / Integrity Error"),
  KEY_RECOVERY("KEY_RECOVERY", "Key Vault Recovery"),
  DRIVE_SYNC("DRIVE_SYNC", "Google Drive Sync"),
  SAF_STORAGE("SAF_STORAGE", "Device Folder Access"),
  APP_CRASH("APP_CRASH", "System Crash"),
  USER_FEEDBACK("USER_FEEDBACK", "User Feedback"),
}

@Serializable
private data class PendingCrashMarker(
  val occurredAtMillis: Long,
  val errorCode: String,
  val sanitizedSummary: String,
  val appVersion: String,
  val androidSdk: Int,
  val deviceModel: String,
)

/**
 * Records sanitized system failures, crashes, crypto/storage problems, and user-authored reports
 * locally first into Room (`system_issue_reports`) so users retain full control to review, add
 * personal feedback, and submit reports to our cloud database on their own.
 */
class SystemIssueRecorder(
  private val context: Context,
  private val vaultDao: VaultDao,
) {
  companion object {
    private const val CRASH_MARKER_FILE = ".pending_crash_report.json"

    @Volatile
    private var crashHandlerInstalled = false

    fun installUncaughtCrashHook(context: Context) {
      if (crashHandlerInstalled) return
      crashHandlerInstalled = true
      val appContext = context.applicationContext
      val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
      Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
          val markerFile = File(appContext.filesDir, CRASH_MARKER_FILE)
          val rawSummary = "${throwable::class.java.simpleName} on ${thread.name}: ${throwable.message.orEmpty()}"
          val marker = PendingCrashMarker(
            occurredAtMillis = System.currentTimeMillis(),
            errorCode = throwable::class.java.simpleName.take(48).ifBlank { "UNCAUGHT_EXCEPTION" },
            sanitizedSummary = sanitizeDiagnosticText(rawSummary).take(1000),
            appVersion = resolveAppVersion(appContext),
            androidSdk = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(72),
          )
          markerFile.writeText(Json.encodeToString(marker), StandardCharsets.UTF_8)
        }
        previousHandler?.uncaughtException(thread, throwable)
      }
    }

    fun resolveAppVersion(context: Context): String =
      runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)?.versionName ?: "1.0"
      }.getOrDefault("1.0")

    fun sanitizeDiagnosticText(raw: String): String {
      var cleaned = raw
        .replace(Regex("content://\\S+"), "[redacted-uri]")
        .replace(Regex("/storage/[^\\s\"']+"), "[redacted-path]")
        .replace(Regex("/data/[^\\s\"']+"), "[redacted-app-path]")
        .replace(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}\\.vlock"), "[redacted-file].vlock")
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[redacted-email]")
        .replace(Regex("AIza[0-9A-Za-z_-]{20,}"), "[redacted-key]")
        .trim()
      if (cleaned.isBlank()) {
        cleaned = "Unspecified system diagnostic event"
      }
      return cleaned.take(1200)
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  fun observeIssues(): Flow<List<SystemIssueReportEntity>> = vaultDao.observeSystemIssues()

  suspend fun ingestPendingCrashMarker() {
    val markerFile = File(context.filesDir, CRASH_MARKER_FILE)
    if (!markerFile.exists()) return
    val parsed = runCatching {
      Json.decodeFromString<PendingCrashMarker>(markerFile.readText(StandardCharsets.UTF_8))
    }.getOrNull()
    runCatching { markerFile.delete() }
    if (parsed != null) {
      vaultDao.upsertSystemIssue(
        SystemIssueReportEntity(
          id = UUID.randomUUID().toString(),
          occurredAtMillis = parsed.occurredAtMillis,
          category = IssueCategory.APP_CRASH.wireValue,
          errorCode = parsed.errorCode,
          sanitizedSummary = parsed.sanitizedSummary,
          healthSnapshot = "crash_recovered=true;api=${parsed.androidSdk}",
          userFeedback = "",
          appVersion = parsed.appVersion,
          androidSdk = parsed.androidSdk,
          deviceModel = parsed.deviceModel,
          syncStatus = "PENDING",
        ),
      )
    }
  }

  fun recordIssueAsync(
    category: IssueCategory,
    errorCode: String,
    rawSummary: String,
    healthSnapshot: String = "",
    userFeedback: String = "",
  ) {
    scope.launch {
      recordIssue(
        category = category,
        errorCode = errorCode,
        rawSummary = rawSummary,
        healthSnapshot = healthSnapshot,
        userFeedback = userFeedback,
      )
    }
  }

  suspend fun recordIssue(
    category: IssueCategory,
    errorCode: String,
    rawSummary: String,
    healthSnapshot: String = "",
    userFeedback: String = "",
  ): SystemIssueReportEntity {
    val entity = SystemIssueReportEntity(
      id = UUID.randomUUID().toString(),
      occurredAtMillis = System.currentTimeMillis(),
      category = category.wireValue,
      errorCode = errorCode.trim().take(64).ifBlank { "GENERAL_ERROR" },
      sanitizedSummary = sanitizeDiagnosticText(rawSummary),
      healthSnapshot = sanitizeDiagnosticText(healthSnapshot).take(800),
      userFeedback = sanitizeDiagnosticText(userFeedback).take(2000).let {
        if (userFeedback.isBlank()) "" else it
      },
      appVersion = resolveAppVersion(context).take(32),
      androidSdk = Build.VERSION.SDK_INT.coerceIn(24, 50),
      deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80).ifBlank { "Android" },
      syncStatus = "PENDING",
    )
    vaultDao.upsertSystemIssue(entity)
    return entity
  }

  suspend fun getPendingIssues(): List<SystemIssueReportEntity> =
    vaultDao.getPendingSystemIssuesOnce()

  suspend fun markSent(id: String, cloudDocId: String, userFeedback: String) {
    vaultDao.markSystemIssueSent(
      id = id,
      status = "SENT",
      cloudDocId = cloudDocId,
      userFeedback = userFeedback.take(2000),
    )
  }

  suspend fun clearSentHistory() {
    vaultDao.clearSentSystemIssues()
  }
}
