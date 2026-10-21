package com.monostr.app.ui.feed

import com.monostr.nostr.StartupGate
import com.monostr.app.R
import com.monostr.app.data.FeedSnapshot
import com.monostr.app.data.ListSnapshots
import com.monostr.app.ui.common.Message
import com.monostr.app.ui.common.attempt
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.SaveResult
import com.monostr.nostr.repo.FeedSource
import com.monostr.nostr.repo.FeedTabsRepository
import com.monostr.nostr.repo.ListsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How a pinned list stands (spec 2026-10-18 §2.1, §5). */
enum class ListStatus { LOADING, READY, EMPTY, UNAVAILABLE, LOCKED }

/** A tab of the home feed: Following first, then the pinned lists in their stored order. */
sealed interface HomeTab {
    val key: String

    data object Following : HomeTab {
        override val key: String = "following"
    }

    /** [title] is empty for an untitled list or one not loaded yet. */
    data class Pinned(val address: ListAddress, val title: String, val status: ListStatus) : HomeTab {
        override val key: String get() = address.toString()
    }
}

data class HomeTabsState(
    val tabs: List<HomeTab> = listOf(HomeTab.Following),
    val selected: Int = 0,
    /** The feed of each list tab opened so far, by [HomeTab.key] (Following's is [HomeTabsController.following]). */
    val feeds: Map<String, FeedController> = emptyMap(),
    val message: Message? = null,
) {
    /** The row shows once a list is pinned (spec §2.1). */
    val showRow: Boolean get() = tabs.size > 1
    val current: HomeTab get() = tabs.getOrElse(selected) { HomeTab.Following }
}

/**
 * The home feed's tabs (spec 2026-10-18 §4.3): one [FeedController] per list tab, created when the tab is
 * first shown, rebuilt when the list's members change, closed when it is unpinned. Only the visible tab is
 * live; switching to a tab refreshes it.
 */
class HomeTabsController(
    private val pins: FeedTabsRepository,
    private val lists: ListsRepository,
    private val scope: CoroutineScope,
    private val self: String,
    val following: FeedController,
    /** Spec perf 3.7: a feed snapshot per pinned list; null (tests that do not care) keeps none. */
    private val snapshots: ListSnapshots? = null,
    /** Spec perf2 A3: a foreign pinned list is read from the database at once, from the relays after the home feed's first page. */
    private val gate: StartupGate = StartupGate.OPEN,
    private val newFeed: (FeedSource.Authors, CoroutineScope, FeedSnapshot?) -> FeedController,
) {
    private val _state = MutableStateFlow(HomeTabsState())
    val state: StateFlow<HomeTabsState> = _state.asStateFlow()

    /** A list tab's feed, its own scope (cancelled on close) and the members it was built for, as a set: their order does not matter. */
    private class Slot(val members: Set<String>, val scope: CoroutineScope, val feed: FeedController)

    private val slots = HashMap<String, Slot>()

    /** Lists looked up by address (foreign, or own ones the start load did not have); null = found nowhere. */
    private val found = MutableStateFlow<Map<String, FollowSet?>>(emptyMap())
    private val looking = HashSet<String>()
    private var sets: Map<String, FollowSet> = emptyMap()
    /** The tab titles are marked once, when no pinned tab is loading any more (spec perf 3.9). */
    private var titlesMarked = false
    /** The pins the list snapshots were last trimmed to. */
    private var lastPinned: List<ListAddress>? = null

    fun start() {
        val own: Flow<List<FollowSet>?> = lists.own().map<List<FollowSet>, List<FollowSet>?> { it }.onStart { emit(null) }
        scope.launch {
            combine(pins.tabs(), own, found) { p, o, f -> Triple(p, o, f) }.collect { (p, o, f) -> rebuild(p, o, f) }
        }
    }

    private fun statusOf(set: FollowSet): ListStatus = when {
        set.locked -> ListStatus.LOCKED
        set.members.isEmpty() -> ListStatus.EMPTY
        else -> ListStatus.READY
    }

    private fun rebuild(pinned: List<ListAddress>, own: List<FollowSet>?, lookedUp: Map<String, FollowSet?>) {
        if (pinned != lastPinned) {
            lastPinned = pinned
            // a list unpinned here or on another device loses its snapshot (spec perf 3.7, privacy)
            snapshots?.let { s -> scope.launch { s.retain(pinned) } }
        }
        val resolved = pinned.map { address ->
            val key = address.toString()
            val mine = if (address.pubkey == self) own?.firstOrNull { it.address == address } else null
            val (set, status) = when {
                address.pubkey == self && own == null -> null to ListStatus.LOADING
                mine != null -> mine to statusOf(mine)
                key !in lookedUp -> { lookUp(address); null to ListStatus.LOADING }
                else -> lookedUp[key].let { s -> s to (s?.let(::statusOf) ?: ListStatus.UNAVAILABLE) }
            }
            HomeTab.Pinned(address, set?.title.orEmpty(), status) to set
        }
        sets = resolved.mapNotNull { (tab, set) -> set?.let { tab.key to it } }.toMap()
        // a list that was unpinned, is no longer readable or whose member SET changed loses its feed (spec §4.3, perf 3.7):
        // a relay result with the same members in another order must not throw the tab's notes away
        slots.keys.toList().forEach { key ->
            val ready = sets[key]?.takeIf { statusOf(it) == ListStatus.READY }
            if (ready == null || ready.members.toSet() != slots.getValue(key).members) close(key)
        }
        val before = _state.value
        val tabs = listOf<HomeTab>(HomeTab.Following) + resolved.map { it.first }
        val selected = tabs.indexOfFirst { it.key == before.current.key }.takeIf { it >= 0 } ?: 0
        if (selected == 0 && before.current !is HomeTab.Following) following.setLive(true)
        _state.update { it.copy(tabs = tabs, selected = selected) }
        if (!titlesMarked && resolved.isNotEmpty() && resolved.none { it.first.status == ListStatus.LOADING }) {
            titlesMarked = true
            PerfTrace.mark("tab title shown")
        }
        activate()
    }

    private fun lookUp(address: ListAddress) {
        val key = address.toString()
        if (!looking.add(key)) return
        scope.launch {
            attempt { lists.byAddress(address, fetch = false) }?.let { set -> found.update { it + (key to set) } }
            gate.await()
            val fetched = attempt { lists.byAddress(address, fetch = true) }
            found.update { it + (key to (fetched ?: it[key])) } // a failed fetch keeps what the database had
            looking -= key
        }
    }

    /** The visible list tab gets its feed (created on first view, after a member change anew) and is live. */
    private fun activate() {
        val tab = _state.value.current as? HomeTab.Pinned ?: return
        if (tab.status != ListStatus.READY) return
        slots[tab.key]?.let { it.feed.setLive(true); return }
        val set = sets[tab.key] ?: return
        val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val feed = newFeed(FeedSource.Authors(set.members), child, snapshots?.of(set.address))
        slots[tab.key] = Slot(set.members.toSet(), child, feed)
        _state.update { it.copy(feeds = it.feeds + (tab.key to feed)) }
        feed.start()
    }

    private fun close(key: String) {
        slots.remove(key)?.scope?.cancel()
        _state.update { it.copy(feeds = it.feeds - key) }
    }

    /** Shows tab [index]: the tab left pauses its live subscription, the new one goes live and refreshes. */
    fun select(index: Int) {
        val s = _state.value
        if (index !in s.tabs.indices || index == s.selected) return
        feedOf(s.current)?.setLive(false)
        _state.update { it.copy(selected = index) }
        when (s.tabs[index]) {
            HomeTab.Following -> following.setLive(true)
            is HomeTab.Pinned -> activate()
        }
    }

    /** The feed shown for [tab]; null for a list tab that is not ready or not opened yet. */
    fun feedOf(tab: HomeTab): FeedController? = when (tab) {
        HomeTab.Following -> following
        is HomeTab.Pinned -> slots[tab.key]?.feed
    }

    fun unpin(address: ListAddress) = write { pins.unpin(address) }
    fun move(address: ListAddress, delta: Int) = write { pins.move(address, delta) }

    /** "Locked — tap to unlock": decrypts again, asking the signer; the own lists bring the result. */
    fun unlock(address: ListAddress) {
        scope.launch { attempt { lists.unlock(address) } }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** A pin change; the repository rolls a failure back, the snackbar offers to repeat it (spec §3.3). */
    private fun write(op: suspend () -> SaveResult) {
        scope.launch {
            val r = attempt { op() } ?: SaveResult.Failed
            if (r == SaveResult.Failed) _state.update { it.copy(message = Message(uiText(R.string.list_save_failed), retry = { write(op) })) }
        }
    }
}
