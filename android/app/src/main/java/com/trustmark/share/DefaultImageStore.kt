package com.trustmark.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.ln

data class TextureReport(
    val accepted: Boolean,
    val entropy: Double,
    val edgeScore: Double,
    val texturedBlocks: Double,
)

object DefaultImageStore {
    private const val PREFS = "default_images"

    fun first(context: Context): LocalMedia = current(
        context, "first", R.drawable.default_payload, "default-payload.png", "image/png"
    )

    fun second(context: Context): LocalMedia = current(
        context, "second", R.drawable.default_landscape, "default-landscape.png", "image/png"
    )

    fun replace(context: Context, slot: String, uri: Uri): LocalMedia {
        val imported = MediaFiles.import(context, uri)
        require(imported.isImage) { "请选择图片文件" }
        requireNotNull(BitmapFactory.decodeFile(imported.file.absolutePath)) { "无法读取所选图片" }.recycle()
        val ext = imported.displayName.substringAfterLast('.', "img").lowercase().take(8)
        val dir = File(context.filesDir, "default-images").apply { mkdirs() }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString("${slot}_path", null)?.let(::File)
        val dst = File(dir, "$slot-user-${System.nanoTime()}.$ext")
        imported.file.inputStream().use { input -> dst.outputStream().use(input::copyTo) }
        prefs.edit()
            .putString("${slot}_path", dst.absolutePath)
            .putString("${slot}_name", imported.displayName)
            .putString("${slot}_mime", imported.mime)
            .apply()
        previous?.takeIf { it.parentFile == dir && it.name.startsWith("$slot-user-") }?.delete()
        return LocalMedia(dst, imported.displayName, imported.mime)
    }

    fun optimizePayloadImage(source: LocalMedia, workDir: File, quality: Int = 75): LocalMedia {
        if (!source.isImage) return source
        if (isAnimated(source.file)) return source
        val digest = MessageDigest.getInstance("SHA-256")
        source.file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val key = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        workDir.mkdirs()
        val candidate = File(workDir, "$key-q${quality.coerceIn(0, 100)}.webp")
        if (candidate.isFile && candidate.length() in 1 until source.file.length()) {
            candidate.setLastModified(System.currentTimeMillis())
            return LocalMedia(candidate, source.displayName.substringBeforeLast('.') + ".webp", "image/webp")
        }
        val bitmap = decode(source)
        val temporary = File(workDir, "$key-${System.nanoTime()}.tmp")
        val ok = FileOutputStream(temporary).use { output ->
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality.coerceIn(0, 100), output)
        }
        bitmap.recycle()
        if (!ok || temporary.length() <= 0L || temporary.length() >= source.file.length()) {
            temporary.delete()
            return source
        }
        if (!temporary.renameTo(candidate)) {
            temporary.copyTo(candidate, overwrite = true)
            temporary.delete()
        }
        return LocalMedia(candidate, source.displayName.substringBeforeLast('.') + ".webp", "image/webp")
    }

    fun prepareFirstCarrier(source: LocalMedia, workDir: File): LocalMedia {
        val bitmap = decode(source)
        workDir.mkdirs()
        val candidate = File(workDir, "carrier-${System.nanoTime()}.jpg")
        val ok = FileOutputStream(candidate).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        if (!ok || candidate.length() <= 0L || candidate.length() >= source.file.length()) {
            candidate.delete()
            return source
        }
        return LocalMedia(candidate, "carrier.jpg", "image/jpeg")
    }

    fun revision(source: LocalMedia): String = "${source.file.absolutePath}:${source.file.length()}:${source.file.lastModified()}"

    private fun isAnimated(file: File): Boolean {
        val header = ByteArray(21)
        val count = file.inputStream().buffered().use { it.read(header) }
        if (count >= 6) {
            val signature = header.copyOfRange(0, 6).toString(Charsets.US_ASCII)
            if (signature == "GIF87a" || signature == "GIF89a") return true
        }
        return count >= 21 && header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" &&
            header.copyOfRange(12, 16).toString(Charsets.US_ASCII) == "VP8X" &&
            (header[20].toInt() and 0x02) != 0
    }

    fun textureReport(source: LocalMedia): TextureReport {
        val original = decode(source)
        val bitmap = Bitmap.createScaledBitmap(original, 256, 256, true)
        if (bitmap !== original) original.recycle()
        val pixels = IntArray(256 * 256)
        bitmap.getPixels(pixels, 0, 256, 0, 0, 256, 256)
        bitmap.recycle()
        val gray = IntArray(pixels.size)
        val histogram = IntArray(256)
        for (i in pixels.indices) {
            val p = pixels[i]
            val y = (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
            gray[i] = y; histogram[y]++
        }
        var entropy = 0.0
        histogram.forEach { count -> if (count > 0) { val p = count.toDouble() / gray.size; entropy -= p * (ln(p) / ln(2.0)) } }
        var edgeSum = 0.0
        var edgeCount = 0
        val active = BooleanArray(8 * 8)
        for (y in 1 until 255) for (x in 1 until 255) {
            val i = y * 256 + x
            val lap = kotlin.math.abs(4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - 256] - gray[i + 256])
            edgeSum += lap; edgeCount++
            if (lap >= 18) active[(y / 32).coerceAtMost(7) * 8 + (x / 32).coerceAtMost(7)] = true
        }
        val edge = edgeSum / edgeCount.coerceAtLeast(1)
        val coverage = active.count { it }.toDouble() / active.size
        return TextureReport(entropy >= 6.2 && edge >= 10.0 && coverage >= 0.55, entropy, edge, coverage)
    }

    private fun current(context: Context, slot: String, resId: Int, name: String, mime: String): LocalMedia {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString("${slot}_path", null)?.let(::File)?.takeIf { it.isFile }
        if (saved != null) return LocalMedia(saved, prefs.getString("${slot}_name", name) ?: name, prefs.getString("${slot}_mime", mime) ?: mime)
        val dir = File(context.filesDir, "default-images").apply { mkdirs() }
        val dst = File(dir, name)
        if (!dst.isFile) context.resources.openRawResource(resId).use { input -> dst.outputStream().use(input::copyTo) }
        return LocalMedia(dst, name, mime)
    }

    private fun decode(source: LocalMedia): Bitmap =
        requireNotNull(BitmapFactory.decodeFile(source.file.absolutePath)) { "无法解码图片：${source.displayName}" }
}
