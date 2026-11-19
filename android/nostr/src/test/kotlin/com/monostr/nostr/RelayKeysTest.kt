package com.monostr.nostr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelayKeysTest {
    private val base = "wss://relay.example.com"

    @Test fun `spellings of the same relay give one key`() {
        assertEquals(base, RelayKeys.of("wss://relay.example.com/"))
        assertEquals(base, RelayKeys.of("  WSS://Relay.EXAMPLE.com  "))
        assertEquals(base, RelayKeys.of("wss://relay.example.com:443"))
        assertEquals("ws://relay.example.com", RelayKeys.of("ws://relay.example.com:80/"))
    }

    @Test fun `a different relay never shares the key`() {
        for (raw in listOf(
            "ws://relay.example.com", "wss://relay.example.com:444", "wss://relay.example.com/inbox",
            "wss://relay.example.com?x=1", "wss://relay.example.com.", "wss://rеlay.example.com" /* Cyrillic е */,
            "https://relay.example.com", "wss://relay.example.com@evil.example",
        )) {
            assertNotEquals(base, RelayKeys.of(raw), raw)
        }
        RelayKeys.of("wss://relay.example.com@evil.example")?.let { assertTrue("evil.example" in it, it) }
    }

    @Test fun `IDN and punycode agree or the IDN is refused`() {
        val u = RelayKeys.of("wss://bücher.example")
        val p = RelayKeys.of("wss://xn--bcher-kva.example")
        assertTrue(u == null || u == p, "$u vs $p")
    }

    @Test fun `garbage has no key`() {
        assertNull(RelayKeys.of(""))
        assertNull(RelayKeys.of("not a url"))
    }
}
