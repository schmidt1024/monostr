package com.monostr.app.ui

import com.monostr.app.ui.settings.DmInboxHints
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DmInboxHintsTest {
    private val other = "wss://inbox.example.com"

    @Test
    fun `the monostr card shows while relay monostr com is missing from the effective list`() {
        assertTrue(DmInboxHints.missing(listOf(other)))
        assertTrue(DmInboxHints.missing(emptyList()))
        assertFalse(DmInboxHints.missing(listOf(other, DmInboxHints.MONOSTR)))
    }

    @Test
    fun `the second card shows for exactly one relay and suggests only relays not in the list`() {
        assertTrue(DmInboxHints.needsSecond(listOf(DmInboxHints.MONOSTR)))
        assertFalse(DmInboxHints.needsSecond(emptyList()))
        assertFalse(DmInboxHints.needsSecond(listOf(DmInboxHints.MONOSTR, other)))
        val first = DmInboxHints.DM_RELAY_SUGGESTIONS.firstOrNull() ?: return
        assertFalse(first in DmInboxHints.suggestions(listOf(first)))
        assertTrue(first in DmInboxHints.suggestions(listOf(DmInboxHints.MONOSTR)))
    }

    @Test
    fun `every suggestion is a wss url and never the monostr relay`() {
        DmInboxHints.DM_RELAY_SUGGESTIONS.forEach {
            assertTrue(it.startsWith("wss://"), it)
            assertFalse(it == DmInboxHints.MONOSTR)
        }
    }

    @Test
    fun `a list of four is full`() {
        assertTrue(DmInboxHints.full(listOf("wss://a.example", "wss://b.example", "wss://c.example", "wss://d.example")))
        assertFalse(DmInboxHints.full(listOf("wss://a.example")))
        assertEquals(DmInboxHints.MONOSTR, "wss://relay.monostr.com")
    }
}
