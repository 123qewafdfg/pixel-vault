package com.trustmark.share

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class QqBrowserRequired(val shareUrl: String) : IllegalStateException("请在 QQ 官方页面完成必要验证后提取原图")

object QqChannelClient {
    private const val MAX_HTML = 4 * 1024 * 1024
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun originalImages(raw: String): List<String> = withContext(Dispatchers.IO) {
        val input = Regex("https://pd\\.qq\\.com/[^\\s<>]+", RegexOption.IGNORE_CASE).find(raw)?.value
            ?.trimEnd('。', '，', ',', '；', ';', ')', ']', '}', '"', '\'')
            ?: error("请填写 QQ 频道公开帖子链接")
        var url = input
        var html: String? = null
        for (attempt in 0..5) {
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host.equals("pd.qq.com", true) && uri.userInfo == null && uri.port in listOf(-1, 443)) { "QQ 帖子地址不安全" }
            client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    url = uri.resolve(response.header("Location") ?: error("QQ 链接重定向失败")).toString()
                } else {
                    require(response.isSuccessful) { "QQ 帖子无法访问（HTTP ${response.code}）" }
                    val body = requireNotNull(response.body)
                    require(body.contentLength() <= MAX_HTML) { "QQ 帖子页面过大" }
                    html = body.byteStream().use { stream ->
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) { val count = stream.read(buffer); if (count < 0) break
                            require(bytes.size() + count <= MAX_HTML) { "QQ 帖子页面过大" }; bytes.write(buffer, 0, count) }
                        bytes.toString("UTF-8")
                    }
                }
            }
            if (html != null) break
        }
        val page = html ?: error("QQ 链接重定向次数过多")
        runCatching { parseHtml(page) }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() }
            ?: throw QqBrowserRequired(url)
    }

    internal fun parseState(json: String): List<String> = parseHtml("<script>window.__NUXT__=$json;</script>")
    internal fun imageHeaders(url:String):Map<String,String> {
        val host=runCatching{URI(url).host}.getOrNull().orEmpty()
        return if(listOf("photo.store.qq.com","qpic.cn","gtimg.cn").any{host==it||host.endsWith(".$it")})
            mapOf("User-Agent" to UA,"Referer" to "https://pd.qq.com/")else emptyMap()
    }

    internal fun parseHtml(html: String): List<String> {
        val feeds = mutableListOf<Map<String, Any?>>()
        fun collect(value: Any?, depth: Int = 0) {
            if (depth > 80) return
            when (value) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    if (value["images"] is List<*>) feeds += value as Map<String, Any?>
                    value.values.forEach { collect(it, depth + 1) }
                }
                is List<*> -> value.forEach { collect(it, depth + 1) }
            }
        }
        val dataTag = Regex("<script[^>]*id=[\"']__NUXT_DATA__[\"'][^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE).find(html)
        if (dataTag != null) {
            val pool = JSONArray(dataTag.groupValues[1])
            fun resolve(value: Any?, seen: Set<Int>, depth: Int, reference: Boolean = true): Any? {
                if (depth > 80) return null
                return when (value) {
                    is Number -> {
                        val index = value.toInt()
                        if(!reference)value else if (index !in 0 until pool.length() || index in seen) null else resolve(pool.get(index), seen + index, depth + 1,false)
                    }
                    is JSONObject -> value.keys().asSequence().associateWith { resolve(value.get(it), seen, depth + 1) }
                    is JSONArray -> if(value.length()==2&&value.optString(0) in listOf("Reactive","ShallowReactive","Ref","ShallowRef"))
                        resolve(value.get(1),seen,depth+1) else (0 until value.length()).map { resolve(value.get(it), seen, depth + 1) }
                    JSONObject.NULL -> null
                    else -> value
                }
            }
            for (index in 0 until pool.length()) {
                val item = pool.get(index)
                if (item is JSONObject && (item.has("feedDetail") || item.has("images"))) collect(resolve(item, setOf(index), 0))
            }
        }
        val assignment = html.indexOf("window.__NUXT__=").takeIf { it >= 0 }
            ?: html.indexOf("window.__NUXT__ =").takeIf { it >= 0 }
        if (assignment != null) {
            val start = html.indexOf('=', assignment) + 1
            val script = html.substring(start, html.indexOf("</script>", start).takeIf { it >= 0 } ?: html.length)
            val function = Regex("^\\s*\\(function\\(([^)]*)\\)\\s*\\{\\s*return\\s*").find(script)
            if (function != null) {
                val parser = LiteralParser(script, function.range.last + 1)
                val tree = parser.value()
                parser.space()
                if (parser.peek() == ';') parser.expect(';')
                parser.expect('}')
                parser.space()
                if (parser.peek() == '(') parser.expect('(') else { parser.expect(')'); parser.expect('(') }
                val args = parser.arguments()
                val parameters = function.groupValues[1].split(',').map { it.trim() }
                val bindings = parameters.zip(args).toMap()
                fun bind(value: Any?, depth: Int = 0): Any? = when {
                    depth > 80 -> null
                    value is Reference -> bindings[value.name]
                    value is Map<*, *> -> value.entries.associate { it.key to bind(it.value, depth + 1) }
                    value is List<*> -> value.map { bind(it, depth + 1) }
                    else -> value
                }
                collect(bind(tree))
            } else if (script.trimStart().startsWith('{')) collect(LiteralParser(script).value())
        }
        return feeds.flatMap { feed ->
            (feed["images"] as? List<*>)?.mapNotNull { it as? Map<*, *> }
                ?.sortedBy { (it["display_index"] as? Number)?.toInt() ?: 0 }
                ?.mapNotNull { it["picUrl"] as? String } ?: emptyList()
        }.filter { url ->
            runCatching {
                val uri = URI(url)
                uri.scheme == "https" && uri.userInfo == null && uri.host != null &&
                    listOf("photo.store.qq.com", "qpic.cn", "gtimg.cn").any { uri.host == it || uri.host.endsWith(".$it") }
            }.getOrDefault(false)
        }.distinct()
    }

    // This parser accepts serialized literal values only; it never evaluates JavaScript.
    private data class Reference(val name: String)
    private class LiteralParser(private val text: String, var position: Int = 0) {
        private var count = 0
        fun space() { while (position < text.length && text[position].isWhitespace()) position++ }
        fun peek(): Char { space(); return text.getOrElse(position) { '\u0000' } }
        fun expect(char: Char) { space(); require(text.getOrNull(position) == char) { "QQ 页面状态格式已变化" }; position++ }
        fun arguments(): List<Any?> {
            val values = mutableListOf<Any?>()
            while (peek() != ')') { values += value(); if (peek() != ',') break; position++ }
            expect(')'); return values
        }
        fun value(depth: Int = 0): Any? {
            require(depth <= 80 && ++count < 100000) { "QQ 页面状态过于复杂" }
            return when (peek()) {
                '{' -> {
                    position++; val result = linkedMapOf<String, Any?>()
                    while (peek() != '}') {
                        val key = if (peek() in listOf('"', '\'')) string() else identifier()
                        expect(':'); result[key] = value(depth + 1)
                        if (peek() != ',') break; position++
                    }
                    expect('}'); result
                }
                '[' -> {
                    position++; val result = mutableListOf<Any?>()
                    while (peek() != ']') { result += value(depth + 1); if (peek() != ',') break; position++ }
                    expect(']'); result
                }
                '"', '\'' -> string()
                '-', in '0'..'9' -> {
                    val start = position
                    while (position < text.length && text[position] in "0123456789.-+eE") position++
                    text.substring(start, position).toDouble()
                }
                else -> when (val name = identifier()) {
                    "null", "undefined" -> null; "true" -> true; "false" -> false
                    else -> Reference(name)
                }
            }
        }
        private fun identifier(): String {
            space(); val start = position
            while (position < text.length && (text[position].isLetterOrDigit() || text[position] in "_$")) position++
            require(position > start) { "QQ 页面状态格式已变化" }; return text.substring(start, position)
        }
        private fun string(): String {
            space(); val quote = text[position++]; val result = StringBuilder()
            while (position < text.length) {
                val c = text[position++]
                if (c == quote) return result.toString()
                if (c != '\\') { result.append(c); continue }
                require(position < text.length)
                when (val escaped = text[position++]) {
                    'u', 'x' -> { val length = if (escaped == 'u') 4 else 2
                        require(position + length <= text.length); result.append(text.substring(position, position + length).toInt(16).toChar()); position += length }
                    'n' -> result.append('\n'); 'r' -> result.append('\r'); 't' -> result.append('\t')
                    'b' -> result.append('\b'); 'f' -> result.append('\u000c')
                    else -> result.append(escaped)
                }
            }
            error("QQ 页面状态字符串不完整")
        }
    }
}
