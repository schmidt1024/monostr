package com.monostr.tips

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelayHostsTest {
    @Test
    fun `ipv4 is null for anything but four plain decimal octets, it never throws`() {
        listOf("a.b.c.1", "1a.0.0.1", "1.2.3.x", "+1.0.0.1", "-1.0.0.1", "1.2.3.٣", "1.2.3", "", "...", "256.0.0.1", "01.0.0.1")
            .forEach { assertNull(RelayHosts.ipv4(it), it) }
        assertEquals("192.168.1.1", RelayHosts.ipv4("192.168.1.1")?.hostAddress)
    }

    @Test
    fun `public hosts pass`() {
        listOf("wss://relay.damus.io", "wss://nos.lol/", "wss://relay.monostr.com:443/path", "wss://8.8.8.8", "wss://[2001:4860::8888]", "wss://100.63.0.1", "wss://172.32.0.1")
            .forEach { assertTrue(RelayHosts.isPublicWss(it), it) }
    }

    @Test
    fun `lan, loopback and local names are refused`() {
        listOf(
            "wss://localhost", "wss://LOCALHOST:7777", "wss://foo.localhost", "wss://printer.local", "wss://nas.lan", "wss://box.home.arpa",
            "wss://svc.internal", "wss://router", "wss://127.0.0.1", "wss://10.1.2.3", "wss://172.16.0.1", "wss://172.31.255.255",
            "wss://192.168.1.5:4848", "wss://169.254.1.1", "wss://100.64.0.1", "wss://0.0.0.0", "wss://[::1]", "wss://[fd00::1]",
            "wss://[fe80::1]", "wss://[::ffff:192.168.1.1]", "wss://[::]",
        ).forEach { assertFalse(RelayHosts.isPublicWss(it), it) }
    }

    @Test
    fun `ipv4 forms other than four plain decimal octets are refused`() {
        // rust's url parser reads 0177.0.0.1 as octal 127.0.0.1, Java as 177.0.0.1: only the plain form is classified
        listOf("wss://0177.0.0.1", "wss://010.0.0.1", "wss://127.1", "wss://2130706433", "wss://1.2.3.4.5", "wss://256.1.1.1", "wss://08.8.8.8")
            .forEach { assertFalse(RelayHosts.isPublicWss(it), it) }
        assertTrue(RelayHosts.isPublicWss("wss://1.0.0.1"))
    }

    @Test
    fun `only wss counts as a public relay`() {
        assertFalse(RelayHosts.isPublicWss("ws://relay.damus.io"))
        assertFalse(RelayHosts.isPublicWss("https://relay.damus.io"))
        assertFalse(RelayHosts.isPublicWss("not a url"))
        assertTrue(RelayHosts.isPublic("https://watcher.monostr.com"))
        assertFalse(RelayHosts.isPublic("https://192.168.1.10:8080"))
    }

    @Test
    fun `isOnion reads the host, case-insensitive, trailing dot, port and path`() {
        listOf("wss://abc.onion", "ws://abc.onion", "ws://ABC.ONION:8080/x", "wss://x.ONION.", "ws://sub.abc.onion/path?q=1", " ws://abc.onion ")
            .forEach { assertTrue(RelayHosts.isOnion(it), it) }
        listOf("wss://onion.example", "wss://xonion", "wss://onions.net", "wss://relay.onion.example", "wss://a.onion.com/x.onion", "wss://relay.damus.io", "ws://[::1]", "")
            .forEach { assertFalse(RelayHosts.isOnion(it), it) }
    }

    @Test
    fun `isOnion is false for a malformed url and never throws`() {
        listOf("not a url", "wss://", "ws://a b.onion", "://x.onion", "wss://[bad.onion", "\u0000").forEach { assertFalse(RelayHosts.isOnion(it), it) }
    }

    @Test
    fun `isPublicWss refuses wss onion, so foreign lists, hints and watcher lists drop it`() {
        listOf("wss://abc.onion", "wss://ABC.onion/", "wss://abc.onion:443", "wss://x.onion.").forEach {
            assertFalse(RelayHosts.isPublicWss(it), it)
            assertFalse(RelayHosts.isPublic(it), it)
        }
        assertFalse(RelayHosts.isPublicWss("ws://abc.onion"))
        assertTrue(RelayHosts.isPublicWss("wss://onion.example"))
    }

    @Test
    fun `isOnionHost reads the host of a rust-normalised or odd url by hand`() {
        listOf(
            "wss://a_b.onion", "ws://foo_bar.abc.onion", "ws://abc%2Eonion", "ws://abc%2eONION:80/x", "wss://abc.onion/ x", "wss://abc.onion\\x",
            "ws://user:pw@abc.onion", "ws://ABC.ONION.", "ws://abc.onion?q", "ws://abc.onion#f",
        ).forEach { assertTrue(RelayHosts.isOnionHost(it), it) }
        listOf("wss://onion.example", "wss://xonion", "ws://abc.onion@evil.example", "wss://evil.example/abc.onion", "wss://relay.damus.io:443", "", "wss://[::1]:7777")
            .forEach { assertFalse(RelayHosts.isOnionHost(it), it) }
    }
}
