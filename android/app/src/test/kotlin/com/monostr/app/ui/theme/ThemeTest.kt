package com.monostr.app.ui.theme

import com.monostr.app.data.Accent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemeTest {
    @Test
    fun `mono keeps the monochrome primary tokens in both modes`() {
        val light = AccentPalette.tokens(Accent.MONO, dark = false)
        assertEquals(MonoColors.LightPrimary, light.primary)
        assertEquals(MonoColors.LightOnPrimary, light.onPrimary)
        assertEquals(MonoColors.LightSurfaceContainer, light.primaryContainer)
        val dark = AccentPalette.tokens(Accent.MONO, dark = true)
        assertEquals(MonoColors.DarkPrimary, dark.primary)
        assertEquals(MonoColors.DarkOnPrimary, dark.onPrimary)
        assertEquals(MonoColors.DarkSurfaceContainer, dark.primaryContainer)
    }

    @Test
    fun `every accent reads in both modes and only touches the primary tokens`() {
        for (accent in Accent.entries.filter { it != Accent.MONO }) {
            for (dark in listOf(false, true)) {
                val t = AccentPalette.tokens(accent, dark)
                val surface = if (dark) MonoColors.DarkSurface else MonoColors.LightSurface
                // primary is the colour of every TextButton label, icon tint and link: it must read on the surface (spec 11.1: AAA)
                val rawReads = Contrast.ratio(accent.seedArgb, surface) >= AccentPalette.primaryMin(dark) &&
                    Contrast.ratio(accent.seedArgb, Contrast.onColorFor(accent.seedArgb)) >= AccentPalette.ON_PRIMARY_MIN
                if (rawReads) assertEquals(accent.seedArgb, t.primary, "$accent dark=$dark keeps the raw seed")
                else assertTrue(t.primary != accent.seedArgb, "$accent dark=$dark seed was adjusted")
                assertTrue(Contrast.ratio(t.primary, surface) >= AccentPalette.primaryMin(dark), "$accent dark=$dark primary on surface: ${Contrast.ratio(t.primary, surface)}")
                assertTrue(Contrast.ratio(t.primary, t.onPrimary) >= AccentPalette.ON_PRIMARY_MIN, "$accent dark=$dark onPrimary")
                assertTrue(Contrast.ratio(t.primaryContainer, t.onPrimaryContainer) >= AccentPalette.CONTAINER_MIN, "$accent dark=$dark container")
                assertEquals(AccentPalette.blend(accent.seedArgb, surface, 0.12), t.primaryContainer, "$accent dark=$dark container blend")
            }
        }
    }

    @Test
    fun `a seed that already reads stays raw`() {
        // Monero orange on black exceeds 7:1 and its black label reads on it (spec 11.1 §2 table)
        assertEquals(Accent.ORANGE.seedArgb, AccentPalette.tokens(Accent.ORANGE, dark = true).primary)
    }

    @Test
    fun `dark accents are lighter and light accents darker than their seed`() {
        // the user's complaint (spec 11.1 §1): an adjusted accent always moves towards the mode's text colour, never away
        for (accent in Accent.entries.filter { it != Accent.MONO }) {
            val light = AccentPalette.tokens(accent, dark = false).primary
            val dark = AccentPalette.tokens(accent, dark = true).primary
            assertTrue(Contrast.luminance(light) <= Contrast.luminance(accent.seedArgb), "$accent light mode must not be lighter than its seed")
            assertTrue(Contrast.luminance(dark) >= Contrast.luminance(accent.seedArgb), "$accent dark mode must not be darker than its seed")
        }
    }

    @Test
    fun `light accents sit around tone 40, dark accents around tone 80`() {
        // Material 3 practice (2026-10-08): 5.5:1 on white keeps a hue readable as itself (7:1 turned orange brown), 7:1 on black is a pastel
        assertEquals(5.5, AccentPalette.primaryMin(dark = false))
        assertEquals(7.0, AccentPalette.primaryMin(dark = true))
        val orange = Contrast.ratio(AccentPalette.tokens(Accent.ORANGE, dark = false).primary, MonoColors.LightSurface)
        assertTrue(orange >= 5.5 && orange < 7.0, "orange light ratio $orange")
    }

    @Test
    fun `accent tokens are stable and documented`() {
        // the measured values the acceptance on the phone refers to (spec 11.1 §2); change the table there when you change these
        val expected = mapOf(
            (Accent.ORANGE to false) to 0xFFB34700L, (Accent.ORANGE to true) to 0xFFFF6600L,
            (Accent.BLUE to false) to 0xFF285EC9L, (Accent.BLUE to true) to 0xFF6D9AF2L,
            (Accent.PURPLE to false) to 0xFF744BCBL, (Accent.PURPLE to true) to 0xFFA98DE4L,
        )
        assertEquals((Accent.entries.size - 2) * 2, expected.size) // every fixed accent except MONO, both modes; CUSTOM comes from the hue (AccentSeedTest)
        for ((key, primary) in expected) {
            val (accent, dark) = key
            assertEquals(primary, AccentPalette.tokens(accent, dark).primary, "$accent dark=$dark primary=%08X".format(AccentPalette.tokens(accent, dark).primary))
        }
    }

    @Test
    fun `blend composites alpha over an opaque background`() {
        assertTrue(AccentPalette.blend(0xFF000000, 0xFFFFFFFF, 0.5) in setOf(0xFF7F7F7F, 0xFF808080))
        assertEquals(0xFF000000, AccentPalette.blend(0xFF000000, 0xFFFFFFFF, 1.0))
        assertEquals(0xFFFFFFFF, AccentPalette.blend(0xFF000000, 0xFFFFFFFF, 0.0))
    }
}
