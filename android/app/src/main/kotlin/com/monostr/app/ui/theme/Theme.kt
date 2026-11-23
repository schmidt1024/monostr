package com.monostr.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.monostr.app.data.Accent
import com.monostr.app.data.ThemeMode

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.BLACK -> true
}

private fun c(argb: Long) = Color(argb)

/**
 * Corner radii (spec 2026-11-10 §5): call sites use MaterialTheme.shapes, never a hand-built radius. Squircle corners
 * since 2026-11-10: buttons and fields take [Shapes.medium], cards [Shapes.large].
 */
val MonostrShapes = Shapes(
    extraSmall = SquircleShape(4.dp),
    small = SquircleShape(8.dp),
    medium = SquircleShape(12.dp),
    large = SquircleShape(16.dp),
    extraLarge = SquircleShape(24.dp),
)

/**
 * Plain rounded corners for clips on the feed's scroll path: pictures (note media, thumbnails, link-preview images, quoted
 * media, attachments, banners), the tap-effect bounds of the note actions and the content clip of cards (their border and
 * background stay squircles). A clip to a squircle path costs GPU time every frame, a rounded rectangle clips on the fast
 * path. Pixel 7a, 2026-11-10, 30 s feed scroll: squircle clips everywhere 7-8 ms median frame (was 5), 2x GPU time; with
 * these plain clips back to 5 ms. Squircle pictures cost another 1-2 ms at the 90th percentile, so pictures stay plain
 * until they are drawn as a shader-filled squircle instead of a clip (backlog). Owner: performance before design.
 */
object PlainShapes {
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(16.dp)
    val extraLarge = RoundedCornerShape(24.dp)
}

/** What the app theme was made from: the reader re-themes itself from it (backlog 51). */
data class ThemeChoice(val mode: ThemeMode, val accent: Accent, val customHue: Float, val readerDark: Boolean? = null, val readerSepia: Boolean = false, val readerTextStep: Int = 1)

// a reader-pref change (sheet tap) recomposes the app subtree reading this: only on a user tap, never on the feed path
val LocalThemeChoice = staticCompositionLocalOf { ThemeChoice(ThemeMode.SYSTEM, Accent.MONO, AccentSeed.DEFAULT_HUE) }

/** Backlog 102: the article reader's sepia paper, used in place of the light surfaces when the reader is light. */
object ReaderSepia {
    val paper = Color(0xFFF4ECD8)
    val ink = Color(0xFF3B2F25)
    val inkMuted = Color(0xFF6B5A48)
}

/** The Material colour scheme of the app theme; [sepia] repaints the light scheme only (backlog 102). */
fun monostrColorScheme(dark: Boolean, black: Boolean, accent: Accent, customHue: Float, sepia: Boolean = false): ColorScheme {
    val a = AccentPalette.tokens(accent, dark, AccentSeed.fromHue(customHue), black)
    // the dark scheme paints on the brand grey, or on pure black for OLED
    val ds = MonoColors.darkSurface(black)
    val dc = MonoColors.darkSurfaceContainer(black)
    return if (dark) {
        darkColorScheme(
            background = c(ds), surface = c(ds),
            surfaceContainer = c(dc), surfaceContainerLow = c(dc),
            surfaceContainerHigh = c(dc), surfaceContainerHighest = c(dc),
            surfaceVariant = c(dc),
            onBackground = c(MonoColors.DarkOnSurface), onSurface = c(MonoColors.DarkOnSurface), onSurfaceVariant = c(MonoColors.DarkOnSurfaceVariant),
            outline = c(MonoColors.DarkOnSurfaceVariant), outlineVariant = c(MonoColors.darkOutlineVariant(black)),
            primary = c(a.primary), onPrimary = c(a.onPrimary), primaryContainer = c(a.primaryContainer), onPrimaryContainer = c(a.onPrimaryContainer),
            secondary = c(MonoColors.DarkOnSurfaceVariant), onSecondary = c(ds),
            secondaryContainer = c(dc), onSecondaryContainer = c(MonoColors.DarkOnSurface),
            tertiary = c(MonoColors.DarkOnSurfaceVariant), onTertiary = c(ds),
            tertiaryContainer = c(dc), onTertiaryContainer = c(MonoColors.DarkOnSurface),
            inverseSurface = c(MonoColors.DarkOnSurface), inverseOnSurface = c(ds),
            inversePrimary = c(if (accent == Accent.MONO) ds else a.primary),
            surfaceBright = c(dc), surfaceDim = c(ds), surfaceContainerLowest = c(ds),
            scrim = Color.Black,
            surfaceTint = c(ds),
            primaryFixed = c(a.primaryContainer), primaryFixedDim = c(a.primary), onPrimaryFixed = c(a.onPrimaryContainer), onPrimaryFixedVariant = c(a.onPrimaryContainer),
            secondaryFixed = c(dc), secondaryFixedDim = c(dc), onSecondaryFixed = c(MonoColors.DarkOnSurface), onSecondaryFixedVariant = c(MonoColors.DarkOnSurface),
            tertiaryFixed = c(dc), tertiaryFixedDim = c(dc), onTertiaryFixed = c(MonoColors.DarkOnSurface), onTertiaryFixedVariant = c(MonoColors.DarkOnSurface),
        )
    } else {
        val light = lightColorScheme(
            background = c(MonoColors.LightSurface), surface = c(MonoColors.LightSurface),
            surfaceContainer = c(MonoColors.LightSurfaceContainer), surfaceContainerLow = c(MonoColors.LightSurfaceContainer),
            surfaceContainerHigh = c(MonoColors.LightSurfaceContainer), surfaceContainerHighest = c(MonoColors.LightSurfaceContainer),
            surfaceVariant = c(MonoColors.LightSurfaceContainer),
            onBackground = c(MonoColors.LightOnSurface), onSurface = c(MonoColors.LightOnSurface), onSurfaceVariant = c(MonoColors.LightOnSurfaceVariant),
            outline = c(MonoColors.LightOnSurfaceVariant), outlineVariant = c(MonoColors.LightOutlineVariant),
            primary = c(a.primary), onPrimary = c(a.onPrimary), primaryContainer = c(a.primaryContainer), onPrimaryContainer = c(a.onPrimaryContainer),
            secondary = c(MonoColors.LightOnSurfaceVariant), onSecondary = c(MonoColors.LightSurface),
            secondaryContainer = c(MonoColors.LightSurfaceContainer), onSecondaryContainer = c(MonoColors.LightOnSurface),
            tertiary = c(MonoColors.LightOnSurfaceVariant), onTertiary = c(MonoColors.LightSurface),
            tertiaryContainer = c(MonoColors.LightSurfaceContainer), onTertiaryContainer = c(MonoColors.LightOnSurface),
            inverseSurface = c(MonoColors.LightOnSurface), inverseOnSurface = c(MonoColors.LightSurface),
            inversePrimary = c(if (accent == Accent.MONO) MonoColors.LightSurface else a.primary),
            surfaceBright = c(MonoColors.LightSurface), surfaceDim = c(MonoColors.LightSurfaceContainer), surfaceContainerLowest = c(MonoColors.LightSurface),
            scrim = Color.Black,
            surfaceTint = c(MonoColors.LightSurface),
            primaryFixed = c(a.primaryContainer), primaryFixedDim = c(a.primary), onPrimaryFixed = c(a.onPrimaryContainer), onPrimaryFixedVariant = c(a.onPrimaryContainer),
            secondaryFixed = c(MonoColors.LightSurfaceContainer), secondaryFixedDim = c(MonoColors.LightSurfaceContainer), onSecondaryFixed = c(MonoColors.LightOnSurface), onSecondaryFixedVariant = c(MonoColors.LightOnSurface),
            tertiaryFixed = c(MonoColors.LightSurfaceContainer), tertiaryFixedDim = c(MonoColors.LightSurfaceContainer), onTertiaryFixed = c(MonoColors.LightOnSurface), onTertiaryFixedVariant = c(MonoColors.LightOnSurface),
        )
        if (sepia) light.copy(
            background = ReaderSepia.paper, surface = ReaderSepia.paper,
            surfaceContainer = ReaderSepia.paper, surfaceContainerLow = ReaderSepia.paper,
            surfaceContainerHigh = ReaderSepia.paper, surfaceContainerHighest = ReaderSepia.paper,
            surfaceVariant = ReaderSepia.paper, surfaceBright = ReaderSepia.paper, surfaceDim = ReaderSepia.paper, surfaceContainerLowest = ReaderSepia.paper,
            onBackground = ReaderSepia.ink, onSurface = ReaderSepia.ink, onSurfaceVariant = ReaderSepia.inkMuted,
        ) else light
    }
}

/** Spec 4: monochrome base, accent only on the primary tokens, flat shapes, Inter/JetBrains Mono. */
@Composable
fun MonostrTheme(mode: ThemeMode, accent: Accent, customHue: Float = AccentSeed.DEFAULT_HUE, sepia: Boolean = false, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    // Spec 4.3: status/navigation bar icons follow the in-app mode, not only the device night mode.
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    val scheme = monostrColorScheme(dark, mode == ThemeMode.BLACK, accent, customHue, sepia)
    MaterialTheme(
        colorScheme = scheme,
        typography = MonostrTypography,
        shapes = MonostrShapes,
        content = content,
    )
}
