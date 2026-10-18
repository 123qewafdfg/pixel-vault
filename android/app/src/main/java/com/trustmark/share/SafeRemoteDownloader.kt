package com.trustmark.share

import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class SafeDownloadResult(
    val sha256: String,
    val carrierIndex: NativeWorkflow.CarrierIndex,
    val contentType: String?,
)

internal object RemoteAddressPolicy {
    const val MAX_URL_CHARS = 2048

    fun checkedUri(raw: String): URI {
        require(raw.length in 8..MAX_URL_CHARS) { "下载地址长度无效" }
        val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("下载地址格式无效", it) }
        require(uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null && uri.rawFragment == null) {
            "只允许不含账号、密码或片段标记的 HTTPS 下载地址"
        }
        return uri
    }

    fun resolvePublic(host: String): List<InetAddress> {
        val addresses = InetAddress.getAllByName(host).toList()
        require(addresses.isNotEmpty() && addresses.none(::isForbidden)) { "下载地址解析到内网、保留或危险 IP" }
        return addresses
    }

    internal fun isForbidden(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address.map { it.toInt() and 0xff }
        if (address is Inet4Address) {
            val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
            return a == 0 || a == 10 || a == 127 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 0 && c == 0) ||
                (a == 192 && b == 0 && c == 2) ||
                (a == 192 && b == 88 && c == 99) ||
                (a == 192 && b == 168) ||
                (a == 198 && b in 18..19) ||
                (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113) || a >= 224
        }
        if (address is Inet6Address) {
            val first = bytes[0]
            if (first and 0xfe == 0xfc) return true
            if (first == 0xfe && bytes[1] and 0xc0 == 0x80) return true
            if (bytes.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8)) return true
            val mapped = bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff
            if (mapped) return isForbidden(InetAddress.getByAddress(bytes.drop(12).map(Int::toByte).toByteArray()))
        }
        return false
    }
}

object SafeRemoteDownloader {
    private const val MAX_REDIRECTS = 5
    private const val USER_AGENT = "TrustMarkShare/3.3"
    private val redirectCodes = setOf(301, 302, 303, 307, 308)
    private val sensitiveHeaders = setOf("authorization", "cookie", "proxy-authorization", "x-api-key")

    suspend fun download(
        candidate: RemoteCandidate,
        target: File,
        maxBytes: Long,
        progress: (Long, Long, Long) -> Unit = { _, _, _ -> },
    ): SafeDownloadResult = withContext(Dispatchers.IO) {
        require(maxBytes > NativeWorkflow.INDEX_BYTES) { "远程载体大小上限无效" }
        if (candidate.size > 0) require(candidate.size <= maxBytes) { "远程图片超过允许大小" }
        val origin = RemoteAddressPolicy.checkedUri(candidate.url)
        var current = origin
        var contentType: String? = null
        try {
            for (redirectCount in 0..MAX_REDIRECTS) {
                coroutineContext.ensureActive()
                val addresses = RemoteAddressPolicy.resolvePublic(requireNotNull(current.host))
                val pinnedDns = object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (!hostname.equals(current.host, true)) throw UnknownHostException("拒绝未检查的 DNS 主机：$hostname")
                        return addresses
                    }
                }
                val client = OkHttpClient.Builder()
                    .dns(pinnedDns)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(current.toString())
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "image/png,image/jpeg,image/webp,application/octet-stream;q=0.5")
                val sameHost = current.host.equals(origin.host, true)
                candidate.headers.forEach { (name, value) ->
                    val lower = name.lowercase()
                    if (sameHost || lower !in sensitiveHeaders && lower in setOf("user-agent", "accept")) request.header(name, value)
                }
                val call = client.newCall(request.build())
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        if (response.code in redirectCodes) {
                            require(redirectCount < MAX_REDIRECTS) { "下载重定向次数过多" }
                            val location = response.header("Location") ?: error("下载重定向缺少地址")
                            current = RemoteAddressPolicy.checkedUri(current.resolve(location).toString())
                            continue
                        }
                        require(response.isSuccessful) { "下载失败（HTTP ${response.code}）" }
                        val body = requireNotNull(response.body) { "下载内容为空" }
                        contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                        require(contentType !in setOf("text/html", "application/json", "application/javascript", "text/javascript", "application/vnd.android.package-archive")) {
                            "远程响应不是图片载体"
                        }
                        val declared = body.contentLength()
                        require(declared <= maxBytes) { "远程图片超过允许大小" }
                        target.parentFile?.mkdirs()
                        var received = 0L
                        val digest = MessageDigest.getInstance("SHA-256")
                        val started = System.nanoTime(); var lastUpdate = started
                        progress(0L, declared, 0L)
                        body.byteStream().use { input -> target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer); if (count < 0) break
                                received += count
                                require(received <= maxBytes) { "远程图片超过允许大小" }
                                digest.update(buffer, 0, count); output.write(buffer, 0, count)
                                val now = System.nanoTime()
                                if (now - lastUpdate >= 250_000_000L) {
                                    progress(received, declared, received * 1_000_000_000L / (now - started).coerceAtLeast(1)); lastUpdate = now
                                }
                            }
                        } }
                        require(received > 0 && (declared < 0 || declared == received)) { "下载内容为空或长度不一致" }
                        validateImage(target, contentType)
                        val index = NativeWorkflow.inspectCarrier(target)
                        progress(received, declared, received * 1_000_000_000L / (System.nanoTime() - started).coerceAtLeast(1))
                        return@withContext SafeDownloadResult(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, index, contentType)
                    }
                } finally { cancellation.cancel() }
            }
            error("下载重定向次数过多")
        } catch (failure: Throwable) {
            target.delete()
            coroutineContext.ensureActive()
            throw failure
        }
    }

    private fun validateImage(file: File, contentType: String?) {
        val prefix = ByteArray(12)
        val count = file.inputStream().use { it.read(prefix) }
        require(count >= 4) { "远程图片内容过短" }
        val kind = when {
            count >= 8 && prefix.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
            prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte() -> "image/jpeg"
            count >= 12 && prefix.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && prefix.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> error("远程内容不是 PNG、JPEG 或 WebP 图片")
        }
        if (contentType?.startsWith("image/") == true) require(contentType == kind || contentType == "image/jpg") { "响应 MIME 类型与图片内容不一致" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "远程图片无法解码" }
    }
}
