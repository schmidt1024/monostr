package com.monostr.app.ui.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.ui.settings.SettingsCard
import com.monostr.app.ui.settings.SettingsCardDivider
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsViewModel

/** Notifications: the two message-preview switches the DM notifier reads, in one card (sender first: less sensitive). */
@Composable
fun NotificationsPage(vm: SettingsViewModel, focus: String?, onBack: () -> Unit) {
    SettingsPageScaffold(SettingsPage.NOTIFICATIONS, focus, onBack, vm) {
        val dmPreviewName by vm.dmPreviewName.collectAsStateWithLifecycle()
        val dmPreviewText by vm.dmPreviewText.collectAsStateWithLifecycle()
        SettingsCard {
            SettingSwitch(stringResource(R.string.settings_dm_preview_name), "", dmPreviewName, vm::setDmPreviewName, "settings-dm-preview-name")
            SettingsCardDivider()
            SettingSwitch(stringResource(R.string.settings_dm_preview_text), "", dmPreviewText, vm::setDmPreviewText, "settings-dm-preview-text")
        }
    }
}
