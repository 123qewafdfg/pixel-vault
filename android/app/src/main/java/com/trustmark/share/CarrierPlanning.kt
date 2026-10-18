package com.trustmark.share

enum class CarrierAllocationMode { STANDARD, AUTO }
enum class CarrierSelectionMode { DEFAULT, MANUAL }

data class CarrierAllocation(
    val mode: CarrierAllocationMode = CarrierAllocationMode.STANDARD,
    val targetAppendBytes: Long = CarrierPlanner.DEFAULT_AUTO_APPEND_BYTES,
)

data class CarrierPlan(
    val plainSizes: List<Long>,
    val appendSizes: List<Long>,
    val estimatedCarrierSizes: List<Long>,
) {
    val partCount: Int get() = plainSizes.size
    val estimatedOutputBytes: Long get() = estimatedCarrierSizes.sum()
}

object CarrierPlanner {
    const val STANDARD_PLAIN_BYTES = 20L * 1024 * 1024
    const val MIN_AUTO_APPEND_BYTES = 1_000_000L
    const val MAX_AUTO_APPEND_BYTES = 4_000_000L
    const val DEFAULT_AUTO_APPEND_BYTES = 3_500_000L
    const val GCM_TAG_BYTES = 16L
    const val INDEX_BYTES = 112L
    const val MAX_PROTOCOL_PARTS = 65_535
    const val ESTIMATED_METADATA_BYTES = 384

    fun plan(
        secretSize: Long,
        coverSizes: List<Long>,
        allocation: CarrierAllocation = CarrierAllocation(),
        metadataSize: Int = ESTIMATED_METADATA_BYTES,
        manualCovers: Boolean = coverSizes.size > 1,
    ): CarrierPlan {
        require(secretSize >= 0) { "隐藏内容大小无效" }
        require(coverSizes.isNotEmpty() && coverSizes.all { it > 0 }) { "请至少选择一张有效承载图" }
        require(metadataSize in 0..4096) { "加密元数据大小无效" }

        val capacities = when {
            manualCovers -> List(coverSizes.size) { STANDARD_PLAIN_BYTES }
            allocation.mode == CarrierAllocationMode.AUTO -> {
                require(allocation.targetAppendBytes in MIN_AUTO_APPEND_BYTES..MAX_AUTO_APPEND_BYTES &&
                    allocation.targetAppendBytes % 500_000L == 0L) { "单张尾追加容量必须为 1.0–4.0 MB，步进 0.5 MB" }
                val first = allocation.targetAppendBytes - GCM_TAG_BYTES - INDEX_BYTES - metadataSize
                val rest = allocation.targetAppendBytes - GCM_TAG_BYTES - INDEX_BYTES
                require(first >= 0 && rest > 0) { "单张尾追加容量不足以容纳协议开销" }
                val count = requiredPartCount(secretSize, first, rest)
                List(count) { if (it == 0) first else rest }
            }
            else -> List(requiredPartCount(secretSize, STANDARD_PLAIN_BYTES, STANDARD_PLAIN_BYTES)) { STANDARD_PLAIN_BYTES }
        }
        require(capacities.size <= MAX_PROTOCOL_PARTS) { "分片数量超过协议上限 $MAX_PROTOCOL_PARTS" }
        require(secretSize <= capacities.sum()) { "承载图片数量不足，请选择更多图片" }

        val plainSizes = distribute(secretSize, capacities, coverSizes, metadataSize)
        val appendSizes = plainSizes.mapIndexed { index, plain ->
            plain + GCM_TAG_BYTES + INDEX_BYTES + if (index == 0) metadataSize else 0
        }
        val expandedCoverSizes = List(plainSizes.size) { coverSizes[if (coverSizes.size == 1) 0 else it] }
        return CarrierPlan(
            plainSizes = plainSizes,
            appendSizes = appendSizes,
            estimatedCarrierSizes = appendSizes.mapIndexed { index, append -> expandedCoverSizes[index] + append },
        )
    }

    private fun requiredPartCount(total: Long, firstCapacity: Long, otherCapacity: Long): Int {
        if (total <= firstCapacity) return 1
        val remaining = total - firstCapacity
        val extra = (remaining + otherCapacity - 1) / otherCapacity
        require(extra < MAX_PROTOCOL_PARTS) { "分片数量超过协议上限 $MAX_PROTOCOL_PARTS" }
        return (1 + extra).toInt()
    }

    private fun distribute(total: Long, capacities: List<Long>, coverSizes: List<Long>, metadataSize: Int): List<Long> {
        if (total == 0L) return List(capacities.size) { 0L }
        val repeatedCoverSizes = List(capacities.size) { coverSizes[if (coverSizes.size == 1) 0 else it] }
        val bases = repeatedCoverSizes.mapIndexed { index, size ->
            size + GCM_TAG_BYTES + INDEX_BYTES + if (index == 0) metadataSize else 0
        }
        var low = bases.minOrNull() ?: 0L
        var high = bases.indices.maxOf { bases[it] + capacities[it] }
        while (low < high) {
            val target = low + (high - low) / 2
            val available = capacities.indices.sumOf { index -> (target - bases[index]).coerceIn(0L, capacities[index]) }
            if (available >= total) high = target else low = target + 1
        }
        val result = capacities.indices.map { index -> (low - bases[index]).coerceIn(0L, capacities[index]) }.toMutableList()
        var excess = result.sum() - total
        for (index in result.indices.reversed()) {
            if (excess == 0L) break
            val reduction = minOf(excess, result[index])
            result[index] -= reduction
            excess -= reduction
        }
        require(result.sum() == total) { "无法生成均衡分片" }
        return result
    }
}
