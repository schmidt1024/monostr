package com.monostr.app.ui.settings.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monostr.app.R
import com.monostr.app.data.GifMode
import com.monostr.app.data.POW_CHOICES
import com.monostr.app.ui.settings.MediaServerSetting
import com.monostr.app.ui.settings.QuickReactionsSection
import com.monostr.app.ui.settings.SettingsCard
import com.monostr.app.ui.settings.SettingsCardDivider
import com.monostr.app.ui.settings.SettingsGroup
import com.monostr.app.ui.settings.SettingsPage
import com.monostr.app.ui.settings.SettingsViewModel
import com.monostr.app.ui.settings.settingAnchor
import com.monostr.app.ui.theme.Spacing

/**
 * Content & media (round 2, UX «Proposed order»): pictures and video first (loading on tap is the privacy control: picture
 * servers see the IP), the upload server second (uploads are public), the PoW feed filter, and the quick reactions as one
 * row that opens their own page.
 */
@Composable
fun ContentPage(vm: SettingsViewModel, focus: String?, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    SettingsPageScaffold(SettingsPage.CONTENT, focus, onBack, vm) {
        SettingsGroup(stringResource(R.string.settings_label_pictures), first = true) {
            val onTap by vm.mediaOnTap.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_media_on_tap), stringResource(R.string.settings_media_on_tap_hint), onTap, vm::setMediaOnTap, "settings-media-on-tap")
            SettingsCardDivider()
            val blur by vm.blurSensitive.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_media_blur), stringResource(R.string.settings_media_blur_hint), blur, vm::setBlurSensitive, "settings-media-blur")
            SettingsCardDivider()
            val gifMode by vm.gifMode.collectAsStateWithLifecycle()
            SettingBlock(stringResource(R.string.settings_gif), stringResource(R.string.settings_gif_hint)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().settingAnchor("settings-gif").testTag("settings-gif")) {
                    GifMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = gifMode == mode, onClick = { vm.setGifMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, GifMode.entries.size, baseShape = MaterialTheme.shapes.medium), modifier = Modifier.testTag("settings-gif-$mode"),
                        ) {
                            Text(stringResource(when (mode) { GifMode.AUTO -> R.string.settings_gif_auto; GifMode.ON_TAP -> R.string.settings_gif_tap; GifMode.OFF -> R.string.settings_gif_off }))
                        }
                    }
                }
            }
        }
        SettingsGroup(stringResource(R.string.settings_label_uploads)) {
            val mediaServer by vm.mediaServer.collectAsStateWithLifecycle()
            // a column, not a box: the field, its hint and «Default» stack instead of lying on top of each other (UX P1-2)
            Column(Modifier.fillMaxWidth().settingAnchor("settings-media-server").padding(start = Spacing.l, end = Spacing.l, top = Spacing.l, bottom = Spacing.m)) {
                MediaServerSetting(mediaServer, onSave = vm::setMediaServer, onReset = { vm.resetMediaServer() })
            }
        }
        SettingsGroup(stringResource(R.string.settings_label_feed_filter)) {
            val powFilter by vm.powFilterBits.collectAsStateWithLifecycle()
            SettingBlock(stringResource(R.string.settings_pow_filter), stringResource(R.string.settings_pow_filter_hint)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().settingAnchor("settings-pow-filter").testTag("settings-pow-filter")) {
                    POW_CHOICES.forEachIndexed { i, bits ->
                        SegmentedButton(
                            selected = powFilter == bits, onClick = { vm.setPowFilterBits(bits) },
                            shape = SegmentedButtonDefaults.itemShape(i, POW_CHOICES.size, baseShape = MaterialTheme.shapes.medium), modifier = Modifier.testTag("settings-pow-filter-$bits"),
                        ) { Text(if (bits == 0) stringResource(R.string.settings_pow_off) else stringResource(R.string.settings_pow_bits, bits)) }
                    }
                }
            }
        }
        SettingsGroup(stringResource(R.string.settings_reactions)) {
            // round 2 (UX P2-8): the editor dominated the page; here one row with the current emojis, the editor on its own page
            val quick by vm.quickReactions.collectAsStateWithLifecycle()
            SettingsLinkRow(
                stringResource(R.string.settings_quick_reactions), quick.take(5).joinToString(" "), "settings-open-reactions",
            ) { onOpen(SettingsPage.REACTIONS) }
        }
    }
}

/** Content & media › Quick reactions: the editor of the long press on the heart, in one card. */
@Composable
fun ReactionsPage(vm: SettingsViewModel, focus: String?, onBack: () -> Unit) {
    SettingsPageScaffold(SettingsPage.REACTIONS, focus, onBack, vm) {
        val quick by vm.quickReactions.collectAsStateWithLifecycle()
        SettingsCard(Modifier.settingAnchor("settings-reaction-input")) {
            Box(Modifier.fillMaxWidth().padding(Spacing.l)) { QuickReactionsSection(quick, onChange = vm::setQuickReactions, showTitle = false) }
        }
    }
}
