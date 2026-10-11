package com.monostr.app.data.linkpreview

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

class AddressPolicyTest {
    private fun public(ip: String) = AddressPolicy.isPublic(InetAddress.getByName(ip))

    @Test
    fun `public v4 and v6 are allowed`() {
        assertTrue(public("1.1.1.1"))
        assertTrue(public("9.9.9.9"))
        assertTrue(public("2620:fe::fe"))
    }

    @Test
    fun `local, private and special ranges are refused`() {
        listOf(
            "127.0.0.1", "127.8.9.10", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1", "100.64.0.1", "100.127.255.254",
            "169.254.169.254", "0.0.0.0", "0.1.2.3", "224.0.0.1", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1",
            "::ffff:127.0.0.1", "::ffff:192.168.0.1",
        ).forEach { assertFalse(public(it), it) }
    }

    @Test
    fun `neighbours of the refused ranges stay allowed`() {
        listOf("172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "11.0.0.1").forEach { assertTrue(public(it), it) }
    }

    @Test
    fun `reserved v4 ranges are refused`() {
        listOf("240.0.0.1", "255.255.255.255", "198.18.0.1", "198.19.255.254", "192.0.0.8").forEach { assertFalse(public(it), it) }
        assertTrue(public("198.20.0.1"))
    }

    @Test
    fun `v6 addresses that carry a private v4 address are refused`() {
        // NAT64 (DNS64 on IPv6-only mobile networks), 6to4 and the old IPv4-compatible form
        listOf("64:ff9b::7f00:1", "64:ff9b::c0a8:101", "64:ff9b::a00:1", "2002:c0a8:101::1", "2002:7f00:1::1", "::c0a8:101", "::7f00:1")
            .forEach { assertFalse(public(it), it) }
        // the same wrappers around a public v4 address stay allowed
        listOf("64:ff9b::101:101", "2002:101:101::1").forEach { assertTrue(public(it), it) }
    }
}
