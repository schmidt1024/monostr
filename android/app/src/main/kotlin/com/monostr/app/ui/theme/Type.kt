package com.monostr.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.monostr.app.R

object MonostrFonts {
    val sans: FontFamily = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
    )
    val mono: FontFamily = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal))
}

/** M3 scale in Inter: headlines semi-bold and slightly tightened, body regular (spec 4.2). */
val MonostrTypography: Typography = Typography().let { base ->
    fun sans(style: androidx.compose.ui.text.TextStyle, weight: FontWeight = FontWeight.Normal, tighten: Boolean = false, lineHeightFactor: Double) =
        style.copy(
            fontFamily = MonostrFonts.sans, fontWeight = weight,
            letterSpacing = if (tighten) (-0.02).em else style.letterSpacing,
            lineHeight = style.fontSize * lineHeightFactor,
        )
    // spec 4.2: line height 1.4-1.5 - display/headline (tighter, larger glyphs) at 1.4, title/body/label at 1.5
    base.copy(
        displayLarge = sans(base.displayLarge, FontWeight.SemiBold, tighten = true, lineHeightFactor = 1.4),
        displayMedium = sans(base.displayMedium, FontWeight.SemiBold, tighten = true, lineHeightFactor = 1.4),
        displaySmall = sans(base.displaySmall, FontWeight.SemiBold, tighten = true, lineHeightFactor = 1.4),
        headlineLarge = sans(base.headlineLarge, FontWeight.SemiBold, tighten = true, lineHeightFactor = 1.4),
        headlineMedium = sans(base.headlineMedium, FontWeight.SemiBold, tighten = true, lineHeightFactor = 1.4),
        headlineSmall = sans(base.headlineSmall, FontWeight.SemiBold, tighten = true, lineHeightFactor = 1.4),
        titleLarge = sans(base.titleLarge, FontWeight.Medium, lineHeightFactor = 1.5),
        titleMedium = sans(base.titleMedium, FontWeight.Medium, lineHeightFactor = 1.5),
        titleSmall = sans(base.titleSmall, FontWeight.Medium, lineHeightFactor = 1.5),
        bodyLarge = sans(base.bodyLarge, lineHeightFactor = 1.5),
        bodyMedium = sans(base.bodyMedium, lineHeightFactor = 1.5),
        bodySmall = sans(base.bodySmall, lineHeightFactor = 1.5),
        labelLarge = sans(base.labelLarge, FontWeight.Medium, lineHeightFactor = 1.5),
        labelMedium = sans(base.labelMedium, FontWeight.Medium, lineHeightFactor = 1.5),
        labelSmall = sans(base.labelSmall, FontWeight.Medium, lineHeightFactor = 1.5),
    )
}
