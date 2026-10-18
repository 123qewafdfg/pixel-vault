package com.trustmark.share

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

class BiliRegistrationUncertain : IllegalStateException("口令登记结果未确认，请勿重复提交；返回后重新选择也无法撤回先前请求。")
class BiliSubmissionUncertain(cause: Throwable? = null) : IllegalStateException("B站私密稿件提交结果未确认，请先到创作中心核实，切勿重复投稿。", cause)

object BiliVerification {
    suspend fun verify(
        resource: GeneratedResource,
        images: List<BiliUploadedImage>,
        folder: File,
        download: suspend (Int, String, File) -> Unit,
        progress: suspend (Int, Int) -> Unit,
        concurrency: Int = 3,
        validationLevel: RemoteValidationLevel = RemoteValidationLevel.FULL,
    ): List<VerifiedRemote> = coroutineScope {
        require(images.size == resource.carriers.size) { "B站图片数量与分片数量不一致" }
        folder.mkdirs()
        val completed = AtomicInteger(0)
        val limiter = Semaphore(concurrency.coerceIn(1, 4))
        resource.carriers.mapIndexed { index, carrier -> async(Dispatchers.IO) {
            limiter.withPermit {
                coroutineContext.ensureActive()
                val target = File(folder, "verify-$index.png")
                try {
                    download(index, images[index].url, target)
                    val indexes = runCatching { NativeWorkflow.inspectCarrier(carrier.file) to NativeWorkflow.inspectCarrier(target) }
                        .getOrElse { throw IllegalArgumentException("第 ${index + 1} 张承载图基础内容完整性校验失败", it) }
                    val (localIndex, remoteIndex) = indexes
                    require(remoteIndex.partIndex == localIndex.partIndex && remoteIndex.partCount == localIndex.partCount &&
                        remoteIndex.resourceUuid.contentEquals(localIndex.resourceUuid) && remoteIndex.cipherLength == localIndex.cipherLength &&
                        remoteIndex.cipherSha256 == localIndex.cipherSha256) { "第 ${index + 1} 张承载图基础内容完整性校验失败" }
                    val actualHash = RemoteRegistration.hash(target)
                    if (validationLevel == RemoteValidationLevel.FULL) require(target.length() == carrier.file.length() && actualHash == carrier.remoteSha256) {
                        "第 ${index + 1} 张承载图匿名完整校验失败，无法保证提取完整性"
                    }
                    progress(completed.incrementAndGet(), resource.carriers.size)
                    VerifiedRemote(images[index].url, verifiedHash = actualHash, validationLevel = validationLevel)
                } finally { target.delete() }
            }
        } }.awaitAll()
    }
}

object BiliWorkflow {
    suspend fun run(
        context: Context,
        items: List<LocalMedia>,
        verifyRemote: Boolean,
        persistent: Boolean = false,
        progress: suspend (String, Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        require(items.isNotEmpty()) { "请先选择隐藏文件" }
        require(BiliPayload.eligible(BiliPayload.size(items))) { "B站隐藏文件必须为 1 字节至 100 MiB，不含载体图" }
        val session = BiliSession.get(context)
        progress(if (persistent) "正在核验 B站账号与今日额度" else "正在核验 B站账号", .02f)
        check(session.verify()) { "请先登录 B站" }
        val account = session.account()
        if (persistent) BiliQuota.requireAvailable(context, account)
        val task = File(context.cacheDir, "bili-task-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            progress("正在打包隐藏内容", .06f)
            val secret = PayloadBundle.pack(items, File(task, "bundle"))
            require(secret.file.length() <= BiliPartition.MAX_PAYLOAD) { "打包后隐藏内容超过 100 MiB，请减少文件" }
            coroutineContext.ensureActive()
            val cover = File(task, "cover.png")
            context.assets.open("bili_carrier.png").use { input -> cover.outputStream().use { input.copyTo(it) } }
            progress("正在生成加密承载图", .12f)
            val resource = NativeWorkflow.generateBili(secret, LocalMedia(cover, "cover.png", "image/png"), File(task, "carriers"))
            val progressScope = CoroutineScope(coroutineContext)
            val uploaded = resource.carriers.mapIndexed { index, carrier ->
                coroutineContext.ensureActive()
                check(RemoteRegistration.hash(carrier.file) == carrier.remoteSha256) { "本地承载图已改变" }
                session.upload(carrier.file) { sent, total ->
                    val within = if (total > 0) sent.toFloat() / total else 0f
                    progressScope.launch {
                        progress("正在上传第 ${index + 1}/${resource.carriers.size} 张 · ${(within * 100).toInt()}%", .18f + .42f * (index + within) / resource.carriers.size)
                    }
                }
            }
            if (persistent) {
                val title = randomTitle()
                progress("正在创建仅自己可见的 B站专栏", .62f)
                BiliQuota.markSubmitting(context, account, title)
                try {
                    session.publishPrivate(title, uploaded)
                    BiliQuota.markSubmitted(context, account)
                } catch (rejected: BiliApiRejected) {
                    BiliQuota.clearRejected(context); throw rejected
                }
            }
            val validationLevel = if (verifyRemote) RemoteValidationLevel.FULL else RemoteValidationLevel.BASIC
            progress(if (verifyRemote) {
                if (persistent) "投稿成功，正在匿名完整校验" else "上传成功，正在匿名完整校验"
            } else "上传成功，正在执行基础安全与内容检查", .68f)
            val sources = BiliVerification.verify(resource, uploaded, File(task, "verify"), { index, url, file ->
                val candidate = RemoteRegistration.directCandidate(url).copy(headers = mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache"))
                RemoteRegistration.download(candidate, file, maxBytes = if (verifyRemote) resource.carriers[index].file.length() else resource.carriers[index].file.length() + 32L * 1024 * 1024)
            }, { done, total -> progress("正在校验第 $done/$total 张承载图", .68f + .22f * done / total) }, validationLevel = validationLevel)
            progress("正在登记口令，请勿重复提交", .92f)
            try { NativeWorkflow.createRecord(resource, sources) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw BiliRegistrationUncertain() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (uncertain: BiliSubmissionUncertain) { throw uncertain }
        catch (uncertain: BiliRegistrationUncertain) { throw uncertain }
        catch (failure: Exception) {
            throw IllegalStateException("B站自动上传失败，未登记口令。\n${failure.message ?: "请检查网络"}", failure)
        } finally { task.deleteRecursively() }
    }

    private fun randomTitle(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val random = SecureRandom()
        return buildString { repeat(5) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }
}
