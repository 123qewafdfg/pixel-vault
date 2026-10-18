package com.trustmark.share

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class UpdateApplyProgress(val label: String, val fraction: Float)

object DeltaUpdater {
    private val magic = "TMDLT001".toByteArray(Charsets.US_ASCII)
    private const val MAX_PATCH = 150L * 1024 * 1024
    private const val MAX_APK = 300L * 1024 * 1024

    fun prepare(context: Context, update: AppUpdate, preferPatch: Boolean, onProgress: (UpdateApplyProgress) -> Unit): File {
        if (!preferPatch || update.patch == null) return prepareFull(context, update, onProgress)
        val patchInfo = requireNotNull(update.patch) { "当前版本没有可用的差分补丁" }
        require(patchInfo.fromVersionCode == BuildConfig.VERSION_CODE) { "差分补丁与当前版本不匹配" }
        require(context.applicationInfo.splitSourceDirs.isNullOrEmpty()) { "分包安装不支持差分更新，请使用完整更新" }
        val baseApk = File(context.applicationInfo.sourceDir)
        require(baseApk.isFile) { "无法读取当前安装包" }
        onProgress(UpdateApplyProgress("正在校验当前版本", .03f))
        require(sha256(baseApk) == patchInfo.baseApkSha256) { "当前安装包与差分补丁不匹配，请使用完整更新" }

        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        updateDir.listFiles()?.forEach { if (it.name != "update-${update.versionCode}.apk") it.delete() }
        val patchFile = File(updateDir, "update-${patchInfo.fromVersionCode}-${update.versionCode}.tmdelta.gz")
        val output = File(updateDir, "update-${update.versionCode}.apk")
        if (output.isFile && sha256(output) == patchInfo.resultApkSha256) {
            verifyApk(context, output, update.versionCode)
            onProgress(UpdateApplyProgress("更新包已准备完成", 1f))
            return output
        }

        downloadAny(patchInfo.urls, patchFile, minOf(MAX_PATCH, patchInfo.size.coerceAtLeast(1L)), patchInfo.size, patchInfo.sha256) { mirror, downloaded, total ->
            val denominator = if (total > 0) total else patchInfo.size
            onProgress(UpdateApplyProgress("正在下载差分补丁 · 线路 $mirror/${patchInfo.urls.size} · ${humanBytes(downloaded)}/${humanBytes(denominator)}", .05f + .55f * (downloaded.toFloat() / denominator.coerceAtLeast(1L)).coerceIn(0f, 1f)))
        }
        onProgress(UpdateApplyProgress("正在合成新安装包", .64f))
        try {
            applyPatch(baseApk, patchFile, output) { fraction -> onProgress(UpdateApplyProgress("正在合成新安装包", .64f + .26f * fraction)) }
            require(output.length() <= MAX_APK) { "合成后的安装包过大" }
            require(sha256(output) == patchInfo.resultApkSha256) { "新安装包完整性校验失败" }
            onProgress(UpdateApplyProgress("正在验证签名", .93f))
            verifyApk(context, output, update.versionCode)
            onProgress(UpdateApplyProgress("更新包已准备完成", 1f))
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            patchFile.delete()
        }
    }

    private fun prepareFull(context: Context, update: AppUpdate, onProgress: (UpdateApplyProgress) -> Unit): File {
        val info = requireNotNull(update.fullApk) { "服务器没有提供完整安装包镜像" }
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val output = File(updateDir, "update-${update.versionCode}.apk")
        if (output.isFile && output.length() == info.size && sha256(output) == info.sha256) {
            verifyApk(context, output, update.versionCode)
            onProgress(UpdateApplyProgress("更新包已准备完成", 1f))
            return output
        }
        downloadAny(info.urls, output, MAX_APK, info.size, info.sha256) { mirror, downloaded, total ->
            val denominator = if (total > 0) total else info.size
            onProgress(UpdateApplyProgress("正在下载完整更新 · 线路 $mirror/${info.urls.size} · ${humanBytes(downloaded)}/${humanBytes(denominator)}", .05f + .85f * (downloaded.toFloat() / denominator.coerceAtLeast(1L)).coerceIn(0f, 1f)))
        }
        onProgress(UpdateApplyProgress("正在验证签名", .94f))
        verifyApk(context, output, update.versionCode)
        onProgress(UpdateApplyProgress("更新包已准备完成", 1f))
        return output
    }

    fun install(context: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            throw IllegalStateException("请允许此应用安装未知应用，然后返回并再次点击“安装更新”")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun applyPatch(base: File, patch: File, output: File, onProgress: (Float) -> Unit) {
        DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(patch), 64 * 1024), 64 * 1024)).use { input ->
            val foundMagic = ByteArray(magic.size).also(input::readFully)
            require(foundMagic.contentEquals(magic)) { "不支持的差分补丁格式" }
            val baseSize = input.readLong()
            val targetSize = input.readLong()
            val baseHash = ByteArray(32).also(input::readFully).toHex()
            val targetHash = ByteArray(32).also(input::readFully).toHex()
            require(base.length() == baseSize && sha256(base) == baseHash) { "基础安装包校验失败" }
            require(targetSize in 1..MAX_APK) { "目标安装包大小无效" }
            RandomAccessFile(base, "r").use { old ->
                BufferedOutputStream(FileOutputStream(output), 128 * 1024).use { out ->
                    val buffer = ByteArray(128 * 1024)
                    var written = 0L
                    while (true) {
                        when (val opcode = input.readUnsignedByte()) {
                            0 -> {
                                val offset = input.readLong(); val length = input.readInt()
                                require(offset >= 0 && length > 0 && offset + length <= baseSize) { "差分复制指令越界" }
                                old.seek(offset); var remaining = length
                                while (remaining > 0) { val count = minOf(buffer.size, remaining); old.readFully(buffer, 0, count); out.write(buffer, 0, count); remaining -= count; written += count }
                            }
                            1 -> {
                                val length = input.readInt(); require(length > 0 && written + length <= targetSize) { "差分数据指令越界" }
                                var remaining = length
                                while (remaining > 0) { val count = minOf(buffer.size, remaining); input.readFully(buffer, 0, count); out.write(buffer, 0, count); remaining -= count; written += count }
                            }
                            127 -> break
                            else -> error("未知差分指令：$opcode")
                        }
                        require(written <= targetSize) { "差分输出越界" }
                        onProgress(written.toFloat() / targetSize)
                    }
                    require(written == targetSize) { "差分输出大小不完整" }
                }
            }
            require(sha256(output) == targetHash) { "差分目标哈希校验失败" }
            require(input.read() == -1) { "差分补丁包含多余数据" }
        }
    }

    private fun verifyApk(context: Context, apk: File, expectedVersion: Int) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val candidate = requireNotNull(context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)) { "无法解析新安装包" }
        require(candidate.packageName == context.packageName) { "新安装包包名不匹配" }
        val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) candidate.longVersionCode else @Suppress("DEPRECATION") candidate.versionCode.toLong()
        require(version == expectedVersion.toLong() && version > BuildConfig.VERSION_CODE) { "新安装包版本号无效" }
        val current = context.packageManager.getPackageInfo(context.packageName, flags)
        require(signatures(candidate).contentEquals(signatures(current))) { "新安装包签名与当前版本不一致" }
    }

    private fun signatures(info: android.content.pm.PackageInfo): Array<String> {
        val values = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") info.signatures
        return values.orEmpty().map { bytesSha256(it.toByteArray()) }.sorted().toTypedArray()
    }

    private fun downloadAny(urls: List<String>, target: File, max: Long, expectedSize: Long, expectedSha256: String, onProgress: (Int, Long, Long) -> Unit) {
        var lastError: Throwable? = null
        urls.forEachIndexed { index, raw ->
            target.delete()
            try {
                download(raw, target, max) { downloaded, total -> onProgress(index + 1, downloaded, total) }
                require(target.length() == expectedSize) { "下载文件大小不一致" }
                require(sha256(target) == expectedSha256) { "下载文件完整性校验失败" }
                return
            } catch (error: Throwable) {
                lastError = error
                target.delete()
            }
        }
        throw IllegalStateException("全部 ${urls.size} 条更新线路均下载失败：${lastError?.message ?: "未知错误"}", lastError)
    }

    private fun download(raw: String, target: File, max: Long, onProgress: (Long, Long) -> Unit) {
        val connection = (URL(raw).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 120_000; instanceFollowRedirects = true; setRequestProperty("User-Agent", "TrustMarkShare/${BuildConfig.VERSION_NAME}") }
        try {
            require(connection.responseCode in 200..299) { "补丁下载失败（HTTP ${connection.responseCode}）" }
            require(connection.url.protocol.equals("https", true)) { "拒绝非 HTTPS 重定向" }
            val total = connection.contentLengthLong
            require(total <= 0 || total <= max) { "差分补丁超过大小限制" }
            connection.inputStream.use { input -> FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024); var downloaded = 0L
                while (true) { val read = input.read(buffer); if (read < 0) break; downloaded += read; require(downloaded <= max) { "差分补丁超过大小限制" }; output.write(buffer, 0, read); onProgress(downloaded, total) }
            } }
        } finally { connection.disconnect() }
    }

    private fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(256 * 1024)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        digest.digest().toHex()
    }
    private fun bytesSha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun humanBytes(bytes: Long) = if (bytes < 1024 * 1024) "%.1f KB".format(bytes / 1024.0) else "%.1f MB".format(bytes / 1048576.0)
}
