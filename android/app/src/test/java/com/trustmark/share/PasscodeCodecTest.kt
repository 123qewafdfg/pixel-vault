package com.trustmark.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasscodeCodecTest {
    @Test fun normalizesLowercase() {
        assertEquals("ABCDE", PasscodeCodec.normalize(" abcde "))
        assertTrue(PasscodeCodec.isCurrent("abcde"))
        assertFalse(PasscodeCodec.isCurrent("AB12E"))
    }

    @Test fun roundTripsCurrentPasscodes() {
        listOf("AAAAA", "ABCDE", "ZZZZZ").forEach { code ->
            assertEquals(code, PasscodeCodec.decodeCandidates(PasscodeCodec.encodeWatermark(code)).first())
        }
    }

    @Test fun preservesLegacyWatermarkCandidate() {
        val bytes = byteArrayOf(0x12, 0x34, 0xab.toByte(), 0xcd.toByte())
        assertEquals(listOf("1234abcd"), PasscodeCodec.decodeCandidates(bytes))
        assertEquals(bytes.toList(), PasscodeCodec.encodeWatermark("1234ABCD").toList())
    }

    @Test fun markerCollisionFallsBackToLegacyCandidate() {
        val candidates = PasscodeCodec.decodeCandidates(byteArrayOf(0x50, 0, 0, 1))
        assertEquals("AAAAB", candidates.first())
        assertEquals("50000001", candidates.last())
    }
}
