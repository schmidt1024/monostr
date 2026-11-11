package com.monostr.app.ui.settings.pages

import com.monostr.app.ui.settings.settingAnchor
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsViewModel
import com.monostr.app.ui.theme.Spacing

/** Search relays (NIP-50): what goes to them first (privacy rule), the list card, the reset to the defaults last. */
@Composable
fun SearchRelaysPage(vm: SettingsViewModel, focus: String?, onBack: () -> Unit) {
    SettingsPageScaffold(SettingsPage.SEARCH_RELAYS, focus, onBack, vm) {
        val searchState by vm.search.state.collectAsStateWithLifecycle()
        Text(
            stringResource(R.string.settings_search_relays_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.m),
        )
        RelayListEditor(
            relays = searchState.relays, live = null, error = searchState.error,
            onAdd = vm.search::add, onRemove = { url, done -> vm.search.remove(url, done) }, tagPrefix = "settings-search-relay", onUndo = { url, i, _ -> vm.search.restore(url, i) },
        )
        Spacer(Modifier.height(Spacing.s))
        TextButton(onClick = vm::resetSearchRelays, modifier = Modifier.settingAnchor("settings-search-relays-reset").testTag("settings-search-relays-reset")) { Text(stringResource(R.string.settings_search_relays_reset)) }
    }
}
