package com.monostr.app.ui.theme

import com.monostr.app.data.Accent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccentSeedTest {
    @Test
    fun `hue maps to a saturated seed`() {
        // V = 0.90 → 230 (0xE6), S = 0.85 → the low channels sit at 230 × 0.15 ≈ 34 (0x22)
        assertEquals(0xFFE62222L, AccentSeed.fromHue(0f)) // red
        assertEquals(0xFF22E622L, AccentSeed.fromHue(120f)) // green
        assertEquals(0xFF2222E6L, AccentSeed.fromHue(240f)) // blue
        assertEquals(AccentSeed.fromHue(0f), AccentSeed.fromHue(360f))
        assertEquals(AccentSeed.fromHue(30f), AccentSeed.fromHue(-330f))
    }

    @Test
    fun `every hue reads in both modes`() {
        for (h in 0 until 360 step 15) {
            val seed = AccentSeed.fromHue(h.toFloat())
            for (dark in listOf(false, true)) {
                val t = AccentPalette.tokens(Accent.CUSTOM, dark, customSeed = seed)
                val surface = if (dark) MonoColors.DarkSurface else MonoColors.LightSurface
                assertTrue(Contrast.ratio(t.primary, surface) >= AccentPalette.primaryMin(dark), "hue $h dark=$dark primary")
                assertTrue(Contrast.ratio(t.primary, t.onPrimary) >= AccentPalette.ON_PRIMARY_MIN, "hue $h dark=$dark label")
                assertTrue(Contrast.ratio(t.primaryContainer, t.onPrimaryContainer) >= AccentPalette.CONTAINER_MIN, "hue $h dark=$dark container")
            }
        }
    }

    @Test
    fun `the custom seed is what the palette mixes, not the placeholder`() {
        val seed = AccentSeed.fromHue(0f)
        val fromSeed = AccentPalette.tokens(Accent.CUSTOM, dark = true, customSeed = seed)
        val fromPlaceholder = AccentPalette.tokens(Accent.CUSTOM, dark = true)
        assertTrue(fromSeed.primary != fromPlaceholder.primary)
        assertEquals(AccentPalette.blend(seed, MonoColors.DarkSurface, 0.12), fromSeed.primaryContainer)
    }
}
