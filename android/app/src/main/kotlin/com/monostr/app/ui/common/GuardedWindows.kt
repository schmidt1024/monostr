package com.monostr.app.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.launch

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

/**
 * [ModalBottomSheet] whose content keeps the link prompt, and that settles again when its content changes height.
 *
 * Material3 1.4 moves the sheet to new anchors while it rests or while its animation still runs, but content that changes height
 * in the frame the opening animation ends is lost: the sheet stays at the old height's place, its end below the screen, and stays
 * «hidden» for the drag (Pixel finding 2026-11-25: the switcher in a fresh activity, its rows loaded a moment after it opened;
 * SheetGrowTest). Every height change of a shown sheet therefore animates it to its target again; a running dismissal is left alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardedModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val settleScope = rememberCoroutineScope()
    val lastHeight = remember { intArrayOf(-1) }
    val reanchor = Modifier.onSizeChanged { size ->
        val previous = lastHeight[0]
        lastHeight[0] = size.height
        val target = sheetState.targetValue
        if (previous >= 0 && previous != size.height && target != SheetValue.Hidden) {
            // a drag of the user wins: the lower priority settle is cancelled
            settleScope.launch {
                if (target == SheetValue.PartiallyExpanded && sheetState.hasPartiallyExpandedState) sheetState.partialExpand() else sheetState.expand()
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier.then(reanchor), sheetState = sheetState) {
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

/** [Popup] whose content keeps the link prompt. */
@Composable
fun GuardedPopup(popupPositionProvider: PopupPositionProvider, content: @Composable () -> Unit) {
    Popup(popupPositionProvider = popupPositionProvider) { KeepLinkGuard(content) }
}
