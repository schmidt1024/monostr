package com.monostr.app.ui.lists

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex

/**
 * Backlog 38: drag to reorder rows of a LazyColumn. A long press lifts the row, it follows the finger and swaps
 * places with the row under its middle; on release [onDrop] gets where it started and where it ended (one write).
 * While a drag runs, [rows] shows the dragged order instead of the screen's list.
 */
class ListReorderState<T>(
    private val key: (T) -> Any,
    /** The rows on screen now (key, top, height): from the LazyColumn's layout; tests give their own. */
    private val layout: () -> List<Slot>,
) {
    constructor(listState: LazyListState, key: (T) -> Any) : this(key, { listState.layoutInfo.visibleItemsInfo.map { Slot(it.key, it.offset, it.size) } })

    /** One row as laid out. */
    data class Slot(val key: Any, val offset: Int, val size: Int)

    private var shown by mutableStateOf<List<T>>(emptyList())
    private var dragged by mutableStateOf<Any?>(null)
    private var offset by mutableFloatStateOf(0f)
    private var startIndex = -1
    /** Where the layout showed the dragged row at the last swap: until it shows it elsewhere the places are stale. */
    private var staleOffset: Int? = null

    /** [items] (the screen's list), or the dragged order while a drag runs: a late update does not tear the row away. */
    fun rows(items: List<T>): List<T> = if (dragged != null) shown else items

    internal fun isDragged(item: T) = dragged == key(item)
    internal fun offsetOf(item: T) = if (isDragged(item)) offset else 0f

    internal fun start(item: T, items: List<T>) {
        shown = items
        dragged = key(item)
        offset = 0f
        staleOffset = null
        startIndex = shown.indexOfFirst { key(it) == dragged }
    }

    internal fun drag(dy: Float) {
        val k = dragged ?: return
        offset += dy
        val visible = layout()
        val current = visible.firstOrNull { it.key == k } ?: return
        // the swap is not laid out yet: the other rows' places are old, a second swap would undo the first
        if (staleOffset == current.offset) return
        staleOffset = null
        val middle = current.offset + offset + current.size / 2f
        val keys = shown.map(key).toSet()
        val target = visible.firstOrNull { it.key != k && it.key in keys && middle > it.offset && middle < it.offset + it.size } ?: return
        val from = shown.indexOfFirst { key(it) == k }
        val to = shown.indexOfFirst { key(it) == target.key }
        if (from < 0 || to < 0) return
        shown = shown.toMutableList().apply { add(to, removeAt(from)) }
        offset += current.offset - target.offset
        staleOffset = current.offset
    }

    internal fun end(onDrop: (from: Int, to: Int) -> Unit) {
        val k = dragged ?: return
        val to = shown.indexOfFirst { key(it) == k }
        val from = startIndex
        dragged = null
        offset = 0f
        startIndex = -1
        staleOffset = null
        if (from >= 0 && to >= 0 && from != to) onDrop(from, to)
    }
}

@Composable
fun <T> rememberListReorderState(listState: LazyListState, key: (T) -> Any): ListReorderState<T> = remember(listState) { ListReorderState(listState, key) }

/**
 * The row of [item] (at [index] of [items], the list as shown before the drag) in a reorderable list: long press and drag moves it; screen readers get «move up» / «move down»
 * ([moveUp]/[moveDown] labels; null at the edges) which call [onDrop] directly.
 */
fun <T> Modifier.reorderable(
    state: ListReorderState<T>,
    item: T,
    items: List<T>,
    index: Int,
    moveUp: String,
    moveDown: String,
    onDrop: (from: Int, to: Int) -> Unit,
): Modifier = this
    .zIndex(if (state.isDragged(item)) 1f else 0f)
    .graphicsLayer {
        translationY = state.offsetOf(item)
        if (state.isDragged(item)) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 8f }
    }
    // keyed by the list too: after a drop the next drag must start from the new order, not the one captured before
    .pointerInput(item, items) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.start(item, items) },
            onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
            onDragEnd = { state.end(onDrop) },
            onDragCancel = { state.end(onDrop) },
        )
    }
    .semantics {
        customActions = buildList {
            if (index > 0) add(CustomAccessibilityAction(moveUp) { onDrop(index, index - 1); true })
            if (index < items.size - 1) add(CustomAccessibilityAction(moveDown) { onDrop(index, index + 1); true })
        }
    }

/**
 * Backlog 38: the visible grip (≡) of a reorderable row: dragging it moves the row at once, no long press. The row's
 * own [reorderable] keeps the long press and the screen reader actions.
 */
@Composable
fun <T> DragHandle(state: ListReorderState<T>, item: T, items: List<T>, onDrop: (from: Int, to: Int) -> Unit, modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.DragHandle,
        contentDescription = null, // the row's «move up» / «move down» actions speak for it
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .size(48.dp)
            .padding(12.dp)
            .pointerInput(item, items) {
                detectDragGestures(
                    onDragStart = { state.start(item, items) },
                    onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
                    onDragEnd = { state.end(onDrop) },
                    onDragCancel = { state.end(onDrop) },
                )
            },
    )
}
