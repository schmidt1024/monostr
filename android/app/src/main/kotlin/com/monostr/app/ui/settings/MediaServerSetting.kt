package com.monostr.app.ui.settings

import androidx.compose.foundation.layout.Row
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
 * address it does not accept; the field then says what an address must look like.
 */
@Composable
fun MediaServerSetting(current: String, onSave: (String) -> Boolean, onReset: () -> Unit) {
    var text by rememberSaveable(current) { mutableStateOf(current) }
    var invalid by remember(current) { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; invalid = false },
        label = { Text(stringResource(R.string.settings_media_server)) },
        singleLine = true,
        isError = invalid,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
        supportingText = {
            if (invalid) {
                Text(stringResource(R.string.settings_media_server_invalid), modifier = Modifier.testTag("settings-media-server-error"))
            } else {
                Text(stringResource(R.string.settings_media_server_hint))
            }
        },
        modifier = Modifier.fillMaxWidth().testTag("settings-media-server"),
    )
    Row {
        TextButton(onClick = { invalid = !onSave(text) }, enabled = text.trim() != current, modifier = Modifier.testTag("settings-media-server-save")) {
            Text(stringResource(R.string.profile_edit_save))
        }
        if (current != MediaServer.DEFAULT) {
            TextButton(onClick = onReset, modifier = Modifier.testTag("settings-media-server-reset")) {
                Text(stringResource(R.string.settings_media_server_reset), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
