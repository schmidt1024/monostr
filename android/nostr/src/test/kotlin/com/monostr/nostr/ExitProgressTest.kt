package com.monostr.nostr

import com.monostr.tips.event.Event as JsonEvent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExitProgressTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private fun ev(id: String, pubkey: String = "c".repeat(64)) = JsonEvent(id, pubkey, 1, 1, emptyList(), "", "0".repeat(128))

    @Test
    fun `AllIds is met whatever the case of the asked ids`() {
        val p = ExitProgress(Exit.AllIds(setOf(a.uppercase(), b)))
        assertFalse(p.met)
        assertTrue(p.add(ev(a)))
        assertFalse(p.met)
        assertTrue(p.add(ev(b)))
        assertTrue(p.met)
    }

    @Test
    fun `a second copy of an id is not new`() {
        val p = ExitProgress(Exit.AnyEvent)
        assertTrue(p.add(ev(a)))
        assertFalse(p.add(ev(a)))
        assertTrue(p.met)
    }

    @Test
    fun `NewestPerAuthor is met when every author has an event`() {
        val alice = "1".repeat(64)
        val bob = "2".repeat(64)
        val p = ExitProgress(Exit.NewestPerAuthor(setOf(alice, bob)))
        p.add(ev(a, alice))
        assertFalse(p.met)
        p.add(ev(b, bob))
        assertTrue(p.met)
    }

    @Test
    fun `empty sets wait for nothing and take the plain path`() {
        assertTrue(ExitProgress(Exit.AllIds(emptySet())).empty)
        assertTrue(ExitProgress(Exit.NewestPerAuthor(emptySet())).empty)
        assertFalse(ExitProgress(Exit.AnyEvent).empty)
    }
}
