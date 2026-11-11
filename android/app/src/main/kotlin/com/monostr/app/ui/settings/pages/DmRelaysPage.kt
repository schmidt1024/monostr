package com.monostr.app.ui.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.monostr.app.ui.settings.SettingsCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.ui.common.GuardedAlertDialog
import com.monostr.app.ui.settings.DmInboxHints
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsUiState
import com.monostr.app.ui.settings.SettingsViewModel
import com.monostr.app.ui.theme.Spacing

/** Message (DM) relays: the inbox list (kind 10050) with the hints for a missing or single inbox relay. */
@Composable
fun DmRelaysPage(vm: SettingsViewModel, focus: String?, onBack: () -> Unit) {
    SettingsPageScaffold(SettingsPage.DM_RELAYS, focus, onBack, vm) {
        val dmState by vm.dm.state.collectAsStateWithLifecycle()
        val dmMissingDismissed by vm.dmMissingDismissed.collectAsStateWithLifecycle()
        val dmSecondDismissed by vm.dmSecondDismissed.collectAsStateWithLifecycle()
        DmRelaysSection(
            dmState = dmState, onAdd = vm.dm::add, onRemove = { url, done -> vm.dm.remove(url, done) }, onUndo = { url, i -> vm.dm.restore(url, i) },
            showMissing = !dmMissingDismissed && DmInboxHints.missing(dmState.relays),
            showSecond = !dmSecondDismissed && DmInboxHints.needsSecond(dmState.relays),
            onReplace = vm.dm::replace, onDismissHint = vm::dismissDmHint,
        )
    }
}

/** DM inbox relays (kind 10050) with the inbox hints; the notification-preview switches live on the Notifications page. */
@Composable
private fun DmRelaysSection(
    dmState: SettingsUiState,
    onAdd: (String) -> Boolean,
    onRemove: (String, () -> Unit) -> Unit,
    onUndo: (String, Int) -> Unit,
    showMissing: Boolean,
    showSecond: Boolean,
    onReplace: (String, String) -> Unit,
    onDismissHint: (String) -> Unit,
) {
    // round 2 (UX «Proposed order»): the list is what people come for; the tips and the explainer follow it
    RelayListEditor(
        relays = dmState.relays, live = null, error = dmState.error,
        onAdd = onAdd, onRemove = onRemove, tagPrefix = "settings-dm-relay", onUndo = { url, i, _ -> onUndo(url, i) },
    )
    if (showMissing) {
        var choose by remember { mutableStateOf(false) }
        InboxHintCard(
            text = stringResource(R.string.dm_relay_hint_missing), tag = "dm-hint-missing",
            onDismiss = { onDismissHint(DmInboxHints.HINT_MISSING) }, dismissTag = "dm-hint-dismiss-missing",
        ) {
            TextButton(
                onClick = { if (DmInboxHints.full(dmState.relays)) choose = true else onAdd(DmInboxHints.MONOSTR) },
                modifier = Modifier.testTag("dm-hint-add"),
            ) { Text(stringResource(R.string.dm_relay_hint_add)) }
        }
        if (choose) {
            GuardedAlertDialog(
                onDismissRequest = { choose = false },
                title = { Text(stringResource(R.string.dm_relay_hint_replace)) },
                text = {
                    Column {
                        dmState.relays.forEachIndexed { i, url ->
                            TextButton(onClick = { choose = false; onReplace(url, DmInboxHints.MONOSTR) }, modifier = Modifier.testTag("dm-hint-replace-$i")) { Text(url.removePrefix("wss://")) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { choose = false }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }
    if (showSecond) {
        InboxHintCard(
            text = stringResource(R.string.dm_relay_hint_second), tag = "dm-hint-second",
            onDismiss = { onDismissHint(DmInboxHints.HINT_SECOND) }, dismissTag = "dm-hint-dismiss-second",
        ) {
            DmInboxHints.suggestions(dmState.relays).forEachIndexed { i, url ->
                TextButton(onClick = { onAdd(url) }, modifier = Modifier.testTag("dm-hint-suggest-$i")) { Text(url.removePrefix("wss://")) }
            }
        }
    }
    Text(
        stringResource(R.string.settings_dm_relays_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.m),
    )
}

/** Spec 6: one inbox hint card with its actions and "Hide" (per account, once). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxHintCard(text: String, tag: String, onDismiss: () -> Unit, dismissTag: String, actions: @Composable () -> Unit) {
    Spacer(Modifier.height(Spacing.m))
    SettingsCard(Modifier.testTag(tag)) {
        Column(Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.m)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = Spacing.s))
            FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                actions()
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(dismissTag)) { Text(stringResource(R.string.dm_relay_hint_dismiss)) }
            }
        }
    }
}
