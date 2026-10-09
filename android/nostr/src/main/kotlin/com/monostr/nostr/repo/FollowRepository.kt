package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.time.Duration

/** Spec 2: whether the user follows a pubkey. [Unknown]: no contact list is known yet (the button waits). */
enum class FollowState { Following, NotFollowing, Unknown }

sealed class FollowError(message: String) : Exception(message) {
    /** No current contact list (relay fetch empty, no local list fresher than five minutes): nothing was published. */
    class NoList : FollowError("no contact list known")
    /** No relay accepted the new list: the local list is unchanged. */
    class NotAccepted : FollowError("no relay accepted the contact list")
}

/** Spec 2: follow and unfollow on the user's kind 3. */
interface FollowRepository {
    /** From the newest stored kind 3; a successful write updates it. */
    fun state(pubkey: String): Flow<FollowState>
    /** Emits after every write that a relay accepted (the feed reloads on it). */
    val changes: SharedFlow<Unit>
    suspend fun follow(pubkey: String)
    suspend fun unfollow(pubkey: String)
}

/**
 * Writes kind 3 only on top of a current list: the list is fetched from the relays first (5 s), and a
 * local list counts only when a relay delivered it less than [localMaxAgeSeconds] ago. An empty list
 * would unfollow everyone, so without a current list nothing is written ([FollowError.NoList]); the one
 * exception is an account that provably has no list yet (no local copy, and a completed fetch found
 * none: see [fetchList]), which gets a new list with the single `p`. Every other tag and the
 * `content` (relay JSON of older clients) are copied unchanged; [send] stores locally only after an OK.
 */
class NostrFollowRepository(
    private val engine: NostrEngine,
    private val feed: FeedRepository,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val send: suspend (EventBuilder) -> PublishResult = { engine.signAndPublish(it) },
    private val fetchTimeout: Duration = Duration.ofSeconds(5),
    private val localMaxAgeSeconds: Long = 300,
) : FollowRepository {
    private val self = requireNotNull(engine.pubkey) { "follows need a logged-in user" }
    private val mutex = Mutex()

    /** [followed]: `p` values of the newest list; [known]: a list exists or is provably absent. */
    private class Snapshot(val followed: Set<String>, val known: Boolean)

    private val snapshot = MutableStateFlow<Snapshot?>(null)
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val changes: SharedFlow<Unit> = _changes

    private fun filter() = Filter().kind(Kind(3u)).author(PublicKey.parse(self)).limit(1u)

    override fun state(pubkey: String): Flow<FollowState> = flow {
        // re-read on every collection (a feed fetch may have stored the list meanwhile); fetch only when nothing is stored
        val loaded = mutex.withLock { load() }
        snapshot.value = loaded
        emitAll(
            snapshot.filterNotNull().map { s ->
                when {
                    pubkey in s.followed -> FollowState.Following
                    s.known -> FollowState.NotFollowing
                    else -> FollowState.Unknown
                }
            }.distinctUntilChanged(),
        )
    }

    override suspend fun follow(pubkey: String) = write(pubkey, add = true)

    override suspend fun unfollow(pubkey: String) = write(pubkey, add = false)

    private class Fetch(val found: Boolean, val completed: Boolean)

    /**
     * One relay fetch of the own kind 3 (stored by the engine). Completed, i.e. an empty result means
     * "no list anywhere": the fetch returned before its timeout without an error, EVERY configured
     * normal relay was connected before and after it (rust-nostr ends the fetch on the EOSE of the
     * relays it reached, so one connected relay proves nothing about the others), and none of them
     * sent a CLOSED meanwhile (a refusal is no answer).
     */
    private suspend fun fetchList(): Fetch {
        val configured = engine.relayUrls().toSet()
        val connectedBefore = engine.connectedNormalRelayUrls()
        val mark = engine.closedMark()
        val started = System.nanoTime()
        val events = try {
            engine.fetch(filter(), fetchTimeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Fetch(found = false, completed = false)
        }
        val inTime = System.nanoTime() - started < fetchTimeout.toNanos()
        if (events.isNotEmpty()) feed.markContactsFetched(now())
        val everyRelay = configured.isNotEmpty() && connectedBefore.containsAll(configured)
        // The notification loop that records CLOSED runs apart from the fetch: give a frame that came
        // in just before the fetch returned the moment to land (only where it decides anything).
        if (events.isEmpty() && inTime && everyRelay && !engine.closedSince(mark, configured)) {
            withContext(Dispatchers.Default) { delay(CLOSED_SETTLE_MS) }
        }
        val completed = inTime && everyRelay && engine.connectedNormalRelayUrls().containsAll(configured) && !engine.closedSince(mark, configured)
        return Fetch(events.isNotEmpty(), completed)
    }

    private suspend fun load(): Snapshot {
        var list = engine.query(filter()).firstOrNull()
        if (list != null) return snapshotOf(list)
        val fetch = fetchList()
        list = engine.query(filter()).firstOrNull()
        return if (list != null) snapshotOf(list) else Snapshot(emptySet(), known = fetch.completed)
    }

    private fun snapshotOf(list: Event) = Snapshot(pTags(list.tags().toVec().map { it.asVec() }), known = true)

    private fun pTags(tags: List<List<String>>): Set<String> = tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }.toSet()

    private suspend fun write(target: String, add: Boolean) {
        require(target != self) { "the own pubkey is never a follow target" }
        val wrote = mutex.withLock {
            val fetch = fetchList()
            val base = engine.query(filter()).firstOrNull()
            val fresh = fetch.found || feed.contactsFetchedAt?.let { now() - it < localMaxAgeSeconds } == true
            // a list we cannot prove current would replace the real one; no list at all is only safe after a completed fetch
            if (base != null && !fresh) throw FollowError.NoList()
            if (base == null && !fetch.completed) throw FollowError.NoList()
            val tags = base?.tags()?.toVec()?.map { it.asVec() } ?: emptyList()
            val has = tags.any { it.size >= 2 && it[0] == "p" && it[1] == target }
            if (add == has) {
                snapshot.value = if (base != null) snapshotOf(base) else Snapshot(emptySet(), known = true)
                return@withLock false
            }
            val next = if (add) tags + listOf(listOf("p", target)) else tags.filterNot { it.size >= 2 && it[0] == "p" && it[1] == target }
            // kind 3 is replaceable: the new list must be strictly newer than the one it replaces
            val createdAt = maxOf(now(), (base?.createdAt()?.asSecs()?.toLong() ?: 0L) + 1)
            val builder = EventBuilder(Kind(3u), base?.content() ?: "")
                .tags(next.map { Tag.parse(it) })
                .customCreatedAt(Timestamp.fromSecs(createdAt.toULong()))
            if (!send(builder).sentToAny) throw FollowError.NotAccepted()
            engine.query(filter()).firstOrNull()?.let { snapshot.value = snapshotOf(it) }
            true
        }
        if (wrote) _changes.tryEmit(Unit)
    }

    private companion object {
        const val CLOSED_SETTLE_MS = 100L
    }
}
