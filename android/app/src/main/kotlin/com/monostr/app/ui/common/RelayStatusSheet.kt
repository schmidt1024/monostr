package com.monostr.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.app.ui.settings.RelayStatusLine
import com.monostr.app.ui.settings.relayShownUrl
import com.monostr.app.ui.settings.relayStatusLine
import com.monostr.app.ui.settings.relayTone
import com.monostr.app.ui.settings.pages.color
import com.monostr.app.ui.theme.Spacing
import com.monostr.nostr.model.RelayInfo
import com.monostr.nostr.model.RelayState

/**
 * Owner 2026-11-12: a tap on the top bar's relay dot shows which relays are connected and which are not, with the same
 * dot colours and status words as the relay settings, and a way there. Reads the engine's two-second relay states only.
 * Owner 2026-11-12 (variant A): compact and technical, a header with «Manage», two groups, one line per relay (app font: monospace read as a break in style, owner).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayStatusSheet(relays: List<RelayInfo>, onManage: () -> Unit, onDismiss: () -> Unit) {
    GuardedModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("relay-sheet")) {
        val sorted = remember(relays) { relays.sortedWith(compareBy<RelayInfo> { it.state.order() }.thenBy { relayShownUrl(it.url) }) }
        val connected = relays.count { it.state == RelayState.CONNECTED }
        // Pixel 2026-11-12 (11 relays): a sheet taller than the screen reached under the status bar, took its inset, got
        // shorter, slid down, lost the inset and grew again, hopping up and down without end. Capped below the screen, the
        // list scrolls inside instead.
        val maxHeight = (LocalConfiguration.current.screenHeightDp * MAX_HEIGHT_FRACTION).dp
        val now = System.currentTimeMillis() / 1000
        val rows = sorted.map { info ->
            // no relay-seen file here: «never connected» is not known, so a lost relay is amber, red stays for one that rests
            val line = relayStatusLine(info, lastSeen = null, now = now).copy(neverSeen = false)
            RelayRow(info, line, relayTone(info, line).color())
        }
        val (up, other) = rows.partition { it.info.state == RelayState.CONNECTED }
        val torActive = LocalTorActive.current
        val subtitle = stringResource(R.string.settings_chip_relays_spoken, connected, relays.size).let {
            if (torActive) stringResource(R.string.relay_sheet_via_tor, it) else it
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.settings_relays), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onManage, modifier = Modifier.testTag("relay-sheet-manage")) {
                    Text(stringResource(R.string.relay_sheet_manage_short))
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RelayGroup(stringResource(R.string.relay_group_connected, up.size), up)
            RelayGroup(stringResource(R.string.relay_group_other, other.size), other)
        }
    }
}

private class RelayRow(val info: RelayInfo, val line: RelayStatusLine, val tone: Color)

/** One group of the sheet: a small caption and one single-line row per relay; nothing for an empty group. */
@Composable
private fun RelayGroup(title: String, rows: List<RelayRow>) {
    if (rows.isEmpty()) return
    Text(
        title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs),
    )
    rows.forEachIndexed { i, row ->
        if (i > 0) HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        val host = relayShownUrl(row.info.url)
        val long = row.line.state.asString()
        val short = when {
            row.info.state == RelayState.CONNECTED -> stringResource(R.string.relay_short_ok)
            row.info.state == RelayState.CONNECTING -> stringResource(R.string.relay_connecting)
            row.info.resting -> stringResource(R.string.relay_short_resting)
            else -> stringResource(R.string.relay_disconnected)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).clearAndSetSemantics { contentDescription = "$host, $long" }.testTag("relay-sheet-row"),
        ) {
            Box(Modifier.size(8.dp).background(row.tone, CircleShape))
            Spacer(Modifier.width(Spacing.m))
            Text(host, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Spacing.s))
            Text(short, style = MaterialTheme.typography.labelSmall, color = row.tone, maxLines = 1)
        }
    }
}

/** Owner 2026-11-12 : one relay is one line of about this height. */
private val ROW_HEIGHT = 30.dp

/** The share of the screen height the sheet may take at most. */
private const val MAX_HEIGHT_FRACTION = 0.8f

/** Connected first, then connecting, then the rest. */
private fun RelayState.order(): Int = when (this) {
    RelayState.CONNECTED -> 0
    RelayState.CONNECTING -> 1
    RelayState.DISCONNECTED -> 2
}
