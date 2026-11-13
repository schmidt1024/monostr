package com.monostr.app.ui

import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.feed.Reposts
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Backlog 80: one person reposting the same note twice shows one row, the newest. */
class RepostsTest {
    private val alice = "a".repeat(64); private val bob = "b".repeat(64); private val carol = "c".repeat(64)
    private fun ui(n: Note) = NoteUi(n, Profile.empty(n.author), Profile.empty(n.repostOf?.author ?: n.author))

    @Test
    fun `two reposts of one note by one person show as one row, the newest`() {
        val original = note('1', carol)
        val newer = ui(repostOf(original, by = bob, createdAt = 9))
        val older = ui(repostOf(original, by = bob, createdAt = 8))
        val other = ui(note('2', alice, createdAt = 7))
        assertEquals(listOf(newer, other), Reposts.collapsed(listOf(newer, other, older)))
    }

    @Test
    fun `reposts of one note by different people all stay`() {
        val original = note('1', carol)
        val byBob = ui(repostOf(original, by = bob, createdAt = 9))
        val byAlice = ui(repostOf(original, by = alice, createdAt = 8))
        assertEquals(listOf(byBob, byAlice), Reposts.collapsed(listOf(byBob, byAlice)))
    }

    @Test
    fun `a repost without its original embedded collapses by the referenced id`() {
        val a = ui(Note("d".repeat(64), bob, "", 9, 6, null, null, null, emptyList(), repostedId = "1".repeat(64)))
        val b = ui(Note("e".repeat(64), bob, "", 8, 6, null, null, null, emptyList(), repostedId = "1".repeat(64)))
        assertEquals(listOf(a), Reposts.collapsed(listOf(a, b)))
    }

    @Test
    fun `no duplicate means the same list instance`() {
        val list = listOf(ui(note('1', alice)), ui(repostOf(note('2', carol), by = bob, createdAt = 5)))
        assertSame(list, Reposts.collapsed(list))
    }
}
