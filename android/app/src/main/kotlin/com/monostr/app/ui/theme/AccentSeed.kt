package com.monostr.app.ui.theme

import kotlin.math.abs

/**
 * Spec 11.4 §3: the custom accent is a hue the user picks on a slider; saturation and value are fixed so every
 * choice is a usable, non-neon colour. The palette then mixes it towards the mode's text colour like the
 * fixed accents (5.5:1 in the light, 7:1 in the dark), so any hue reads in both modes.
 */
object AccentSeed {
    const val DEFAULT_HUE = 200f
    private const val S = 0.85f
    private const val V = 0.90f

    /** HSV([hue], 0.85, 0.90) as opaque ARGB; [hue] in degrees, any value is wrapped into 0..360. */
    fun fromHue(hue: Float): Long {
        val h = ((hue % 360f) + 360f) % 360f
        val c = V * S
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = V - c
        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
        return 0xFF000000 or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}
