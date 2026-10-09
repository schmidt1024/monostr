package com.monostr.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.monostr.app.R
import com.monostr.app.data.PrefsRelayStore

/** True when [relays] are the app's default relays, and for the empty list the screen holds before the store answered. */
fun isDefaultRelayList(relays: List<String>, defaults: List<String> = PrefsRelayStore.DEFAULT_RELAYS): Boolean =
    relays.isEmpty() || relays.toSet() == defaults.toSet()

/** What [RelayListActions] is asking the user to confirm. */
private enum class RelayListAction { PUBLISH, ADOPT, RESET }

/**
 * The actions on the whole relay list, side by side so that their directions cannot be mixed up:
 * publish the app's relays as the NIP-65 list, replace the app's relays by the published list,
 * and go back to the default relays ([isDefault]: nothing to go back to). Each replaces a list
 * for good and therefore asks first.
 */
@Composable
fun RelayListActions(isDefault: Boolean, busy: Boolean, onPublish: () -> Unit, onAdopt: () -> Unit, onReset: () -> Unit) {
    var asking by rememberSaveable { mutableStateOf<RelayListAction?>(null) }
    OutlinedButton(onClick = { asking = RelayListAction.PUBLISH }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-publish-relays")) {
        Text(stringResource(R.string.settings_publish_relays))
    }
    Text(stringResource(R.string.settings_publish_relays_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = { asking = RelayListAction.ADOPT }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-adopt-relays")) {
        Text(stringResource(R.string.settings_adopt_relays))
    }
    if (!isDefault) {
        OutlinedButton(onClick = { asking = RelayListAction.RESET }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settings-reset-relays")) {
            Text(stringResource(R.string.settings_reset_relays))
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag(confirmTag)) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("settings-confirm-cancel")) { Text(stringResource(R.string.action_cancel)) } },
    )
}
