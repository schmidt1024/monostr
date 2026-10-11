package com.monostr.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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

/** Spec 4: monochrome base, accent only on the primary tokens, flat shapes, Inter/JetBrains Mono. */
@Composable
fun MonostrTheme(mode: ThemeMode, accent: Accent, customHue: Float = AccentSeed.DEFAULT_HUE, content: @Composable () -> Unit) {
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
    val black = mode == ThemeMode.BLACK
    val a = AccentPalette.tokens(accent, dark, AccentSeed.fromHue(customHue), black)
    // the dark scheme paints on the brand grey, or on pure black for OLED
    val ds = MonoColors.darkSurface(black)
    val dc = MonoColors.darkSurfaceContainer(black)
    val scheme = if (dark) {
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
        lightColorScheme(
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
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = MonostrTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(20.dp)),
        content = content,
    )
}
