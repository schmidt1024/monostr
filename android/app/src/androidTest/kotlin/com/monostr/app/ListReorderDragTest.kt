package com.monostr.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.ui.lists.rememberListReorderState
import com.monostr.app.ui.lists.reorderable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Backlog 38: dragging rows of a list, several times in a row (each drag sees the order the previous one left). */
@RunWith(AndroidJUnit4::class)
class ListReorderDragTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun a_second_drag_starts_from_the_order_the_first_one_left() {
        val items = mutableStateOf(listOf("a", "b", "c"))
        rule.setContent {
            val listState = rememberLazyListState()
            val reorder = rememberListReorderState<String>(listState) { it }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState) {
                    val shown = items.value
                    itemsIndexed(reorder.rows(shown), key = { _, s -> s }) { i, s ->
                        Text(
                            s,
                            Modifier.fillMaxWidth().height(80.dp).testTag("row-$s")
                                .reorderable(reorder, s, shown, i, "up", "down") { from, to -> items.value = shown.toMutableList().apply { add(to, removeAt(from)) } },
                        )
                    }
                }
            }
        }
        // one finger, frames in between (as on a phone): the list lays the swapped rows out while the finger moves
        fun drag(tag: String, dy: Float) {
            val node = rule.onNodeWithTag(tag)
            node.performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(1_000) // long press
            rule.waitForIdle()
            repeat(10) {
                node.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, dy / 10)); advanceEventTime(50) }
                rule.waitForIdle()
            }
            node.performTouchInput { up() }
        }
        val row = with(rule.density) { 80.dp.toPx() }
        drag("row-a", row * 2.2f) // a to the bottom
        rule.waitForIdle()
        assertEquals(listOf("b", "c", "a"), items.value)
        drag("row-a", -row * 2.2f) // and back to the top
        rule.waitForIdle()
        assertEquals(listOf("a", "b", "c"), items.value)
    }

    @Test
    fun the_handle_drags_at_once_without_a_long_press() {
        val items = mutableStateOf(listOf("a", "b", "c"))
        rule.setContent {
            val listState = rememberLazyListState()
            val reorder = rememberListReorderState<String>(listState) { it }
            LazyColumn(state = listState) {
                val shown = items.value
                itemsIndexed(reorder.rows(shown), key = { _, s -> s }) { i, s ->
                    val drop: (Int, Int) -> Unit = { from, to -> items.value = shown.toMutableList().apply { add(to, removeAt(from)) } }
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().height(80.dp).reorderable(reorder, s, shown, i, "up", "down", drop),
                    ) {
                        Text(s, Modifier.weight(1f))
                        com.monostr.app.ui.lists.DragHandle(reorder, s, shown, drop, Modifier.testTag("handle-$s"))
                    }
                }
            }
        }
        val row = with(rule.density) { 80.dp.toPx() }
        val node = rule.onNodeWithTag("handle-a")
        node.performTouchInput { down(center) } // no long press
        repeat(10) {
            node.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, row * 1.2f / 10)); advanceEventTime(50) }
            rule.waitForIdle()
        }
        node.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf("b", "a", "c"), items.value)
    }
}
