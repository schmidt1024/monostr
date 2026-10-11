package com.monostr.app.ui

import com.monostr.app.ui.common.Reactions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The user's own quick reactions (2026-10-11): stored as a JSON list, edited in Settings. */
class ReactionsTest {
    @Test
    fun `a stored list reads back, anything unreadable or empty is the default`() {
        assertEquals(listOf("🔥", "🤙"), Reactions.decode(Reactions.encode(listOf("🔥", "🤙"))))
        assertEquals(Reactions.QUICK, Reactions.decode(null))
        assertEquals(Reactions.QUICK, Reactions.decode("not json"))
        assertEquals(Reactions.QUICK, Reactions.decode("[]"))
        // junk entries are dropped, not the whole list
        assertEquals(listOf("🔥"), Reactions.decode("""["🔥", "", "two words", "🔥"]"""))
    }

    @Test
    fun `adding cleans, skips repeats and stops at the maximum`() {
        assertEquals(listOf("🔥", "🤙"), Reactions.add(listOf("🔥"), " 🤙 "))
        assertEquals(listOf("🔥"), Reactions.add(listOf("🔥"), "🔥"))
        assertEquals(listOf("🔥"), Reactions.add(listOf("🔥"), "two words"))
        val full = List(Reactions.MAX_QUICK) { "e$it" }
        assertEquals(full, Reactions.add(full, "🚀"))
        assertNull(Reactions.clean(""))
    }

    @Test
    fun `moving swaps with the neighbour and stays inside the list, removing drops one`() {
        val l = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), Reactions.move(l, 1, -1))
        assertEquals(listOf("a", "c", "b"), Reactions.move(l, 1, +1))
        assertEquals(l, Reactions.move(l, 0, -1))
        assertEquals(l, Reactions.move(l, 2, +1))
        assertEquals(listOf("a", "c"), Reactions.remove(l, 1))
    }

    @Test
    fun `only emojis count as such, text from a relay is shown as the heart`() {
        assertEquals("🔥", Reactions.shown("🔥"))
        assertEquals("👍🏽", Reactions.shown("👍🏽"))
        assertEquals("🏳️‍🌈", Reactions.shown("🏳️‍🌈"))
        assertEquals("❤️", Reactions.shown("❤️"))
        assertEquals("👨‍👩‍👧", Reactions.shown("👨‍👩‍👧"))
        assertNull(Reactions.shown("+"))
        assertNull(Reactions.shown(""))
        assertNull(Reactions.shown("sent you 10 XMR"))
        assertNull(Reactions.shown("PAID"))
        assertNull(Reactions.shown("10"))
        assertNull(Reactions.shown(":shaka:"))
        assertNull(Reactions.shown("🔥🔥🔥🔥🔥🔥🔥🔥🔥"))
        // look-alikes of digits and letters that are no letter or digit to the JDK
        assertNull(Reactions.shown("¹⁰"))
        assertNull(Reactions.shown("①⓪"))
        assertNull(Reactions.shown("❶"))
        assertNull(Reactions.shown("ⅩⅯ"))
        assertNull(Reactions.shown("🅧🅜🅡"))
        assertNull(Reactions.shown("🇽🇲🇷"))
        assertNull(Reactions.shown("$₿!"))
        assertNull(Reactions.shown("‍️"))
    }
}
