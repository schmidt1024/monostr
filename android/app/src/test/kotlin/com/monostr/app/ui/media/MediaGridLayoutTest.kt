package com.monostr.app.ui.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Backlog 39: how a note's pictures are arranged. */
class MediaGridLayoutTest {
    @Test
    fun `one and two pictures keep their layout`() {
        assertEquals(MediaGridLayout(MediaGridLayout.Kind.SINGLE, shown = 1, more = 0), MediaGridLayout.of(1))
        assertEquals(MediaGridLayout(MediaGridLayout.Kind.PAIR, shown = 2, more = 0), MediaGridLayout.of(2))
    }

    @Test
    fun `three pictures are one big on the left and two stacked on the right`() {
        assertEquals(MediaGridLayout(MediaGridLayout.Kind.BIG_LEFT, shown = 3, more = 0), MediaGridLayout.of(3))
    }

    @Test
    fun `four are a two by two grid, more show four with plus the rest on the last`() {
        assertEquals(MediaGridLayout(MediaGridLayout.Kind.GRID, shown = 4, more = 0), MediaGridLayout.of(4))
        assertEquals(MediaGridLayout(MediaGridLayout.Kind.GRID, shown = 4, more = 1), MediaGridLayout.of(5))
        assertEquals(MediaGridLayout(MediaGridLayout.Kind.GRID, shown = 4, more = 6), MediaGridLayout.of(10))
    }

    @Test
    fun `three pictures are three squares, the big one as tall as the two small ones with the gap`() {
        val (big, small) = MediaGridLayout.bigLeftSides(width = 300f, gap = 3f)
        assertEquals(98f, small, 0.001f)
        assertEquals(2 * small + 3f, big, 0.001f)
        assertEquals(300f, big + 3f + small, 0.001f) // the row fills the width
    }
}
