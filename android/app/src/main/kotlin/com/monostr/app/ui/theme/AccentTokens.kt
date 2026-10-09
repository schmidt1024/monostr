package com.monostr.app.ui.theme

import com.monostr.app.data.Accent
import kotlin.math.roundToInt

/** The four primary tokens an accent replaces (spec 4.3); everything else stays monochrome. */
data class AccentTokens(val primary: Long, val onPrimary: Long, val primaryContainer: Long, val onPrimaryContainer: Long)

object AccentPalette {
    /**
     * Spec 11.1 §2, tuned 2026-10-08 to Material 3 practice: in the light the accent sits around tone 40
     * (5.5:1 on white keeps the hue readable as itself — 7:1 turned orange brown), in the dark around tone 80
     * (7:1 on black, a pastel of the seed); its own label reads on it like normal text (AA).
     */
    const val PRIMARY_MIN_LIGHT = 5.5
    const val PRIMARY_MIN_DARK = 7.0
    const val ON_PRIMARY_MIN = 4.5

    /** The surface threshold the accent must reach in this mode. */
    fun primaryMin(dark: Boolean): Double = if (dark) PRIMARY_MIN_DARK else PRIMARY_MIN_LIGHT
    /** Chips and hint surfaces carry text, so the accent on its tinted container needs AA too. */
    const val CONTAINER_MIN = 4.5

    /** [customSeed] is used for [Accent.CUSTOM] only (spec 11.4 §3: computed from the stored hue). */
    fun tokens(accent: Accent, dark: Boolean, customSeed: Long = Accent.CUSTOM.seedArgb): AccentTokens {
        if (accent == Accent.MONO) {
            return if (dark) AccentTokens(MonoColors.DarkPrimary, MonoColors.DarkOnPrimary, MonoColors.DarkSurfaceContainer, MonoColors.DarkOnSurface)
            else AccentTokens(MonoColors.LightPrimary, MonoColors.LightOnPrimary, MonoColors.LightSurfaceContainer, MonoColors.LightOnSurface)
        }
        val surface = if (dark) MonoColors.DarkSurface else MonoColors.LightSurface
        val seed = if (accent == Accent.CUSTOM) customSeed else accent.seedArgb
        // primary colours every TextButton label, icon tint and link, so it must read on the surface: a seed
        // that already does stays raw (neon green on black), otherwise it is mixed towards black on the light
        // surface (cypherpunk green ends up a deep green) or towards white on the dark one (a dark purple lifts)
        val towards = if (dark) 0xFFFFFFFF else 0xFF000000
        var primary = seed
        var mixed = 0.0
        // ...and its own label (white or black, whichever is better) must read on it too: a mid-tone lifted
        // purple satisfies neither until it is lifted a little further
        val primaryMin = primaryMin(dark)
        while ((Contrast.ratio(primary, surface) < primaryMin || Contrast.ratio(primary, Contrast.onColorFor(primary)) < ON_PRIMARY_MIN) && mixed < 1.0) {
            mixed += 0.05
            primary = blend(towards, seed, mixed)
        }
        val container = blend(seed, surface, 0.12)
        // on the tinted container the accent itself must read; in the dark it is lifted towards white, in the
        // light towards black — starting at the brand-looking share and adding more until WCAG 4.5:1 holds
        // (a neon seed like cypherpunk green needs a lot more black than a muted one)
        val mix = if (dark) 0xFFFFFFFF else 0xFF000000
        var share = if (dark) 0.35 else 0.25
        var onContainer = blend(mix, seed, share)
        while (Contrast.ratio(container, onContainer) < CONTAINER_MIN && share < 1.0) {
            share += 0.05
            onContainer = blend(mix, seed, share)
        }
        return AccentTokens(primary, Contrast.onColorFor(primary), container, onContainer)
    }

    /** Alpha-composites [fg] with [alpha] over the opaque [bg]; channels rounded to the nearest integer. */
    fun blend(fg: Long, bg: Long, alpha: Double): Long {
        fun ch(shift: Int): Long {
            val f = ((fg shr shift) and 0xFF).toDouble()
            val b = ((bg shr shift) and 0xFF).toDouble()
            return (f * alpha + b * (1 - alpha)).roundToInt().coerceIn(0, 255).toLong()
        }
        return 0xFF000000 or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
