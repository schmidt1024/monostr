package com.monostr.app.ui

import com.monostr.app.ui.common.fullTime
import com.monostr.app.ui.common.detailTimeLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class CommonTimeTest {
    @Test
    fun `the full time follows the locale and names the year and the minute`() {
        val at = 1_791_000_000L // 2026-10-03 UTC; the clock depends on the JVM's zone, the year and the minute separator do not
        val de = fullTime(at, Locale.GERMANY)
        val us = fullTime(at, Locale.US)
        assertTrue(de.contains("2026") && de.contains(":"), de)
        assertTrue(us.contains("2026") && us.contains(":"), us)
        assertNotEquals(de, us)
    }

    @Test
    fun `the detail line adds the client after a middle dot, or nothing`() {
        assertEquals("3 Oct 2026, 10:00 · via Amethyst", detailTimeLine("3 Oct 2026, 10:00", "Amethyst", via = "via %1\$s"))
        assertEquals("3 Oct 2026, 10:00", detailTimeLine("3 Oct 2026, 10:00", null, via = "via %1\$s"))
        assertEquals("3 Oct 2026, 10:00 · über Damus", detailTimeLine("3 Oct 2026, 10:00", "Damus", via = "über %1\$s"))
    }
}
