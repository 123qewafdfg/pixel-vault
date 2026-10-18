package com.trustmark.share

import kotlin.math.abs

object TrustMarkCodec {
    private const val PACKET_BITS = 100
    private const val DATA_BITS = 40
    private const val ECC_BITS = 56
    private const val MAX_ERRORS = 8
    private val generator: BooleanArray by lazy { buildGenerator() }

    fun encode(id: ByteArray): FloatArray {
        require(id.size == 4) { "水印数据长度不正确" }
        val data = BooleanArray(DATA_BITS)
        id.forEachIndexed { byteIndex, byte ->
            for (bit in 0..7) data[byteIndex * 8 + bit] = ((byte.toInt() ushr (7 - bit)) and 1) != 0
        }
        val work = BooleanArray(DATA_BITS + ECC_BITS)
        data.copyInto(work)
        for (i in 0 until DATA_BITS) if (work[i]) {
            for (j in generator.indices) work[i + j] = work[i + j] xor generator[j]
        }
        return FloatArray(PACKET_BITS).also { out ->
            for (i in data.indices) out[i] = if (data[i]) 1f else 0f
            for (i in 0 until ECC_BITS) out[DATA_BITS + i] = if (work[DATA_BITS + i]) 1f else 0f
        }
    }

    fun decode(logits: FloatArray): Pair<ByteArray, Int> {
        require(logits.size == PACKET_BITS) { "TrustMark 数据包必须为 100 位" }
        val observed = BooleanArray(96) { logits[it] >= 0f }
        val raw = ByteArray(4)
        for (b in 0..3) for (bit in 0..7) if (observed[b * 8 + bit]) {
            raw[b] = (raw[b].toInt() or (1 shl (7 - bit))).toByte()
        }
        var best = raw
        var bestDistance = distance(observed, encode(raw))
        if (bestDistance > MAX_ERRORS) {
            val weak = (0 until 32).sortedBy { abs(logits[it]) }.take(12)
            for (mask in 1 until (1 shl weak.size)) {
                val candidate = raw.copyOf()
                for (i in weak.indices) if ((mask and (1 shl i)) != 0) {
                    val p = weak[i]
                    candidate[p / 8] = (candidate[p / 8].toInt() xor (1 shl (7 - p % 8))).toByte()
                }
                val d = distance(observed, encode(candidate))
                if (d < bestDistance) { bestDistance = d; best = candidate }
            }
        }
        require(bestDistance <= MAX_ERRORS) { "BCH_SUPER 校验失败（错误位 $bestDistance）" }
        return best to bestDistance
    }

    private fun distance(observed: BooleanArray, encoded: FloatArray): Int =
        observed.indices.count { observed[it] != (encoded[it] > .5f) }

    /** BCH(127,71,t=8), primitive polynomial x^7+x^3+1 (137). */
    private fun buildGenerator(): BooleanArray {
        val exp = IntArray(254)
        var x = 1
        for (i in 0 until 127) {
            exp[i] = x
            exp[i + 127] = x
            x = x shl 1
            if ((x and 0x80) != 0) x = x xor 137
        }
        var poly = intArrayOf(1)
        val used = BooleanArray(127)
        for (root in 1..16) {
            if (used[root]) continue
            val coset = mutableListOf<Int>()
            var value = root
            do { coset += value; used[value] = true; value = (value * 2) % 127 } while (value != root)
            var factor = intArrayOf(1)
            for (power in coset) {
                val next = IntArray(factor.size + 1)
                factor.forEachIndexed { i, c -> next[i] = next[i] xor gfMul(c, exp[power], exp); next[i + 1] = next[i + 1] xor c }
                factor = next
            }
            val next = IntArray(poly.size + factor.size - 1)
            poly.forEachIndexed { i, a -> factor.forEachIndexed { j, b -> next[i + j] = next[i + j] xor gfMul(a, b, exp) } }
            poly = next
        }
        require(poly.size == 57 && poly.all { it == 0 || it == 1 }) { "BCH 生成多项式错误" }
        return BooleanArray(poly.size) { poly[poly.lastIndex - it] == 1 }
    }

    private fun gfMul(a: Int, b: Int, exp: IntArray): Int {
        if (a == 0 || b == 0) return 0
        var la = 0; while (exp[la] != a) la++
        var lb = 0; while (exp[lb] != b) lb++
        return exp[(la + lb) % 127]
    }
}
