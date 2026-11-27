package com.monostr.app.ui

import com.monostr.app.ui.monero.MoneroIntroGate
import com.monostr.nostr.repo.OwnMonero
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/** Backlog 121: the intro comes only when the relays said for sure that nothing is set up. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoneroIntroGateTest {
    private class Rig(private vararg val answers: OwnMonero) {
        var asked = 0
        var seen = 0
        val lookup: suspend () -> OwnMonero = { answers[minOf(asked++, answers.size - 1)] }
        val markSeen: suspend () -> Unit = { seen++ }
    }

    @Test
    fun `an account that already receives Monero never sees the intro and counts as answered`() = runTest {
        val rig = Rig(OwnMonero.FOUND)
        assertFalse(MoneroIntroGate.due(created = false, rig.lookup, rig.markSeen))
        assertEquals(1, rig.seen)
    }

    @Test
    fun `a complete answer without Monero brings the intro`() = runTest {
        val rig = Rig(OwnMonero.NONE)
        assertTrue(MoneroIntroGate.due(created = false, rig.lookup, rig.markSeen))
        assertEquals(0, rig.seen)
    }

    @Test
    fun `an unclear answer is asked again and decides once it is clear`() = runTest {
        val rig = Rig(OwnMonero.UNKNOWN, OwnMonero.UNKNOWN, OwnMonero.FOUND)
        assertFalse(MoneroIntroGate.due(created = false, rig.lookup, rig.markSeen))
        assertEquals(3, rig.asked)
        assertEquals(1, rig.seen)
        assertEquals(13_000L, currentTime, "3 s after the first answer, 10 s after the second")
    }

    @Test
    fun `answers that stay unclear show nothing and leave the question open for the next start`() = runTest {
        val rig = Rig(OwnMonero.UNKNOWN)
        assertFalse(MoneroIntroGate.due(created = false, rig.lookup, rig.markSeen))
        assertEquals(MoneroIntroGate.TRIES, rig.asked)
        assertEquals(0, rig.seen, "not answered: asked again at the next start")
    }

    @Test
    fun `a failing lookup counts as unclear`() = runTest {
        var asked = 0
        assertFalse(MoneroIntroGate.due(created = false, lookup = { asked++; error("relay") }, markSeen = {}, tries = 2))
        assertEquals(2, asked)
    }

    @Test
    fun `an account created here gets the intro at once and asks nobody`() = runTest {
        val rig = Rig(OwnMonero.FOUND)
        assertTrue(MoneroIntroGate.due(created = true, rig.lookup, rig.markSeen))
        assertEquals(0, rig.asked)
    }
}
