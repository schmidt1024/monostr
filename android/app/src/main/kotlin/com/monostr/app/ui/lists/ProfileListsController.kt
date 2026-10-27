package com.monostr.app.ui.lists

import kotlinx.coroutines.flow.combine
import com.monostr.nostr.model.SaveResult
import com.monostr.app.R
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.common.Message
import com.monostr.app.ui.common.attempt
import com.monostr.app.ui.profile.ProfileCache
import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.repo.FeedTabsRepository
import com.monostr.nostr.repo.ListsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileListsState(
    /** Null until the tab was opened and the lists are read. */
    val lists: List<FollowSet>? = null,
    val pinned: Set<ListAddress> = emptySet(),
    val message: Message? = null,
)

/** Spec 2026-10-18 §2.2: the own lists (private ones marked) or another account's public lists, each with a pin toggle. */
class ProfileListsController(
    private val author: String,
    private val self: String,
    private val lists: ListsRepository,
    private val pins: FeedTabsRepository,
    private val scope: CoroutineScope,
    private val writeScope: CoroutineScope = scope,
    /** Spec perf 3.5/3.7: the session's last profiles, their lists too; null (tests that do not care) keeps nothing. */
    private val cache: ProfileCache? = null,
) {
    private val _state = MutableStateFlow(ProfileListsState())
    val state: StateFlow<ProfileListsState> = _state.asStateFlow()
    val isSelf: Boolean get() = author == self
    private val pinActions = PinActions(pins, writeScope) { m -> _state.update { it.copy(message = m) } }
    private var opened = false

    /** The tab opened: reads once (the own lists stay live, a foreign profile is read when opened). */
    fun open() {
        if (opened) return
        opened = true
        scope.launch { pins.tabs().collect { p -> _state.update { it.copy(pinned = p.toSet()) } } }
        scope.launch {
            if (isSelf) {
                // backlog 38: in the order the user dragged (the pinned ones as tabs follow it)
                combine(lists.own(), pins.order(), pins.tabs()) { own, order, tabs -> ListOrder.arrange(own, order, tabs) }
                    .collect { l -> _state.update { it.copy(lists = l) } }
            } else {
                // spec perf 3.7: the session's last view, then the database, then the relays (~16 s before, one after
                // another). With nothing stored the spinner stays until the relays answered: "No public lists" before
                // them would be wrong for most foreign profiles.
                cache?.lists(author)?.let { l -> _state.update { it.copy(lists = l) } }
                val stored = attempt { lists.localOf(author) }.orEmpty()
                if (stored.isNotEmpty()) show(stored)
                val fetched = attempt { lists.of(author) }
                show(fetched ?: _state.value.lists ?: stored)
            }
        }
    }

    private fun show(found: List<FollowSet>) {
        _state.update { it.copy(lists = found) }
        cache?.putLists(author, found)
    }

    fun togglePin(set: FollowSet) = pinActions.toggle(set.address, set.address in _state.value.pinned)

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Backlog 38: the own list at [from] dropped at [to]; stores the whole shown order (one write), says so when no relay took it. */
    fun move(from: Int, to: Int) {
        if (!isSelf) return
        val shown = _state.value.lists?.map { it.address } ?: return
        val next = ListOrder.move(shown, from, to)
        if (next == shown) return
        writeScope.launch {
            if (pins.reorder(next) != SaveResult.Ok) {
                _state.update { it.copy(message = Message(uiText(R.string.list_save_failed), retry = { move(from, to) })) }
            }
        }
    }
}
