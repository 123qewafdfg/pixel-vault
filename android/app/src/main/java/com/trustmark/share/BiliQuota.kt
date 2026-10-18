package com.trustmark.share

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

object BiliQuota {
    private const val PREFS = "bili_upload_quota"
    private val zone = ZoneId.of("Asia/Shanghai")

    fun limit(level: Int): Int = when (level) {
        in 1..3 -> 1
        in 4..6 -> 5
        else -> 0
    }

    fun remaining(context: Context, account: BiliAccount): Int {
        val used = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(dayKey(account.mid), 0)
        return (limit(account.level) - used).coerceAtLeast(0)
    }

    fun requireAvailable(context: Context, account: BiliAccount) {
        require(limit(account.level) > 0) { "B站 LV0 账号暂不支持此功能" }
        require(remaining(context, account) > 0) { "该 B站账号今天的私密稿件次数已用完（LV1–3 每天 1 次，LV4–6 每天 5 次）" }
        require(!hasPending(context, account.mid)) { "上次 B站投稿结果未确认，请先到创作中心核实，避免重复投稿" }
    }

    fun markSubmitting(context: Context, account: BiliAccount, title: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("pending_mid", account.mid).putString("pending_title", title)
            .putLong("pending_at", System.currentTimeMillis()).commit()
    }

    fun markSubmitted(context: Context, account: BiliAccount) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = dayKey(account.mid)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1)
            .remove("pending_mid").remove("pending_title").remove("pending_at").commit()
    }

    fun clearRejected(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("pending_mid").remove("pending_title").remove("pending_at").commit()
    }

    private fun hasPending(context: Context, mid: Long): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("pending_mid", -1L) == mid

    private fun dayKey(mid: Long) = "used_${mid}_${LocalDate.now(zone)}"
}
