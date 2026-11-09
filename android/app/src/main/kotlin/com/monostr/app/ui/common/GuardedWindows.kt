package com.monostr.app.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.window.DialogProperties

/**
 * The guarded handler of [LinkPromptHost], under a key of our own. A sheet, dialog, popup or menu gets a window of its own, and that
 * window's ComposeView provides the platform [LocalUriHandler] again, which opens the browser without the prompt (spec 2026-11-06
 * section 6). Our own locals pass into a window unchanged, so this one still holds the guarded handler there.
 */
val LocalGuardedUriHandler = staticCompositionLocalOf<UriHandler?> { null }

/** Puts the guarded handler back into [LocalUriHandler] for [content]; every window's content goes through this (NoBareWindowsTest). */
@Composable
fun KeepLinkGuard(content: @Composable () -> Unit) {
    // outside a LinkPromptHost (a preview, a test of one screen) the window keeps what it has
    CompositionLocalProvider(LocalUriHandler provides (LocalGuardedUriHandler.current ?: LocalUriHandler.current), content = content)
}

/** [ModalBottomSheet] whose content keeps the link prompt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardedModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier, sheetState = sheetState) {
        val scope = this
        KeepLinkGuard { scope.content() }
    }
}

/** [AlertDialog] whose every slot keeps the link prompt. */
@Composable
fun GuardedAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { KeepLinkGuard(confirmButton) },
        modifier = modifier,
        dismissButton = dismissButton?.let { slot -> { KeepLinkGuard(slot) } },
        title = title?.let { slot -> { KeepLinkGuard(slot) } },
        text = text?.let { slot -> { KeepLinkGuard(slot) } },
        properties = properties,
    )
}

/** [DropdownMenu] whose items keep the link prompt. */
@Composable
fun GuardedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier) {
        val scope = this
        KeepLinkGuard { scope.content() }
    }
}
