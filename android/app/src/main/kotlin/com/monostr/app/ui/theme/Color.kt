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

    // "Dark": the dark grey of the monostr banner and app icon background (user 2026-10-11)
    const val DarkSurface = 0xFF111113
    const val DarkSurfaceContainer = 0xFF1C1C20
    const val DarkOnSurface = 0xFFF2F2F2
    const val DarkOnSurfaceVariant = 0xFF9A9A9A
    const val DarkOutlineVariant = 0xFF2C2C31

    // "Black": pure black for OLED screens, with the same text colours
    const val BlackSurface = 0xFF000000
    const val BlackSurfaceContainer = 0xFF141414
    const val BlackOutlineVariant = 0xFF262626

    /** The surface, container and outline of a dark mode: black or the brand grey. */
    fun darkSurface(black: Boolean) = if (black) BlackSurface else DarkSurface
    fun darkSurfaceContainer(black: Boolean) = if (black) BlackSurfaceContainer else DarkSurfaceContainer
    fun darkOutlineVariant(black: Boolean) = if (black) BlackOutlineVariant else DarkOutlineVariant
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
