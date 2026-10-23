package com.monostr.app.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * Bug 25: a lazy list keeps its first visible row by key, so a row inserted above (a live notification, a refresh)
 * landed out of sight even when the list stood at its very top. Standing at the top, the list now stays there and
 * shows the new row; scrolled down, the reading place stays (like X). Call it with the current first row's key.
 */
@Composable
fun KeepAtTop(state: LazyListState, firstKey: Any?) {
    val atTop by remember(state) { derivedStateOf { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 } }
    // asked during the composition that brings the new first row, before the list measures it: the request wins over
    // the key anchor; atTop still reads the old position then
    val seen = remember(state) { arrayOfNulls<Any>(1) }
    if (seen[0] != firstKey) {
        if (seen[0] != null && atTop) state.requestScrollToItem(0)
        seen[0] = firstKey
    }
}
