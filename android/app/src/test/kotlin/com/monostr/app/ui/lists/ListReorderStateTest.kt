package com.monostr.app.ui.lists

import com.monostr.app.ui.lists.ListReorderState.Slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Backlog 38: dragging a row; rows are 100 px high, a at 0, b at 100, c at 200. */
class ListReorderStateTest {
    private var slots = listOf(Slot("a", 0, 100), Slot("b", 100, 100), Slot("c", 200, 100))
    private val state = ListReorderState<String>({ it }, { slots })
    private val items = listOf("a", "b", "c")

    @Test
    fun `dragging the middle row up past the first swaps once, even before the list is laid out again`() {
        state.start("b", items)
        state.drag(-60f) // b's middle at 90: over a
        // the layout still shows the old places (no frame in between); further moves must not swap again
        state.drag(-40f)
        state.drag(-40f)
        assertEquals(listOf("b", "a", "c"), state.rows(items))
        var dropped: Pair<Int, Int>? = null
        state.end { from, to -> dropped = from to to }
        assertEquals(1 to 0, dropped)
    }

    @Test
    fun `dragging down a long way before the list is laid out again does not swap back`() {
        state.start("a", items)
        state.drag(60f) // a's middle at 110: over b
        state.drag(100f) // still the old layout: b seems to sit at 100..200 where a's middle is now
        assertEquals(listOf("b", "a", "c"), state.rows(items))
        var dropped: Pair<Int, Int>? = null
        state.end { from, to -> dropped = from to to }
        assertEquals(0 to 1, dropped)
    }

    @Test
    fun `after the layout caught up, further dragging swaps with the next row`() {
        state.start("c", items)
        state.drag(-60f) // over b
        assertEquals(listOf("a", "c", "b"), state.rows(items))
        slots = listOf(Slot("a", 0, 100), Slot("c", 100, 100), Slot("b", 200, 100)) // laid out again
        state.drag(-100f) // c's middle now over a
        assertEquals(listOf("c", "a", "b"), state.rows(items))
        var dropped: Pair<Int, Int>? = null
        state.end { from, to -> dropped = from to to }
        assertEquals(2 to 0, dropped)
    }

    @Test
    fun `a small drag that stays over the own row drops nothing`() {
        state.start("a", items)
        state.drag(20f)
        var called = false
        state.end { _, _ -> called = true }
        assertEquals(false, called)
    }
}
