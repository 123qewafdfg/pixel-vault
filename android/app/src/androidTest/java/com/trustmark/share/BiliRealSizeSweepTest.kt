package com.trustmark.share

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToLong

@RunWith(AndroidJUnit4::class)
class BiliRealSizeSweepTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private data class PreparedSingleCarrier(
        val label: String,
        val payloadBytes: Long,
        val carrier: File,
        val carrierBytes: Long,
        val localSha256: String,
    )
    private data class SingleCarrierUpload(
        val prepared: PreparedSingleCarrier,
        val url: String,
        val uploadSeconds: Long,
    )
    private data class UploadAttempt(val prepared: PreparedSingleCarrier, val uploaded: SingleCarrierUpload?, val error: String)
    private data class DownloadAttempt(val uploaded: SingleCarrierUpload, val result: String, val remoteBytes: Long, val remoteSha256: String, val error: String)

    @Test
    fun realSingleCarrierRetentionSweep() = runBlocking {
        val progressReport = report("bili-single-carrier-upload-progress.csv")
        val finalReport = report("bili-single-carrier-retention.csv")
        progressReport.writeText("size_mib,payload_bytes,carrier_bytes,upload_result,upload_seconds,local_sha256,url,error\n", Charsets.UTF_8)
        finalReport.writeText("size_mib,payload_bytes,carrier_bytes,upload_result,download_result,remote_bytes,local_sha256,remote_sha256,url,error\n", Charsets.UTF_8)
        val root = File(context.cacheDir, "bili-single-carrier-sweep").apply { deleteRecursively(); mkdirs() }
        val cover = File(root, "cover.png")
        context.assets.open("bili_carrier.png").use { input -> cover.outputStream().use(input::copyTo) }
        val session = BiliSession.get(context)
        check(session.verify()) { "请先登录 B站" }

        try {
            val prepared = mutableListOf<PreparedSingleCarrier>()
            for (tenths in 10..60) {
                val label = label(tenths)
                val payloadBytes = bytes(tenths)
                val caseDir = File(root, "case-$tenths").apply { mkdirs() }
                val source = File(caseDir, "payload-${label}MiB.bin")
                writeDeterministic(source, payloadBytes, tenths)
                val resource = NativeWorkflow.generate(
                    LocalMedia(source, source.name, "application/octet-stream"),
                    listOf(LocalMedia(cover, "cover.png", "image/png")),
                    File(caseDir, "carrier"),
                    bili = false,
                )
                assertEquals("single-carrier test must never split", 1, resource.carriers.size)
                val carrier = resource.carriers.single()
                prepared += PreparedSingleCarrier(label, payloadBytes, carrier.file, carrier.file.length(), carrier.remoteSha256)
            }

            Log.i(TAG, "SINGLE GENERATED ALL ${prepared.size} CARRIERS; starting serial upload batch")
            val uploadGate = Semaphore(1)
            val reportLock = Mutex()
            val uploadAttempts = coroutineScope {
                prepared.map { item -> async(Dispatchers.IO) {
                    uploadGate.withPermit {
                        val started = System.nanoTime()
                        try {
                            Log.i(TAG, "SINGLE ${item.label} MiB UPLOAD carrierBytes=${item.carrierBytes}")
                            val url = BiliApi.imageUrl(session.upload(item.carrier))
                            val seconds = (System.nanoTime() - started) / 1_000_000_000L
                            val uploaded = SingleCarrierUpload(item, url, seconds)
                            reportLock.withLock {
                                progressReport.appendText("${item.label},${item.payloadBytes},${item.carrierBytes},PASS,$seconds,${item.localSha256},$url,\n", Charsets.UTF_8)
                            }
                            UploadAttempt(item, uploaded, "")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Throwable) {
                            val seconds = (System.nanoTime() - started) / 1_000_000_000L
                            val error = csvError(failure)
                            reportLock.withLock {
                                progressReport.appendText("${item.label},${item.payloadBytes},${item.carrierBytes},FAIL,$seconds,${item.localSha256},,$error\n", Charsets.UTF_8)
                            }
                            Log.e(TAG, "SINGLE ${item.label} MiB UPLOAD FAIL $error", failure)
                            UploadAttempt(item, null, error)
                        }
                    }
                } }.awaitAll()
            }
            val uploaded = uploadAttempts.mapNotNull { it.uploaded }
            Log.i(TAG, "SINGLE ALL UPLOADS FINISHED success=${uploaded.size} failed=${uploadAttempts.size-uploaded.size}; waiting before batch download")
            delay(20_000)

            Log.i(TAG, "SINGLE STARTING 3-WAY BATCH DOWNLOAD count=${uploaded.size}")
            val downloadGate = Semaphore(3)
            val downloadAttempts = coroutineScope {
                uploaded.map { item -> async(Dispatchers.IO) {
                    downloadGate.withPermit {
                        val data = item.prepared
                        val target = File(root, "download-${data.label}.png")
                        try {
                            val candidate = RemoteRegistration.directCandidate(item.url).copy(
                                headers = mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache"),
                            )
                            RemoteRegistration.download(candidate, target, maxBytes = 20L * 1024 * 1024)
                            val remoteBytes = target.length()
                            val remoteHash = RemoteRegistration.hash(target)
                            val pass = remoteBytes == data.carrierBytes && remoteHash == data.localSha256
                            val result = if (pass) "PASS" else "FAIL"
                            val error = if (pass) "" else "完整字节数或 SHA-256 不一致"
                            Log.i(TAG, "SINGLE ${data.label} MiB DOWNLOAD $result remoteBytes=$remoteBytes")
                            DownloadAttempt(item, result, remoteBytes, remoteHash, error)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Throwable) {
                            val error = csvError(failure)
                            Log.e(TAG, "SINGLE ${data.label} MiB DOWNLOAD FAIL $error", failure)
                            DownloadAttempt(item, "FAIL", target.length(), "", error)
                        } finally { target.delete() }
                    }
                } }.awaitAll()
            }
            val downloadsByLabel = downloadAttempts.associateBy { it.uploaded.prepared.label }
            uploadAttempts.sortedBy { it.prepared.payloadBytes }.forEach { attempt ->
                val data = attempt.prepared
                val done = downloadsByLabel[data.label]
                if (attempt.uploaded == null) {
                    finalReport.appendText("${data.label},${data.payloadBytes},${data.carrierBytes},FAIL,SKIPPED,0,${data.localSha256},,,${attempt.error}\n", Charsets.UTF_8)
                } else {
                    requireNotNull(done)
                    finalReport.appendText("${data.label},${data.payloadBytes},${data.carrierBytes},PASS,${done.result},${done.remoteBytes},${data.localSha256},${done.remoteSha256},${attempt.uploaded.url},${done.error}\n", Charsets.UTF_8)
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun localGenerateRestoreSweep() {
        val report = report("bili-local-size-sweep.csv")
        report.writeText("size_mib,bytes,result,parts,max_carrier_bytes,max_tail_bytes,error\n", Charsets.UTF_8)
        val cover = File(context.cacheDir, "bili-sweep-cover.png")
        context.assets.open("bili_carrier.png").use { input -> cover.outputStream().use(input::copyTo) }
        val failures = mutableListOf<String>()
        try {
            for (tenths in 10..60) {
                val label = label(tenths)
                val byteCount = bytes(tenths)
                val caseDir = caseDir("local", tenths)
                val source = File(caseDir, "payload-${label}MiB.bin")
                writeDeterministic(source, byteCount, tenths)
                var result = "FAIL"
                var parts = 0
                var maxCarrier = 0L
                var maxTail = 0L
                var error = ""
                try {
                    val resource = NativeWorkflow.generateBili(
                        LocalMedia(source, source.name, "application/octet-stream"),
                        LocalMedia(cover, "cover.png", "image/png"),
                        File(caseDir, "carriers"),
                    )
                    parts = resource.carriers.size
                    maxCarrier = resource.carriers.maxOf { it.file.length() }
                    maxTail = resource.carriers.maxOf { it.file.length() - cover.length() }
                    assertTrue("carrier exceeds 4,800,000 bytes", maxCarrier <= BiliPartition.MAX_CARRIER_BYTES)
                    val restored = NativeWorkflow.restore(localRecord(resource), resource.carriers.map { it.file }, File(caseDir, "restore"))
                    assertEquals(byteCount, restored.file.length())
                    assertEquals(sha256(source), sha256(restored.file))
                    result = "PASS"
                    Log.i(TAG, "LOCAL $label MiB PASS parts=$parts maxCarrier=$maxCarrier")
                } catch (failure: Throwable) {
                    error = csvError(failure)
                    failures += "$label MiB: $error"
                    Log.e(TAG, "LOCAL $label MiB FAIL $error", failure)
                } finally {
                    report.appendText("$label,$byteCount,$result,$parts,$maxCarrier,$maxTail,$error\n", Charsets.UTF_8)
                    caseDir.deleteRecursively()
                }
            }
        } finally {
            cover.delete()
        }
        assertTrue("local sweep failures: ${failures.joinToString("; ")}", failures.isEmpty())
    }

    @Test
    fun realBoundaryUploadAndRestore() = runBlocking {
        val report = report("bili-real-boundary-sweep.csv")
        report.writeText("size_mib,bytes,result,parts,passcode,elapsed_seconds,error\n", Charsets.UTF_8)
        val failures = mutableListOf<String>()
        for (tenths in intArrayOf(19, 39, 58, 60)) {
            val label = label(tenths)
            val byteCount = bytes(tenths)
            val caseDir = caseDir("real", tenths)
            val source = File(caseDir, "payload-${label}MiB.bin")
            writeDeterministic(source, byteCount, tenths)
            val expectedHash = sha256(source)
            val started = System.nanoTime()
            var passcode = ""
            var parts = 0
            var result = "FAIL"
            var error = ""
            try {
                passcode = BiliWorkflow.run(
                    context,
                    listOf(LocalMedia(source, source.name, "application/octet-stream")),
                ) { state, progress -> Log.i(TAG, "REAL $label MiB ${(progress * 100).toInt()}% $state") }
                val record = NativeWorkflow.getRecord(passcode)
                parts = record.partCount
                val restored = NativeWorkflow.restoreRemote(record, File(caseDir, "restore"), context)
                assertEquals(byteCount, restored.file.length())
                assertEquals(expectedHash, sha256(restored.file))
                result = "PASS"
                Log.i(TAG, "REAL $label MiB PASS parts=$parts passcode=$passcode")
            } catch (failure: Throwable) {
                error = csvError(failure)
                failures += "$label MiB: $error"
                Log.e(TAG, "REAL $label MiB FAIL passcode=$passcode error=$error", failure)
            } finally {
                val elapsed = (System.nanoTime() - started) / 1_000_000_000L
                report.appendText("$label,$byteCount,$result,$parts,$passcode,$elapsed,$error\n", Charsets.UTF_8)
                caseDir.deleteRecursively()
            }
        }
        assertTrue("real boundary failures: ${failures.joinToString("; ")}", failures.isEmpty())
    }

    private fun localRecord(resource: GeneratedResource) = ResourceRecord(
        "LOCAL", 1, resource.encrypted.fileKey, resource.encrypted.encryptedMetadata,
        resource.encrypted.originalSha256.toHex(), resource.carriers.size,
        resource.carriers.map { ResourcePart(it.partIndex, "", it.remoteSha256, it.cipherSha256, it.cipherSize) },
    )

    private fun report(name: String) = File(requireNotNull(context.getExternalFilesDir(null)), name)
    private fun caseDir(kind: String, tenths: Int) = File(context.cacheDir, "bili-$kind-sweep-$tenths").apply {
        deleteRecursively()
        mkdirs()
    }
    private fun label(tenths: Int) = "${tenths / 10}.${tenths % 10}"
    private fun bytes(tenths: Int) = (tenths * 1024.0 * 1024.0 / 10.0).roundToLong()
    private fun csvError(failure: Throwable) = (failure.message ?: failure.javaClass.simpleName)
        .replace('\n', ' ').replace('\r', ' ').replace(',', '，')

    private fun writeDeterministic(file: File, byteCount: Long, seed: Int) {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        file.outputStream().buffered().use { output ->
            while (written < byteCount) {
                val count = minOf(buffer.size.toLong(), byteCount - written).toInt()
                for (index in 0 until count) buffer[index] = ((written + index + seed * 31L) and 0xff).toByte()
                output.write(buffer, 0, count)
                written += count
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private companion object { const val TAG = "BiliSizeSweep" }
}
