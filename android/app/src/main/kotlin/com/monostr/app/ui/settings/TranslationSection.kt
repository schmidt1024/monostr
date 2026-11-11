package com.monostr.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.app.translate.TranslationMode
import com.monostr.app.ui.theme.Spacing

/**
 * Spec 2026-11-03: which server translates notes — monostr's, an own LibreTranslate server (address field, normalized
 * like the media server) or none — and what that server sees. Round 2 (privacy rule): what the server sees stands above
 * the choice, the choice is one card. [onSaveUrl] answers false for an address it refuses.
 */
@Composable
fun TranslationSection(mode: TranslationMode, customUrl: String, onMode: (TranslationMode) -> Unit, onSaveUrl: (String) -> Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            stringResource(R.string.settings_translate_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.l).testTag("translate-privacy-hint"),
        )
        SettingsGroup(stringResource(R.string.settings_translate_service)) {
            Column(Modifier.settingAnchor("settings-translate-mode").selectableGroup()) {
                ModeRow(stringResource(R.string.settings_translate_monostr), mode == TranslationMode.MONOSTR, "translate-mode-monostr") { onMode(TranslationMode.MONOSTR) }
                SettingsCardDivider(SETTINGS_ICON_INSET)
                ModeRow(stringResource(R.string.settings_translate_custom), mode == TranslationMode.CUSTOM, "translate-mode-custom") { onMode(TranslationMode.CUSTOM) }
                if (mode == TranslationMode.CUSTOM) CustomServerField(customUrl, onSaveUrl)
                SettingsCardDivider(SETTINGS_ICON_INSET)
                ModeRow(stringResource(R.string.settings_translate_off), mode == TranslationMode.OFF, "translate-mode-off") { onMode(TranslationMode.OFF) }
            }
        }
    }
}

/** The own server's address, under its option inside the card. */
@Composable
private fun CustomServerField(customUrl: String, onSaveUrl: (String) -> Boolean) {
    var text by rememberSaveable(customUrl) { mutableStateOf(customUrl) }
    var invalid by remember(customUrl) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = SETTINGS_ICON_INSET, end = Spacing.l)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; invalid = false },
            label = { Text(stringResource(R.string.settings_translate_url)) },
            singleLine = true,
            isError = invalid,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            supportingText = if (invalid) {
                { Text(stringResource(R.string.settings_translate_url_invalid), modifier = Modifier.testTag("translate-url-error")) }
            } else null,
            modifier = Modifier.fillMaxWidth().settingAnchor("settings-translate-server").testTag("translate-url"),
            shape = MaterialTheme.shapes.medium,
        )
        TextButton(onClick = { invalid = !onSaveUrl(text) }, enabled = text.trim() != customUrl, modifier = Modifier.testTag("translate-url-save")) {
            Text(stringResource(R.string.profile_edit_save))
        }
    }
}

@Composable
private fun ModeRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = Spacing.l, vertical = Spacing.xs).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Spacing.l))
    }
}
