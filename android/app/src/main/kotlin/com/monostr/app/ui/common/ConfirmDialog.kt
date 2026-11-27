package com.monostr.app.ui.common

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.monostr.app.R

/**
 * The one question before an action that cannot be undone, in Settings and everywhere else (UX review 2026-11-26, backlog
 * 114: four hand-built looks before): a title, an optional [text], the action as the confirm button ([confirmTag]; error colour when [destructive]) and
 * Cancel (`settings-confirm-cancel`). [dismissButton] replaces Cancel where the dialog offers another way out (sign out:
 * «Show key»); tapping outside or Back still cancels.
 */
@Composable
fun ConfirmDialog(
    title: String?,
    text: String?,
    confirm: String,
    confirmTag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    dismissButton: (@Composable () -> Unit)? = null,
    /** Below the text, e.g. a switch that belongs to the question. */
    extra: (@Composable () -> Unit)? = null,
) {
    GuardedAlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = if (text == null && extra == null) null else {
            { androidx.compose.foundation.layout.Column { text?.let { Text(it) }; extra?.invoke() } }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(confirmTag)) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = dismissButton ?: { TextButton(onClick = onDismiss, modifier = Modifier.testTag("settings-confirm-cancel")) { Text(stringResource(R.string.action_cancel)) } },
        modifier = modifier,
    )
}

/**
 * Backlog 121: in the «Log out» question. On: the account's settings stay on this device as a package only its own key opens
 * (`KeptSettings`), and the next login of the account takes them back.
 */
@Composable
fun KeepSettingsSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(top = com.monostr.app.ui.theme.Spacing.m)
            .toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange).testTag("logout-keep-settings"),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(end = com.monostr.app.ui.theme.Spacing.m)) {
            Text(stringResource(R.string.logout_keep_settings), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.logout_keep_settings_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null)
    }
}
