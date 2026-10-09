package com.monostr.app.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first

/**
 * Shows the outcome of deletion requests ([NoteDeletions.messages]) in [snackbar], one after the other,
 * and consumes each once shown. Only a started screen collects, so a message shows once, on whatever
 * screen is in front when it arrives: after a deleted thread closed, on the screen below it.
 */
@Composable
fun DeletionMessages(deletions: NoteDeletions, snackbar: SnackbarHostState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    // a loop instead of a state-keyed effect: two equal messages in a row each show, and a message
    // arriving while another one shows does not restart it
    LaunchedEffect(deletions, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val next = deletions.messages.first { it.isNotEmpty() }.first()
                snackbar.showSnackbar(next.resolve(context))
                deletions.consume(next)
            }
        }
    }
}
