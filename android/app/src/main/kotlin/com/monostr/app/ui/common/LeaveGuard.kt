package com.monostr.app.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.monostr.app.R

/**
 * Leaving a form with changes nobody saved asks first (UX review 2026-11-26, backlog 111: the profile and list editors
 * dropped them without a word, while the composer keeps a draft). Returns what the back arrow calls; the system's back
 * goes the same way while [dirty]. «Discard» leaves, «Keep editing» stays.
 */
@Composable
fun rememberLeaveGuard(dirty: Boolean, onLeave: () -> Unit): () -> Unit {
    var asking by rememberSaveable { mutableStateOf(false) }
    val isDirty by rememberUpdatedState(dirty)
    val leave by rememberUpdatedState(onLeave)
    BackHandler(enabled = dirty) { asking = true }
    if (asking) {
        ConfirmDialog(
            title = stringResource(R.string.unsaved_title), text = null,
            confirm = stringResource(R.string.create_cancel_yes), confirmTag = "unsaved-discard", destructive = true,
            onConfirm = { asking = false; leave() }, onDismiss = { asking = false },
            dismissButton = { TextButton(onClick = { asking = false }, modifier = Modifier.testTag("unsaved-keep")) { Text(stringResource(R.string.unsaved_keep)) } },
        )
    }
    return { if (isDirty) asking = true else leave() }
}
