package com.trustmark.share

/** The hidden files are the processed files, not their source sizes or carrier images. */
object BiliPayload {
    fun size(items: List<LocalMedia>): Long = items.sumOf { it.file.length() }
    fun eligible(bytes: Long): Boolean = bytes in 1..BiliPartition.MAX_PAYLOAD
    fun shouldSuggest(loggedIn: Boolean, items: List<LocalMedia>, registrationUncertain: Boolean): Boolean =
        loggedIn && !registrationUncertain && eligible(size(items))
}
