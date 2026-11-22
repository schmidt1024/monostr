package com.monostr.app.ui.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.ui.common.openDeviceTranslatorStore
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsViewModel
import com.monostr.app.ui.settings.TranslationSection

/** Translation: what the server sees, then the service card (the top bar names the page). */
@Composable
fun TranslationPage(vm: SettingsViewModel, focus: String?, onBack: () -> Unit) {
    SettingsPageScaffold(SettingsPage.TRANSLATION, focus, onBack, vm) {
        val translateMode by vm.translateMode.collectAsStateWithLifecycle()
        val translateUrl by vm.translateUrl.collectAsStateWithLifecycle()
        val deviceInstalled by vm.deviceInstalled.collectAsStateWithLifecycle()
        val context = LocalContext.current
        // the installed check: as the page opens and again on return (from the store), never on the feed path
        LifecycleResumeEffect(Unit) {
            vm.checkDeviceTranslator()
            onPauseOrDispose {}
        }
        TranslationSection(
            translateMode, translateUrl, deviceInstalled,
            onMode = { vm.setTranslateMode(it) }, onSaveUrl = vm::setTranslateUrl,
            onGetDeviceApp = { openDeviceTranslatorStore(context) },
        )
    }
}
