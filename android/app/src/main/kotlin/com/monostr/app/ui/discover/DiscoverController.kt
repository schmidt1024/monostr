package com.monostr.app.ui.discover

import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.userMessage
import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.DiscoverRepository
import com.monostr.nostr.repo.FollowRepository
import com.monostr.nostr.repo.MuteRepository
import com.monostr.nostr.repo.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class DiscoverRow(val profile: Profile, val following: Boolean)

data class DiscoverSection(val address: ListAddress, val title: String, val rows: List<DiscoverRow>, val allFollowed: Boolean)

sealed interface DiscoverStatus {
    data object Loading : DiscoverStatus
    data object Ready : DiscoverStatus
    data object Failed : DiscoverStatus
}

data class DiscoverUi(val status: DiscoverStatus = DiscoverStatus.Loading, val sections: List<DiscoverSection> = emptyList(), val message: UiText? = null)

/**
 * Spec 2026-10-27 §5, §8: the curated sections of the feed block and of «Entdecken». Shows the stored lists at once,
 * refreshes them in the background; [ROWS] rows per list: the first members not followed, not muted and not the
 * user (all followed: the first ones, as following); a shown row stays, as following once followed. Profiles are
 * looked up for the shown rows only.
 */
class DiscoverController(
    private val discover: DiscoverRepository,
    private val follows: FollowRepository,
    private val profiles: ProfileRepository,
    private val mute: MuteRepository,
    private val self: String,
    private val scope: CoroutineScope,
    /** Follow writes run here (the engine's background scope in the app): leaving the screen must not cancel them. */
    private val writeScope: CoroutineScope = scope,
) {
    private val _state = MutableStateFlow(DiscoverUi())
    val state: StateFlow<DiscoverUi> = _state.asStateFlow()

    private val lists = MutableStateFlow<List<FollowSet>?>(null)
    private val failed = MutableStateFlow(false)
    private val known = MutableStateFlow<Map<String, Profile>>(emptyMap())
    /** Pubkeys whose profile was asked for already (local read + prefetch once each). */
    private val asked = ConcurrentHashMap.newKeySet<String>()
    /** Lists whose «follow all» is in flight: a second tap does nothing. */
    private val followingAll = ConcurrentHashMap.newKeySet<ListAddress>()
    private var started = false
    /** The rows each section showed last; only [build] (one collector) touches it. */
    private val shownRows = HashMap<ListAddress, List<String>>()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(lists, follows.followed(), mute.muted, known, failed) { l, f, m, p, fail -> build(l, f ?: emptySet(), m, p, fail) }
                .collect { ui -> _state.update { ui.copy(message = it.message) } }
        }
        scope.launch { profiles.updates.collect { u -> known.update { it + u } } }
        load()
    }

    fun retry() {
        failed.value = false
        load()
    }

    fun follow(pubkey: String) {
        writeScope.launch {
            try {
                follows.follow(pubkey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.userMessage()) }
            }
        }
    }

    fun followAll(address: ListAddress) {
        val set = lists.value?.firstOrNull { it.address == address } ?: return
        if (!followingAll.add(address)) return
        val muted = mute.muted.value
        writeScope.launch {
            try {
                follows.followAll(set.members.filter { it != self && it !in muted })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.userMessage()) }
            } finally {
                followingAll.remove(address)
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun load() {
        scope.launch {
            discover.local().takeIf { it.isNotEmpty() }?.let { lists.value = it }
            discover.refresh()
            val after = discover.local()
            if (after.isNotEmpty()) lists.value = after else if (lists.value == null) failed.value = true
        }
    }

    private fun build(sets: List<FollowSet>?, followed: Set<String>, muted: Set<String>, known: Map<String, Profile>, fail: Boolean): DiscoverUi {
        if (sets == null) return DiscoverUi(if (fail) DiscoverStatus.Failed else DiscoverStatus.Loading)
        val sections = sets.map { set ->
            val candidates = set.members.filter { it != self && it !in muted }.distinct()
            val open = candidates.filter { it !in followed }
            // a row once shown stays (as following after a tap): nothing moves under the finger; free places fill up
            val before = shownRows[set.address].orEmpty().filter { it in candidates }
            val shown = (before + open.filter { it !in before }).take(ROWS).ifEmpty { candidates.take(ROWS) }
            shownRows[set.address] = shown
            DiscoverSection(set.address, set.title, shown.map { DiscoverRow(known[it] ?: Profile.empty(it), it in followed) }, allFollowed = open.isEmpty())
        }
        lookUp(sections.flatMap { s -> s.rows.map { it.profile.pubkey } })
        return DiscoverUi(DiscoverStatus.Ready, sections)
    }

    /** Spec 2026-10-27 §8: stored profiles at once, the rest through the batched background lookup. */
    private fun lookUp(pubkeys: List<String>) {
        val fresh = pubkeys.filter { asked.add(it) }
        if (fresh.isEmpty()) return
        scope.launch {
            val local = profiles.local(fresh).associateBy { it.pubkey }
            if (local.isNotEmpty()) known.update { it + local }
            profiles.prefetch(fresh)
        }
    }

    companion object {
        const val ROWS = 3
    }
}
