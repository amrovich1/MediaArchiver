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

object Downloader {

    private var ready = false

    private val MEDIA_EXT = setOf("mp4", "mkv", "webm", "mov", "m4a", "mp3", "opus", "ogg", "aac")

    @Synchronized
    private fun ensureInit(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
        ready = true
    }

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

    /** Blocking: downloads the latest yt-dlp engine. Run from a background thread. */
    fun updateEngine(ctx: Context): String {
        ensureInit(ctx)
        val status = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel._STABLE)
        val version = YoutubeDL.getInstance().versionName(ctx)
        return "تحديث المحرك: " + status + " | الإصدار: " + version
    }

    /**
     * Blocking call: run it from a background thread.
     * Returns a short text describing where the file was saved.
     * The temporary folder is always deleted, even when the download fails.
     */
    fun download(
        ctx: Context,
        url: String,
        audioOnly: Boolean,
        onProgress: (Float) -> Unit
    ): String {
        ensureInit(ctx)

        val workDir = File(
            ctx.getExternalFilesDir(null) ?: ctx.filesDir,
            "tmp/" + System.currentTimeMillis()
        )
        workDir.mkdirs()

        try {
            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")
            request.addOption("--no-mtime")
            request.addOption("--embed-metadata")
            request.addOption("-o", workDir.absolutePath + "/%(title).60s [%(id)s].%(ext)s")

            if (hasCookies(ctx)) {
                request.addOption("--cookies", cookiesFile(ctx).absolutePath)
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

            YoutubeDL.getInstance().execute(request, "mvp-download") { progress, _, _ ->
                onProgress(progress)
            }

            val result = workDir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in MEDIA_EXT }
                ?.maxByOrNull { it.length() }
                ?: throw IllegalStateException("لم يتم إنتاج أي ملف")

            return saveToDevice(ctx, result, audioOnly)
        } finally {
            workDir.deleteRecursively()
        }
    }

    fun cancel() {
        try {
            YoutubeDL.getInstance().destroyProcessById("mvp-download")
        } catch (e: Exception) {
            // ignore
        }
    }

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
