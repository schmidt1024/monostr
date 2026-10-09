package com.monostr.app.ui.theme

import com.monostr.app.data.Accent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContrastTest {
    @Test
    fun `wcag luminance and ratio match the reference values`() {
        assertEquals(1.0, Contrast.luminance(0xFFFFFFFF), 1e-9)
        assertEquals(0.0, Contrast.luminance(0xFF000000), 1e-9)
        assertEquals(21.0, Contrast.ratio(0xFFFFFFFF, 0xFF000000), 1e-9)
        assertEquals(Contrast.ratio(0xFF000000, 0xFFFFFFFF), Contrast.ratio(0xFFFFFFFF, 0xFF000000), 1e-9)
        assertEquals(0xFF111111, Contrast.onColorFor(0xFFFFFFFF))
        assertEquals(0xFFFFFFFF, Contrast.onColorFor(0xFF000000))
    }

    @Test
    fun `every accent gets a readable on colour`() {
        for (accent in Accent.entries) {
            val on = Contrast.onColorFor(accent.seedArgb)
            assertTrue(Contrast.ratio(accent.seedArgb, on) >= 4.5, "$accent: ratio ${Contrast.ratio(accent.seedArgb, on)}")
        }
    }
}
