package com.monostr.app.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.monostr.app.R

/**
 * A message a list screen shows in its snackbar. [undoMute] is the account just muted: only the mute
 * confirmation sets it (spec 9.4), so any later message replaces the "Undo" with it.
 */
data class Message(val text: UiText, val undoMute: String? = null)

/** A plain message without an action. */
fun UiText.asMessage(): Message = Message(this)

/**
 * The message snackbar of a list screen. After a mute ([Message.undoMute] is the account) it carries the
 * action "Undo", which calls [onUnmute] (spec 9.4); [onShown] runs when the snackbar is gone.
 */
@Composable
fun MessageSnackbar(message: Message?, snackbar: SnackbarHostState, onUnmute: (String) -> Unit, onShown: () -> Unit) {
    val undoLabel = stringResource(R.string.mute_undo)
    val text = message?.text?.asString()
    val undoMute = message?.undoMute
    LaunchedEffect(text, undoMute) {
        text ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(text, actionLabel = undoLabel.takeIf { undoMute != null })
        if (result == SnackbarResult.ActionPerformed && undoMute != null) onUnmute(undoMute)
        onShown()
    }
}

/** Test tag of the snackbar host while it offers "Undo". */
fun Modifier.muteUndoTag(message: Message?): Modifier = if (message?.undoMute != null) testTag("mute-undo") else this
