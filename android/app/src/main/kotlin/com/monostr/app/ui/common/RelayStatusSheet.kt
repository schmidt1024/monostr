package com.monostr.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monostr.app.R
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayStatusSheet(relays: List<RelayInfo>, onManage: () -> Unit, onDismiss: () -> Unit) {
    GuardedModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("relay-sheet")) {
        val sorted = remember(relays) { relays.sortedWith(compareBy<RelayInfo> { it.state.order() }.thenBy { relayShownUrl(it.url) }) }
        val connected = relays.count { it.state == RelayState.CONNECTED }
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(stringResource(R.string.settings_relays), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_chip_relays_spoken, connected, relays.size),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val now = System.currentTimeMillis() / 1000
            for (info in sorted) {
                // no relay-seen file here: «never connected» is not known, so a lost relay is amber, red stays for one that rests
                val line = relayStatusLine(info, lastSeen = null, now = now).copy(neverSeen = false)
                val tone = relayTone(info, line).color()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("relay-sheet-row")) {
                    Box(Modifier.size(10.dp).background(tone, CircleShape))
                    Spacer(Modifier.width(Spacing.m))
                    Column(Modifier.weight(1f)) {
                        Text(relayShownUrl(info.url), style = MaterialTheme.typography.bodyLarge)
                        Text(line.state.asString(), style = MaterialTheme.typography.bodySmall, color = tone)
                    }
                }
            }
            OutlinedButton(onClick = onManage, shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("relay-sheet-manage")) {
                Text(stringResource(R.string.relay_sheet_manage))
            }
        }
    }
}

/** Connected first, then connecting, then the rest. */
private fun RelayState.order(): Int = when (this) {
    RelayState.CONNECTED -> 0
    RelayState.CONNECTING -> 1
    RelayState.DISCONNECTED -> 2
}
