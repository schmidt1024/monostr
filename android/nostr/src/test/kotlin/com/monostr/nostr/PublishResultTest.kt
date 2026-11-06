package com.monostr.nostr

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublishResultTest {
    private fun accepted(vararg relays: String) = PublishResult("0".repeat(64), 9738, relays.toList(), emptyMap())

    @Test
    fun `a relay counts as reached with or without the slash the client adds to its url`() {
        assertTrue(accepted("wss://w.example/").reached(listOf("wss://w.example")))
        assertTrue(accepted("wss://w.example").reached(listOf("wss://w.example/")))
    }

    @Test
    fun `acceptance by another relay does not count, nor does an empty list`() {
        assertFalse(accepted("wss://own.example").reached(listOf("wss://w.example")))
        assertFalse(accepted("wss://own.example").reached(emptyList()))
    }
}
