package com.trustmark.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class RemoteSecurityTest {
    private fun forbidden(value: String) = RemoteAddressPolicy.isForbidden(InetAddress.getByName(value))

    @Test fun rejectsIpv4LocalAndReservedRanges() {
        listOf("0.0.0.0", "10.1.2.3", "100.64.1.2", "127.0.0.1", "169.254.3.4", "172.16.0.1",
            "192.168.1.1", "192.0.2.2", "198.18.0.1", "198.51.100.2", "203.0.113.2", "224.0.0.1")
            .forEach { assertTrue(it, forbidden(it)) }
        assertFalse(forbidden("8.8.8.8"))
    }

    @Test fun rejectsIpv6LocalAndReservedRanges() {
        listOf("::", "::1", "fe80::1", "fc00::1", "fd12::1", "ff02::1", "2001:db8::1")
            .forEach { assertTrue(it, forbidden(it)) }
        assertFalse(forbidden("2606:4700:4700::1111"))
    }

    @Test fun urlGateRejectsCredentialsFragmentsAndHttp() {
        assertTrue(runCatching { RemoteAddressPolicy.checkedUri("http://example.com/a") }.isFailure)
        assertTrue(runCatching { RemoteAddressPolicy.checkedUri("https://u:p@example.com/a") }.isFailure)
        assertTrue(runCatching { RemoteAddressPolicy.checkedUri("https://example.com/a#fragment") }.isFailure)
        assertTrue(runCatching { RemoteAddressPolicy.checkedUri("https://example.com/a") }.isSuccess)
    }
}
