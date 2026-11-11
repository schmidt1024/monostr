package com.monostr.app.ui.settings.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.ui.settings.AccentList
import com.monostr.app.ui.settings.AppLanguageRow
import com.monostr.app.ui.settings.SettingsGroup
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsViewModel
import com.monostr.app.ui.settings.ThemeModeControl
import com.monostr.app.ui.settings.settingAnchor
import com.monostr.app.ui.theme.Spacing

/** Appearance (round 2): theme, accent colour and the app language, each its own card under its label. */
@Composable
fun AppearancePage(vm: SettingsViewModel, focus: String?, onBack: () -> Unit) {
    SettingsPageScaffold(SettingsPage.APPEARANCE, focus, onBack, vm) {
        val mode by vm.themeMode.collectAsStateWithLifecycle()
        val accent by vm.accent.collectAsStateWithLifecycle()
        val customHue by vm.customHue.collectAsStateWithLifecycle()
        SettingsGroup(stringResource(R.string.settings_theme_mode), first = true) {
            Box(Modifier.fillMaxWidth().padding(Spacing.l)) { ThemeModeControl(mode, vm::setThemeMode) }
        }
        SettingsGroup(stringResource(R.string.settings_accent), modifier = Modifier.settingAnchor("settings-accent")) {
            AccentList(mode, accent, customHue, vm::setAccent, vm::setCustomHue, rowPadding = Spacing.l)
        }
        SettingsGroup(stringResource(R.string.settings_language)) { AppLanguageRow() }
    }
}
