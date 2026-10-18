package com.trustmark.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.ConnectException
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLException

data class EncryptedInfo(
    val resourceUuid: ByteArray, val fileKey: ByteArray, val originalSha256: ByteArray,
    val metadataIv: ByteArray, val encryptedMetadata: ByteArray,
)
data class GeneratedCarrier(
    val file: File, val remoteSha256: String, val cipherSha256: String,
    val cipherSize: Long, val partIndex: Int,
)
data class ResourcePart(
    val partIndex: Int, val downloadUrl: String, val remoteSha256: String,
    val cipherSha256: String, val cipherSize: Long, val cloud: CloudLocator? = null,
)
data class ResourceRecord(
    val id: String, val protocolVersion: Int, val fileKey: ByteArray,
    val encryptedMetadata: ByteArray, val originalSha256: String,
    val partCount: Int, val parts: List<ResourcePart>,
)
data class GeneratedResource(val encrypted: EncryptedInfo, val carriers: List<GeneratedCarrier>)
data class RemoteDownloadProgress(
    val partIndex: Int,
    val partCount: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val completedParts: Int = partIndex,
    val fraction: Float = if (totalBytes > 0) ((partIndex + downloadedBytes.toFloat() / totalBytes) / partCount.coerceAtLeast(1)).coerceIn(0f, 1f) else 0f,
)
data class RestoredFile(
    val file: File,
    val name: String,
    val mime: String,
    val children: List<LocalMedia> = emptyList(),
)
data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val downloadUrl: String,
    val force: Boolean,
    val patch: UpdatePatch?,
    val fullApk: UpdateDownload?,
)
data class UpdateDownload(val size: Long, val sha256: String, val urls: List<String>)
data class UpdatePatch(
    val fromVersionCode: Int,
    val urls: List<String>,
    val size: Long,
    val sha256: String,
    val baseApkSha256: String,
    val resultApkSha256: String,
)

object NativeWorkflow {
    const val INDEX_BYTES = 112L
    private const val MIN_FREE_SPACE = 16L * 1024 * 1024
    private const val INDEX_SIZE = INDEX_BYTES.toInt()
    private const val STREAM_BUFFER = 64 * 1024
    private val random = SecureRandom()

    fun estimateCarrierSizes(secretSize: Long, covers: List<LocalMedia>, allocation: CarrierAllocation = CarrierAllocation()): List<Long> =
        if (covers.isEmpty()) emptyList() else runCatching {
            CarrierPlanner.plan(secretSize, covers.map { it.file.length() }, allocation).estimatedCarrierSizes
        }.getOrDefault(emptyList())

    fun checkUpdate(): AppUpdate {
        val json = request(JSONObject().put("action", "tm-version").put("channel", BuildConfig.UPDATE_CHANNEL))
        if (BuildConfig.EXTRACT_ONLY && json.optString("channel") != BuildConfig.UPDATE_CHANNEL) {
            return AppUpdate(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, "", BuildConfig.API_BASE_URL, false, null, null)
        }
        val url = json.getString("downloadUrl")
        require(URL(url).protocol.equals("https", true)) { "更新地址无效" }
        fun urlsOf(item: JSONObject, fallback: String? = null): List<String> {
            val values = mutableListOf<String>()
            item.optJSONArray("mirrors")?.let { mirrors ->
                for (index in 0 until mirrors.length()) {
                    val value = mirrors.get(index)
                    values += if (value is JSONObject) value.getString("url") else value.toString()
                }
            }
            if (values.isEmpty() && fallback != null) values += fallback
            return values.distinct().onEach { require(URL(it).protocol.equals("https", true)) { "更新镜像地址无效" } }
                .also { require(it.isNotEmpty() && it.size <= 5) { "更新镜像数量无效" } }
        }
        val fullApk = json.optJSONObject("fullApk")?.let { item ->
            UpdateDownload(item.getLong("size"), item.getString("sha256").lowercase(), urlsOf(item, url)).also {
                require(it.size in 1..300L * 1024 * 1024 && it.sha256.matches(Regex("[0-9a-f]{64}"))) { "完整更新信息无效" }
            }
        }
        val patch = json.optJSONArray("patches")?.let { patches ->
            (0 until patches.length()).asSequence()
                .map { patches.getJSONObject(it) }
                .firstOrNull { it.optInt("fromVersionCode", -1) == BuildConfig.VERSION_CODE }
                ?.let { item ->
                    val patchUrl = item.optString("url").takeIf { it.isNotBlank() }
                    UpdatePatch(
                        fromVersionCode = item.getInt("fromVersionCode"),
                        urls = urlsOf(item, patchUrl),
                        size = item.getLong("size"),
                        sha256 = item.getString("sha256").lowercase(),
                        baseApkSha256 = item.getString("baseApkSha256").lowercase(),
                        resultApkSha256 = item.getString("resultApkSha256").lowercase(),
                    ).also {
                        require(it.size in 1..150L * 1024 * 1024) { "差分更新大小无效" }
                        require(listOf(it.sha256, it.baseApkSha256, it.resultApkSha256).all { hash -> hash.matches(Regex("[0-9a-f]{64}")) }) { "差分更新哈希无效" }
                    }
                }
        }
        return AppUpdate(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            notes = json.optString("notes"),
            downloadUrl = url,
            force = json.optBoolean("force", false),
            patch = patch,
            fullApk = fullApk,
        )
    }

    fun generateBili(secret: LocalMedia, cover: LocalMedia, workDir: File): GeneratedResource =
        generate(secret, listOf(cover), workDir, bili = true)

    fun generate(secret: LocalMedia, covers: List<LocalMedia>, workDir: File, bili: Boolean = false,
        allocation: CarrierAllocation = CarrierAllocation()): GeneratedResource {
        if (bili) require(secret.file.length() in 1..BiliPartition.MAX_PAYLOAD) { "B站隐藏内容不能超过 100 MiB" }
        require(covers.isNotEmpty()) { "请至少选择一张承载图" }
        val coverEnds = covers.map { cover ->
            val bytes = cover.file.readBytes()
            require(cleanImageLength(bytes) == bytes.size) { "${cover.displayName} 不是无尾部数据的有效 PNG/JPEG/WebP" }
            bytes
        }
        val uuid = ByteBuffer.allocate(16).putLong(UUID.randomUUID().mostSignificantBits).putLong(UUID.randomUUID().leastSignificantBits).array()
        val key = ByteArray(32).also(random::nextBytes)
        val originalHash = sha256(secret.file)
        val metadata = JSONObject().apply {
            put("version", 1); put("fileName", secret.displayName); put("mimeType", secret.mime)
            put("originalSize", secret.file.length()); put("originalSha256", hex(originalHash))
        }.toString().toByteArray(Charsets.UTF_8)
        val metadataIv = ByteArray(12).also(random::nextBytes)
        val encryptedMetadata = aes(true, metadata, key, metadataIv, "TMSHARE1:META".toByteArray() + uuid)
        val biliSizes = if (bili) BiliPartition.plan(secret.file.length(), encryptedMetadata.size, coverEnds.single().size.toLong()) else null
        val plan = if (biliSizes == null) CarrierPlanner.plan(secret.file.length(), coverEnds.map { it.size.toLong() }, allocation, encryptedMetadata.size) else null
        val plainSizes = biliSizes ?: requireNotNull(plan).plainSizes
        val partCount = plainSizes.size
        val estimatedOutput = plainSizes.fold(0L) { total, size -> Math.addExact(total, size) }
        val coverOutput = plainSizes.indices.fold(0L) { total, index ->
            Math.addExact(total, coverEnds[if (covers.size == 1) 0 else index].size.toLong())
        }
        val protocolOutput = Math.addExact(Math.addExact(estimatedOutput, coverOutput),
            Math.addExact(encryptedMetadata.size.toLong(), Math.multiplyExact(partCount.toLong(), INDEX_BYTES + CarrierPlanner.GCM_TAG_BYTES)))
        requireUsableSpace(workDir, Math.addExact(protocolOutput, MIN_FREE_SPACE))
        val resourceDir = File(workDir, hex(uuid)).apply { mkdirs() }
        val carriers = mutableListOf<GeneratedCarrier>()
        BufferedInputStream(secret.file.inputStream(), STREAM_BUFFER).use { input ->
            for (index in 0 until partCount) {
                val plainSize = plainSizes[index]
                val iv = ByteArray(12).also(random::nextBytes)
                val png = coverEnds[if (covers.size == 1) 0 else index]
                val meta = if (index == 0) encryptedMetadata else ByteArray(0)
                val ext = covers[if (covers.size == 1) 0 else index].displayName.substringAfterLast('.', "png").lowercase()
                val output = File(resourceDir, "${hex(uuid)}-part-${(index + 1).toString().padStart(2, '0')}.$ext")
                val cipherHash = MessageDigest.getInstance("SHA-256")
                val cipherLength = plainSize + CarrierPlanner.GCM_TAG_BYTES
                BufferedOutputStream(output.outputStream(), STREAM_BUFFER).use { out ->
                    out.write(png)
                    val cipher = cipher(true, key, iv, dataAad(uuid, index, partCount))
                    val buffer = ByteArray(STREAM_BUFFER)
                    var remaining = plainSize
                    while (remaining > 0) {
                        val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                        val count = input.read(buffer, 0, wanted)
                        require(count > 0) { "源文件提前结束" }
                        cipher.update(buffer, 0, count)?.takeIf { it.isNotEmpty() }?.let { encrypted ->
                            cipherHash.update(encrypted); out.write(encrypted)
                        }
                        remaining -= count
                    }
                    cipher.doFinal().takeIf { it.isNotEmpty() }?.let { encrypted -> cipherHash.update(encrypted); out.write(encrypted) }
                    out.write(meta)
                    out.write(tailIndex(uuid, index, partCount, cipherLength, meta.size, png.size.toLong(), iv, metadataIv, cipherHash.digest()))
                }
                if (bili) {
                    require(output.length() - png.size <= BiliPartition.MAX_APPEND) { "B站分片尾追加超过 6 MiB" }
                    require(output.length() <= BiliPartition.MAX_CARRIER_BYTES) { "B站承载图完整大小超过安全上限" }
                }
                val indexInfo = inspectCarrier(output)
                carriers += GeneratedCarrier(output, hex(sha256(output)), indexInfo.cipherSha256, cipherLength, index)
            }
            require(input.read() < 0) { "源文件长度在处理期间发生变化" }
        }
        return GeneratedResource(EncryptedInfo(uuid, key, originalHash, metadataIv, encryptedMetadata), carriers)
    }

    fun createRecord(resource: GeneratedResource, sources: List<VerifiedRemote>): String {
        require(sources.size == resource.carriers.size) { "承载图片匹配不完整" }
        require(sources.indices.all { index ->
            val source = sources[index]
            source.verifiedHash.matches(Regex("[0-9a-f]{64}")) &&
                (source.validationLevel == RemoteValidationLevel.BASIC || source.verifiedHash == resource.carriers[index].remoteSha256)
        }) { "图片校验结果已失效，请重新登记" }
        fun partJson(index: Int, carrier: GeneratedCarrier): JSONObject = JSONObject().apply {
            put("partIndex", carrier.partIndex)
            val source = sources[index]
            if(source.cloud==null)put("downloadUrl",source.url)else put("cloudSource",source.cloud.json(source.verifiedHash))
            put("remoteSha256", source.verifiedHash)
            put("cipherSha256", carrier.cipherSha256); put("cipherSize", carrier.cipherSize)
        }
        val parts = resource.carriers.mapIndexed(::partJson)
        fun header(action: String) = JSONObject().apply {
            put("action", action)
            put("idFormat", "letters5")
            put("protocolVersion", if(sources.any{it.cloud!=null})3 else 1); put("fileKey", base64Url(resource.encrypted.fileKey))
            put("encryptedMetadata", base64Url(resource.encrypted.encryptedMetadata)); put("originalSha256", hex(resource.encrypted.originalSha256))
        }
        val singleBody = header("tm-create").put("parts", JSONArray(parts))
        val allBilibili = sources.all { source -> source.cloud == null && runCatching {
            val host = URI(source.url).host.lowercase()
            listOf("biliimg.com", "hdslb.com").any { host == it || host.endsWith(".$it") }
        }.getOrDefault(false) }
        val useSingleRequest = (parts.size <= 25 || allBilibili && parts.size <= BiliPartition.MAX_PARTS) &&
            singleBody.toString().toByteArray(Charsets.UTF_8).size <= 220 * 1024
        if (useSingleRequest) return PasscodeCodec.normalizeServerId(request(singleBody).optString("id"))

        val draft = request(header("tm-create-draft").put("expectedPartCount", parts.size))
        val id = PasscodeCodec.normalizeServerId(draft.getString("id"))
        val token = draft.getString("draftToken")
        val batchSize = draft.optInt("batchSize", 50).coerceIn(1, 50)
        parts.chunked(batchSize).forEachIndexed { batchIndex, batch ->
            request(JSONObject().put("action", "tm-create-parts").put("id", id).put("draftToken", token)
                .put("startIndex", batchIndex * batchSize).put("parts", JSONArray(batch)))
        }
        return PasscodeCodec.normalizeServerId(request(JSONObject().put("action", "tm-create-finalize").put("id", id).put("draftToken", token)).getString("id"))
    }

    fun createInlineText(secret: LocalMedia): String {
        require(secret.isText && secret.file.isFile) { "仅支持手打文字" }
        val plain = secret.file.readBytes()
        require(plain.isNotEmpty()) { "文字内容不能为空" }
        require(plain.size <= 1000) { "文字内容不能超过 1000 字节" }
        val key = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val ciphertext = aes(true, plain, key, iv, "TMSHARE2:INLINE".toByteArray(Charsets.US_ASCII))
        val body = JSONObject().apply {
            put("action", "tm-create")
            put("idFormat", "letters5")
            put("protocolVersion", 2)
            put("fileKey", base64Url(key))
            put("encryptedMetadata", base64Url(iv + ciphertext))
            put("originalSha256", hex(MessageDigest.getInstance("SHA-256").digest(plain)))
            put("parts", JSONArray())
        }
        return PasscodeCodec.normalizeServerId(request(body).optString("id"))
    }

    fun getRecord(id: String): ResourceRecord {
        val json = request(JSONObject().put("action","tm-get").put("id",id))
        val partObjects = mutableListOf<JSONObject>()
        val inlineParts = json.getJSONArray("parts")
        for (index in 0 until inlineParts.length()) partObjects += inlineParts.getJSONObject(index)
        if (json.optBoolean("partsPaged", false)) {
            var start = 0
            while (true) {
                val page = request(JSONObject().put("action", "tm-get-parts").put("id", id).put("startIndex", start))
                val pageParts = page.getJSONArray("parts")
                for (index in 0 until pageParts.length()) partObjects += pageParts.getJSONObject(index)
                if (page.isNull("nextStart")) break
                val next = page.getInt("nextStart")
                require(next > start) { "服务器返回的分片分页无效" }
                start = next
            }
        }
        val parts = partObjects.map { item -> item.run {
            ResourcePart(getInt("partIndex"), optString("downloadUrl"), getString("remoteSha256"), getString("cipherSha256"), getLong("cipherSize"), optJSONObject("cloudSource")?.let(CloudLocator::fromJson))
        } }
        val partCount = json.getInt("partCount")
        require(parts.size == partCount && parts.map { it.partIndex } == (0 until partCount).toList()) { "服务器返回的分片不完整或顺序无效" }
        return ResourceRecord(json.getString("id"), json.getInt("protocolVersion"), decodeBase64Url(json.getString("fileKey")), decodeBase64Url(json.getString("encryptedMetadata")), json.getString("originalSha256"), partCount, parts)
    }

    suspend fun restoreRemote(record: ResourceRecord, workDir: File, context: Context? = null, onProgress: (RemoteDownloadProgress) -> Unit = {}): RestoredFile {
        if (record.protocolVersion == 2) return restoreInlineText(record, workDir)
        require(record.protocolVersion == 1 || record.protocolVersion == 3) { "不支持的资源协议版本" }
        workDir.mkdirs()
        val sortedParts = record.parts.sortedBy { it.partIndex }
        val concurrency = downloadConcurrency(context).coerceAtMost(sortedParts.size.coerceAtLeast(1))
        val limiter = Semaphore(concurrency)
        val downloaded = LongArray(sortedParts.size)
        val totals = LongArray(sortedParts.size) { -1L }
        val speeds = LongArray(sortedParts.size)
        val finished = BooleanArray(sortedParts.size)
        val progressLock = Any()
        fun publishProgress(position: Int, done: Long, total: Long, speed: Long, complete: Boolean = false) {
            val snapshot = synchronized(progressLock) {
                downloaded[position] = maxOf(downloaded[position], done)
                if (total > 0) totals[position] = total
                speeds[position] = if (complete) 0L else speed.coerceAtLeast(0L)
                if (complete) finished[position] = true
                val allTotalsKnown = totals.all { it > 0 }
                val knownTotal = if (allTotalsKnown) totals.sum() else -1L
                val fraction = totals.indices.sumOf { index ->
                    when {
                        finished[index] -> 1.0
                        totals[index] > 0 -> (downloaded[index].toDouble() / totals[index]).coerceIn(0.0, 1.0)
                        else -> 0.0
                    }
                }.div(sortedParts.size.coerceAtLeast(1)).toFloat()
                RemoteDownloadProgress(
                    partIndex = position,
                    partCount = sortedParts.size,
                    downloadedBytes = downloaded.sum(),
                    totalBytes = knownTotal,
                    bytesPerSecond = speeds.sum(),
                    completedParts = finished.count { it },
                    fraction = fraction,
                )
            }
            onProgress(snapshot)
        }
        val files = coroutineScope { sortedParts.mapIndexed { position, part -> async(Dispatchers.IO) {
            limiter.withPermit {
                val target = File(workDir, "remote-${part.partIndex}.png")
                val candidate = part.cloud?.let { CloudDriveService.get(requireNotNull(context){"网盘提取需要应用环境"}).resolve(it) }
                    ?: RemoteRegistration.directCandidate(part.downloadUrl)
                try {
                    var verified = false
                    for (attempt in 1..3) {
                        target.delete()
                        RemoteRegistration.download(candidate, target) { done,total,speed ->
                            publishProgress(position,done,total,speed)
                        }
                        if (RemoteRegistration.hash(target) == part.remoteSha256) {
                            verified = true
                            break
                        }
                    }
                    require(verified){"第 ${part.partIndex+1} 个分片连续 3 次下载不完整或哈希不一致，请检查网络后重试；若持续失败，远程图片可能已变化"}
                    publishProgress(position,target.length(),target.length(),0L,true)
                    target
                } finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable){candidate.cleanup()} }
            }
        } }.awaitAll() }
        return try {
            restore(record, files, workDir)
        } catch (error: Throwable) {
            val reason = when (error) {
                is AEADBadTagException -> "解密校验失败，密钥或分片内容不匹配"
                else -> error.message?.takeIf { it.isNotBlank() } ?: "未知恢复错误"
            }
            throw IllegalStateException("文件恢复失败：$reason", error)
        } finally { files.forEach { it.delete() } }
    }

    private fun downloadConcurrency(context: Context?): Int {
        if (context == null) return 4
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return 4
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return 4
        return if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 8 else 4
    }

    private fun networkFailureMessage(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "连接或读取超时"
        is UnknownHostException -> "无法解析图片服务器地址"
        is ConnectException -> "无法连接图片服务器"
        is SSLException -> "HTTPS 安全连接失败"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "网络异常"
    }

    private fun restoreInlineText(record: ResourceRecord, workDir: File): RestoredFile {
        require(record.parts.isEmpty()) { "内联文字记录不应包含远程分片" }
        require(record.encryptedMetadata.size > 12 + 16) { "内联文字密文无效" }
        val iv = record.encryptedMetadata.copyOfRange(0, 12)
        val ciphertext = record.encryptedMetadata.copyOfRange(12, record.encryptedMetadata.size)
        val plain = aes(false, ciphertext, record.fileKey, iv, "TMSHARE2:INLINE".toByteArray(Charsets.US_ASCII))
        require(plain.size <= 1000 && hex(MessageDigest.getInstance("SHA-256").digest(plain)) == record.originalSha256) { "文字内容完整性校验失败" }
        workDir.mkdirs()
        val output = File(workDir, "restored-${System.currentTimeMillis()}-文字.txt").apply { writeBytes(plain) }
        return RestoredFile(output, "提取文字.txt", "text/plain")
    }

    fun restore(record: ResourceRecord, files: List<File>, workDir: File): RestoredFile {
        require(files.size == record.partCount) { "承载图数量应为 ${record.partCount}" }
        val parsed = files.map { it to inspectCarrier(it) }.sortedBy { it.second.partIndex }
        require(parsed.map { it.second.partIndex } == (0 until record.partCount).toList()) { "分片编号不完整或重复" }
        val uuid = parsed.first().second.resourceUuid
        require(parsed.all { it.second.resourceUuid.contentEquals(uuid) && it.second.partCount == record.partCount }) { "承载图不属于同一资源" }
        val first = parsed.first { it.second.partIndex == 0 }.second
        val metadataCipher = if (first.metadata.isNotEmpty()) first.metadata else record.encryptedMetadata
        val metaText = aes(false, metadataCipher, record.fileKey, first.metadataIv, "TMSHARE1:META".toByteArray() + uuid).toString(Charsets.UTF_8)
        val meta = JSONObject(metaText)
        val originalSize = meta.getLong("originalSize")
        require(originalSize >= 0) { "恢复文件大小无效" }
        val safeName = File(meta.getString("fileName")).name.ifBlank { "restored.bin" }
        workDir.mkdirs()
        requireUsableSpace(workDir, Math.addExact(originalSize, MIN_FREE_SPACE))
        val output = File(workDir, "restored-${System.currentTimeMillis()}-$safeName")
        val partial = File(workDir, ".${output.name}.${System.nanoTime()}.partial")
        val plainDigest = MessageDigest.getInstance("SHA-256")
        val expectedParts = record.parts.associateBy { it.partIndex }
        try {
            BufferedOutputStream(partial.outputStream(), STREAM_BUFFER).use { out -> parsed.forEach { (file, part) ->
                val expected = expectedParts[part.partIndex]
                require(expected == null || expected.cipherSize == part.cipherLength) { "分片 ${part.partIndex + 1} 密文长度不一致" }
                decryptPart(file, part, record.fileKey, out, plainDigest)
                require(expected == null || expected.cipherSha256 == part.cipherSha256) { "分片 ${part.partIndex + 1} 密文哈希与记录不一致" }
            } }
            val restoredHash = hex(plainDigest.digest())
            require(partial.length() == originalSize && restoredHash == meta.getString("originalSha256") && restoredHash == record.originalSha256) { "恢复文件的大小或 SHA-256 不一致" }
            require(partial.renameTo(output)) { "无法原子完成恢复文件" }
        } catch (failure: Throwable) {
            partial.delete()
            output.delete()
            throw failure
        }
        val mime = meta.optString("mimeType", MediaFiles.mimeOf(safeName))
        val children = if (safeName == "hidden-files.zip" && mime == "application/zip") {
            PayloadBundle.unpack(output, File(workDir, "unpacked"))
        } else emptyList()
        return RestoredFile(output, safeName, mime, children)
    }

    private fun requireUsableSpace(target: File, requiredBytes: Long) {
        val existing = generateSequence(target) { it.parentFile }.firstOrNull { it.exists() } ?: return
        val usable = existing.usableSpace
        require(usable <= 0L || usable >= requiredBytes) {
            "可用存储空间不足：需要至少 ${requiredBytes / 1024 / 1024} MiB，当前约 ${usable / 1024 / 1024} MiB"
        }
    }

    data class CarrierIndex(
        val resourceUuid: ByteArray,
        val partIndex: Int,
        val partCount: Int,
        val dataIv: ByteArray,
        val metadataIv: ByteArray,
        val cipherSha256: String,
        val imageLength: Long,
        val cipherLength: Long,
        val metadata: ByteArray,
    )

    fun inspectCarrier(file: File): CarrierIndex {
        require(file.length() >= INDEX_SIZE) { "承载图过短" }
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(file.length() - INDEX_SIZE); val idx = ByteArray(INDEX_SIZE); raf.readFully(idx)
            require(idx.copyOfRange(0, 8).contentEquals("TMSHARE1".toByteArray(Charsets.US_ASCII))) { "没有找到承载图尾索引" }
            val crc = CRC32().apply { update(idx, 0, 108) }.value
            val b = ByteBuffer.wrap(idx).order(ByteOrder.BIG_ENDIAN)
            require((b.getInt(108).toLong() and 0xffffffffL) == crc) { "尾索引 CRC32 错误" }
            val version = b.getShort(8).toInt() and 0xffff; require(version == 1) { "不支持的协议版本" }
            val uuid = idx.copyOfRange(12, 28); val pi = b.getShort(28).toInt() and 0xffff; val pc = b.getShort(30).toInt() and 0xffff
            val cipherLen = b.getLong(32); val metaLen = b.getInt(40); val pngLen = b.getLong(44)
            require(pc in 1..CarrierPlanner.MAX_PROTOCOL_PARTS && pi in 0 until pc) { "分片编号或总数无效" }
            require(cipherLen >= CarrierPlanner.GCM_TAG_BYTES && metaLen in 0..4096 && pngLen > 0) { "承载图索引长度无效" }
            require(pngLen + cipherLen + metaLen + INDEX_SIZE == file.length()) { "承载图长度与索引不一致" }
            val meta = ByteArray(metaLen)
            if (metaLen > 0) { raf.seek(pngLen + cipherLen); raf.readFully(meta) }
            return CarrierIndex(uuid, pi, pc, idx.copyOfRange(52, 64), idx.copyOfRange(64, 76), hex(idx.copyOfRange(76, 108)), pngLen, cipherLen, meta)
        }
    }

    private fun decryptPart(file: File, part: CarrierIndex, key: ByteArray, out: BufferedOutputStream, plainDigest: MessageDigest) {
        val decryptor = cipher(false, key, part.dataIv, dataAad(part.resourceUuid, part.partIndex, part.partCount))
        val cipherDigest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(part.imageLength)
            val buffer = ByteArray(STREAM_BUFFER)
            var remaining = part.cipherLength
            while (remaining > 0) {
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                raf.readFully(buffer, 0, count)
                cipherDigest.update(buffer, 0, count)
                decryptor.update(buffer, 0, count)?.takeIf { it.isNotEmpty() }?.let { plain -> plainDigest.update(plain); out.write(plain) }
                remaining -= count
            }
            try {
                decryptor.doFinal().takeIf { it.isNotEmpty() }?.let { plain -> plainDigest.update(plain); out.write(plain) }
            } catch (failure: AEADBadTagException) {
                throw IllegalArgumentException("分片 ${part.partIndex + 1} GCM 标签校验失败", failure)
            }
        }
        require(hex(cipherDigest.digest()) == part.cipherSha256) { "分片 ${part.partIndex + 1} 密文校验失败" }
    }

    private fun tailIndex(uuid: ByteArray, pi: Int, pc: Int, cipherLen: Long, metaLen: Int, pngLen: Long, dataIv: ByteArray, metaIv: ByteArray, cipherHash: ByteArray): ByteArray {
        val out = ByteArray(INDEX_SIZE); val b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        b.put("TMSHARE1".toByteArray()); b.putShort(1); b.putShort(0); b.put(uuid); b.putShort(pi.toShort()); b.putShort(pc.toShort())
        b.putLong(cipherLen); b.putInt(metaLen); b.putLong(pngLen); b.put(dataIv); b.put(metaIv); b.put(cipherHash)
        val crc = CRC32().apply { update(out, 0, 108) }.value; b.putInt(108, crc.toInt()); return out
    }
    private fun dataAad(uuid: ByteArray, index: Int, count: Int) = ByteBuffer.allocate(16 + 16 + 4).apply { put("TMSHARE1:DATA".toByteArray()); put(uuid); putShort(index.toShort()); putShort(count.toShort()) }.array()
    private fun cipher(encrypt: Boolean, key: ByteArray, iv: ByteArray, aad: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        updateAAD(aad)
    }
    private fun aes(encrypt: Boolean, input: ByteArray, key: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray = cipher(encrypt, key, iv, aad).doFinal(input)
    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").run { file.inputStream().buffered().use { input -> val buf = ByteArray(1024 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; update(buf, 0, n) } }; digest() }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun base64Url(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decodeBase64Url(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun findPngEnd(bytes: ByteArray): Int { require(bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))); var at=8; while(at+12<=bytes.size){ val n=ByteBuffer.wrap(bytes,at,4).order(ByteOrder.BIG_ENDIAN).int; val type=bytes.copyOfRange(at+4,at+8).toString(Charsets.US_ASCII); val end=at+12+n; require(end<=bytes.size); if(type=="IEND"){require(n==0);return end};at=end }; error("PNG 缺少 IEND") }
    private fun cleanImageLength(bytes: ByteArray): Int {
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) return findPngEnd(bytes)
        if (bytes.size >= 12 && bytes.copyOfRange(0,4).toString(Charsets.US_ASCII)=="RIFF" && bytes.copyOfRange(8,12).toString(Charsets.US_ASCII)=="WEBP") {
            val size = ByteBuffer.wrap(bytes,4,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            return (size + 8L).toInt()
        }
        if (bytes.size >= 4 && bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte()) {
            for (i in bytes.size-2 downTo 2) if (bytes[i]==0xff.toByte() && bytes[i+1]==0xd9.toByte()) return i+2
        }
        error("不支持的图片格式")
    }
    private fun download(raw: String, max: Long, onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> }): ByteArray {
        val c=(URL(raw).openConnection() as HttpURLConnection).apply { connectTimeout=15_000;readTimeout=120_000;instanceFollowRedirects=true;setRequestProperty("User-Agent","TrustMarkShare/2.0") }
        try {
            require(c.responseCode in 200..299){"下载失败（HTTP ${c.responseCode}）"}
            require(c.url.protocol.equals("https",true)){"拒绝非 HTTPS 重定向"}
            val expected=c.contentLengthLong.coerceAtLeast(-1L)
            val started=System.nanoTime()
            var lastUpdate=started
            onProgress(0L,expected,0L)
            return c.inputStream.use{input->
                val out=java.io.ByteArrayOutputStream();val buf=ByteArray(64*1024);var downloaded=0L
                while(true){
                    val n=input.read(buf);if(n<0)break
                    downloaded+=n;require(downloaded<=max){"远程文件过大"};out.write(buf,0,n)
                    val now=System.nanoTime()
                    if(now-lastUpdate>=250_000_000L){
                        val speed=(downloaded*1_000_000_000L/(now-started).coerceAtLeast(1L)).coerceAtLeast(0L)
                        onProgress(downloaded,expected,speed);lastUpdate=now
                    }
                }
                val elapsed=(System.nanoTime()-started).coerceAtLeast(1L)
                onProgress(downloaded,expected,(downloaded*1_000_000_000L/elapsed).coerceAtLeast(0L))
                out.toByteArray()
            }
        }finally{c.disconnect()}
    }
    private fun request(body: JSONObject): JSONObject {
        val c=(URL(BuildConfig.API_BASE_URL).openConnection() as HttpURLConnection).apply{
            requestMethod="POST";connectTimeout=10_000;readTimeout=60_000;doOutput=true
            setRequestProperty("Accept","application/json");setRequestProperty("Content-Type","application/json; charset=utf-8")
            outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}
        }
        try {
            val code=c.responseCode
            val raw=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}.orEmpty()
            val envelope=runCatching{JSONObject(raw)}.getOrElse{error("服务器响应格式错误")}
            if(code !in 200..299||!envelope.optBoolean("ok",false)){
                val message=envelope.optString("message").takeIf{it.isNotBlank()}?:when(code){404->"对应内容不存在或已经失效";429->"请求过于频繁，请稍后再试";else->"服务器请求失败（HTTP $code）"}
                error(message)
            }
            return envelope.optJSONObject("data")?:error("服务器响应缺少数据")
        }finally{c.disconnect()}
    }
}
