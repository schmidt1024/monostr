package com.monostr.app.ui

import com.monostr.app.ui.compose.PollDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Polls task 6: the poll draft's rules. */
class PollDraftTest {
    @Test
    fun `default is two empty choices and one day`() {
        val d = PollDraft()
        assertEquals(listOf("", ""), d.choices)
        assertEquals(86_400L, d.seconds)
        assertFalse(d.valid())
    }

    @Test
    fun `valid needs two filled choices`() {
        assertFalse(PollDraft(choices = listOf("a", "")).valid())
        assertFalse(PollDraft(choices = listOf("a", "   ")).valid())
        assertTrue(PollDraft(choices = listOf(" a ", "b")).valid())
        assertTrue(PollDraft(choices = listOf("a", "b", "c", "d")).valid())
        assertFalse(PollDraft(choices = listOf("a", "b", "c", "d", "e")).valid())
    }

    @Test
    fun `a choice longer than 25 is invalid`() {
        assertTrue(PollDraft(choices = listOf("x".repeat(25), "b")).valid())
        assertFalse(PollDraft(choices = listOf("x".repeat(26), "b")).valid())
        assertTrue(PollDraft(choices = listOf("  " + "x".repeat(25) + "  ", "b")).valid())
        assertTrue(PollDraft(choices = listOf("😀".repeat(25), "b")).valid()) // code points, not UTF-16 units
        assertEquals(25, PollDraft.limit("x".repeat(30)).length)
        assertEquals("😀".repeat(25), PollDraft.limit("😀".repeat(30)))
    }

    @Test
    fun `less than five minutes is invalid, seven days is the most`() {
        val c = listOf("a", "b")
        assertFalse(PollDraft(c, 0, 0, 4).valid())
        assertTrue(PollDraft(c, 0, 0, 5).valid())
        assertFalse(PollDraft(c, 0, 0, 0).valid())
        assertTrue(PollDraft(c, 7, 0, 0).valid())
        assertFalse(PollDraft(c, 7, 0, 1).valid())
    }

    @Test
    fun `seven days clears hours and minutes`() {
        val d = PollDraft().withLength(7, 5, 30)
        assertEquals(PollDraft(days = 7, hours = 0, minutes = 0), d)
        assertEquals(PollDraft(days = 2, hours = 23, minutes = 59), PollDraft().withLength(2, 99, 99))
        assertEquals(PollDraft(days = 7), PollDraft().withLength(9, 1, 1))
        assertEquals(PollDraft(days = 0, hours = 0, minutes = 5), PollDraft().withLength(-1, -1, -1))
    }

    @Test
    fun `the pickers cannot make a poll shorter than five minutes`() {
        assertEquals(PollDraft(days = 0, hours = 0, minutes = 5), PollDraft().withLength(0, 0, 2))
        assertEquals(PollDraft(days = 0, hours = 0, minutes = 30), PollDraft().withLength(0, 0, 30))
        assertEquals(PollDraft(days = 0, hours = 1, minutes = 0), PollDraft().withLength(0, 1, 0))
        assertTrue(PollDraft(listOf("a", "b")).withLength(0, 0, 0).valid())
    }
}
