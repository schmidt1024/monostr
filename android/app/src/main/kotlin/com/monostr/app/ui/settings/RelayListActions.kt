package com.monostr.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import com.monostr.app.ui.theme.SettingsIcons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.monostr.app.ui.theme.Spacing
import com.monostr.app.R
import com.monostr.app.data.PrefsRelayStore
import com.monostr.nostr.model.RelayMode

/** True when [relays] are the app's default relays, and for the empty list the screen holds before the store answered. */
fun isDefaultRelayList(
    relays: List<String>,
    defaults: List<String> = PrefsRelayStore.DEFAULT_RELAYS,
    /** Stored read and write modes: the reset also clears them, so the default list with modes can still be reset. */
    modes: Map<String, RelayMode> = emptyMap(),
): Boolean = relays.isEmpty() || (relays.toSet() == defaults.toSet() && modes.isEmpty())

/** What [RelayListActions] is asking the user to confirm. */
private enum class RelayListAction { PUBLISH, ADOPT, RESET }

/**
 * The actions on the whole relay list (UX P2-3): publish the app's relays as the NIP-65 list (the one primary, filled
 * action), take over the published list, and go back to the default relays ([isDefault]: nothing to go back to). The card
 * title names NIP-65, so the buttons are short; each replaces a list for good and therefore asks first, with the full text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RelayListActions(isDefault: Boolean, busy: Boolean, onPublish: () -> Unit, onAdopt: () -> Unit, onReset: () -> Unit) {
    var asking by rememberSaveable { mutableStateOf<RelayListAction?>(null) }
    // a filled button: the theme maps the tonal container to the card colour, so a tonal one read as plain text
    Button(onClick = { asking = RelayListAction.PUBLISH }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-publish-relays"), shape = MaterialTheme.shapes.medium) {
        Text(stringResource(R.string.settings_publish_list))
    }
    FlowRow {
        TextButton(onClick = { asking = RelayListAction.ADOPT }, enabled = !busy, modifier = Modifier.testTag("settings-adopt-relays")) {
            Text(stringResource(R.string.settings_use_published_list))
        }
        if (!isDefault) {
            TextButton(onClick = { asking = RelayListAction.RESET }, enabled = !busy, modifier = Modifier.testTag("settings-reset-relays")) {
                Text(stringResource(R.string.settings_reset_relays))
            }
        }
    }
    when (asking) {
        RelayListAction.PUBLISH -> ConfirmRelayListAction(
            R.string.settings_publish_relays, R.string.settings_publish_relays_hint, R.string.action_send, "settings-publish-relays-confirm",
            onConfirm = { asking = null; onPublish() }, onDismiss = { asking = null },
        )
        RelayListAction.ADOPT -> ConfirmRelayListAction(
            R.string.settings_adopt_relays, R.string.settings_adopt_relays_hint, R.string.action_replace, "settings-adopt-relays-confirm",
            onConfirm = { asking = null; onAdopt() }, onDismiss = { asking = null },
        )
        RelayListAction.RESET -> ConfirmRelayListAction(
            R.string.settings_reset_relays, R.string.settings_reset_relays_hint, R.string.action_replace, "settings-reset-relays-confirm",
            onConfirm = { asking = null; onReset() }, onDismiss = { asking = null },
        )
        null -> Unit
    }
}

@Composable
private fun ConfirmRelayListAction(title: Int, text: Int, confirm: Int, confirmTag: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SettingsConfirmDialog(stringResource(title), stringResource(text), stringResource(confirm), confirmTag, onConfirm, onDismiss)
}

/**
 * 0.24.3: says how the app's relays differ from the published NIP-65 list, above the button that publishes them. Round 2
 * (UX P2-3): a normal state, so neutral text with an info icon, not error red.
 */
@Composable
fun RelayListDiffHint(diff: RelayListDiff) {
    val lines = buildList {
        if (diff.noneFound) add(stringResource(R.string.relays_none_found))
        if (diff.unpublished > 0) add(pluralStringResource(R.plurals.relays_unpublished, diff.unpublished, diff.unpublished))
        if (diff.missing > 0) add(pluralStringResource(R.plurals.relays_missing_here, diff.missing, diff.missing))
    }
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
        Icon(SettingsIcons.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.m))
        Text(lines.joinToString(" "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("settings-relay-diff"))
    }
}
