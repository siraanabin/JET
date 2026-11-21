package com.jet.downloader
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.LinkedHashMap

class DownloadNotificationService : Service() {
    private val active = LinkedHashMap<Int, Notification>()
    private var foregroundId: Int? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val id = intent.getIntExtra(EXTRA_ID, 4100)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "JET Download" }
        when (action) {
            ACTION_START, ACTION_UPDATE -> {
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0).coerceIn(0, 99)
                val speed = intent.getStringExtra(EXTRA_SPEED).orEmpty()
                val notification = activeNotification(id, title, progress, speed)
                active[id] = notification
                if (foregroundId == null || foregroundId == id || action == ACTION_START) promote(id, notification)
                else getSystemService(NotificationManager::class.java).notify(id, notification)
            }
            ACTION_COMPLETE, ACTION_FAILED -> {
                active.remove(id)
                val next = active.entries.firstOrNull()
                if (foregroundId == id || foregroundId == null) {
                    if (next != null) promote(next.key, next.value)
                    else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        foregroundId = null
                        stopSelf()
                    }
                }
                val text = if (action == ACTION_COMPLETE) "Download Completed · " + intent.getStringExtra(EXTRA_SIZE).orEmpty()
                    else "Download Failed · " + intent.getStringExtra(EXTRA_ERROR).orEmpty()
                getSystemService(NotificationManager::class.java).notify(id, finalNotification(id, title, text))
            }
            ACTION_CANCEL -> {
                active.remove(id)
                getSystemService(NotificationManager::class.java).cancel(id)
                val next = active.entries.firstOrNull()
                if (foregroundId == id) {
                    if (next != null) promote(next.key, next.value)
                    else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        foregroundId = null
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promote(id: Int, notification: Notification) {
        foregroundId = id
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(id, notification)
    }

    private fun contentIntent(id: Int): PendingIntent {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_TAB, "downloads")
        }
        return PendingIntent.getActivity(this, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun activeNotification(id: Int, title: String, progress: Int, speed: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(speed.ifBlank { "Downloading…" })
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(id))
            .build()

    private fun finalNotification(id: Int, title: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(id))
            .build()

    companion object {
        const val CHANNEL_ID = "jet_download_manager"
        const val EXTRA_OPEN_TAB = "open_tab"
        private const val EXTRA_ID = "notification_id"
        private const val EXTRA_TITLE = "notification_title"
        private const val EXTRA_PROGRESS = "notification_progress"
        private const val EXTRA_SPEED = "notification_speed"
        private const val EXTRA_SIZE = "notification_size"
        private const val EXTRA_ERROR = "notification_error"
        private const val ACTION_START = "com.jet.downloader.download.START"
        private const val ACTION_UPDATE = "com.jet.downloader.download.UPDATE"
        private const val ACTION_COMPLETE = "com.jet.downloader.download.COMPLETE"
        private const val ACTION_FAILED = "com.jet.downloader.download.FAILED"
        private const val ACTION_CANCEL = "com.jet.downloader.download.CANCEL"

        private fun send(context: Context, action: String, id: String, title: String, extra: Intent.() -> Unit = {}) {
            val notificationId = id.hashCode().and(0x7fffffff).coerceAtLeast(1)
            val request = Intent(context, DownloadNotificationService::class.java).apply {
                this.action = action
                putExtra(EXTRA_ID, notificationId)
                putExtra(EXTRA_TITLE, title)
                extra()
            }
            ContextCompat.startForegroundService(context, request)
        }

        fun start(context: Context, id: String, title: String, thumbnailUrl: String) =
            send(context, ACTION_START, id, title)

        fun update(context: Context, id: String, title: String, progress: Int, speed: String, thumbnailUrl: String) =
            send(context, ACTION_UPDATE, id, title) {
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_SPEED, speed)
            }

        fun completed(context: Context, id: String, title: String, size: String, thumbnailUrl: String) =
            send(context, ACTION_COMPLETE, id, title) { putExtra(EXTRA_SIZE, size) }

        fun failed(context: Context, id: String, title: String, error: String) =
            send(context, ACTION_FAILED, id, title) { putExtra(EXTRA_ERROR, error) }

        fun cancel(context: Context, id: String) = send(context, ACTION_CANCEL, id, "JET Download")
    }
}