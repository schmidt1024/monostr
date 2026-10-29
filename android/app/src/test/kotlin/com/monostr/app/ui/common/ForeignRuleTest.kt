package com.monostr.app.ui.common

import com.monostr.nostr.model.AddressRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Final review I1: what a note card may fetch inside a screen opened from a direct message ([LocalAllowForeign] false). */
class ForeignRuleTest {
    private val ref = AddressRef(30023, "a".repeat(64), "post", listOf("wss://sender.example"))

    @Test
    fun `without foreign relays a card drops hints, author relays and link previews`() {
        assertEquals(emptyList<String>(), ForeignRule.quoteHints(listOf("wss://sender.example"), allowForeign = false))
        assertNull(ForeignRule.quoteAuthor("b".repeat(64), allowForeign = false))
        assertEquals(ref.copy(relays = emptyList()) to false, ForeignRule.address(ref, allowForeign = false))
        assertFalse(ForeignRule.linkPreview(allowForeign = false))
    }

    @Test
    fun `elsewhere nothing changes`() {
        assertEquals(listOf("wss://sender.example"), ForeignRule.quoteHints(listOf("wss://sender.example"), allowForeign = true))
        assertEquals("b".repeat(64), ForeignRule.quoteAuthor("b".repeat(64), allowForeign = true))
        assertEquals(ref to true, ForeignRule.address(ref, allowForeign = true))
        assertTrue(ForeignRule.linkPreview(allowForeign = true))
    }
}
