package com.trustmark.share

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object PayloadBundle {
    private const val MAX_ENTRIES = 1000
    private const val MAX_EXTRACTED_BYTES = 700L * 1024 * 1024
    private const val MIN_FREE_SPACE = 16L * 1024 * 1024

    fun pack(items: List<LocalMedia>, workDir: File): LocalMedia {
        require(items.isNotEmpty()) { "请至少添加一项隐藏内容" }
        if (items.size == 1) return items.first()
        workDir.mkdirs()
        val required = items.fold(MIN_FREE_SPACE) { total, item -> Math.addExact(total, item.file.length()) }
        val usable = workDir.usableSpace
        require(usable <= 0L || usable >= required) {
            "可用存储空间不足：打包至少需要 ${required / 1024 / 1024} MiB，当前约 ${usable / 1024 / 1024} MiB"
        }
        val output = File(workDir, "hidden-files-${System.nanoTime()}.zip")
        val used = mutableSetOf<String>()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            items.forEachIndexed { index, item ->
                var name = File(item.displayName).name.ifBlank { "file-${index + 1}" }
                if (!used.add(name)) name = "${index + 1}-$name".also(used::add)
                zip.putNextEntry(ZipEntry(name))
                item.file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return LocalMedia(output, "hidden-files.zip", "application/zip")
    }

    fun unpack(bundle: File, workDir: File): List<LocalMedia> {
        require(bundle.isFile) { "恢复压缩包不存在" }
        workDir.mkdirs()
        val root = workDir.canonicalFile
        val used = mutableSetOf<String>()
        val restored = mutableListOf<LocalMedia>()
        var total = 0L
        try {
            ZipInputStream(bundle.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "压缩包不能包含文件夹" }
                    require(restored.size < MAX_ENTRIES) { "压缩包文件数量超过 $MAX_ENTRIES 个" }
                    val name = entry.name
                    require(name.isNotBlank() && name == File(name).name && name != "." && name != "..") { "压缩包包含不安全路径" }
                    require(used.add(name.lowercase())) { "压缩包包含重复文件名" }
                    val output = File(root, name).canonicalFile
                    require(output.parentFile == root) { "压缩包包含不安全路径" }
                    try {
                        output.outputStream().buffered().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_EXTRACTED_BYTES) { "解压后内容超过 700 MB" }
                                val usable = root.usableSpace
                                require(usable <= 0L || usable >= MIN_FREE_SPACE) { "可用存储空间不足，已停止解包" }
                                out.write(buffer, 0, count)
                            }
                        }
                    } catch (failure: Throwable) {
                        output.delete()
                        throw failure
                    }
                    restored += LocalMedia(output, name, MediaFiles.mimeOf(name))
                    zip.closeEntry()
                }
            }
        } catch (failure: Throwable) {
            restored.forEach { it.file.delete() }
            throw failure
        }
        require(restored.isNotEmpty()) { "恢复压缩包为空" }
        return restored
    }
}
