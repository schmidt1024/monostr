package com.monostr.app.ui.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsViewModel
import com.monostr.app.ui.settings.TranslationSection

/** Translation: what the server sees, then the service card (the top bar names the page). */
@Composable
fun TranslationPage(vm: SettingsViewModel, focus: String?, onBack: () -> Unit) {
    SettingsPageScaffold(SettingsPage.TRANSLATION, focus, onBack, vm) {
        val translateMode by vm.translateMode.collectAsStateWithLifecycle()
        val translateUrl by vm.translateUrl.collectAsStateWithLifecycle()
        TranslationSection(translateMode, translateUrl, onMode = { vm.setTranslateMode(it) }, onSaveUrl = vm::setTranslateUrl)
    }
}
