package com.trustmark.share

import android.content.Context
import org.json.JSONArray

enum class PasscodeHistoryKind(val key: String) { UPLOAD("upload"), EXTRACT("extract") }

object PasscodeHistory {
    private const val PREFS = "passcode_history"
    private const val LIMIT = 100

    fun list(context: Context, kind: PasscodeHistoryKind): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(kind.key, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, kind: PasscodeHistoryKind, value: String) {
        val code = PasscodeCodec.normalizeServerId(value)
        val updated = (listOf(code) + list(context, kind).filterNot { it.equals(code, true) }).take(LIMIT)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(kind.key, JSONArray(updated).toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
