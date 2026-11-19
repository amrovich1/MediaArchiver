package com.mediaarchiver

import android.content.ContentValues
import android.content.Context
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

    /**
     * Blocking call: run it from a background thread (Dispatchers.IO).
     * Returns a short text describing where the file was saved.
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

        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("--no-mtime")
        request.addOption("--embed-metadata")
        request.addOption("-o", workDir.absolutePath + "/%(title).60s [%(id)s].%(ext)s")

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

        val savedPath = saveToDevice(ctx, result, audioOnly)
        workDir.deleteRecursively()
        return savedPath
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
