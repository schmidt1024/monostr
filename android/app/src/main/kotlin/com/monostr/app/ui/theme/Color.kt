package com.monostr.app.ui.theme

/** Spec 4.3 monochrome tokens as packed ARGB. */
object MonoColors {
    const val LightSurface = 0xFFFFFFFF
    const val LightSurfaceContainer = 0xFFF4F4F4
    const val LightOnSurface = 0xFF111111
    const val LightOnSurfaceVariant = 0xFF6B6B6B
    const val LightOutlineVariant = 0xFFE4E4E4
    const val LightPrimary = 0xFF111111
    const val LightOnPrimary = 0xFFFFFFFF

    const val DarkSurface = 0xFF000000
    const val DarkSurfaceContainer = 0xFF141414
    const val DarkOnSurface = 0xFFF2F2F2
    const val DarkOnSurfaceVariant = 0xFF9A9A9A
    const val DarkOutlineVariant = 0xFF262626
    const val DarkPrimary = 0xFFF2F2F2
    const val DarkOnPrimary = 0xFF111111
}

/** Status colours (relay connectivity) that stay green/red whatever accent is chosen; each reads on its surface (WCAG 4.5:1). */
object StatusColors {
    const val LightOk = 0xFF1B7F3B
    const val DarkOk = 0xFF3DDC84
    const val LightBad = 0xFFC62828
    const val DarkBad = 0xFFEF5350
}
