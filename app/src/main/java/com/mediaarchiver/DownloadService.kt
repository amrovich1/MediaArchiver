package com.mediaarchiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow

/** Shared download state, read by the UI and written by the service. */
object DownloadState {
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow(0f)
    val status = MutableStateFlow("جاهز. الصق الرابط ثم اضغط ابدأ.")

    fun markStarting() {
        busy.value = true
        progress.value = 0f
        status.value = "جارٍ التحضير…"
    }
}

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    @Volatile
    private var cancelRequested = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                // Must be called right away after startForegroundService().
                startInForeground(progressNotification("جارٍ التحضير…", 0, true))

                if (worker?.isActive == true) {
                    return START_NOT_STICKY
                }

                val url = intent.getStringExtra(EXTRA_URL)
                val audio = intent.getBooleanExtra(EXTRA_AUDIO, false)
                if (url.isNullOrBlank()) {
                    DownloadState.busy.value = false
                    DownloadState.status.value = "الرابط غير صالح."
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                runDownload(url, audio)
            }

            ACTION_CANCEL -> {
                if (worker?.isActive == true) {
                    cancelRequested = true
                    Downloader.cancel()
                } else {
                    stopSelf()
                }
            }

            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun runDownload(url: String, audio: Boolean) {
        cancelRequested = false
        DownloadState.markStarting()
        getSystemService(NotificationManager::class.java).cancel(RESULT_ID)
        acquireWakeLock()

        worker = scope.launch {
            var ok = false
            var message = ""
            try {
                var lastNotify = 0L
                val saved = Downloader.download(
                    applicationContext,
                    url,
                    audio,
                    { text ->
                        DownloadState.status.value = text
                        notifyProgress(text, 0)
                    },
                    { p ->
                        val percent = p.coerceIn(0f, 100f)
                        DownloadState.progress.value = percent / 100f
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNotify >= 1000L) {
                            lastNotify = now
                            notifyProgress("جارٍ التحميل: " + percent.toInt() + "%", percent.toInt())
                        }
                    }
                )
                ok = true
                message = "تم الحفظ ✓\n" + saved
            } catch (e: Exception) {
                message = if (cancelRequested) {
                    "تم إلغاء التحميل."
                } else {
                    "فشل: " + shortReason(e)
                }
            }

            releaseWakeLock()
            DownloadState.status.value = message
            DownloadState.progress.value = 0f
            DownloadState.busy.value = false

            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (!cancelRequested) {
                    showResult(ok, message)
                }
                stopSelf()
            }
        }
    }

    private fun shortReason(e: Exception): String {
        val text = e.message ?: "خطأ غير معروف"
        if (e is DownloadFailedException) {
            return text.take(1500)
        }
        val last = text.lines().lastOrNull { it.isNotBlank() } ?: "خطأ غير معروف"
        return last.take(300)
    }

    // ---------- Notifications ----------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "تقدم التحميل", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "نتيجة التحميل", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun startInForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun notifyProgress(text: String, percent: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, progressNotification(text, percent, percent <= 0))
    }

    private fun progressNotification(text: String, percent: Int, indeterminate: Boolean): Notification {
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إلغاء", cancelIntent())
            .build()
    }

    private fun showResult(ok: Boolean, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(
                if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error
            )
            .setContentTitle(if (ok) "اكتمل التحميل" else "فشل التحميل")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_ID, notification)
    }

    private fun cancelIntent(): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(
            this, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---------- Wake lock (time-limited, released as soon as the download ends) ----------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediaArchiver:download")
        lock.setReferenceCounted(false)
        lock.acquire(2 * 60 * 60 * 1000L)
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        if (lock != null && lock.isHeld) {
            lock.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        if (worker?.isActive == true) {
            cancelRequested = true
            Downloader.cancel()
            DownloadState.busy.value = false
            DownloadState.progress.value = 0f
            DownloadState.status.value = "تم إيقاف التحميل."
        }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mediaarchiver.START"
        const val ACTION_CANCEL = "com.mediaarchiver.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_AUDIO = "audio"

        private const val CHANNEL_PROGRESS = "download_progress"
        private const val CHANNEL_RESULT = "download_result"
        private const val NOTIF_ID = 1
        private const val RESULT_ID = 2

        fun start(ctx: Context, url: String, audioOnly: Boolean) {
            val intent = Intent(ctx, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_AUDIO, audioOnly)
            try {
                ContextCompat.startForegroundService(ctx, intent)
            } catch (e: Exception) {
                DownloadState.busy.value = false
                DownloadState.status.value = "تعذر بدء خدمة التحميل: " + (e.message ?: "")
            }
        }

        fun cancel(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL))
            } catch (e: Exception) {
                Downloader.cancel()
            }
        }
    }
}
