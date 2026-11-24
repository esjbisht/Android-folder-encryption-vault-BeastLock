package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.storage.EncryptionProgress

data class CryptoNotificationCopy(
  val title: String,
  val text: String,
  val permille: Int,
  val indeterminate: Boolean,
)

class CryptoProgressForegroundService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    val progress = latestProgress
    if (action == ACTION_STOP || progress == null) {
      isServiceActive = false
      ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    ensureNotificationChannel(this)
    val notification = buildNotification(this, progress)
    val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    } else {
      0
    }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    isServiceActive = true
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    isServiceActive = false
    runCatching {
      getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
    super.onDestroy()
  }

  companion object {
    const val CHANNEL_ID = "beastlock_crypto_progress"
    const val NOTIFICATION_ID = 4102
    private const val ACTION_START = "com.example.service.action.START_CRYPTO_PROGRESS"
    private const val ACTION_STOP = "com.example.service.action.STOP_CRYPTO_PROGRESS"
    private const val UPDATE_INTERVAL_MS = 120L

    @Volatile
    private var latestProgress: EncryptionProgress? = null

    @Volatile
    private var isServiceActive: Boolean = false

    @Volatile
    private var lastNotifyAtMs: Long = 0L

    @Volatile
    private var lastNotifyFileKey: String = ""

    @Volatile
    private var lastNotifyPermille: Int = -1

    fun formatNotificationCopy(progress: EncryptionProgress): CryptoNotificationCopy {
      val fraction = progress.overallFraction.coerceIn(0f, 1f)
      val permille = (fraction * 1000f).toInt().coerceIn(0, 1000)
      val percent = (fraction * 100f).toInt().coerceIn(0, 100)
      val indeterminate = progress.totalFiles <= 0 && progress.totalBytes <= 0L

      val title = if (progress.isStealthMasked) {
        if (progress.isLocking) "Encrypting Private Vault…" else "Decrypting Private Vault…"
      } else {
        val actionWord = if (progress.isLocking) "Encrypting" else "Decrypting"
        "$actionWord ${progress.folderName}"
      }

      val totalFiles = progress.totalFiles.coerceAtLeast(1)
      val activeIndex = (progress.processedFiles + 1).coerceIn(1, totalFiles)
      val text = if (progress.isStealthMasked) {
        if (indeterminate) {
          "AES-256-GCM • Preparing Private Vault…"
        } else {
          "Protected file $activeIndex/$totalFiles • $percent%"
        }
      } else {
        if (indeterminate) {
          "${progress.currentFileName} • Preparing…"
        } else {
          "${progress.currentFileName} ($activeIndex/$totalFiles) • $percent%"
        }
      }

      return CryptoNotificationCopy(
        title = title,
        text = text,
        permille = permille,
        indeterminate = indeterminate,
      )
    }

    fun syncProgress(context: Context, progress: EncryptionProgress?) {
      if (progress == null) {
        val wasActive = isServiceActive || latestProgress != null
        latestProgress = null
        isServiceActive = false
        lastNotifyAtMs = 0L
        lastNotifyFileKey = ""
        lastNotifyPermille = -1
        if (wasActive) {
          runCatching {
            context.stopService(Intent(context, CryptoProgressForegroundService::class.java))
          }
          runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
          }
        }
        return
      }

      latestProgress = progress
      val copy = formatNotificationCopy(progress)
      val now = System.currentTimeMillis()
      val fileKey = "${progress.folderId}:${progress.currentFileId}:${progress.currentFileName}"

      if (!isServiceActive) {
        lastNotifyAtMs = now
        lastNotifyFileKey = fileKey
        lastNotifyPermille = copy.permille
        runCatching {
          val intent = Intent(context, CryptoProgressForegroundService::class.java).apply {
            action = ACTION_START
          }
          ContextCompat.startForegroundService(context, intent)
        }
        return
      }

      val fileChanged = fileKey != lastNotifyFileKey
      val significantProgress = kotlin.math.abs(copy.permille - lastNotifyPermille) >= 5
      val elapsedEnough = now - lastNotifyAtMs >= UPDATE_INTERVAL_MS
      if (fileChanged || significantProgress || elapsedEnough || copy.permille == 1000) {
        lastNotifyAtMs = now
        lastNotifyFileKey = fileKey
        lastNotifyPermille = copy.permille
        runCatching {
          ensureNotificationChannel(context)
          val manager = context.getSystemService(NotificationManager::class.java)
          manager?.notify(NOTIFICATION_ID, buildNotification(context, progress))
        }
      }
    }

    private fun ensureNotificationChannel(context: Context) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
          val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.crypto_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
          ).apply {
            description = context.getString(R.string.crypto_notification_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
          }
          manager.createNotificationChannel(channel)
        }
      }
    }

    private fun buildNotification(context: Context, progress: EncryptionProgress): Notification {
      val copy = formatNotificationCopy(progress)
      val launchIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
      val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      val contentIntent = PendingIntent.getActivity(context, 0, launchIntent, pendingFlags)

      return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_qs_lock_vault)
        .setContentTitle(copy.title)
        .setContentText(copy.text)
        .setSubText("AES-256-GCM")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setVisibility(
          if (progress.isStealthMasked) {
            NotificationCompat.VISIBILITY_SECRET
          } else {
            NotificationCompat.VISIBILITY_PRIVATE
          },
        )
        .setProgress(1000, copy.permille, copy.indeterminate)
        .setContentIntent(contentIntent)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
    }
  }
}
