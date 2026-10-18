package com.trustmark.share

import java.util.Locale

object PasscodeCodec {
    private const val MARKER = 0x50
    private const val LENGTH = 5
    private val currentPattern = Regex("^[A-Z]{5}$")
    private val legacyPattern = Regex("^[0-9a-f]{8}$")

    fun normalize(value: String): String = value.trim().uppercase(Locale.ROOT)

    fun isCurrent(value: String): Boolean = currentPattern.matches(normalize(value))

    fun normalizeServerId(value: String): String {
        val trimmed = value.trim()
        return when {
            currentPattern.matches(trimmed.uppercase(Locale.ROOT)) -> trimmed.uppercase(Locale.ROOT)
            legacyPattern.matches(trimmed.lowercase(Locale.ROOT)) -> trimmed.lowercase(Locale.ROOT)
            else -> error("服务器返回了无效口令码")
        }
    }

    fun encodeWatermark(value: String): ByteArray {
        val serverId = normalizeServerId(value)
        if (legacyPattern.matches(serverId)) {
            return ByteArray(4) { index -> serverId.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }
        val code = normalize(serverId)
        var number = 0
        code.forEach { number = number * 26 + (it - 'A') }
        return byteArrayOf(
            MARKER.toByte(),
            (number ushr 16).toByte(),
            (number ushr 8).toByte(),
            number.toByte(),
        )
    }

    fun decodeCandidates(bytes: ByteArray): List<String> {
        require(bytes.size == 4) { "水印口令长度不正确" }
        val legacy = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if ((bytes[0].toInt() and 0xff) != MARKER) return listOf(legacy)
        var number = ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
        if (number >= 11_881_376) return listOf(legacy)
        val chars = CharArray(LENGTH)
        for (index in chars.indices.reversed()) {
            chars[index] = ('A'.code + number % 26).toChar()
            number /= 26
        }
        return listOf(String(chars), legacy)
    }
}
