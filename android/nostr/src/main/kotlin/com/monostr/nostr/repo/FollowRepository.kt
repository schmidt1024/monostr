package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.channels.BufferOverflow
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
    /**
     * Spec 2026-10-27 §4: the `p` set of the newest own kind 3; null while no list is stored and no completed fetch
     * proved there is none. Emits again after every accepted write.
     */
    fun followed(): Flow<Set<String>?>
    /**
     * Spec 2026-10-27 §5: one kind 3 adding every pubkey of [pubkeys] not yet followed (the own key is skipped);
     * returns how many were added, 0 = nothing written. Same errors as [follow].
     */
    suspend fun followAll(pubkeys: Collection<String>): Int
    /**
     * Spec 2026-10-17 §4: the first kind 3 of a new account, one `p` per entry of [pubkeys] in order,
     * empty content; never empty. Throws [FollowError.NotAccepted] when no relay took it.
     */
    suspend fun setInitial(pubkeys: List<String>)
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

    override fun followed(): Flow<Set<String>?> = flow {
        val loaded = mutex.withLock { load() }
        snapshot.value = loaded
        emitAll(snapshot.filterNotNull().map { s -> s.followed.takeIf { s.known } }.distinctUntilChanged())
    }

    override suspend fun follow(pubkey: String) {
        require(pubkey != self) { "the own pubkey is never a follow target" }
        write(listOf(pubkey), add = true)
    }

    override suspend fun unfollow(pubkey: String) {
        require(pubkey != self) { "the own pubkey is never a follow target" }
        write(listOf(pubkey), add = false)
    }

    override suspend fun followAll(pubkeys: Collection<String>): Int = write(pubkeys.filter { it != self }.distinct(), add = true)

    // no fetch: a brand-new account has no list to build on (spec 2026-10-17 §7 checks for one before)
    override suspend fun setInitial(pubkeys: List<String>) {
        require(pubkeys.isNotEmpty()) { "an initial contact list is never empty" }
        mutex.withLock {
            val builder = EventBuilder(Kind(3u), "")
                .tags(pubkeys.map { Tag.parse(listOf("p", it)) })
                .customCreatedAt(Timestamp.fromSecs(now().toULong()))
            if (!send(builder).sentToAny) throw FollowError.NotAccepted()
            engine.query(filter()).firstOrNull()?.let { snapshot.value = snapshotOf(it) }
        }
        _changes.tryEmit(Unit)
    }

    /** One relay fetch of the own kind 3 (stored by the engine), see [fetchOwn]; a found list marks the contacts fetched. */
    private suspend fun fetchList(): OwnFetch =
        engine.fetchOwn(filter(), fetchTimeout).also { if (it.found) feed.markContactsFetched(now()) }

    private suspend fun load(): Snapshot {
        var list = engine.query(filter()).firstOrNull()
        if (list != null) return snapshotOf(list)
        val fetch = fetchList()
        list = engine.query(filter()).firstOrNull()
        return if (list != null) snapshotOf(list) else Snapshot(emptySet(), known = fetch.completed)
    }

    /** The last own contact list read (its id) and its snapshot: every profile open reads it (spec perf 3.5). */
    @Volatile private var memo: Pair<String, Snapshot>? = null

    /** The `p` tags read in Kotlin from the event's JSON (one FFI call instead of one per tag; 0.3–0.6 s for ~300 follows), memoised per event id. */
    private fun snapshotOf(list: Event): Snapshot {
        val id = list.id().toHex()
        memo?.let { (memoId, s) -> if (memoId == id) return s }
        return Snapshot(pTags(NoteMapper.decoded(list).tags), known = true).also { memo = id to it }
    }

    private fun pTags(tags: List<List<String>>): Set<String> = tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }.toSet()

    /** Adds or removes [targets] in one kind 3; returns how many `p` tags changed (0 = nothing published). */
    private suspend fun write(targets: List<String>, add: Boolean): Int {
        if (targets.isEmpty()) return 0
        val changed = mutex.withLock {
            val fetch = fetchList()
            val base = engine.query(filter()).firstOrNull()
            val fresh = fetch.found || feed.contactsFetchedAt?.let { now() - it < localMaxAgeSeconds } == true
            // a list we cannot prove current would replace the real one; no list at all is only safe after a completed fetch
            if (base != null && !fresh) throw FollowError.NoList()
            if (base == null && !fetch.completed) throw FollowError.NoList()
            val tags = base?.tags()?.toVec()?.map { it.asVec() } ?: emptyList()
            val present = pTags(tags)
            val todo = targets.filter { (it in present) != add }
            if (todo.isEmpty()) {
                snapshot.value = if (base != null) snapshotOf(base) else Snapshot(emptySet(), known = true)
                return@withLock 0
            }
            val drop = todo.toSet()
            val next = if (add) tags + todo.map { listOf("p", it) } else tags.filterNot { it.size >= 2 && it[0] == "p" && it[1] in drop }
            // kind 3 is replaceable: the new list must be strictly newer than the one it replaces
            val createdAt = maxOf(now(), (base?.createdAt()?.asSecs()?.toLong() ?: 0L) + 1)
            val builder = EventBuilder(Kind(3u), base?.content() ?: "")
                .tags(next.map { Tag.parse(it) })
                .customCreatedAt(Timestamp.fromSecs(createdAt.toULong()))
            if (!send(builder).sentToAny) throw FollowError.NotAccepted()
            engine.query(filter()).firstOrNull()?.let { snapshot.value = snapshotOf(it) }
            todo.size
        }
        if (changed > 0) _changes.tryEmit(Unit)
        return changed
    }
}
