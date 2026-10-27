package com.monostr.app.ui.discover

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-27 §4: who sees the discover block. */
class DiscoverVisibilityTest {
    private fun follows(n: Int) = (1..n).map { "%064x".format(it) }.toSet()

    @Test
    fun `unknown follow count decides nothing`() {
        assertNull(DiscoverVisibility.decide(null, dismissed = false))
        assertNull(DiscoverVisibility.decide(null, dismissed = true))
    }

    @Test
    fun `nine follows shows, ten does not`() {
        assertEquals(true, DiscoverVisibility.decide(follows(0), dismissed = false))
        assertEquals(true, DiscoverVisibility.decide(follows(9), dismissed = false))
        assertEquals(false, DiscoverVisibility.decide(follows(10), dismissed = false))
    }

    @Test
    fun `dismissed never shows`() {
        assertEquals(false, DiscoverVisibility.decide(follows(0), dismissed = true))
    }

    @Test
    fun `decision keeps the first answer`() {
        val d = DiscoverDecision()
        d.offer(null)
        assertNull(d.shown.value)
        d.offer(true)
        d.offer(false) // follows grew past ten while the block was shown: it stays
        assertTrue(d.shown.value == true)
    }

    @Test
    fun `dismiss hides`() {
        val d = DiscoverDecision()
        d.offer(true)
        d.dismiss()
        assertFalse(d.shown.value == true)
        d.offer(true)
        assertFalse(d.shown.value == true)
    }

    @Test
    fun `an unknown contact list is asked again before it counts as none`() = kotlinx.coroutines.test.runTest {
        // final review I2: the list may arrive seconds after the gate (new device, slow relays)
        val answers = ArrayDeque(listOf<Set<String>?>(null, null, follows(300)))
        var reads = 0
        val got = DiscoverVisibility.awaitFollowed(attempts = 4, pauseMs = 5_000) { reads++; answers.removeFirst() }
        assertEquals(300, got.size)
        assertEquals(3, reads)
    }

    @Test
    fun `a contact list unknown after every attempt counts as none`() = kotlinx.coroutines.test.runTest {
        var reads = 0
        val got = DiscoverVisibility.awaitFollowed(attempts = 4, pauseMs = 5_000) { reads++; null }
        assertTrue(got.isEmpty())
        assertEquals(4, reads)
    }
}
