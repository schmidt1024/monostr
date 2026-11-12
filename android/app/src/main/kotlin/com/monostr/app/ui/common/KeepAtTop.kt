package com.monostr.app.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Bug 25: a lazy list keeps its first visible row by key, so a row inserted above (a live notification, a refresh)
 * landed out of sight even when the list stood at its very top. Standing at the top, the list now stays there and
 * shows the new row; scrolled down, the reading place stays (like X). Call it with the current first row's key.
 *
 * The list's items are read inside its content lambda, so a layout pass can measure the new rows before this
 * composition runs: the key anchor has then already moved the list to index 1, and "first visible index is 0" no
 * longer tells that it stood at the top. Asking instead whether the first visible row is the previous first row with
 * no offset holds in both orders, before that measure (index 0) and after it (index 1).
 */
@Composable
fun KeepAtTop(state: LazyListState, firstKey: Any?) {
    val seen = remember(state) { arrayOfNulls<Any>(1) }
    val previous = seen[0]
    if (previous != firstKey) {
        seen[0] = firstKey
        // read without observation: scrolling must not recompose the caller
        if (previous != null && Snapshot.withoutReadObservation { stoodAtTop(state, previous) }) state.requestScrollToItem(0)
    }
}

private fun stoodAtTop(state: LazyListState, previousFirstKey: Any): Boolean {
    if (state.firstVisibleItemScrollOffset != 0) return false
    val index = state.firstVisibleItemIndex
    if (index == 0) return true
    return state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key == previousFirstKey
}
