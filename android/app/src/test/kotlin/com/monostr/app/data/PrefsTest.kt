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
}
