package com.monostr.nostr.repo

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.monostr.tips.event.Event as JsonEvent

/** Spec perf2 C3 job L: which live events change an open thread. */
class ThreadLiveMatchTest {
    private val root = "a".repeat(64)
    private val focused = "b".repeat(64)
    private val shownReply = "c".repeat(64)
    private val other = "d".repeat(64)
    private val tagIds = listOf(root, focused)
    private val shown = setOf(root, focused, shownReply)
    private fun ev(kind: Int, vararg tags: List<String>) = JsonEvent("0".repeat(64), "1".repeat(64), 1, kind, tags.toList(), "", "0".repeat(128))

    @Test
    fun `a note that tags the root or the focused note and a deletion of a shown note touch the thread`() {
        assertTrue(ThreadLiveMatch.touches(ev(1, listOf("e", root, "", "root")), tagIds, shown))
        assertTrue(ThreadLiveMatch.touches(ev(1, listOf("e", focused)), tagIds, shown))
        assertTrue(ThreadLiveMatch.touches(ev(5, listOf("e", shownReply), listOf("k", "1")), tagIds, shown))
    }

    @Test
    fun `a NIP-22 comment on the root, the focused note or below the root touches the thread`() {
        assertTrue(ThreadLiveMatch.touches(ev(1111, listOf("E", root), listOf("e", root)), tagIds, shown))
        assertTrue(ThreadLiveMatch.touches(ev(1111, listOf("E", root), listOf("e", focused)), tagIds, shown))
        assertTrue(ThreadLiveMatch.touches(ev(1111, listOf("E", root), listOf("e", other)), tagIds, shown))
        assertFalse(ThreadLiveMatch.touches(ev(1111, listOf("E", other), listOf("e", other)), tagIds, shown))
    }

    @Test
    fun `other notes, other deletions, likes and p tags do not`() {
        assertFalse(ThreadLiveMatch.touches(ev(1, listOf("e", other)), tagIds, shown))
        assertFalse(ThreadLiveMatch.touches(ev(5, listOf("e", other)), tagIds, shown))
        assertFalse(ThreadLiveMatch.touches(ev(7, listOf("e", root)), tagIds, shown))
        assertFalse(ThreadLiveMatch.touches(ev(1, listOf("p", root)), tagIds, shown))
        assertFalse(ThreadLiveMatch.touches(ev(1, listOf("e")), tagIds, shown))
    }
}
