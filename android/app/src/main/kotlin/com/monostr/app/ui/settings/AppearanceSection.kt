package com.monostr.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.app.data.Accent
import com.monostr.app.data.ThemeMode
import com.monostr.app.ui.theme.isDark
import com.monostr.app.ui.theme.AccentPalette
import com.monostr.app.ui.theme.AccentSeed

/** Spec 5: appearance controls, as simple as possible — a segmented mode row and the accents as a list. */
@Composable
fun AppearanceSection(mode: ThemeMode, accent: Accent, customHue: Float, onMode: (ThemeMode) -> Unit, onAccent: (Accent) -> Unit, onHue: (Float) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val modes = listOf(ThemeMode.SYSTEM to R.string.settings_theme_system, ThemeMode.LIGHT to R.string.settings_theme_light, ThemeMode.DARK to R.string.settings_theme_dark, ThemeMode.BLACK to R.string.settings_theme_black)
            modes.forEachIndexed { i, (m, label) ->
                SegmentedButton(
                    selected = mode == m, onClick = { onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                    modifier = Modifier.testTag("appearance-mode-${m.name.lowercase()}"),
                ) { Text(stringResource(label)) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        // the swatches show the accent as the active mode really paints it, not the raw seed (2026-10-08)
        val dark = isDark(mode)
        val customSeed = AccentSeed.fromHue(customHue)
        // five choices as a list, one row each (2026-10-30, was a row of swatches): Monero orange first as the default
        Column(Modifier.fillMaxWidth()) {
            PICKER_ORDER.forEach { a ->
                val tokens = AccentPalette.tokens(a, dark, customSeed, black = mode == ThemeMode.BLACK)
                val name = stringResource(accentName(a))
                val selected = a == accent
                val description = if (selected) stringResource(R.string.settings_accent_selected, name) else name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.RadioButton) { onAccent(a) }
                        .semantics(mergeDescendants = true) { contentDescription = description; this.selected = selected }
                        .testTag("appearance-accent-${a.name.lowercase()}")
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .then(if (a == Accent.MONO) Modifier.background(Brush.horizontalGradient(0f to Color.Black, 0.5f to Color.Black, 0.5f to Color.White, 1f to Color.White)) else Modifier.background(Color(tokens.primary)))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (accent == Accent.CUSTOM) {
            // spec 11.4 §3: a hue slider, saturation and value fixed; the swatch shows the mixed colour of the active mode
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_accent_hue), style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // the thumb follows the finger from local state; the store is written once, on release
                var dragging by remember(customHue) { mutableStateOf(customHue) }
                Slider(value = dragging, onValueChange = { dragging = it }, onValueChangeFinished = { onHue(dragging) }, valueRange = 0f..359f, modifier = Modifier.weight(1f).testTag("appearance-hue"))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(AccentPalette.tokens(Accent.CUSTOM, dark, AccentSeed.fromHue(dragging)).primary)))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.settings_language_text), style = MaterialTheme.typography.bodySmall)
        if (Build.VERSION.SDK_INT >= 33) {
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    } catch (e: ActivityNotFoundException) {
                        // Some OEM builds lack the per-app language screen; nothing else to offer.
                    }
                },
                modifier = Modifier.testTag("appearance-language"),
            ) { Text(stringResource(R.string.settings_language_button)) }
        }
    }
}

private val PICKER_ORDER = listOf(Accent.ORANGE, Accent.CHAMPAGNE, Accent.BLUE, Accent.MONO, Accent.CUSTOM)

private fun accentName(a: Accent): Int = when (a) {
    Accent.MONO -> R.string.settings_accent_mono
    Accent.ORANGE -> R.string.settings_accent_orange
    Accent.CHAMPAGNE -> R.string.settings_accent_champagne
    Accent.BLUE -> R.string.settings_accent_blue
    Accent.CUSTOM -> R.string.settings_accent_custom
}
