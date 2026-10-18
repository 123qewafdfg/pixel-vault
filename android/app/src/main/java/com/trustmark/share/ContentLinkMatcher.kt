package com.trustmark.share

internal class ContentLinkMatcher<T>(private val expectedHashes: List<String>, previous: Map<Int, Pair<String,T>> = emptyMap()) {
    enum class Outcome { MATCHED, DUPLICATE, UNRELATED }
    private val stored = previous.filter { (index, value) -> index in expectedHashes.indices && value.first == expectedHashes[index] }
        .mapValues { it.value.second }.toMutableMap()
    init { require(expectedHashes.isNotEmpty() && expectedHashes.distinct().size == expectedHashes.size) { "承载图片指纹重复，请重新生成" } }
    val matches: Map<Int,T> get() = stored.toMap()
    val complete: Boolean get() = stored.size == expectedHashes.size
    fun accept(hash: String, source: T): Outcome {
        val index = expectedHashes.indexOf(hash)
        if(index<0)return Outcome.UNRELATED
        if(stored.containsKey(index))return Outcome.DUPLICATE
        stored[index]=source
        return Outcome.MATCHED
    }
}
