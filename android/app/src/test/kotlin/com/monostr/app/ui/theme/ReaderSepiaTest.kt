package com.monostr.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.monostr.app.data.Accent
import com.monostr.app.data.ThemeMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Backlog 102: the sepia paper of the article reader must stay readable for text, muted text and every accent's links. */
class ReaderSepiaTest {
    private fun argb(c: Color): Long = c.toArgb().toLong() and 0xFFFFFFFFL

    @Test
    fun `ink and muted ink read on the paper`() {
        val paper = argb(ReaderSepia.paper)
        assertTrue(Contrast.ratio(argb(ReaderSepia.ink), paper) >= 4.5, "ink ${Contrast.ratio(argb(ReaderSepia.ink), paper)}")
        assertTrue(Contrast.ratio(argb(ReaderSepia.inkMuted), paper) >= 4.5, "inkMuted ${Contrast.ratio(argb(ReaderSepia.inkMuted), paper)}")
    }

    @Test
    fun `every accent primary reads on the paper in the sepia scheme`() {
        val paper = argb(ReaderSepia.paper)
        for (accent in Accent.entries) {
            val primary = argb(monostrColorScheme(dark = false, black = false, accent = accent, customHue = AccentSeed.DEFAULT_HUE, sepia = true).primary)
            assertTrue(Contrast.ratio(primary, paper) >= 4.5, "$accent primary on paper: ${Contrast.ratio(primary, paper)}")
        }
    }

    @Test
    fun `sepia paints the light scheme only`() {
        val light = monostrColorScheme(dark = false, black = false, accent = Accent.MONO, customHue = AccentSeed.DEFAULT_HUE, sepia = true)
        assertEquals(ReaderSepia.paper, light.background)
        assertEquals(ReaderSepia.paper, light.surface)
        assertEquals(ReaderSepia.ink, light.onBackground)
        assertEquals(ReaderSepia.ink, light.onSurface)
        assertEquals(ReaderSepia.inkMuted, light.onSurfaceVariant)
        assertNotEquals(ReaderSepia.paper, monostrColorScheme(dark = false, black = false, accent = Accent.MONO, customHue = AccentSeed.DEFAULT_HUE, sepia = false).background)
        for (mode in listOf(ThemeMode.DARK, ThemeMode.BLACK)) {
            val on = monostrColorScheme(dark = true, black = mode == ThemeMode.BLACK, accent = Accent.MONO, customHue = AccentSeed.DEFAULT_HUE, sepia = true)
            val off = monostrColorScheme(dark = true, black = mode == ThemeMode.BLACK, accent = Accent.MONO, customHue = AccentSeed.DEFAULT_HUE, sepia = false)
            assertEquals(off.background, on.background)
            assertEquals(off.onSurface, on.onSurface)
        }
    }
}
