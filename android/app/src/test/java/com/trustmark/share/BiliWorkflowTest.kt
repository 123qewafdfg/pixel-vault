package com.trustmark.share

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class BiliWorkflowTest {
    @get:Rule val temp = TemporaryFolder()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j6foAAAAASUVORK5CYII=")

    private fun resource(parts: Int = 1): GeneratedResource {
        val source = temp.newFile().apply { writeBytes(ByteArray(110_123) { it.toByte() }) }
        val cover = temp.newFile().apply { writeBytes(png) }
        val generated = NativeWorkflow.generateBili(LocalMedia(source, "payload.dat", "application/octet-stream"), LocalMedia(cover, "cover.png", "image/png"), temp.newFolder())
        if (parts == 1) return generated
        val carrier = generated.carriers.single()
        return generated.copy(carriers = List(parts) { index -> carrier.copy(partIndex = index) })
    }

    private fun images(count: Int) = List(count) { BiliUploadedImage("https://i0.hdslb.com/$it.png", 1, 1, 1.0) }

    @Test fun partitionSupportsOneHundredMib() {
        val mib = 1024L * 1024
        val plan = BiliPartition.plan(100 * mib, 384)
        assertEquals(52, plan.size)
        assertEquals(100 * mib, plan.sum())
        assertTrue(plan.size <= BiliPartition.MAX_PARTS)
        assertThrows(IllegalArgumentException::class.java) { BiliPartition.plan(100 * mib + 1, 384) }
    }

    @Test fun anonymousVerificationAcceptsOnlyIdenticalBytes() = runBlocking {
        val resource = resource()
        val sources = BiliVerification.verify(resource, images(1), temp.newFolder(), { _, _, target ->
            resource.carriers.single().file.copyTo(target, true)
        }, { _, _ -> })
        assertEquals(resource.carriers.single().remoteSha256, sources.single().verifiedHash)
    }

    @Test fun changedBytesAreRejectedAndTemporaryFileIsDeleted() = runBlocking {
        val resource = resource(); val folder = temp.newFolder()
        try {
            BiliVerification.verify(resource, images(1), folder, { _, _, target -> target.writeBytes(png) }, { _, _ -> })
            fail("changed bytes must fail")
        } catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("完整性")) }
        assertEquals(0, folder.listFiles()!!.size)
    }

    @Test fun verificationUsesBoundedConcurrencyButReturnsOriginalOrder() = runBlocking {
        val resource = resource(4)
        val active = AtomicInteger(); val maximum = AtomicInteger(); val completed = mutableListOf<Int>()
        val sources = BiliVerification.verify(resource, images(4), temp.newFolder(), { index, _, target ->
            val now = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, now) }
            delay((4 - index) * 5L)
            resource.carriers[index].file.copyTo(target, true)
            active.decrementAndGet()
        }, { done, _ -> synchronized(completed) { completed += done } }, concurrency = 2)
        assertTrue(maximum.get() in 1..2)
        assertEquals(images(4).map { it.url }, sources.map { it.url })
        assertEquals(setOf(1, 2, 3, 4), completed.toSet())
    }

    @Test fun cancellationIsPropagated() = runBlocking {
        try {
            BiliVerification.verify(resource(), images(1), temp.newFolder(), { _, _, _ -> throw CancellationException("cancel") }, { _, _ -> })
            fail()
        } catch (_: CancellationException) { }
    }

    @Test fun carrierStillRestoresExactly() {
        val resource = resource(); val carrier = resource.carriers.single()
        val record = ResourceRecord("ABCDE", 1, resource.encrypted.fileKey, resource.encrypted.encryptedMetadata,
            resource.encrypted.originalSha256.joinToString("") { "%02x".format(it.toInt() and 255) }, 1, emptyList())
        val restored = NativeWorkflow.restore(record, listOf(carrier.file), temp.newFolder())
        assertArrayEquals(ByteArray(110_123) { it.toByte() }, restored.file.readBytes())
    }
}
