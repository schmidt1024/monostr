package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Event
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import java.time.Duration

/** What the profile header shows; null = unknown (the line is left out). */
data class FollowCounts(val following: Int?, val followers: Long?)

/** An index that has seen everybody's contact lists (none wired since 0.22.4; nostr.band was one, offline since 2026-10-04): the numbers other clients show. Null = nothing known. */
interface FollowStatsIndexer {
    suspend fun stats(pubkey: String): FollowCounts?
}

interface FollowCountsRepository {
    /** Emits whenever something new is known (following found, a larger follower count); completes when every relay answered or timed out. */
    fun counts(pubkey: String): Flow<FollowCounts>
    /** Forgets every memoised result (after a follow or unfollow: the own list and the target's followers changed). */
    fun invalidateAll()
}

/**
 * Followers: a NIP-45 COUNT of kind 3 with `#p` = pubkey, asked of every [countRelays] and every
 * relay of the user's own set at once (one without NIP-45 refuses or stays silent: no answer); the
 * largest answer counts, because each relay only knows the contact lists it stores (always a lower
 * bound), and it is shown as soon as the first relay answers. [indexers] (if any) have seen
 * every list and give the number other clients show; it takes the maximum over the counts. An answer above [MAX_FOLLOWERS] (or negative: a u64 that wrapped) is a lying relay and is
 * dropped. Following: the distinct `p` tags of the profile's newest kind 3 from the database, fetched
 * from [indexRelays] when there is none or the stored one is older than a day. A result is kept for
 * [maxAgeSeconds] per pubkey, from its first emission on; the COUNT relays stay attached for the session.
 */
class NostrFollowCountsRepository(
    private val engine: NostrEngine,
    private val countRelays: List<String> = COUNT_RELAYS,
    private val indexRelays: List<String> = NostrProfileRepository.INDEX_RELAYS,
    /** Indexes asked alongside the relays: their follower number is a superset of any COUNT and wins the maximum. */
    private val indexers: List<FollowStatsIndexer> = emptyList(),
    private val timeout: Duration = Duration.ofSeconds(6),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val maxAgeSeconds: Long = 300,
    /** Spec perf2 A6: shares the per-relay COUNT cap with the note counts. */
    private val limiter: CountLimiter = CountLimiter(),
) : FollowCountsRepository {
    /** [complete]: every relay answered or timed out; a partial result (the screen left early) is continued at the next open. */
    private class Known(val counts: FollowCounts, val at: Long, val complete: Boolean)

    private val known = HashMap<String, Known>()
    private val attachMutex = Mutex()
    @Volatile private var countRelaysHeld = false

    override fun counts(pubkey: String): Flow<FollowCounts> = flow {
        val memo = synchronized(known) { known[pubkey] }?.takeIf { now() - it.at < maxAgeSeconds }
        if (memo != null) {
            emit(memo.counts)
            if (memo.complete) return@flow
        }
        ensureCountRelays()
        val author = PublicKey.parse(pubkey)
        val relays = (countRelays + engine.relayUrls()).map { it.trim().trimEnd('/') }.distinct()
        val updates = channelFlow {
            val state = Mutex()
            // a partial memo is the floor: the numbers never go back while the run catches up
            var current = memo?.counts ?: FollowCounts(null, null)
            suspend fun publish(change: (FollowCounts) -> FollowCounts) {
                val next = state.withLock { change(current).also { current = it } }
                send(next)
            }
            // the own list is exact: once it is counted, an indexer's following number never replaces it
            var listed = false
            launch { following(author)?.let { n -> publish { listed = true; it.copy(following = n) } } }
            for (relay in relays) launch {
                val n = limiter.withPermit(relay) { engine.countFrom(relay, Filter().kind(Kind(3u)).pubkey(author), timeout, attach = false) }
                    ?.takeIf { it in 0..MAX_FOLLOWERS } ?: return@launch
                publish { if (n > (it.followers ?: -1)) it.copy(followers = n) else it }
            }
            for (indexer in indexers) launch {
                val stats = try { indexer.stats(pubkey) } catch (e: CancellationException) { throw e } catch (e: Exception) { null } ?: return@launch
                publish { current ->
                    val followers = stats.followers?.takeIf { it in 0..MAX_FOLLOWERS && it > (current.followers ?: -1) } ?: current.followers
                    val following = if (listed || stats.following == null) current.following else stats.following
                    FollowCounts(following, followers)
                }
            }
        }.distinctUntilChanged().filter { it.following != null || it.followers != null }
        var last = memo?.counts
        updates.collect { c ->
            // kept on every emission (spec perf 3.5): a screen that leaves before the slowest relay leaves its numbers for the next open
            synchronized(known) { known[pubkey] = Known(c, now(), complete = false) }
            if (c != last) {
                last = c
                emit(c)
            }
        }
        last?.let { c -> synchronized(known) { known[pubkey] = Known(c, now(), complete = true) } }
    }

    /** The COUNT relays join once and stay for the session (spec perf 3.5): attaching and force-removing them per profile cost a connection each time. */
    private suspend fun ensureCountRelays() = attachMutex.withLock {
        if (countRelaysHeld || countRelays.isEmpty()) return@withLock
        withContext(NonCancellable) { engine.attachTemporary(countRelays) }
        countRelaysHeld = true
    }

    override fun invalidateAll() {
        synchronized(known) { known.clear() }
    }

    private suspend fun following(author: PublicKey): Int? {
        val filter = Filter().kind(Kind(3u)).author(author).limit(1u)
        var list = engine.query(filter).firstOrNull()
        val stale = list == null || now() - list.createdAt().asSecs().toLong() > LIST_MAX_AGE_SECONDS
        if (stale && indexRelays.isNotEmpty()) {
            // the index relays are asked whether or not they are the user's own; attachTemporary takes
            // a reference only on the ones it added, and NonCancellable keeps that reference from
            // being lost to a cancellation that lands on the attach's own return
            val held = withContext(NonCancellable) { engine.attachTemporary(indexRelays) }
            try {
                engine.fetchFrom(indexRelays, filter, timeout, attach = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            } finally {
                engine.detachTemporary(held)
            }
            list = engine.query(filter).firstOrNull()
        }
        return list?.let(::followed)
    }

    /** Distinct well-formed `p` tags, read in Kotlin (0.3–2 s through rust for 300–1500 tags). */
    private fun followed(list: Event): Int =
        NoteMapper.decoded(list).tags.filter { it.size >= 2 && it[0] == "p" && HEX64.matches(it[1]) }.map { it[1] }.toSet().size

    companion object {
        /** Relays that answer NIP-45 COUNT (probed 2026-10-08; purplepag.es and snort do not, nostr.band is offline since 2026-10-04). */
        val COUNT_RELAYS = listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://nos.lol")
        /** No account has more followers than this; a bigger answer is a relay making numbers up. */
        const val MAX_FOLLOWERS = 100_000_000L
        /** A stored contact list older than this is asked for again before it is counted. */
        const val LIST_MAX_AGE_SECONDS = 86_400L
        private val HEX64 = Regex("[0-9a-f]{64}")
    }
}
