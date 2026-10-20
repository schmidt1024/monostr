package com.monostr.app.ui.lists

import com.monostr.app.R
import com.monostr.app.ui.common.Message
import com.monostr.app.ui.common.attempt
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.feed.FeedController
import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.Profile
import com.monostr.nostr.model.SaveResult
import com.monostr.nostr.repo.FeedSource
import com.monostr.nostr.repo.FeedTabsRepository
import com.monostr.nostr.repo.ListsRepository
import com.monostr.nostr.repo.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ListTab { NOTES, MEMBERS }

data class ListUiState(
    val set: FollowSet? = null,
    val loading: Boolean = true,
    /** Deleted or found nowhere (spec §5). */
    val unavailable: Boolean = false,
    val isOwn: Boolean = false,
    val pinned: Boolean = false,
    val author: Profile? = null,
    val members: List<Profile> = emptyList(),
    val tab: ListTab = ListTab.NOTES,
    /** The "Notes" tab: the same feed a pinned tab shows; rebuilt when the members change, null without members. */
    val feed: FeedController? = null,
    val message: Message? = null,
)

/** Spec 2026-10-18 §2.3: header, pin, "Notes" and "Members"; own lists can lose members here. */
class ListController(
    private val address: ListAddress,
    private val self: String,
    private val lists: ListsRepository,
    private val pins: FeedTabsRepository,
    private val profiles: ProfileRepository,
    private val scope: CoroutineScope,
    private val writeScope: CoroutineScope = scope,
    private val newFeed: (FeedSource.Authors, CoroutineScope) -> FeedController,
) {
    private val _state = MutableStateFlow(ListUiState(isOwn = address.pubkey == self))
    val state: StateFlow<ListUiState> = _state.asStateFlow()
    private val pinActions = PinActions(pins, writeScope) { m -> _state.update { it.copy(message = m) } }
    private var feedScope: CoroutineScope? = null

    fun start() {
        // the author's and the members' profiles the background lookup finds (spec perf 3.2)
        scope.launch {
            profiles.updates.collect { byKey ->
                _state.update { s -> s.copy(author = byKey[address.pubkey] ?: s.author, members = s.members.map { byKey[it.pubkey] ?: it }) }
            }
        }
        scope.launch { pins.tabs().collect { p -> _state.update { it.copy(pinned = address in p) } } }
        scope.launch { attempt { profiles.get(address.pubkey) }?.let { a -> _state.update { it.copy(author = a) } } }
        if (address.pubkey == self) {
            // the own lists are live: an edit or a removed member shows at once
            scope.launch { lists.own().collect { own -> show(own.firstOrNull { it.address == address } ?: attempt { lists.byAddress(address) }) } }
        } else {
            scope.launch {
                attempt { lists.byAddress(address, fetch = false) }?.let { show(it) }
                show(attempt { lists.byAddress(address) })
            }
        }
    }

    private suspend fun show(set: FollowSet?) {
        if (set == null) {
            closeFeed()
            _state.update { it.copy(set = null, loading = false, unavailable = true, feed = null) }
            return
        }
        val before = _state.value.set
        // the member SET decides (spec perf 3.7): the same members from a relay in another order keep the feed
        if (before == null || before.members.toSet() != set.members.toSet() || before.locked != set.locked) rebuildFeed(set)
        _state.update { it.copy(set = set, loading = false, unavailable = false) }
        val known = attempt {
            profiles.prefetch(set.members)
            profiles.local(set.members).associateBy { it.pubkey }
        } ?: emptyMap()
        // inside the update: a profile that landed meanwhile stays
        _state.update { s ->
            val shown = s.members.associateBy { it.pubkey }
            s.copy(members = set.members.map { pk -> known[pk] ?: shown[pk] ?: Profile.empty(pk) })
        }
    }

    private fun rebuildFeed(set: FollowSet) {
        closeFeed()
        if (set.members.isEmpty() || set.locked) {
            _state.update { it.copy(feed = null) }
            return
        }
        val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        feedScope = child
        val feed = newFeed(FeedSource.Authors(set.members), child)
        _state.update { it.copy(feed = feed) }
        feed.start()
    }

    private fun closeFeed() {
        feedScope?.cancel()
        feedScope = null
    }

    fun selectTab(tab: ListTab) = _state.update { it.copy(tab = tab) }

    fun togglePin() = pinActions.toggle(address, _state.value.pinned)

    /** The ✕ of a member row (own lists); the own lists bring the change, a failure offers Retry. */
    fun removeMember(pubkey: String) {
        writeScope.launch {
            val r = attempt { lists.setMember(address, pubkey, present = false) } ?: SaveResult.Failed
            if (r != SaveResult.Ok) _state.update { it.copy(message = Message(uiText(R.string.list_save_failed), retry = { removeMember(pubkey) })) }
        }
    }

    /** "Locked — tap to unlock". */
    fun unlock() {
        scope.launch { attempt { lists.unlock(address) } }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun close() = closeFeed()
}
