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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one and only download state. The UI and the notification both read from here. */
enum class Phase { Idle, Downloading, Paused, Cancelled, Completed, Error }

object DownloadState {
    val phase = MutableStateFlow(Phase.Idle)
    val progress = MutableStateFlow(0f)
    val bytesText = MutableStateFlow("")
    val status = MutableStateFlow("جاهز. الصق الرابط ثم اضغط ابدأ.")
    val confirmCancel = MutableStateFlow(false)
}

/**
 * Owns the download. Every state change (start, pause, resume, cancel, finish)
 * happens here, on the main thread, so the screen and the notification always agree.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var session: DownloadSession? = null
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var resumeAfterNo = false
    private var cancelling = false

    @Volatile
    private var lastNotify = 0L

    private val listener = object : DownloadSession.Listener {
        override fun onStatus(text: String) {
            if (session == null) return
            DownloadState.status.value = text
            refreshNotification()
        }

        override fun onProgress(fraction: Float, bytesText: String) {
            if (session == null) return
            DownloadState.progress.value = fraction
            DownloadState.bytesText.value = bytesText
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotify >= 1000L) {
                lastNotify = now
                refreshNotification()
            }
        }
    }

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
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_REQUEST_CANCEL -> handleRequestCancel()
            ACTION_CANCEL_NO -> handleCancelNo()
            ACTION_CANCEL_YES -> handleCancelYes()
            else -> Unit
        }

        if (session == null && intent.action != ACTION_START) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------- Commands ----------

    private fun handleStart(intent: Intent) {
        // Must be called right away after startForegroundService().
        startInForeground(buildNotification())

        if (session != null) return

        val spec = specFromIntent(intent)
        if (spec == null) {
            DownloadState.status.value = "الرابط غير صالح."
            DownloadState.phase.value = Phase.Error
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val newSession = DownloadSession(applicationContext, spec, listener)
        session = newSession
        resumeAfterNo = false
        cancelling = false
        DownloadState.confirmCancel.value = false
        DownloadState.progress.value = 0f
        DownloadState.bytesText.value = ""
        DownloadState.status.value = "جارٍ التحضير…"
        DownloadState.phase.value = Phase.Downloading
        acquireWakeLock()
        refreshNotification()

        worker = scope.launch { runSession(newSession) }
    }

    private fun handlePause() {
        val s = session ?: return
        if (cancelling) return
        if (DownloadState.phase.value != Phase.Downloading) return
        if (s.pause()) {
            releaseWakeLock()
            DownloadState.status.value = "تم الإيقاف مؤقتاً. اضغط استئناف للمتابعة."
            DownloadState.phase.value = Phase.Paused
            refreshNotification()
        }
    }

    private fun handleResume() {
        val s = session ?: return
        if (cancelling) return
        DownloadState.confirmCancel.value = false
        resumeAfterNo = false
        if (DownloadState.phase.value != Phase.Paused) return
        acquireWakeLock()
        DownloadState.status.value = "جارٍ استئناف التحميل…"
        DownloadState.phase.value = Phase.Downloading
        s.resume()
        refreshNotification()
    }

    /** Cancel was pressed: stop temporarily and ask the user to confirm. */
    private fun handleRequestCancel() {
        val s = session ?: return
        if (cancelling) return
        val phase = DownloadState.phase.value
        if (phase == Phase.Downloading) {
            if (!s.pause()) return
            resumeAfterNo = true
            releaseWakeLock()
            DownloadState.status.value = "تم الإيقاف مؤقتاً. هل تريد إلغاء التحميل؟"
            DownloadState.phase.value = Phase.Paused
        } else if (phase == Phase.Paused) {
            resumeAfterNo = false
        } else {
            return
        }
        DownloadState.confirmCancel.value = true
        refreshNotification()
    }

    /** "No": keep the download. It continues only if it was running before the question. */
    private fun handleCancelNo() {
        if (!DownloadState.confirmCancel.value) return
        DownloadState.confirmCancel.value = false
        if (resumeAfterNo) {
            resumeAfterNo = false
            handleResume()
        }
    }

    /** "Yes": stop for good and delete the partial files. */
    private fun handleCancelYes() {
        val s = session ?: return
        DownloadState.confirmCancel.value = false
        resumeAfterNo = false
        if (cancelling) return
        cancelling = true
        DownloadState.status.value = "جارٍ الإلغاء…"
        s.cancel()
        refreshNotification()
    }

    // ---------- Running the session ----------

    private suspend fun runSession(s: DownloadSession) {
        var phase: Phase
        var message: String
        try {
            val saved = s.run()
            phase = Phase.Completed
            message = "تم الحفظ ✓\n" + saved
        } catch (e: DownloadCancelledException) {
            phase = Phase.Cancelled
            message = "تم إلغاء التحميل."
        } catch (e: Exception) {
            phase = Phase.Error
            message = "فشل: " + shortReason(e)
        }

        withContext(Dispatchers.Main) {
            finish(phase, message)
        }
    }

    private fun finish(phase: Phase, message: String) {
        session = null
        worker = null
        resumeAfterNo = false
        cancelling = false
        releaseWakeLock()

        DownloadState.confirmCancel.value = false
        DownloadState.status.value = message
        DownloadState.progress.value = 0f
        DownloadState.bytesText.value = ""
        DownloadState.phase.value = phase

        stopForeground(STOP_FOREGROUND_REMOVE)
        if (phase == Phase.Completed || phase == Phase.Error) {
            showResult(phase == Phase.Completed, message)
        }
        stopSelf()
    }

    private fun shortReason(e: Exception): String {
        val text = e.message ?: "خطأ غير معروف"
        if (e is DownloadFailedException) {
            return text.take(1500)
        }
        val last = text.lines().lastOrNull { it.isNotBlank() } ?: "خطأ غير معروف"
        return last.take(300)
    }

    private fun specFromIntent(intent: Intent): DownloadSpec? {
        val url = intent.getStringExtra(EXTRA_URL) ?: return null
        return DownloadSpec(
            url = url,
            audioOnly = intent.getBooleanExtra(EXTRA_AUDIO, false),
            maxHeight = intent.getIntExtra(EXTRA_HEIGHT, 0),
            clipStart = intent.getIntExtra(EXTRA_CLIP_START, -1),
            clipEnd = intent.getIntExtra(EXTRA_CLIP_END, -1),
            estimatedBytes = intent.getLongExtra(EXTRA_EST, 0L),
            hasPreferred = intent.getBooleanExtra(EXTRA_HAS_PREF, false),
            preferredClient = intent.getStringExtra(EXTRA_PREF_CLIENT),
            preferredCookies = intent.getBooleanExtra(EXTRA_PREF_COOKIES, false)
        )
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

    private fun refreshNotification() {
        if (session == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val phase = DownloadState.phase.value
        val paused = phase == Phase.Paused
        val percent = (DownloadState.progress.value * 100f).toInt().coerceIn(0, 100)
        val bytes = DownloadState.bytesText.value

        val text = if (paused) {
            var t = "متوقف مؤقتاً"
            if (percent > 0) t += " • " + percent + "%"
            if (bytes.isNotEmpty()) t += " • " + bytes
            t
        } else if (percent > 0) {
            var t = "جارٍ التحميل: " + percent + "%"
            if (bytes.isNotEmpty()) t += " • " + bytes
            t
        } else {
            DownloadState.status.value
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(if (paused) android.R.drawable.ic_media_pause else android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, percent, !paused && percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())

        if (paused) {
            builder.addAction(android.R.drawable.ic_media_play, "استئناف", serviceIntent(ACTION_RESUME, 12))
        } else {
            builder.addAction(android.R.drawable.ic_media_pause, "إيقاف", serviceIntent(ACTION_PAUSE, 11))
        }
        // Cancel opens the app, which pauses the download and asks for confirmation.
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "إلغاء", confirmCancelIntent())

        return builder.build()
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

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun confirmCancelIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CONFIRM_CANCEL, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, 13, intent,
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

    // ---------- Wake lock (time-limited, released while paused and when finished) ----------

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
        val s = session
        if (s != null) {
            s.cancel()
            session = null
            DownloadState.confirmCancel.value = false
            DownloadState.progress.value = 0f
            DownloadState.bytesText.value = ""
            DownloadState.status.value = "تم إيقاف التحميل."
            DownloadState.phase.value = Phase.Cancelled
        }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mediaarchiver.START"
        const val ACTION_PAUSE = "com.mediaarchiver.PAUSE"
        const val ACTION_RESUME = "com.mediaarchiver.RESUME"
        const val ACTION_REQUEST_CANCEL = "com.mediaarchiver.REQUEST_CANCEL"
        const val ACTION_CANCEL_NO = "com.mediaarchiver.CANCEL_NO"
        const val ACTION_CANCEL_YES = "com.mediaarchiver.CANCEL_YES"

        private const val EXTRA_URL = "url"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_CLIP_START = "clip_start"
        private const val EXTRA_CLIP_END = "clip_end"
        private const val EXTRA_EST = "estimated_bytes"
        private const val EXTRA_HAS_PREF = "has_preferred"
        private const val EXTRA_PREF_CLIENT = "preferred_client"
        private const val EXTRA_PREF_COOKIES = "preferred_cookies"

        private const val CHANNEL_PROGRESS = "download_progress"
        private const val CHANNEL_RESULT = "download_result"
        private const val NOTIF_ID = 1
        private const val RESULT_ID = 2

        fun start(ctx: Context, spec: DownloadSpec) {
            val intent = Intent(ctx, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, spec.url)
                .putExtra(EXTRA_AUDIO, spec.audioOnly)
                .putExtra(EXTRA_HEIGHT, spec.maxHeight)
                .putExtra(EXTRA_CLIP_START, spec.clipStart)
                .putExtra(EXTRA_CLIP_END, spec.clipEnd)
                .putExtra(EXTRA_EST, spec.estimatedBytes)
                .putExtra(EXTRA_HAS_PREF, spec.hasPreferred)
                .putExtra(EXTRA_PREF_CLIENT, spec.preferredClient)
                .putExtra(EXTRA_PREF_COOKIES, spec.preferredCookies)

            DownloadState.confirmCancel.value = false
            DownloadState.progress.value = 0f
            DownloadState.bytesText.value = ""
            DownloadState.status.value = "جارٍ التحضير…"
            DownloadState.phase.value = Phase.Downloading
            try {
                ContextCompat.startForegroundService(ctx, intent)
            } catch (e: Exception) {
                DownloadState.status.value = "تعذر بدء خدمة التحميل: " + (e.message ?: "")
                DownloadState.phase.value = Phase.Error
            }
        }

        private fun send(ctx: Context, action: String) {
            try {
                ctx.startService(Intent(ctx, DownloadService::class.java).setAction(action))
            } catch (e: Exception) {
                // ignore
            }
        }

        fun pause(ctx: Context) = send(ctx, ACTION_PAUSE)
        fun resume(ctx: Context) = send(ctx, ACTION_RESUME)
        fun requestCancel(ctx: Context) = send(ctx, ACTION_REQUEST_CANCEL)
        fun cancelNo(ctx: Context) = send(ctx, ACTION_CANCEL_NO)
        fun cancelYes(ctx: Context) = send(ctx, ACTION_CANCEL_YES)
    }
}
