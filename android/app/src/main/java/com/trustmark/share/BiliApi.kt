package com.trustmark.share

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BiliAuthenticationRequired : IllegalStateException("B站登录已失效，请重新扫码")
class BiliApiRejected(val code: Int, val serverMessage: String, val stage: String) :
    IllegalStateException(buildString {
        append("B站拒绝请求（code=").append(code)
        if (serverMessage.isNotBlank()) append("，message=").append(serverMessage)
        append("，stage=").append(stage).append('）')
    })

class BiliCookies : CookieJar {
    private val values = mutableListOf<Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) values += cookie
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        values.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return values.filter { it.matches(url) }
    }
    @Synchronized fun snapshot(): List<Cookie> = values.filter { it.expiresAt > System.currentTimeMillis() }.toList()
    @Synchronized fun replace(cookies: List<Cookie>) { values.clear(); values += cookies }
    fun credential(name: String): String? = loadForRequest("https://api.bilibili.com/".toHttpUrl())
        .firstOrNull { it.name == name && (it.domain == "bilibili.com" || it.domain.endsWith(".bilibili.com")) }?.value
}

data class BiliEndpoints(
    val generate: String = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate",
    val poll: String = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll",
    val nav: String = "https://api.bilibili.com/x/web-interface/nav",
    val upload: String = "https://api.bilibili.com/x/dynamic/feed/draw/upload_bfs",
    val publish: String = "https://api.bilibili.com/x/dynamic/feed/create/opus",
)
data class BiliQr(val url: String, val key: String)
data class BiliAccount(val mid: Long, val level: Int)
data class BiliUploadedImage(val url: String, val width: Int, val height: Int, val sizeKb: Double)
enum class BiliPoll { WAITING, SCANNED, CONFIRMED, EXPIRED }

internal class ProgressRequestBody(
    private val delegate: RequestBody,
    private val progress: (Long, Long) -> Unit,
) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun writeTo(sink: BufferedSink) {
        var sent = 0L
        val forwarding = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount); sent += byteCount; progress(sent, contentLength())
            }
        }
        val buffered = forwarding.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}

/** Raw-byte transport. Retries and redirects are disabled for credential-bearing requests. */
class BiliApi(val cookies: BiliCookies = BiliCookies(), private val endpoints: BiliEndpoints = BiliEndpoints()) {
    private val client = OkHttpClient.Builder().cookieJar(cookies)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS).build()

    private suspend fun json(request: Request): JSONObject {
        val call = client.newCall(request)
        val raw = suspendCancellableCoroutine<String> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("无法连接 B站，请检查网络后重试"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (it.code == 401) throw BiliAuthenticationRequired()
                            require(it.isSuccessful) { "B站请求失败（HTTP ${it.code}）" }
                            val body = requireNotNull(it.body) { "B站返回空响应" }
                            val bytes = body.byteStream().use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer); if (count < 0) break
                                    require(output.size() + count <= 1024 * 1024) { "B站响应过大" }
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                            require(bytes.size <= 1024 * 1024) { "B站响应过大" }
                            bytes.toString(Charsets.UTF_8)
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
        val envelope = try { JSONObject(raw) } catch (_: Exception) { error("B站响应格式错误") }
        val code = envelope.optInt("code", Int.MIN_VALUE)
        if (code == -101) throw BiliAuthenticationRequired()
        if (code != 0) {
            val message = envelope.optString("message", envelope.optString("msg", ""))
                .replace(Regex("[\\r\\n\\t]+"), " ").take(160)
            throw BiliApiRejected(code, message, request.url.encodedPath)
        }
        return requireNotNull(envelope.optJSONObject("data")) { "B站响应缺少数据" }
    }
    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
        .header("Referer", "https://www.bilibili.com/").header("Origin", "https://www.bilibili.com")

    suspend fun generate(): BiliQr {
        val data = json(request(endpoints.generate).build())
        return BiliQr(data.getString("url"), data.getString("qrcode_key")).also {
            require(it.url.startsWith("https://") && it.key.isNotBlank()) { "B站二维码无效" }
        }
    }
    suspend fun poll(key: String): BiliPoll {
        val url = endpoints.poll.toHttpUrl().newBuilder().addQueryParameter("qrcode_key", key).build()
        return when (json(request(url.toString()).build()).optInt("code", -1)) {
            86101 -> BiliPoll.WAITING
            86090 -> BiliPoll.SCANNED
            86038 -> BiliPoll.EXPIRED
            0 -> BiliPoll.CONFIRMED
            else -> error("B站返回未知扫码状态")
        }
    }
    suspend fun account(): BiliAccount? {
        if (cookies.credential("SESSDATA").isNullOrBlank() || cookies.credential("bili_jct").isNullOrBlank()) return null
        val data = json(request(endpoints.nav).build())
        if (!data.optBoolean("isLogin", false)) return null
        val level = data.optJSONObject("level_info")?.optInt("current_level", -1) ?: -1
        val mid = data.optLong("mid", -1L)
        require(mid > 0 && level in 0..6) { "B站账号信息不完整" }
        return BiliAccount(mid, level)
    }
    suspend fun verify(): Boolean = account() != null
    suspend fun upload(file: File, progress: (Long, Long) -> Unit = { _, _ -> }): BiliUploadedImage {
        val csrf = cookies.credential("bili_jct") ?: throw BiliAuthenticationRequired()
        if (cookies.credential("SESSDATA").isNullOrBlank()) throw BiliAuthenticationRequired()
        require(file.length() in 1..20L * 1024 * 1024) { "B站承载图大小无效" }
        require(file.inputStream().use { input -> ByteArray(8).also { require(input.read(it) == it.size) }.contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)) }) { "B站承载图必须是 PNG" }
        val fileBody = ProgressRequestBody(file.asRequestBody("image/png".toMediaType()), progress)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file_up", file.name, fileBody).build()
        val url = endpoints.upload.toHttpUrl().newBuilder().addQueryParameter("csrf", csrf).build()
        val data = json(request(url.toString()).header("Origin", "https://member.bilibili.com")
            .header("Referer", "https://member.bilibili.com/platform/upload/text").post(body).build())
        return BiliUploadedImage(
            imageUrl(data.optString("image_url", data.optString("url"))),
            data.optInt("image_width", 0), data.optInt("image_height", 0), data.optDouble("img_size", file.length() / 1000.0),
        )
    }
    suspend fun publishPrivate(title: String, images: List<BiliUploadedImage>): String {
        require(title.matches(Regex("[A-Za-z]{5}"))) { "B站稿件标题格式错误" }
        require(images.isNotEmpty()) { "B站稿件没有图片" }
        val csrf = cookies.credential("bili_jct") ?: throw BiliAuthenticationRequired()
        if (cookies.credential("SESSDATA").isNullOrBlank()) throw BiliAuthenticationRequired()
        val nav = json(request(endpoints.nav).build())
        if (!nav.optBoolean("isLogin", false)) throw BiliAuthenticationRequired()
        val mid = nav.optLong("mid", -1L)
        require(mid > 0) { "B站账号信息不完整" }
        val wbi = requireNotNull(nav.optJSONObject("wbi_img")) { "B站未返回 WBI 签名信息" }
        val imgKey = wbiKey(wbi.optString("img_url"))
        val subKey = wbiKey(wbi.optString("sub_url"))
        require(imgKey.isNotBlank() && subKey.isNotBlank()) { "B站 WBI 签名信息无效" }
        val uploadId = "${mid}_${System.currentTimeMillis() / 1000}_${SecureRandom().nextInt(10_000)}"
        val paragraphs = JSONArray().apply { images.forEach { image -> put(JSONObject().apply {
            put("para_type", 2)
            put("pic", JSONObject().put("pics", JSONArray().put(JSONObject().apply {
                put("url", image.url); put("width", image.width); put("height", image.height); put("size", image.sizeKb)
            })).put("style", 0))
        }) } }
        val option = JSONObject()
            .put("aigc", 2).put("close_comment", 0).put("up_choose_comment", 0).put("private_pub", 1)
        check(option.getInt("private_pub") == 1) { "已阻止非私密投稿" }
        val payload = JSONObject().apply {
            put("raw_content", "")
            put("opus_req", JSONObject().apply {
                put("upload_id", uploadId)
                put("opus", JSONObject()
                    .put("opus_source", 2)
                    .put("title", title)
                    .put("content", JSONObject().put("paragraphs", paragraphs))
                    .put("article", JSONObject()
                        .put("category_id", 15).put("list_id", 0).put("originality", 0).put("reproduced", 1))
                    .put("pub_info", JSONObject().put("editor_version", EDITOR_VERSION)))
                put("scene", 12)
                put("meta", JSONObject().put("app_meta", JSONObject()
                    .put("from", "create.creative.h5").put("mobi_app", "web")))
                put("option", option)
            })
        }
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        return try {
            val signed = wbiSign(linkedMapOf(
                "gaia_source" to "main_web",
                "w_opus_req.upload_id" to uploadId,
            ), imgKey, subKey)
            val builder = endpoints.publish.toHttpUrl().newBuilder().addQueryParameter("csrf", csrf)
            signed.forEach { (name, value) -> builder.addQueryParameter(name, value) }
            val url = builder.build()
            val data = json(request(url.toString()).header("Origin", "https://member.bilibili.com")
                .header("Referer", "https://member.bilibili.com/platform/upload/text").post(body).build())
            val id = data.optString("dyn_id_str", data.optString("dyn_id"))
            require(id.all(Char::isDigit) && id.isNotBlank()) { "B站投稿响应缺少稿件 ID" }
            id
        } catch (auth: BiliAuthenticationRequired) { throw auth }
        catch (rejected: BiliApiRejected) { throw rejected }
        catch (failure: Exception) { throw BiliSubmissionUncertain(failure) }
    }
    companion object {
        private const val EDITOR_VERSION = "eva3-4.0.0"
        private val MIXIN_KEY_ENC_TAB = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52)

        private fun wbiKey(url: String): String = url.substringAfterLast('/').substringBefore('.')

        private fun wbiSign(params: Map<String, String>, imgKey: String, subKey: String): Map<String, String> {
            val source = imgKey + subKey
            val mixinKey = buildString {
                MIXIN_KEY_ENC_TAB.forEach { index -> if (index in source.indices) append(source[index]) }
            }.take(32)
            require(mixinKey.length == 32) { "B站 WBI 混淆密钥无效" }
            val wts = (System.currentTimeMillis() / 1000).toString()
            val unsigned = LinkedHashMap(params).apply { put("wts", wts) }
            val query = unsigned.toSortedMap().entries.joinToString("&") { (key, raw) ->
                val value = raw.replace(Regex("[!'()*]"), "")
                "${encodeURIComponent(key)}=${encodeURIComponent(value)}"
            }
            val digest = MessageDigest.getInstance("MD5").digest((query + mixinKey).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return LinkedHashMap(params).apply { put("w_rid", digest); put("wts", wts) }
        }

        private fun encodeURIComponent(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20").replace("%7E", "~")

        fun imageUrl(raw: String): String {
            val parsed = raw.toHttpUrl()
            require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.port in listOf(80,443) &&
                listOf("biliimg.com", "hdslb.com").any { parsed.host == it || parsed.host.endsWith(".$it") }) { "B站返回的图片链接域名不符合预期" }
            return parsed.newBuilder().scheme("https").port(443).build().toString()
        }
    }
}
