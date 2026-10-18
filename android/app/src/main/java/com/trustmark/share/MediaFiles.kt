package com.trustmark.share

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

data class LocalMedia(val file: File, val displayName: String, val mime: String, val sourceSize: Long = file.length()) {
    val isImage get() = mime.startsWith("image/")
    val isVideo get() = mime.startsWith("video/")
    val isAudio get() = mime.startsWith("audio/")
    val isText get() = mime.startsWith("text/") || mime in setOf("application/json", "application/xml")
}

object MediaFiles {
    fun import(context: Context, uri: Uri, maxBytes: Long = Long.MAX_VALUE): LocalMedia {
        val resolver = context.contentResolver
        var name = "file_${System.currentTimeMillis()}"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        val mime = resolver.getType(uri) ?: mimeOf(name)
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        val dir = File(context.cacheDir, "imports").apply { mkdirs() }
        val dst = File(dir, "${System.nanoTime()}_$safe")
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                dst.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var received = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        received += count
                        require(received <= maxBytes) { "文件超过允许的大小" }
                        output.write(buffer,0,count)
                    }
                }
            }
        } catch (failure: Throwable) { dst.delete(); throw failure }
        return LocalMedia(dst, name, mime)
    }

    fun save(context: Context, source: File, name: String, mime: String): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return saveLegacy(context, source, name, mime)
        val image = mime.startsWith("image/")
        val video = mime.startsWith("video/")
        val collection = when { image -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI; video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI; else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI }
        val folder = when { image -> Environment.DIRECTORY_PICTURES; video -> Environment.DIRECTORY_MOVIES; else -> Environment.DIRECTORY_DOWNLOADS }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/TrustMark Share")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(collection, values))
        try {
            context.contentResolver.openOutputStream(uri).use { out -> requireNotNull(out); source.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            return uri
        } catch (e: Throwable) { context.contentResolver.delete(uri, null, null); throw e }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, source: File, name: String, mime: String): Uri {
        val folder = when {
            mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(folder), "TrustMark Share").apply { mkdirs() }
        val safeName = File(name).name.ifBlank { "trustmark-${System.currentTimeMillis()}" }
        val stem = safeName.substringBeforeLast('.', safeName)
        val ext = safeName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var output = File(dir, safeName)
        var suffix = 1
        while (output.exists()) output = File(dir, "$stem-${suffix++}$ext")
        source.inputStream().use { input -> output.outputStream().use(input::copyTo) }
        MediaScannerConnection.scanFile(context, arrayOf(output.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(output)
    }

    fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"
        "mp4" -> "video/mp4"; "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "aac" -> "audio/aac"; "wav" -> "audio/wav"; "flac" -> "audio/flac"; "ogg" -> "audio/ogg"
        "txt", "log", "md", "csv" -> "text/plain"; "xml" -> "application/xml"; "json" -> "application/json"
        "pdf" -> "application/pdf"; "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
