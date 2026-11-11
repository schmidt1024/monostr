package com.monostr.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.input.ImeAction
import com.monostr.app.ui.theme.SettingsIcons
import com.monostr.app.ui.theme.Spacing
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.monostr.app.R
import com.monostr.app.data.MediaServer

/**
 * Spec 5.4 (Plan 10e): the Blossom server pictures are uploaded to. [onSave] answers false for an
 * address it does not accept; the line under the field then says what an address must look like.
 * Round 2 (owner 5, UX P1-2): the save action is a check icon inside the field, shown only while the
 * field holds an unsaved change; the keyboard's Done saves too. The hint sits under the field at its edge.
 */
@Composable
fun MediaServerSetting(current: String, onSave: (String) -> Boolean, onReset: () -> Unit) {
    var text by rememberSaveable(current) { mutableStateOf(current) }
    var invalid by remember(current) { mutableStateOf(false) }
    val dirty = text.trim() != current
    val save = { if (dirty) invalid = !onSave(text) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; invalid = false },
        label = { Text(stringResource(R.string.settings_media_server)) },
        singleLine = true,
        isError = invalid,
        trailingIcon = if (dirty) {
            {
                IconButton(onClick = save, modifier = Modifier.testTag("settings-media-server-save")) {
                    Icon(SettingsIcons.Check, contentDescription = stringResource(R.string.settings_media_server_save))
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
        modifier = Modifier.fillMaxWidth().testTag("settings-media-server"),
        shape = MaterialTheme.shapes.medium,
    )
    Spacer(Modifier.height(Spacing.xs))
    if (invalid) {
        Text(
            stringResource(R.string.settings_media_server_invalid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("settings-media-server-error"),
        )
    } else {
        Text(stringResource(R.string.settings_media_server_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (current != MediaServer.DEFAULT) {
        TextButton(onClick = onReset, modifier = Modifier.testTag("settings-media-server-reset")) {
            Text(stringResource(R.string.settings_media_server_reset), color = MaterialTheme.colorScheme.primary)
        }
    }
}
