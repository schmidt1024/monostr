package com.monostr.app.ui.common

import java.text.DecimalFormatSymbols
import java.util.Locale

/** Spec 4.2: 999, 1.2k (one decimal below 10k), 12k, 3.4M; decimal separator per locale. */
fun formatCount(n: Long, locale: Locale): String {
    if (n < 1000) return n.toString()
    val sep = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    fun scaled(value: Long, unit: Char): String {
        val tenths = value / 100 // value in tenths of the unit
        val whole = tenths / 10
        val frac = tenths % 10
        return if (whole >= 10 || frac == 0L) "$whole$unit" else "$whole$sep$frac$unit"
    }
    return if (n < 1_000_000) scaled(n, 'k') else scaled(n / 1000, 'M')
}
