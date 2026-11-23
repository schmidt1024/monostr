package com.monostr.app.ui.article

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monostr.app.R
import com.monostr.app.data.ThemeMode
import com.monostr.app.ui.theme.MonostrFonts
import com.monostr.app.ui.theme.ReaderSepia
import com.monostr.app.ui.theme.Spacing
import com.monostr.app.ui.theme.ThemeChoice
import com.monostr.app.ui.theme.monostrColorScheme

/**
 * Backlog 102: the reader's «Aa» sheet, composed only while open. Colour swatches (white, sepia, dark) and the text size
 * steps; both apply at once with the article visible behind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderAppearanceSheet(
    app: ThemeChoice,
    dark: Boolean,
    onSwatch: (ReaderSwatch) -> Unit,
    onTextStep: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    com.monostr.app.ui.common.GuardedModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("reader-appearance-sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(bottom = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            ReaderAppearanceControls(app, dark, onSwatch, onTextStep)
        }
    }
}

/** The swatch row and the text size row, shared by the reader's «Aa» sheet and Settings › Appearance › Article view. */
@Composable
fun ReaderAppearanceControls(app: ThemeChoice, dark: Boolean, onSwatch: (ReaderSwatch) -> Unit, onTextStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(stringResource(R.string.reader_colour), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val selected = ReaderSwatch.of(dark, app.readerSepia)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            val black = app.mode == ThemeMode.BLACK
            for (swatch in ReaderSwatch.entries) {
                val (paper, ink) = swatchColours(swatch, app)
                val label = stringResource(when (swatch) {
                    ReaderSwatch.WHITE -> R.string.reader_swatch_white
                    ReaderSwatch.SEPIA -> R.string.reader_swatch_sepia
                    ReaderSwatch.DARK -> if (black) R.string.reader_swatch_black else R.string.reader_swatch_dark
                })
                Swatch(label, paper, ink, swatch == selected, "reader-swatch-${swatch.name.lowercase()}", Modifier.weight(1f)) { onSwatch(swatch) }
            }
        }
        Text(stringResource(R.string.reader_text_size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReaderTextSizeRow(app.readerTextStep, onTextStep)
    }
}

/** Paper and ink of a swatch: white and dark from the app's own schemes (black for the BLACK mode), sepia from [ReaderSepia]. */
private fun swatchColours(swatch: ReaderSwatch, app: ThemeChoice): Pair<Color, Color> = when (swatch) {
    ReaderSwatch.SEPIA -> ReaderSepia.paper to ReaderSepia.ink
    ReaderSwatch.WHITE -> monostrColorScheme(false, false, app.accent, app.customHue).let { it.background to it.onBackground }
    ReaderSwatch.DARK -> monostrColorScheme(true, app.mode == ThemeMode.BLACK, app.accent, app.customHue).let { it.background to it.onBackground }
}

@Composable
private fun Swatch(label: String, paper: Color, ink: Color, selected: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        val shape = MaterialTheme.shapes.large
        Box(
            Modifier.fillMaxWidth().height(72.dp).clip(shape).background(paper)
                .border(if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", color = ink, fontFamily = MonostrFonts.serif, fontSize = 28.sp)
        }
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/** «A−  ●●●●●  A+»: the dots are the five steps (filled up to the current one, tappable); the buttons stop at the ends. */
@Composable
fun ReaderTextSizeRow(step: Int, onStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    val current = ReaderText.clamp(step)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        val smaller = stringResource(R.string.reader_text_smaller)
        TextButton(onClick = { onStep(current - 1) }, enabled = current > 0, modifier = Modifier.testTag("reader-text-smaller").semantics { contentDescription = smaller }) {
            Text("A−", fontSize = 16.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0..ReaderText.LAST) {
                val on = i <= current
                // 44 dp tap area around a 14 dp dot
                val desc = stringResource(R.string.reader_text_step, i + 1, ReaderText.SCALES.size)
                Box(Modifier.size(44.dp).clickable(role = Role.Button) { onStep(i) }.testTag("reader-text-step-$i").semantics { contentDescription = desc; selected = i == current }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                }
            }
        }
        val larger = stringResource(R.string.reader_text_larger)
        TextButton(onClick = { onStep(current + 1) }, enabled = current < ReaderText.LAST, modifier = Modifier.testTag("reader-text-larger").semantics { contentDescription = larger }) {
            Text("A+", fontSize = 22.sp)
        }
    }
}
