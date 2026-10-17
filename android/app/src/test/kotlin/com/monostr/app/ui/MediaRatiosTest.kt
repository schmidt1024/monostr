package com.monostr.app.ui

import com.monostr.app.ui.media.MediaRatioCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Spec 2026-10-16 §5: sizes learned from loaded pictures, per URL, least recently used ones fall out. */
class MediaRatiosTest {
    @Test
    fun `remembers per url and forgets the least recently used beyond the limit`() {
        val c = MediaRatioCache(max = 3)
        c.put("a", 4, 3); c.put("b", 16, 9); c.put("c", 1, 1)
        assertEquals(4 to 3, c["a"]) // a is now the most recently used
        c.put("d", 2, 1)
        assertNull(c["b"])
        assertEquals(4 to 3, c["a"])
        assertEquals(3, c.size)
    }

    @Test
    fun `zero or negative sizes are not remembered, so the placeholder box stays`() {
        val c = MediaRatioCache()
        c.put("a", 0, 100); c.put("b", 100, -1)
        assertNull(c["a"])
        assertNull(c["b"])
    }

    @Test
    fun `the app-wide cache holds about five hundred`() {
        assertEquals(500, MediaRatioCache.MAX)
    }
}
