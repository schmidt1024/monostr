package com.monostr.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.monostr.app.ui.common.HideOnScrollState
import com.monostr.app.ui.common.barsStuck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-16 §2: one connection moves the header and the bottom bar together. */
class HideOnScrollStateTest {
    private fun HideOnScrollState.scroll(dy: Float): Offset = connection.onPreScroll(Offset(0f, dy), NestedScrollSource.UserInput)

    @Test
    fun `scrolling down hides header and bottom bar together, scrolling up shows both`() {
        val s = HideOnScrollState().apply { heightPx = 100f; bottomHeightPx = 80f }
        assertEquals(-30f, s.scroll(-30f).y) // the header takes the scroll, as before
        assertEquals(-30f, s.offsetPx)
        assertEquals(30f, s.bottomOffsetPx)
        s.scroll(-500f)
        assertEquals(-100f, s.offsetPx)
        assertEquals(80f, s.bottomOffsetPx)
        s.scroll(50f)
        assertEquals(-50f, s.offsetPx)
        assertEquals(30f, s.bottomOffsetPx)
    }

    @Test
    fun `the bottom bar takes no scroll, so a screen without a hiding header scrolls with the finger`() {
        val s = HideOnScrollState().apply { bottomHeightPx = 80f }
        assertEquals(0f, s.scroll(-40f).y)
        assertEquals(40f, s.bottomOffsetPx)
        assertEquals(0f, s.offsetPx)
    }

    @Test
    fun `bars count as stuck when no list is shown or the list cannot scroll either way`() {
        assertTrue(barsStuck(hasList = false, canScrollForward = true, canScrollBackward = true)) // empty state or spinner replaced the list
        assertTrue(barsStuck(hasList = true, canScrollForward = false, canScrollBackward = false)) // emptied or too short to scroll
        assertFalse(barsStuck(hasList = true, canScrollForward = false, canScrollBackward = true)) // at the end: scrolling up brings them back
        assertFalse(barsStuck(hasList = true, canScrollForward = true, canScrollBackward = false))
    }

    @Test
    fun `show brings both back`() {
        val s = HideOnScrollState().apply { heightPx = 100f; bottomHeightPx = 80f }
        s.scroll(-500f)
        s.show()
        assertEquals(0f, s.offsetPx)
        assertEquals(0f, s.bottomOffsetPx)
    }
}
