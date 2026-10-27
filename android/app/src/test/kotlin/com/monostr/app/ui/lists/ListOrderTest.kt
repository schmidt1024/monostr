package com.monostr.app.ui.lists

import com.monostr.app.ui.followSet
import com.monostr.nostr.model.FollowSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Backlog 38: the order of the own lists in the profile. */
class ListOrderTest {
    private val self = "e".repeat(64)
    private fun set(d: String, at: Long) = followSet(d, emptyList(), author = self).copy(createdAt = at)
    private fun List<FollowSet>.ds() = map { it.address.d }

    @Test
    fun `without an order the pinned lists come first in tab order, then the rest newest first`() {
        val lists = listOf(set("a", 100), set("b", 300), set("c", 200), set("d", 400))
        val pinned = listOf(set("c", 0).address, set("a", 0).address)
        assertEquals(listOf("c", "a", "d", "b"), ListOrder.arrange(lists, order = emptyList(), pinned = pinned).ds())
    }

    @Test
    fun `with an order, lists missing from it come first newest first, gone ones drop out`() {
        val lists = listOf(set("a", 100), set("b", 300), set("new", 500), set("newer", 600))
        val order = listOf(set("b", 0).address, set("gone", 0).address, set("a", 0).address)
        assertEquals(listOf("newer", "new", "b", "a"), ListOrder.arrange(lists, order, pinned = emptyList()).ds())
    }

    @Test
    fun `moving a list gives the full new order`() {
        val shown = listOf(set("a", 1), set("b", 2), set("c", 3)).map { it.address }
        assertEquals(listOf("b", "c", "a"), ListOrder.move(shown, from = 0, to = 2).map { it.d })
        assertEquals(listOf("c", "a", "b"), ListOrder.move(shown, from = 2, to = 0).map { it.d })
        assertEquals(listOf("a", "b", "c"), ListOrder.move(shown, from = 1, to = 1).map { it.d })
    }
}
