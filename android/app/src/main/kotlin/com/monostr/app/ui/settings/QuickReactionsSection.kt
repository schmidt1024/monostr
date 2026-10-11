package com.monostr.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monostr.app.R
import com.monostr.app.ui.common.Reactions

/** Settings → Reactions: the quick emojis of the long press on the heart, in the user's order. */
@Composable
fun QuickReactionsSection(list: List<String>, onChange: (List<String>) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_reactions), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_reactions_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.forEachIndexed { i, e ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("settings-reaction-$i")) {
                Text(e, fontSize = 22.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { onChange(Reactions.move(list, i, -1)) }, enabled = i > 0, modifier = Modifier.testTag("settings-reaction-up-$i")) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.settings_reactions_up, e))
                }
                IconButton(onClick = { onChange(Reactions.move(list, i, +1)) }, enabled = i < list.lastIndex, modifier = Modifier.testTag("settings-reaction-down-$i")) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.settings_reactions_down, e))
                }
                // the last one stays: an empty list would mean "the default" again
                IconButton(onClick = { onChange(Reactions.remove(list, i)) }, enabled = list.size > 1, modifier = Modifier.testTag("settings-reaction-remove-$i")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_reactions_remove, e))
                }
            }
        }
        var input by remember { mutableStateOf("") }
        val full = list.size >= Reactions.MAX_QUICK
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, singleLine = true, enabled = !full,
                label = { Text(stringResource(R.string.settings_reactions_add)) },
                modifier = Modifier.weight(1f).testTag("settings-reaction-input"),
            )
            Spacer(Modifier.width(8.dp))
            val next = Reactions.add(list, input)
            TextButton(onClick = { onChange(next); input = "" }, enabled = next != list, modifier = Modifier.testTag("settings-reaction-add")) {
                Text(stringResource(R.string.settings_reactions_add_button))
            }
        }
        if (list != Reactions.QUICK) {
            TextButton(onClick = { onChange(emptyList()) }, modifier = Modifier.testTag("settings-reaction-reset")) { Text(stringResource(R.string.settings_reactions_reset)) }
        }
    }
}
