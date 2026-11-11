package com.monostr.app.ui.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.ui.common.TorSettingsRow
import com.monostr.app.ui.settings.SettingsCard
import com.monostr.app.ui.settings.SettingsCardDivider
import com.monostr.app.ui.settings.SettingsGroup
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsViewModel
import com.monostr.app.ui.settings.settingAnchor

/**
 * Tor & privacy: the Tor card first (deep links and the Tor return land here; its title names it, so no label above it),
 * then what the app tells other parties, one card per section (round 2).
 */
@Composable
fun PrivacyPage(vm: SettingsViewModel, focus: String?, onBack: () -> Unit, onMuted: () -> Unit) {
    SettingsPageScaffold(SettingsPage.PRIVACY, focus, onBack, vm) {
        SettingsCard(Modifier.settingAnchor("settings-tor")) { TorSettingsRow() }
        SettingsGroup(stringResource(R.string.settings_label_links)) {
            val askLinks by vm.askLinks.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_ask_links), stringResource(R.string.settings_ask_links_hint), askLinks, vm::setAskLinks, "settings-ask-links")
            SettingsCardDivider()
            val previews by vm.linkPreviews.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_link_previews), stringResource(R.string.settings_link_previews_hint), previews, vm::setLinkPreviews, "settings-link-previews")
        }
        SettingsGroup(stringResource(R.string.settings_label_visibility)) {
            val clientTag by vm.clientTag.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_client_tag), stringResource(R.string.settings_client_tag_hint), clientTag, vm::setClientTag, "settings-client-tag")
        }
        SettingsGroup(stringResource(R.string.settings_label_people)) {
            // round 2 (UX P2-1): a navigation row, not a grey outlined button that looks disabled
            val muted by vm.mutedCount.collectAsStateWithLifecycle()
            SettingsLinkRow(stringResource(R.string.mute_settings_title), muted.takeIf { it > 0 }?.toString(), "settings-muted", anchor = "settings-muted", onClick = onMuted)
        }
    }
}
