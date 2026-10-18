package com.trustmark.share

/** Limits apply only to the Bilibili path; the manual protocol retains its limits. */
object BiliPartition {
    const val MAX_PAYLOAD = 100L * 1024 * 1024
    const val MAX_PARTS = 64
    const val MAX_APPEND = 6L * 1024 * 1024
    const val MAX_CARRIER_BYTES = 4_800_000L
    const val DEFAULT_CARRIER_BYTES = 2_747_567L
    private const val OVERHEAD = 16L + 112L // GCM tag and tail index

    fun plan(bytes: Long, metadataBytes: Int, carrierBytes: Long = DEFAULT_CARRIER_BYTES): List<Long> {
        require(bytes in 1..MAX_PAYLOAD) { "B站隐藏内容必须为 1 字节至 100 MiB" }
        require(carrierBytes in 1 until MAX_CARRIER_BYTES) { "B站载体图本身过大" }
        val appendLimit = minOf(MAX_APPEND, MAX_CARRIER_BYTES - carrierBytes)
        require(metadataBytes >= 0 && metadataBytes + OVERHEAD < appendLimit) { "文件元数据过大" }
        for (count in 1..MAX_PARTS) {
            val bases = List(count) { OVERHEAD + if (it == 0) metadataBytes else 0 }
            if (bases.sumOf { appendLimit - it } < bytes) continue
            var low = bases.max()
            var high = appendLimit
            while (low < high) {
                val middle = low + (high - low) / 2
                if (bases.sumOf { (middle - it).coerceAtLeast(0) } >= bytes) high = middle else low = middle + 1
            }
            val sizes = bases.map { (low - it).coerceAtLeast(0) }.toMutableList()
            var excess = sizes.sum() - bytes
            var index = sizes.lastIndex
            while (excess > 0) {
                if (sizes[index] > 0) { sizes[index]--; excess-- }
                index = if (index == 0) sizes.lastIndex else index - 1
            }
            check(sizes.sum() == bytes && sizes.indices.all { sizes[it] + bases[it] <= appendLimit })
            return sizes
        }
        error("无法生成 B站分片")
    }
}
