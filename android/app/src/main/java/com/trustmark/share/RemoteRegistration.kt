package com.trustmark.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

enum class RemoteValidationLevel { BASIC, FULL }
data class VerifiedRemote(val url: String, val cloud: CloudLocator? = null, val verifiedHash: String = "",
    val validationLevel: RemoteValidationLevel = RemoteValidationLevel.FULL) {
    fun json() = JSONObject().apply {
        if (cloud == null) put("downloadUrl", url) else put("cloudSource", cloud.json(verifiedHash))
    }
}
data class RemoteMatchResult(val matches: Map<Int, VerifiedRemote>, val duplicates: Int, val ignored: Int, val failures: List<String>,
    val loginRequired: CloudLoginRequired? = null, val passwordRequest: String? = null) {
    fun message(total: Int): String = buildString {
        append("已匹配 ${matches.size}/$total 张")
        if (duplicates > 0) append("，已忽略 $duplicates 个重复项")
        if (ignored > 0) append("，已忽略 $ignored 张无关图片")
        if (matches.size < total) append("，还缺 ${total - matches.size} 张，请补充链接后登记")
        if (failures.isNotEmpty()) append("\n" + failures.joinToString("\n"))
    }
}

object RemoteRegistration {
    fun canonical(raw: String): String {
        require(raw.trim().length <= RemoteAddressPolicy.MAX_URL_CHARS) { "链接过长" }
        val parsed = URI(raw.trim())
        require(parsed.scheme.equals("https", true) && parsed.host != null && parsed.userInfo == null) { "请填写不含账号信息的 HTTPS 链接" }
        val host=parsed.host.lowercase().let{if(it.contains(':')&&!it.startsWith('['))"[$it]"else it}
        val port=if(parsed.port in listOf(-1,443))""else ":${parsed.port}"
        return "https://$host$port${parsed.rawPath.ifBlank{"/"}}"+
            (parsed.rawQuery?.let{"?$it"}?:"")+(parsed.rawFragment?.let{"#$it"}?:"")
    }
    fun duplicates(values: List<String>): Boolean {
        val seen = mutableSetOf<String>()
        return values.filter { it.isNotBlank() }.any { !seen.add(runCatching { canonical(it) }.getOrDefault(it.trim())) }
    }
    fun hash(file: File): String = MessageDigest.getInstance("SHA-256").run {
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; update(buffer, 0, count) }
        }
        digest().joinToString("") { "%02x".format(it) }
    }
    fun directCandidate(url:String)=RemoteCandidate(url,headers=QqChannelClient.imageHeaders(url))
    suspend fun download(candidate: RemoteCandidate, target: File, maxBytes: Long = 700L * 1024 * 1024,
        progress: (Long, Long, Long) -> Unit = { _, _, _ -> }): SafeDownloadResult =
        SafeRemoteDownloader.download(candidate, target, maxBytes, progress)

    suspend fun match(context: Context, carriers: List<GeneratedCarrier>, inputs: List<String>,
        previous: Map<Int, VerifiedRemote> = emptyMap(), passwords: Map<String, String> = emptyMap(),
        validationLevel: RemoteValidationLevel = RemoteValidationLevel.FULL,
        progress: (RemoteDownloadProgress) -> Unit = {}): RemoteMatchResult {
        require(carriers.isNotEmpty()) { "请先生成承载图片" }
        carriers.forEach { require(it.file.isFile && hash(it.file) == it.remoteSha256) { "本地承载图片已改变，请返回第 2 步重新生成" } }
        val expectedIndexes = carriers.map { NativeWorkflow.inspectCarrier(it.file) }
        val stateLock = Any()
        val matches = previous.filter { (index, value) -> index in carriers.indices && value.validationLevel == validationLevel &&
            (validationLevel == RemoteValidationLevel.BASIC || value.verifiedHash == carriers[index].remoteSha256) }.toMutableMap()
        val duplicates = AtomicInteger(0); val ignored = AtomicInteger(0)
        val failures = Collections.synchronizedList(mutableListOf<String>())
        var loginRequired: CloudLoginRequired? = null
        var passwordRequest: String? = null
        val seenInputs = mutableSetOf<String>()
        val seenCandidates = Collections.synchronizedSet(mutableSetOf<String>())
        val seenContent = Collections.synchronizedSet(matches.values.map { it.verifiedHash }.toMutableSet())
        val drive = CloudDriveService.get(context)
        data class CandidateJob(val candidate: RemoteCandidate, val inputNumber: Int, val cloud: Boolean)
        val directJobs = mutableListOf<CandidateJob>()
        val cloudJobs = mutableListOf<CandidateJob>()
        val maxLocalSize = carriers.maxOf { it.file.length() }
        val maxBytes = maxOf(maxLocalSize + 32L * 1024 * 1024, maxLocalSize * 2).coerceAtMost(700L * 1024 * 1024)

        inputs.filter { it.isNotBlank() }.forEachIndexed { index, raw ->
            coroutineContext.ensureActive()
            val trimmed = raw.trim()
            try {
                val inputUrl = Regex("https://[^\\s]+", RegexOption.IGNORE_CASE).find(trimmed)?.value
                    ?.trimEnd('。', '，', ',', '；', ';', ')', ']', '}', '"', '\'') ?: trimmed
                val key = canonical(inputUrl)
                if (!seenInputs.add(key)) { duplicates.incrementAndGet(); return@forEachIndexed }
                if (CloudDriveService.platform(trimmed) != null) {
                    val listing = drive.list(trimmed, passwords[trimmed])
                    val exactSizes = carriers.map { it.file.length() }.toSet()
                    val candidates = listing.files.filter { file -> file.fsize <= 0 ||
                        validationLevel == RemoteValidationLevel.FULL && file.fsize in exactSizes ||
                        validationLevel == RemoteValidationLevel.BASIC && file.fsize <= maxBytes }
                    candidates.forEach { file -> cloudJobs += CandidateJob(drive.candidate(listing, file), index + 1, true) }
                } else if (GitHubCandidates.recognizes(inputUrl)) {
                    GitHubCandidates.list(inputUrl, if (validationLevel == RemoteValidationLevel.FULL) carriers.map { it.file.length() }.toSet() else emptySet())
                        .forEach { directJobs += CandidateJob(it, index + 1, false) }
                } else {
                    require(URI(inputUrl).fragment == null) { "图片直链不能带片段标记" }
                    directJobs += CandidateJob(directCandidate(inputUrl), index + 1, false)
                }
            } catch (required: CloudLoginRequired) { loginRequired=required;failures+=required.message.orEmpty() }
            catch (required: SharePasswordRequired) { passwordRequest=trimmed;failures+=required.message.orEmpty() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { failures += "第 ${index + 1} 个链接：${failure.message ?: "解析失败"}" }
        }

        val allJobs = cloudJobs + directJobs
        val downloaded = LongArray(allJobs.size); val totals = LongArray(allJobs.size) { -1L }
        val speeds = LongArray(allJobs.size); val finished = BooleanArray(allJobs.size)
        fun publish(position: Int, done: Long, total: Long, speed: Long, complete: Boolean = false) {
            val snapshot = synchronized(stateLock) {
                downloaded[position] = maxOf(downloaded[position], done)
                if (total > 0) totals[position] = total
                speeds[position] = if (complete) 0 else speed.coerceAtLeast(0)
                if (complete) finished[position] = true
                val fraction = if (allJobs.isEmpty()) 1f else allJobs.indices.sumOf { at ->
                    when { finished[at] -> 1.0; totals[at] > 0 -> (downloaded[at].toDouble()/totals[at]).coerceIn(0.0,1.0); else -> 0.0 }
                }.div(allJobs.size).toFloat()
                RemoteDownloadProgress(position, allJobs.size, downloaded.sum(), if (totals.all { it > 0 }) totals.sum() else -1,
                    speeds.sum(), finished.count { it }, fraction)
            }
            progress(snapshot)
        }

        suspend fun process(job: CandidateJob, position: Int) {
            val candidate = job.candidate
            val identity = candidate.locator?.let { "${it.platform}:${it.shareUrl}:${it.fileId}" } ?: canonical(candidate.url)
            if (!seenCandidates.add(identity)) { duplicates.incrementAndGet(); withContext(kotlinx.coroutines.NonCancellable){candidate.cleanup()}; return }
            val temporary = File.createTempFile("remote-match-", ".bin", context.cacheDir)
            try {
                var result: SafeDownloadResult? = null; var lastFailure: Throwable? = null
                for (attempt in 1..3) {
                    temporary.delete()
                    try {
                        result = download(candidate, temporary, maxBytes) { done, size, speed -> publish(position, done, size, speed) }
                        break
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Throwable) { lastFailure = failure }
                }
                val verified = result ?: throw IllegalStateException(lastFailure?.message ?: "连续3次下载失败", lastFailure)
                val remoteIndex = verified.carrierIndex
                val part = remoteIndex.partIndex
                val expected = expectedIndexes.getOrNull(part)
                val structurallyMatched = expected != null && remoteIndex.partCount == expected.partCount &&
                    remoteIndex.resourceUuid.contentEquals(expected.resourceUuid) && remoteIndex.cipherLength == expected.cipherLength &&
                    remoteIndex.cipherSha256 == expected.cipherSha256
                val fullyMatched = structurallyMatched && verified.sha256 == carriers[part].remoteSha256
                if (!structurallyMatched || validationLevel == RemoteValidationLevel.FULL && !fullyMatched) {
                    ignored.incrementAndGet()
                } else synchronized(stateLock) {
                    if (!seenContent.add(verified.sha256) || matches.containsKey(part)) duplicates.incrementAndGet()
                    else matches[part] = VerifiedRemote(candidate.url, candidate.locator, verified.sha256, validationLevel)
                }
                publish(position, temporary.length(), temporary.length(), 0L, true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Throwable) { failures += "第 ${job.inputNumber} 个链接：${failure.message ?: "下载或校验失败"}" }
            finally { temporary.delete(); withContext(kotlinx.coroutines.NonCancellable){candidate.cleanup()} }
        }
        cloudJobs.forEachIndexed { index, job -> process(job, index) }
        val offset = cloudJobs.size
        coroutineScope {
            val limiter = Semaphore(downloadConcurrency(context))
            directJobs.mapIndexed { index, job -> async(Dispatchers.IO) { limiter.withPermit { process(job, offset + index) } } }.awaitAll()
        }
        return RemoteMatchResult(matches.toMap(), duplicates.get(), ignored.get(), failures.toList(),loginRequired,passwordRequest)
    }

    private fun downloadConcurrency(context: Context): Int {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return 4
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return 4
        return if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 8 else 4
    }
}
