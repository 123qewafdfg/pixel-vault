package com.trustmark.share

import android.content.Context
import java.io.File

data class CacheCleanupResult(val before: Long, val after: Long) {
    val released: Long get() = (before - after).coerceAtLeast(0)
}

object CacheManager {
    private const val MAX_BYTES = 1024L * 1024 * 1024
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private val managedPrefixes = listOf(
        "imports", "payload-bundles", "payload-opt", "carrier-opt", "carriers",
        "remote", "remote-", "restored", "text-", "trustmark-result", "bili-task", "bili-qr", "webp-cache"
    )

    fun size(context: Context): Long = managedRoots(context).sumOf(::sizeOf)

    fun maintain(context: Context): CacheCleanupResult {
        val before = size(context)
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        managedRoots(context).forEach { removeExpired(it, cutoff) }
        trim(context, MAX_BYTES)
        return CacheCleanupResult(before, size(context))
    }

    fun clear(context: Context): CacheCleanupResult {
        val before = size(context)
        managedRoots(context).forEach { it.deleteRecursively() }
        return CacheCleanupResult(before, size(context))
    }

    fun clearTask(context: Context, prefixes: Set<String>): CacheCleanupResult {
        val before = size(context)
        context.cacheDir.listFiles().orEmpty()
            .filter { file -> prefixes.any { file.name == it || file.name.startsWith("$it-") } }
            .forEach { it.deleteRecursively() }
        return CacheCleanupResult(before, size(context))
    }

    private fun managedRoots(context: Context) = context.cacheDir.listFiles().orEmpty()
        .filter { file -> managedPrefixes.any { file.name == it || file.name.startsWith(it) } }

    private fun sizeOf(file: File): Long = if (file.isFile) file.length() else file.listFiles().orEmpty().sumOf(::sizeOf)

    private fun removeExpired(file: File, cutoff: Long) {
        if (file.isDirectory) file.listFiles().orEmpty().forEach { removeExpired(it, cutoff) }
        if ((file.isFile || file.listFiles().orEmpty().isEmpty()) && file.lastModified() in 1 until cutoff) file.delete()
    }

    private fun trim(context: Context, limit: Long) {
        var total = size(context)
        if (total <= limit) return
        for (file in managedRoots(context).flatMap(::leafFiles).sortedBy { it.lastModified() }) {
            if (total <= limit) break
            val length = file.length()
            if (file.delete()) total -= length
        }
        managedRoots(context).forEach(::removeEmptyDirs)
    }

    private fun leafFiles(file: File): List<File> = if (file.isFile) listOf(file) else file.listFiles().orEmpty().flatMap(::leafFiles)
    private fun removeEmptyDirs(file: File) {
        if (!file.isDirectory) return
        file.listFiles().orEmpty().forEach(::removeEmptyDirs)
        if (file.listFiles().orEmpty().isEmpty()) file.delete()
    }
}
