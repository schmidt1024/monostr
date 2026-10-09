package com.monostr.app.ui

import com.monostr.app.ui.common.Muted
import com.monostr.app.ui.feed.NoteUi
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class MutedTest {
    private val alice = "a".repeat(64); private val bob = "b".repeat(64)
    private fun ui(n: Note) = NoteUi(n, Profile.empty(n.author), Profile.empty(n.repostOf?.author ?: n.author))

    @Test
    fun `notes of a muted account and reposts of its notes disappear, reposts BY it of others' notes disappear too`() {
        val own = ui(note('1', alice))
        val other = ui(note('2', bob))
        val repostOfAlice = ui(repostOf(note('3', alice), by = bob, createdAt = 5))
        val repostByAlice = ui(repostOf(note('4', bob), by = alice, createdAt = 6))
        assertEquals(listOf(other), Muted.visible(listOf(own, other, repostOfAlice, repostByAlice), setOf(alice)))
    }

    @Test
    fun `the kept note stays even when its author is muted`() {
        val focused = ui(note('1', alice))
        assertEquals(listOf(focused), Muted.visible(listOf(focused), setOf(alice), keep = focused.note.id))
    }

    @Test
    fun `nothing muted means the same list instance`() {
        val list = listOf(ui(note('1', alice)))
        assertSame(list, Muted.visible(list, emptySet()))
    }
}
