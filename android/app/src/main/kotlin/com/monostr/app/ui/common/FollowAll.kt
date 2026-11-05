package com.monostr.app.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.monostr.app.R

/** «Follow all» changes the contact list in one write: above [CONFIRM_ABOVE] new accounts it asks first, and every run can be undone. */
object FollowAll {
    const val CONFIRM_ABOVE = 10

    /** The accounts a «Follow all» would add. */
    fun newMembers(members: List<String>, followed: Set<String>, muted: Set<String>, self: String): List<String> =
        members.filter { it != self && it !in muted && it !in followed }.distinct()
}

@Composable
fun FollowAllConfirmDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("follow-all-confirm"),
        title = { Text(pluralStringResource(R.plurals.follow_all_confirm_title, count, count)) },
        text = { Text(stringResource(R.string.follow_all_confirm_text)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("follow-all-yes")) { Text(stringResource(R.string.discover_follow_all)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("follow-all-no")) { Text(stringResource(R.string.action_cancel)) } },
    )
}
