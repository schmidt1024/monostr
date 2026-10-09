package com.monostr.app.ui

import com.monostr.app.ui.common.formatCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class CountFormatTest {
    @Test
    fun `below a thousand plain, then k with one decimal, then M`() {
        assertEquals("0", formatCount(0, Locale.ENGLISH))
        assertEquals("999", formatCount(999, Locale.ENGLISH))
        assertEquals("1k", formatCount(1000, Locale.ENGLISH))
        assertEquals("1.2k", formatCount(1234, Locale.ENGLISH))
        assertEquals("9.9k", formatCount(9999, Locale.ENGLISH))
        assertEquals("10k", formatCount(10000, Locale.ENGLISH))
        assertEquals("999k", formatCount(999_999, Locale.ENGLISH))
        assertEquals("1M", formatCount(1_000_000, Locale.ENGLISH))
        assertEquals("3.4M", formatCount(3_400_000, Locale.ENGLISH))
    }

    @Test
    fun `decimal separator follows the locale`() {
        assertEquals("1,2k", formatCount(1234, Locale.GERMAN))
        assertEquals("3,4M", formatCount(3_400_000, Locale.GERMAN))
    }
}
