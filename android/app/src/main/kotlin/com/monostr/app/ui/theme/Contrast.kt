package com.monostr.app.ui.theme

import kotlin.math.pow

/** WCAG 2.x relative luminance and contrast ratio on packed ARGB values; pure Kotlin so it is unit-testable. */
object Contrast {
    private const val LIGHT_ON = 0xFFFFFFFF
    private const val DARK_ON = 0xFF111111

    fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val (hi, lo) = if (la >= lb) la to lb else lb to la
        return (hi + 0.05) / (lo + 0.05)
    }

    /** White or near-black, whichever reads better on [argb]. */
    fun onColorFor(argb: Long): Long = if (ratio(argb, LIGHT_ON) >= ratio(argb, DARK_ON)) LIGHT_ON else DARK_ON
}
