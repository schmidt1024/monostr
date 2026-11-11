package com.monostr.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.monostr.app.R
import com.monostr.app.ui.common.GuardedAlertDialog

/**
 * The question before a settings action that cannot be undone (relay lists, the Monero danger zone, payto removal, sign
 * out): a title, an optional [text], the action as the confirm button ([confirmTag]; error colour when [destructive]) and
 * Cancel (`settings-confirm-cancel`). [dismissButton] replaces Cancel where the dialog offers another way out (sign out:
 * «Show key»); tapping outside or Back still cancels.
 */
@Composable
fun SettingsConfirmDialog(
    title: String,
    text: String?,
    confirm: String,
    confirmTag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    GuardedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(confirmTag)) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = dismissButton ?: { TextButton(onClick = onDismiss, modifier = Modifier.testTag("settings-confirm-cancel")) { Text(stringResource(R.string.action_cancel)) } },
        modifier = modifier,
    )
}
