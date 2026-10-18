package com.trustmark.share

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class NewWorkflowTest {
    @get:Rule val temporary = TemporaryFolder()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j6foAAAAASUVORK5CYII=")
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun resource(): GeneratedResource {
        val plain = temporary.newFile("payload.dat").apply { writeBytes(ByteArray(12_345) { (it*17).toByte() }) }
        val cover = temporary.newFile("cover.png").apply { writeBytes(png) }
        return NativeWorkflow.generate(LocalMedia(plain,"payload.dat","application/octet-stream"),
            List(7){LocalMedia(cover,"cover.png","image/png")}, temporary.newFolder("carriers"))
    }
    private fun record(g: GeneratedResource) = ResourceRecord("12345678",1,g.encrypted.fileKey,
        g.encrypted.encryptedMetadata,hex(g.encrypted.originalSha256),g.carriers.size,emptyList())

    @Test fun multipleCarriersRestoreWhenImagesAreReversed() {
        val g = resource()
        assertEquals(7,g.carriers.size)
        val restored = NativeWorkflow.restore(record(g),g.carriers.reversed().map { it.file },temporary.newFolder("restored"))
        assertArrayEquals(ByteArray(12_345) { (it*17).toByte() },restored.file.readBytes())
    }
    @Test fun duplicateShardIsRejected() {
        val g=resource()
        val files=g.carriers.map { it.file }.toMutableList().apply { this[1]=this[0] }
        val error=assertThrows(IllegalArgumentException::class.java) { NativeWorkflow.restore(record(g),files,temporary.newFolder("restored")) }
        assertTrue(error.message!!.contains("重复"))
    }
    @Test fun corruptedCipherIsRejected() {
        val g=resource()
        val file=g.carriers.first().file
        val bytes=file.readBytes();bytes[png.size]=(bytes[png.size].toInt() xor 1).toByte();file.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { NativeWorkflow.restore(record(g),g.carriers.map { it.file },temporary.newFolder("restored")) }
    }
    @Test fun noCarriersAreRejectedBeforeWriting() {
        val file=temporary.newFile("source").apply { writeBytes(byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { NativeWorkflow.generate(LocalMedia(file,"source","application/octet-stream"),emptyList(),temporary.root) }
    }
}
