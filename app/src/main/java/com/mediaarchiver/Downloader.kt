package com.mediaarchiver

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** Thrown when every download attempt failed. The message holds a readable summary. */
class DownloadFailedException(message: String) : Exception(message)

object Downloader {

    private const val PROCESS_ID = "mvp-download"

    private var ready = false

    @Volatile
    private var cancelled = false

    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov")
    private val AUDIO_EXT = setOf("m4a", "mp3", "opus", "ogg", "aac")

    // null = yt-dlp default clients. Order: most likely to work without a PO Token first.
    private val YT_CLIENTS: List<String?> = listOf(
        null, "android_vr", "tv", "mweb", "web_safari", "tv_embedded", "ios", "android"
    )

    // These clients ignore account cookies, so they are only tried without cookies.
    private val COOKIE_UNSUPPORTED = setOf("ios", "android")

    // Errors where trying another client is pointless.
    private val TERMINAL_ERRORS = listOf("Unsupported URL", "is not a valid URL")

    private data class Attempt(val client: String?, val useCookies: Boolean) {
        fun label(): String {
            val name = client ?: "default"
            return if (useCookies) name + " + كوكيز" else name
        }
    }

    @Synchronized
    private fun ensureInit(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
        ready = true
    }

    // ---------- Cookies ----------

    private fun cookiesFile(ctx: Context): File = File(ctx.filesDir, "cookies.txt")

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

    // ---------- Download with automatic fallback ----------

    private fun isYoutube(url: String): Boolean {
        val host = (Uri.parse(url).host ?: "").lowercase()
        return host.endsWith("youtube.com") || host.endsWith("youtu.be") ||
            host.endsWith("youtube-nocookie.com")
    }

    private fun buildAttempts(url: String, haveCookies: Boolean): List<Attempt> {
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
        return list
    }

    private fun buildRequest(
        ctx: Context,
        url: String,
        audioOnly: Boolean,
        workDir: File,
        attempt: Attempt,
        youtube: Boolean
    ): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("--no-mtime")
        request.addOption("--embed-metadata")
        request.addOption("--socket-timeout", "20")
        request.addOption("--retries", "3")
        request.addOption("--fragment-retries", "3")
        request.addOption("-o", workDir.absolutePath + "/%(title).60s [%(id)s].%(ext)s")

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

        if (audioOnly) {
            request.addOption("-f", "ba[ext=m4a]/ba/b")
            request.addOption("-x")
            request.addOption("--audio-format", "m4a")
        } else {
            request.addOption("-f", "bv*[height<=?720]+ba/b[height<=?720]")
            request.addOption("-S", "res,ext:mp4:m4a")
            request.addOption("--merge-output-format", "mp4")
        }
        return request
    }

    private fun findResult(workDir: File, audioOnly: Boolean): File? {
        val allowed = if (audioOnly) AUDIO_EXT else VIDEO_EXT
        // Ignore leftover single-stream pieces such as "Title [id].f137.mp4".
        val piece = Regex("\\.f[0-9]+\\.[A-Za-z0-9]+$")
        return workDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in allowed && !piece.containsMatchIn(it.name) }
            ?.maxByOrNull { it.length() }
    }

    private fun extractReason(e: Exception): String {
        val text = e.message ?: e.toString()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val line = lines.lastOrNull { it.startsWith("ERROR:") }
            ?: lines.lastOrNull()
            ?: "خطأ غير معروف"
        return line.removePrefix("ERROR:").trim().take(300)
    }

    private fun summarize(errors: List<String>, total: Int): String {
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

    /**
     * Blocking call: run it from a background thread.
     * Tries several yt-dlp clients (and with/without cookies) until one works.
     * Returns a short text describing where the file was saved.
     * The temporary folder is always deleted.
     */
    fun download(
        ctx: Context,
        url: String,
        audioOnly: Boolean,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit
    ): String {
        ensureInit(ctx)
        cancelled = false

        val youtube = isYoutube(url)
        val attempts = buildAttempts(url, hasCookies(ctx))

        val workDir = File(
            ctx.getExternalFilesDir(null) ?: ctx.filesDir,
            "tmp/" + System.currentTimeMillis()
        )
        workDir.mkdirs()

        try {
            val errors = ArrayList<String>()

            for ((index, attempt) in attempts.withIndex()) {
                if (cancelled) throw IllegalStateException("cancelled")

                onStatus("المحاولة " + (index + 1) + " من " + attempts.size + " (" + attempt.label() + ")…")
                onProgress(0f)

                var failure: String? = null
                try {
                    val request = buildRequest(ctx, url, audioOnly, workDir, attempt, youtube)
                    YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, _, _ ->
                        onProgress(progress)
                    }
                } catch (e: Exception) {
                    if (cancelled) throw e
                    failure = extractReason(e)
                }

                val result = findResult(workDir, audioOnly)
                if (result != null) {
                    return saveToDevice(ctx, result, audioOnly)
                }

                val reason = failure ?: "لم ينتج yt-dlp أي ملف"
                errors.add(attempt.label() + ": " + reason)

                if (TERMINAL_ERRORS.any { reason.contains(it, ignoreCase = true) }) {
                    break
                }
            }

            throw DownloadFailedException(summarize(errors, attempts.size))
        } finally {
            workDir.deleteRecursively()
        }
    }

    fun cancel() {
        cancelled = true
        try {
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------- Saving to Movies / Music ----------

    private fun saveToDevice(ctx: Context, file: File, audio: Boolean): String {
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
