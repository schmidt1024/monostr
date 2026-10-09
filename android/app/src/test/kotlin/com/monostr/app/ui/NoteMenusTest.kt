package com.monostr.app.ui

import com.monostr.app.ui.common.offersMute
import com.monostr.nostr.repo.ListState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoteMenusTest {
    private val me = "a".repeat(64); private val bob = "b".repeat(64)

    @Test
    fun `muting is offered for others' notes unless the list is known to be read-only`() {
        assertTrue(offersMute(bob, me, ListState(writable = true, loaded = true)))
        assertFalse(offersMute(bob, me, ListState(writable = false, loaded = true)))
        // not loaded yet (Amber before its interactive load, offline at start): the write loads it first
        assertTrue(offersMute(bob, me, ListState(writable = false, loaded = false)))
        assertFalse(offersMute(me, me, ListState(writable = true, loaded = true)))
    }
}
