package com.monostr.app.ui

import com.monostr.app.ui.feed.tabMoves
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Spec 2026-10-18 §2.1: "Move left" and "Move right" are hidden at the edges; Following (tab 0) never moves. */
class FeedTabsTest {
    @Test
    fun `the moves a tab menu offers`() {
        assertEquals(false to false, tabMoves(1, 2)) // the only list
        assertEquals(false to true, tabMoves(1, 3)) // first list: not left of Following
        assertEquals(true to false, tabMoves(2, 3)) // last list
        assertEquals(true to true, tabMoves(2, 4))
        assertEquals(false to false, tabMoves(0, 4)) // Following has no menu
    }
}
