package com.monostr.app.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Every screen's snackbar host, so all of them look the same ([MonostrSnackbar]). */
@Composable
fun MonostrSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { MonostrSnackbar(it) }
}

/**
 * Material's snackbar is the inverse surface: a white bar on the dark themes that outshines the feed (owner 2026-11-18).
 * There it is a raised dark card with a hairline and the action in the accent; the light theme keeps Material's dark bar.
 */
@Composable
fun MonostrSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val container = if (dark) scheme.surfaceContainerHighest else SnackbarDefaults.color
    val content = if (dark) scheme.onSurface else SnackbarDefaults.contentColor
    val actionColor = if (dark) scheme.primary else SnackbarDefaults.actionColor
    val shape = SnackbarDefaults.shape
    val label = data.visuals.actionLabel
    Snackbar(
        // the margin Material's own snackbar keeps, outside the hairline
        modifier = modifier.padding(SNACKBAR_MARGIN).then(if (dark) Modifier.border(1.dp, scheme.outlineVariant, shape) else Modifier),
        action = label?.let { { TextButton(onClick = { data.performAction() }, colors = ButtonDefaults.textButtonColors(contentColor = actionColor)) { Text(it) } } },
        shape = shape,
        containerColor = container,
        contentColor = content,
        actionContentColor = actionColor,
    ) { Text(data.visuals.message) }
}

/** Space around the snackbar card inside its host (Material's own value). */
val SNACKBAR_MARGIN = 12.dp
