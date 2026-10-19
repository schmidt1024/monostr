package com.monostr.tips

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelayHostsTest {
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
}
