package com.mediaarchiver

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// ============================ Models & helpers ============================

/** Thrown when every download attempt failed. The message holds a readable summary. */
class DownloadFailedException(message: String) : Exception(message)

/** Thrown when the user cancelled (download or link inspection). */
class DownloadCancelledException : Exception("cancelled")

data class QualityOption(val height: Int, val bytes: Long)

data class MediaInfo(
    val url: String,
    val title: String,
    val durationSec: Int,
    val qualities: List<QualityOption>,
    val audioBytes: Long,
    val bestClient: String?,
    val bestUsedCookies: Boolean
)

data class DownloadSpec(
    val url: String,
    val audioOnly: Boolean,
    val maxHeight: Int,
    val clipStart: Int,
    val clipEnd: Int,
    val estimatedBytes: Long,
    val hasPreferred: Boolean,
    val preferredClient: String?,
    val preferredCookies: Boolean
) {
    val hasClip: Boolean get() = clipStart >= 0 && clipEnd > clipStart
}

fun formatBytes(bytes: Long): String {
    val mb = bytes / 1048576.0
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

/** m:ss or h:mm:ss */
fun formatTime(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

private fun clockString(totalSec: Int): String {
    return String.format(
        Locale.US, "%d:%02d:%02d",
        totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60
    )
}

internal data class Attempt(val client: String?, val useCookies: Boolean) {
    fun label(): String {
        val name = client ?: "default"
        return if (useCookies) name + " + كوكيز" else name
    }
}

private fun jsonStr(o: JSONObject, key: String): String? {
    if (o.isNull(key)) return null
    val v = o.optString(key, "")
    return if (v.isEmpty()) null else v
}

private fun jsonNum(o: JSONObject, key: String): Double {
    if (o.isNull(key)) return 0.0
    return o.optDouble(key, 0.0)
}

// ============================ Downloader ============================

object Downloader {

    internal const val DOWNLOAD_ID = "mvp-download"
    private const val INSPECT_ID_PREFIX = "mvp-inspect-"

    private var ready = false

    private val inspectGeneration = AtomicInteger(0)

    @Volatile
    private var inspectProcessId: String? = null

    // null = yt-dlp default clients. Order: most likely to work without a PO Token first.
    private val YT_CLIENTS: List<String?> = listOf(
        null, "android_vr", "tv", "mweb", "web_safari", "tv_embedded", "ios", "android"
    )

    // These clients ignore account cookies, so they are only tried without cookies.
    private val COOKIE_UNSUPPORTED = setOf("ios", "android")

    // Errors where trying another client is pointless.
    private val TERMINAL_ERRORS = listOf("Unsupported URL", "is not a valid URL")

    @Synchronized
    internal fun ensureInit(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
        ready = true
    }

    // ---------- Cookies ----------

    internal fun cookiesFile(ctx: Context): File = File(ctx.filesDir, "cookies.txt")

    fun hasCookies(ctx: Context): Boolean = cookiesFile(ctx).let { it.exists() && it.length() > 0 }

    /** Copies a cookies.txt file chosen by the user into the app's private storage. */
    fun importCookies(ctx: Context, uri: Uri): Boolean {
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return false
            input.use { i ->
                cookiesFile(ctx).outputStream().use { o -> i.copyTo(o) }
            }
            cookiesFile(ctx).length() > 0
        } catch (e: Exception) {
            false
        }
    }

    fun clearCookies(ctx: Context) {
        cookiesFile(ctx).delete()
    }

    // ---------- Optional YouTube settings (PO Token / Visitor Data) ----------

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("contentslayer_prefs", Context.MODE_PRIVATE)

    fun getPoToken(ctx: Context): String = prefs(ctx).getString("po_token", "") ?: ""

    fun setPoToken(ctx: Context, value: String) {
        prefs(ctx).edit().putString("po_token", value.trim()).apply()
    }

    fun getVisitorData(ctx: Context): String = prefs(ctx).getString("visitor_data", "") ?: ""

    fun setVisitorData(ctx: Context, value: String) {
        prefs(ctx).edit().putString("visitor_data", value.trim()).apply()
    }

    /**
     * yt-dlp format is CLIENT.CONTEXT+TOKEN (for example web.gvs+XXXX).
     * If the user pasted only a raw token, it is applied to the common web clients.
     */
    private fun poTokenArg(raw: String): String? {
        val t = raw.filter { !it.isWhitespace() }.trim('"', '\'')
        if (t.isEmpty()) return null
        return if (t.contains("+")) t else "web.gvs+$t,web_safari.gvs+$t,mweb.gvs+$t"
    }

    // ---------- Engine update ----------

    /** Blocking: downloads the latest yt-dlp engine. Run from a background thread. */
    fun updateEngine(ctx: Context): String {
        ensureInit(ctx)
        val status = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel._STABLE)
        val version = YoutubeDL.getInstance().versionName(ctx)
        return "تحديث المحرك: " + status + " | الإصدار: " + version
    }

    // ---------- Attempts (clients x cookies) ----------

    internal fun isYoutube(url: String): Boolean {
        val host = (Uri.parse(url).host ?: "").lowercase()
        return host.endsWith("youtube.com") || host.endsWith("youtu.be") ||
            host.endsWith("youtube-nocookie.com")
    }

    internal fun buildAttempts(url: String, haveCookies: Boolean, preferred: Attempt?): List<Attempt> {
        val list = ArrayList<Attempt>()
        if (isYoutube(url)) {
            if (haveCookies) {
                for (client in YT_CLIENTS) {
                    if (client == null || client !in COOKIE_UNSUPPORTED) {
                        list.add(Attempt(client, true))
                    }
                }
            }
            for (client in YT_CLIENTS) {
                list.add(Attempt(client, false))
            }
        } else {
            if (haveCookies) list.add(Attempt(null, true))
            list.add(Attempt(null, false))
        }

        if (preferred != null) {
            val index = list.indexOf(preferred)
            if (index > 0) {
                list.removeAt(index)
                list.add(0, preferred)
            } else if (index < 0) {
                list.add(0, preferred)
            }
        }
        return list
    }

    /** Options shared by inspection and download: timeout, cookies, YouTube client, PO Token. */
    internal fun addAttemptOptions(
        request: YoutubeDLRequest,
        ctx: Context,
        attempt: Attempt,
        youtube: Boolean
    ) {
        request.addOption("--socket-timeout", "20")

        if (attempt.useCookies) {
            request.addOption("--cookies", cookiesFile(ctx).absolutePath)
        }

        if (youtube) {
            val args = ArrayList<String>()
            if (attempt.client != null) {
                args.add("player_client=" + attempt.client)
            }
            val poToken = poTokenArg(getPoToken(ctx))
            if (poToken != null) {
                args.add("po_token=" + poToken)
            }
            val visitor = getVisitorData(ctx).trim()
            if (visitor.isNotEmpty() && !attempt.useCookies) {
                args.add("player_skip=webpage,configs")
                args.add("visitor_data=" + visitor)
            }
            if (args.isNotEmpty()) {
                request.addOption("--extractor-args", "youtube:" + args.joinToString(";"))
            }
        }
    }

    // ---------- Error helpers ----------

    internal fun extractReason(e: Exception): String {
        val text = e.message ?: e.toString()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val line = lines.lastOrNull { it.startsWith("ERROR:") }
            ?: lines.lastOrNull()
            ?: "خطأ غير معروف"
        return line.removePrefix("ERROR:").trim().take(300)
    }

    internal fun isTerminal(reason: String): Boolean =
        TERMINAL_ERRORS.any { reason.contains(it, ignoreCase = true) }

    internal fun summarize(errors: List<String>, total: Int): String {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for (item in errors) {
            val idx = item.indexOf(": ")
            val label = if (idx > 0) item.substring(0, idx) else ""
            val reason = if (idx > 0) item.substring(idx + 2) else item
            grouped.getOrPut(reason) { ArrayList() }.add(label)
        }

        val sb = StringBuilder()
        sb.append("فشلت كل المحاولات (").append(errors.size).append(" من ").append(total).append(").\n")
        var shown = 0
        for ((reason, labels) in grouped) {
            if (shown >= 4) break
            sb.append("• ").append(reason).append("\n   [").append(labels.joinToString("، ")).append("]\n")
            shown++
        }

        val all = errors.joinToString(" ").lowercase()
        if (all.contains("sign in to confirm") || all.contains("not a bot")) {
            sb.append("\nالسبب: يوتيوب يطلب التحقق. جرّب ملف كوكيز صالح (أدوات الإصلاح) أو غيّر الشبكة.")
        } else if (all.contains("403") || all.contains("forbidden") || all.contains("po token")) {
            sb.append("\nالسبب: يوتيوب رفض الطلب (403) وغالباً يحتاج PO Token أو كوكيز.")
        } else if (all.contains("requested format is not available")) {
            sb.append("\nالسبب: الصيغة المطلوبة غير متاحة عبر هذه العملاء.")
        }
        return sb.toString().trim()
    }

    // ---------- Link inspection (available qualities + size estimates) ----------

    /**
     * Blocking: run from a background thread. Tries the same clients as the downloader
     * and returns the qualities that really exist for this link.
     */
    fun inspect(ctx: Context, url: String): MediaInfo {
        ensureInit(ctx)
        val generation = inspectGeneration.incrementAndGet()
        val youtube = isYoutube(url)
        val attempts = buildAttempts(url, hasCookies(ctx), null)
        val errors = ArrayList<String>()

        for (attempt in attempts) {
            if (inspectGeneration.get() != generation) throw DownloadCancelledException()

            val processId = INSPECT_ID_PREFIX + generation
            inspectProcessId = processId

            var raw: String? = null
            var failure: String? = null
            try {
                val request = YoutubeDLRequest(url)
                request.addOption("--dump-single-json")
                request.addOption("--no-playlist")
                request.addOption("--skip-download")
                request.addOption("--no-warnings")
                addAttemptOptions(request, ctx, attempt, youtube)
                val response = YoutubeDL.getInstance().execute(request, processId) { _, _, _ -> }
                raw = response.out
            } catch (e: Exception) {
                if (inspectGeneration.get() != generation) throw DownloadCancelledException()
                failure = extractReason(e)
            }

            if (raw != null) {
                val info = parseInfo(url, raw, attempt)
                if (info != null) return info
                failure = "تعذّر قراءة معلومات الرابط"
            }

            val reason = failure ?: "خطأ غير معروف"
            errors.add(attempt.label() + ": " + reason)
            if (isTerminal(reason)) break
        }
        throw DownloadFailedException(summarize(errors, attempts.size))
    }

    fun cancelInspect() {
        inspectGeneration.incrementAndGet()
        val id = inspectProcessId
        if (id != null) {
            try {
                YoutubeDL.getInstance().destroyProcessById(id)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private class VideoFormat(val ext: String, val size: Long, val muxed: Boolean)

    private fun formatSize(f: JSONObject, durationSec: Int): Long {
        var size = jsonNum(f, "filesize").toLong()
        if (size <= 0) size = jsonNum(f, "filesize_approx").toLong()
        if (size <= 0 && durationSec > 0) {
            size = (jsonNum(f, "tbr") * 125.0 * durationSec).toLong()
        }
        return if (size > 0) size else 0L
    }

    private fun parseInfo(url: String, raw: String, attempt: Attempt): MediaInfo? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = try {
            JSONObject(raw.substring(start, end + 1))
        } catch (e: Exception) {
            return null
        }

        val duration = jsonNum(root, "duration").toInt()
        val title = jsonStr(root, "title") ?: url

        val byHeight = HashMap<Int, ArrayList<VideoFormat>>()
        var audioBest = 0L
        var audioBestIsM4a = false

        val formats = root.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val vcodec = jsonStr(f, "vcodec") ?: "none"
                val acodec = jsonStr(f, "acodec") ?: "none"
                val ext = jsonStr(f, "ext") ?: ""
                val height = jsonNum(f, "height").toInt()
                val size = formatSize(f, duration)
                val hasVideo = vcodec != "none"
                val hasAudio = acodec != "none"

                if (hasVideo && height > 0) {
                    byHeight.getOrPut(height) { ArrayList() }.add(VideoFormat(ext, size, hasAudio))
                } else if (!hasVideo && hasAudio) {
                    val isM4a = ext == "m4a"
                    if ((isM4a && !audioBestIsM4a) || (isM4a == audioBestIsM4a && size > audioBest)) {
                        audioBest = size
                        audioBestIsM4a = isM4a
                    }
                }
            }
        }

        val qualities = byHeight.entries
            .filter { it.key >= 144 }
            .sortedByDescending { it.key }
            .map { entry ->
                val list = entry.value
                val mp4 = list.filter { it.ext == "mp4" }
                val pool = if (mp4.isNotEmpty()) mp4 else list
                val chosen = pool.maxByOrNull { it.size }!!
                val total = if (chosen.size > 0) {
                    chosen.size + (if (chosen.muxed) 0L else audioBest)
                } else {
                    0L
                }
                QualityOption(entry.key, total)
            }

        return MediaInfo(
            url = url,
            title = title,
            durationSec = duration,
            qualities = qualities,
            audioBytes = audioBest,
            bestClient = attempt.client,
            bestUsedCookies = attempt.useCookies
        )
    }

    // ---------- Process helpers ----------

    /** Best effort: stops ffmpeg children that may survive after the yt-dlp process is killed. */
    internal fun killStrayFfmpeg() {
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -f 'libffmpe[g]\\.so'"))
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------- Saving to Movies / Music ----------

    internal fun saveToDevice(ctx: Context, file: File, audio: Boolean): String {
        val ext = file.extension.lowercase()
        val systemMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        val mime = if (audio) {
            if (systemMime != null && systemMime.startsWith("audio/")) systemMime else "audio/mp4"
        } else {
            if (systemMime != null && systemMime.startsWith("video/")) systemMime else "video/mp4"
        }

        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val collection = if (audio) {
            MediaStore.Audio.Media.getContentUri(volume)
        } else {
            MediaStore.Video.Media.getContentUri(volume)
        }
        val baseFolder = if (audio) "Music" else "Movies"

        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, baseFolder + "/MediaArchive")
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)

        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("تعذر إنشاء الملف في التخزين")

        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("تعذر فتح الملف للكتابة")
            out.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        val done = ContentValues()
        done.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, done, null, null)

        return baseFolder + "/MediaArchive/" + file.name
    }
}

// ============================ Progress (downloaded MB / total MB) ============================

private class ProgressTracker(
    private val estimated: Long,
    private val audioOnly: Boolean
) {
    private var completed = 0L
    private var streamTotal = 0L
    private var lastPercent = 0f

    private val sizeRegex = Regex("of\\s+~?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?i?B)")

    private fun unitMultiplier(unit: String): Double {
        val u = unit.uppercase(Locale.US)
        return when {
            u.startsWith("T") -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            u.startsWith("G") -> 1024.0 * 1024.0 * 1024.0
            u.startsWith("M") -> 1024.0 * 1024.0
            u.startsWith("K") -> 1024.0
            else -> 1.0
        }
    }

    /** Called every time a new yt-dlp process starts (first start or after resume). */
    @Synchronized
    fun onNewProcess() {
        if (lastPercent >= 99.5f) {
            completed += streamTotal
            streamTotal = 0L
        }
        lastPercent = 0f
    }

    /** Returns (overall fraction 0..1, text like "45.2 MB / ≈ 180.0 MB"). */
    @Synchronized
    fun update(percent: Float, line: String): Pair<Float, String> {
        val p = percent.coerceIn(0f, 100f)

        // Percent jumped back: a new stream (for example audio after video) started.
        if (lastPercent - p > 40f) {
            completed += streamTotal
            streamTotal = 0L
        }

        val match = sizeRegex.find(line)
        if (match != null) {
            val value = match.groupValues[1].toDoubleOrNull()
            if (value != null) {
                streamTotal = (value * unitMultiplier(match.groupValues[2])).toLong()
            }
        }
        lastPercent = p

        val downloaded = completed + (streamTotal * (p / 100.0)).toLong()
        val known = completed + streamTotal
        val total = if (estimated > 0) maxOf(estimated, downloaded) else maxOf(known, downloaded)
        val fr
