package com.monostr.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.monostr.app.ui.theme.PlainShapes
import com.monostr.app.ui.theme.Spacing

/** Where the text of a row with a 24 dp leading icon starts inside a card: the divider under it starts there too. */
val SETTINGS_ICON_INSET: Dp = Spacing.l + 24.dp + Spacing.l

/**
 * One group of settings as a rounded card (round 2, owner decision 1: grouped like Android 16 settings). The background is
 * the theme's squircle; the content clip, which bounds the rows' press ripple at the corners, is a plain rounded rect so the
 * card costs no path clip per frame while a page scrolls (see [PlainShapes]). Rows inside bring their own 16 dp side padding;
 * put a [SettingsCardDivider] between them.
 */
@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // the caller's modifier goes inside the fill and the clip: a search anchor's flash (drawBehind) then paints over the card
    // colour, within the rounded corners, instead of under it where the fill would hide it (wave A review)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
            .clip(PlainShapes.large)
            .then(modifier),
        content = content,
    )
}

/** A thin line between two rows of a card, inset to where the row text starts (no full-width line). */
@Composable
fun SettingsCardDivider(inset: Dp = Spacing.l) {
    HorizontalDivider(Modifier.padding(start = inset), color = MaterialTheme.colorScheme.outlineVariant)
}

/** The grey label above a card: 24 dp above (none for the [first] on a page), 8 dp below, aligned with the rows' text. */
@Composable
fun SettingsGroupLabel(text: String, modifier: Modifier = Modifier, first: Boolean = false) {
    if (!first) Spacer(Modifier.height(Spacing.xl))
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = Spacing.l, bottom = Spacing.s).semantics { heading() },
    )
}

/** A label (when given) and its card: the unit every settings subpage is built from. */
@Composable
fun SettingsGroup(label: String?, modifier: Modifier = Modifier, first: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    if (label != null) SettingsGroupLabel(label, first = first) else if (!first) Spacer(Modifier.height(Spacing.m))
    SettingsCard(modifier, content)
}
