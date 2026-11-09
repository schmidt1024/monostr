package com.monostr.app.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrefsTest {
    @Test
    fun `validates relay urls`() {
        assertTrue(isValidRelayUrl("wss://a.example"))
        assertTrue(isValidRelayUrl("ws://x"))
        assertFalse(isValidRelayUrl("wss://a b"))
        assertFalse(isValidRelayUrl("https://a"))
        assertFalse(isValidRelayUrl("wss://"))
    }

    @Test
    fun `an onion relay takes ws or wss, in any case, with a port`() {
        assertTrue(isValidRelayUrl("ws://abcdefghijklmnop.onion"))
        assertTrue(isValidRelayUrl("ws://ABC.ONION:7777"))
        assertTrue(isValidRelayUrl("wss://abc.onion"))
        assertFalse(isValidRelayUrl("http://abc.onion"))
        assertFalse(isValidRelayUrl("ws://abc.onion/ x"))
    }
}
