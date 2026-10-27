package com.monostr.app.ui.lists

import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress

/** Backlog 38: the order of the own lists in the profile, which the user drags; the pinned ones follow it as home tabs. */
object ListOrder {
    /**
     * [lists] as the user ordered them. Without an [order] (never dragged): the [pinned] ones first in tab order, then
     * the rest newest first, so nothing moves after the update. With one: lists missing from it (new, or made in
     * another app) on top, newest first, then the ordered ones; addresses of deleted lists drop out.
     */
    fun arrange(lists: List<FollowSet>, order: List<ListAddress>, pinned: List<ListAddress>): List<FollowSet> {
        val newest = lists.sortedByDescending { it.createdAt }
        if (order.isEmpty()) {
            val tab = pinned.withIndex().associate { (i, a) -> a to i }
            return newest.filter { it.address in tab }.sortedBy { tab.getValue(it.address) } + newest.filter { it.address !in tab }
        }
        val index = order.withIndex().associate { (i, a) -> a to i }
        return newest.filter { it.address !in index } + newest.filter { it.address in index }.sortedBy { index.getValue(it.address) }
    }

    /** [shown] with the entry at [from] moved to [to]: the full new order to store. */
    fun move(shown: List<ListAddress>, from: Int, to: Int): List<ListAddress> {
        if (from !in shown.indices || to !in shown.indices || from == to) return shown
        return shown.toMutableList().apply { add(to, removeAt(from)) }
    }
}
