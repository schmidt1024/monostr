package com.monostr.nostr.repo

import com.monostr.nostr.Exit
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.RefreshGate
import com.monostr.nostr.Signer
import com.monostr.nostr.StartupGate
import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.SaveResult
import com.monostr.tips.event.Event as JsonEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** NIP-51 follow sets (kind 30000) of the user and of others (spec 2026-10-18 §3, §4.1). */
interface ListsRepository {
    /** The user's own lists, newest first: the persisted summary, then the database; the relays are asked behind them (spec perf 3.7). */
    fun own(): Flow<List<FollowSet>>
    /** [author]'s lists from the database only (spec perf 3.7): the own ones as loaded, a foreign account's stored public part. */
    suspend fun localOf(author: String): List<FollowSet>
    /** [author]'s lists: for the user the own ones, else the public part of each (spec §2.2), newest first; a foreign account is asked of the relays at most once per 10 min. */
    suspend fun of(author: String): List<FollowSet>
    /** One list, or null when it is deleted or found nowhere. [fetch] false reads the database only. */
    suspend fun byAddress(address: ListAddress, fetch: Boolean = true): FollowSet?
    /** Publishes the whole new [set] (create, edit, private switch); the own lists change at once, a failure rolls back. */
    suspend fun save(set: FollowSet): SaveResult
    /** Sets title, description and private on the list's current state (members as they are now), under the list's lock. */
    suspend fun edit(address: ListAddress, title: String, description: String?, private: Boolean): SaveResult
    /** Adds or removes one member on top of the last accepted state; calls per list run one after another (spec §3.4). */
    suspend fun setMember(address: ListAddress, pubkey: String, present: Boolean): SaveResult
    /** Kind 5 for the list's address, then an empty replacement (spec §3.2). */
    suspend fun delete(set: FollowSet): SaveResult
    /** Decrypts a locked own list again, asking the signer; true when it is readable now. */
    suspend fun unlock(address: ListAddress): Boolean
    /** A fresh `d` for a new list: 12 characters a-z0-9 (spec §3.1). */
    fun newId(): String
}

class NostrListsRepository(
    private val engine: NostrEngine,
    private val signer: Signer,
    private val authorRelays: AuthorRelaysRepository,
    /** The app's default relays: lists are looked up there too (spec §4.2). */
    private val defaultRelays: List<String> = emptyList(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val send: suspend (EventBuilder) -> PublishResult = { engine.signAndPublish(it) },
    private val fetchTimeout: Duration = Duration.ofSeconds(6),
    private val random: java.util.Random = SecureRandom(),
    /** Spec perf 3.7: the own lists as last read, per account (app-private, private members decrypted); null keeps nothing. */
    private val cache: OwnListsCache? = null,
    /** Where the relay reconcile runs after the local lists are shown (session: the engine's background scope); null (tests) = only [reconcileOwn]. */
    private val reconcileScope: CoroutineScope? = null,
    /** Spec perf2 A3: the relay reconcile waits until the home feed's first page is in; a write opens it (A2 (5)). */
    private val gate: StartupGate = StartupGate.OPEN,
) : ListsRepository {
    private val privateTags = PrivateTags(signer)

    /** Null until the summary or the database answered. */
    private val _own = MutableStateFlow<List<FollowSet>?>(null)
    private val loadMutex = Mutex()
    private val persistMutex = Mutex()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** The database read of the own lists ran (the summary alone does not count: writes build on the database). */
    @Volatile private var loaded = false

    /** The relay reconcile: once per repository once a relay replied; a run without any reply stays unstamped and runs again at the next collection. */
    private val ownReconcile = reconcileScope?.let { RefreshGate<String>(it, ttlSeconds = Long.MAX_VALUE, now = now) }

    /** The first reconcile run of this repository finished (replied or not): own-list writes no longer wait for it (R16). */
    private val firstReconcileDone = CompletableDeferred<Unit>()

    /** Own lists by the event they were read from: an unchanged private list is never decrypted again (spec perf 3.7: one Amber call per list and start before). */
    private val byEventId = ConcurrentHashMap<String, FollowSet>()

    /** Foreign lists asked of the relays in this session: not again within [FOREIGN_TTL_SECONDS] (spec perf 3.7). */
    private val foreignRefresh = RefreshGate<String>(engine.backgroundScope, ttlSeconds = FOREIGN_TTL_SECONDS, now = now)

    /** Newest `created_at` of any own list event seen or written: every write is strictly newer (replaceable, NIP-01). */
    @Volatile private var lastCreatedAt = 0L

    private fun kind() = Kind(FollowSetCodec.KIND.toUShort())
    private fun authorFilter(author: String) = Filter().kind(kind()).author(PublicKey.parse(author))
    private fun ownFilter() = Filter().kind(kind()).author(PublicKey.parse(signer.pubkey))
    private fun lockFor(d: String): Mutex = locks.computeIfAbsent(d) { Mutex() }
    /** Newer than any own list event seen and than [after], the version being replaced (it may come from a clock ahead). */
    private fun nextCreatedAt(after: Long = 0): Long = maxOf(now(), lastCreatedAt + 1, after + 1).also { lastCreatedAt = it }

    override fun own(): Flow<List<FollowSet>> = flow {
        coroutineScope {
            // the summary and then the database land in _own while it is collected: nothing waits for a relay (spec perf 3.7)
            launch { ensureOwn() }
            startReconcile()
            emitAll(_own.filterNotNull())
        }
    }

    /**
     * Spec perf 3.7: the persisted summary first (no database, no signer), then the database through the decrypt
     * cache, then the relays in the background. Before, the relays were asked first (6 s + 6 s) and the tab row
     * said "Untitled list" meanwhile.
     */
    private suspend fun ensureOwn() {
        loadMutex.withLock {
            if (loaded) return@withLock
            if (_own.value == null) adoptSummary()
            _own.value = readOwn()
            loaded = true
            persist()
        }
    }

    /** The summary's lists as the first state; their event ids seed the decrypt cache. A broken or missing summary is skipped. */
    private suspend fun adoptSummary() {
        val stored = try {
            cache?.load(signer.pubkey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        val sets = stored.mapNotNull { it.toSet() }.filter { it.author == signer.pubkey }
        sets.forEach { set ->
            if (set.eventId.isNotEmpty()) byEventId[set.eventId] = set
            lastCreatedAt = maxOf(lastCreatedAt, set.createdAt)
        }
        _own.value = sets.sortedByDescending { it.createdAt }
    }

    private suspend fun readOwn(): List<FollowSet> {
        val events = newestPerD(engine.queryDecoded(ownFilter()))
        events.forEach { lastCreatedAt = maxOf(lastCreatedAt, it.createdAt) }
        return events.mapNotNull { decodeOwn(it, interactive = false) }.sortedByDescending { it.createdAt }
    }

    /** [event] as an own list; one whose id was read before is taken as it was, without the signer (spec perf 3.7). */
    private suspend fun decodeOwn(event: JsonEvent, interactive: Boolean): FollowSet? {
        byEventId[event.id]?.let { return it }
        val set = FollowSetCodec.decode(event, privateTags, interactive) ?: return null
        if (!set.locked) byEventId[event.id] = set
        return set
    }

    /** What the own lists are now goes into the summary; locked ones are left out, they hold nothing readable. Best effort. */
    private suspend fun persist() {
        val c = cache ?: return
        persistMutex.withLock {
            val lists = _own.value ?: return@withLock
            quietly { c.save(signer.pubkey, lists.filterNot { it.locked }.map(CachedList::of)) }
        }
    }

    /**
     * Once per repository, on [reconcileScope]: the local lists (again on this scope, so a collector that left
     * early cannot cancel it), then the relays. Started again by a later collection while no relay has replied.
     */
    private fun startReconcile() {
        val run = ownReconcile?.launchStamped(RECONCILE_KEY) {
            // the tab titles come from here at once (perf 3.7); only the relays wait
            ensureOwn()
            gate.await()
            reconcileOwn()
        }
        // replied or not, failed, or cancelled before it began (the scope ended): writes stop waiting for it
        run?.invokeOnCompletion { firstReconcileDone.complete(Unit) }
    }

    /**
     * R16: a write right after start builds on what the relays hold, not only on the database (a version from
     * another device would be overwritten). Waits for the first reconcile (starting it when no collection did),
     * at most [fetchTimeout]; offline the write goes on from the database. Called before the list's lock: the
     * reconcile takes the same lock. No wait once it ran, nor without [reconcileScope].
     */
    private suspend fun awaitFirstReconcile() {
        if (ownReconcile == null || firstReconcileDone.isCompleted) return
        // the user's write goes before the feed's first page (opened here, outside the list lock)
        gate.open("write")
        startReconcile()
        withTimeoutOrNull(fetchTimeout.toMillis()) { firstReconcileDone.await() }
    }

    /**
     * Spec perf 3.7: asks the session and the default relays (side by side) for the own lists and takes over every
     * version newer than the shown one, each under its list's lock, so a write in flight is never undone. An empty
     * replacement removes the list; a list the database does not hold at all is kept as it is. True when a relay
     * replied (the versions taken over are then the relays' word).
     */
    internal suspend fun reconcileOwn(): Boolean {
        val replied = fetchWide(engine, ownFilter(), defaultRelays, fetchTimeout)
        val ds = newestPerD(engine.queryDecoded(ownFilter())).mapNotNull { it.firstTagValue("d") }
        var changed = false
        for (d in ds) {
            lockFor(d).withLock {
                // read again under the lock: a write that held it has stored its version meanwhile
                val latest = engine.queryDecoded(ownFilter().identifier(d)).maxByOrNull { it.createdAt } ?: return@withLock
                lastCreatedAt = maxOf(lastCreatedAt, latest.createdAt)
                val current = _own.value?.firstOrNull { it.address.d == d }
                if (current != null && latest.createdAt <= current.createdAt) return@withLock
                val set = decodeOwn(latest, interactive = false)
                if (set != null) {
                    putOwn(set)
                    changed = true
                } else if (current != null) {
                    _own.update { cur -> cur.orEmpty().filterNot { it.address.d == d } }
                    changed = true
                }
            }
        }
        if (changed) persist()
        return replied
    }

    private fun putOwn(set: FollowSet) {
        lastCreatedAt = maxOf(lastCreatedAt, set.createdAt)
        _own.update { cur -> (listOf(set) + cur.orEmpty().filterNot { it.address == set.address }).sortedByDescending { it.createdAt } }
    }

    /** Puts [previous] back for [address] (or removes the entry when there was none). */
    private fun restoreOwn(address: ListAddress, previous: FollowSet?) =
        _own.update { cur -> (cur.orEmpty().filterNot { it.address == address } + listOfNotNull(previous)).sortedByDescending { it.createdAt } }

    override suspend fun localOf(author: String): List<FollowSet> {
        if (author == signer.pubkey) {
            ensureOwn()
            return _own.value.orEmpty()
        }
        return newestPerD(engine.queryDecoded(authorFilter(author))).mapNotNull { FollowSetCodec.decode(it, null, interactive = false) }.sortedByDescending { it.createdAt }
    }

    override suspend fun of(author: String): List<FollowSet> {
        if (author == signer.pubkey) return localOf(author)
        val filter = authorFilter(author)
        // the author's write relays are looked up inside the second leg: the session relays are asked at once
        foreignRefresh.awaitStamped("author:$author") { fetchWide(engine, filter, fetchTimeout) { authorRelays.writeRelays(author) + defaultRelays } }
        return localOf(author)
    }

    override suspend fun byAddress(address: ListAddress, fetch: Boolean): FollowSet? {
        if (address.pubkey == signer.pubkey) {
            ensureOwn()
            _own.value?.firstOrNull { it.address == address }?.let { return it }
            if (!fetch) return null
            // not among the lists read at start (offline then, or made on another device since): one fetch for it.
            // Under the list's lock: a delete in flight finishes first, so its old version is not put back.
            return lockFor(address.d).withLock {
                _own.value?.firstOrNull { it.address == address }?.let { return@withLock it }
                val filter = ownFilter().identifier(address.d)
                fetchWide(engine, filter, defaultRelays, fetchTimeout)
                val set = engine.queryDecoded(filter).maxByOrNull { it.createdAt }?.let { decodeOwn(it, interactive = false) } ?: return@withLock null
                putOwn(set)
                persist()
                set
            }
        }
        val filter = authorFilter(address.pubkey).identifier(address.d)
        if (fetch) foreignRefresh.awaitStamped("list:$address") { fetchWide(engine, filter, fetchTimeout, Exit.NewestPerAuthor(setOf(address.pubkey))) { authorRelays.writeRelays(address.pubkey) + defaultRelays } }
        return engine.queryDecoded(filter).maxByOrNull { it.createdAt }?.let { FollowSetCodec.decode(it, null, interactive = false) }
    }

    override suspend fun save(set: FollowSet): SaveResult {
        awaitFirstReconcile()
        return lockFor(set.address.d).withLock { write(set) }
    }

    override suspend fun edit(address: ListAddress, title: String, description: String?, private: Boolean): SaveResult {
        awaitFirstReconcile()
        return lockFor(address.d).withLock { editLocked(address, title, description, private) }
    }

    private suspend fun editLocked(address: ListAddress, title: String, description: String?, private: Boolean): SaveResult {
        ensureOwn()
        val current = _own.value?.firstOrNull { it.address == address } ?: return SaveResult.Failed
        return write(current.copy(title = title, description = description, private = private))
    }

    override suspend fun setMember(address: ListAddress, pubkey: String, present: Boolean): SaveResult {
        awaitFirstReconcile()
        return lockFor(address.d).withLock { setMemberLocked(address, pubkey, present) }
    }

    private suspend fun setMemberLocked(address: ListAddress, pubkey: String, present: Boolean): SaveResult {
        ensureOwn()
        val current = _own.value?.firstOrNull { it.address == address } ?: return SaveResult.Failed
        if (current.locked) return SaveResult.Locked
        if ((pubkey in current.members) == present) return SaveResult.Ok
        return write(current.copy(members = if (present) current.members + pubkey else current.members - pubkey))
    }

    /** The write behind [save] and [setMember]; the caller holds the list's lock. */
    private suspend fun write(set: FollowSet): SaveResult {
        ensureOwn()
        val previous = _own.value?.firstOrNull { it.address == set.address }
        if (previous?.locked == true) return SaveResult.Locked
        val ts = nextCreatedAt(after = previous?.createdAt ?: 0)
        // no event id until a relay took it: a summary written meanwhile must not file these members under the old event
        val next = set.copy(author = signer.pubkey, createdAt = ts, locked = false, eventId = "")
        putOwn(next) // the UI updates at once (spec §3.4)
        val result = try {
            val (tags, content) = FollowSetCodec.encode(next, privateTags)
            send(EventBuilder(kind(), content).tags(tags.map { Tag.parse(it) }).customCreatedAt(Timestamp.fromSecs(ts.toULong())))
        } catch (e: CancellationException) {
            restoreOwn(set.address, previous)
            throw e
        } catch (e: Exception) {
            null
        }
        if (result == null || !result.sentToAny) {
            restoreOwn(set.address, previous)
            persist() // another write may have put the unpublished state into the summary meanwhile
            return SaveResult.Failed
        }
        // the accepted version under its event's id: the next start takes it without the signer (spec perf 3.7)
        val stored = next.copy(eventId = result.eventId)
        byEventId[result.eventId] = stored
        putOwn(stored)
        persist()
        return SaveResult.Ok
    }

    override suspend fun delete(set: FollowSet): SaveResult {
        awaitFirstReconcile()
        return lockFor(set.address.d).withLock { deleteLocked(set) }
    }

    private suspend fun deleteLocked(set: FollowSet): SaveResult {
        ensureOwn()
        val previous = _own.value?.firstOrNull { it.address == set.address }
        _own.update { cur -> cur.orEmpty().filterNot { it.address == set.address } }
        val ok = try {
            // the kind 5 deletes versions up to its own created_at (NIP-09): the empty replacement must be newer
            val deletionAt = nextCreatedAt(after = maxOf(previous?.createdAt ?: 0, set.createdAt))
            quietly {
                send(
                    EventBuilder(Kind(5u), "")
                        .tags(listOf(Tag.parse(listOf("a", set.address.toString())), Tag.parse(listOf("k", FollowSetCodec.KIND.toString()))))
                        .customCreatedAt(Timestamp.fromSecs(deletionAt.toULong())),
                )
            }
            val replacementAt = nextCreatedAt()
            send(EventBuilder(kind(), "").tags(listOf(Tag.parse(listOf("d", set.address.d)))).customCreatedAt(Timestamp.fromSecs(replacementAt.toULong()))).sentToAny
        } catch (e: CancellationException) {
            restoreOwn(set.address, previous)
            throw e
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            restoreOwn(set.address, previous)
            persist() // another write may have put the unpublished state into the summary meanwhile
            return SaveResult.Failed
        }
        // its decrypted members leave the memory and the summary with it (spec perf 3.7, privacy)
        byEventId.entries.removeAll { it.value.address == set.address }
        persist()
        return SaveResult.Ok
    }

    override suspend fun unlock(address: ListAddress): Boolean = lockFor(address.d).withLock {
        ensureOwn()
        val event = engine.queryDecoded(ownFilter().identifier(address.d)).maxByOrNull { it.createdAt } ?: return@withLock false
        val set = FollowSetCodec.decode(event, privateTags, interactive = true) ?: return@withLock false
        if (!set.locked) byEventId[event.id] = set
        putOwn(set)
        persist()
        !set.locked
    }

    override fun newId(): String = String(CharArray(ID_LENGTH) { ID_CHARS[random.nextInt(ID_CHARS.length)] })

    companion object {
        /** A foreign list is asked of the relays at most this often (spec perf 3.7). */
        const val FOREIGN_TTL_SECONDS = 600L
        private const val RECONCILE_KEY = "own"
        private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
        private const val ID_LENGTH = 12
    }
}

/** The newest event per `d`: relays and the database may still hold older versions of an addressable event. */
internal fun newestPerD(events: List<JsonEvent>): List<JsonEvent> =
    events.groupBy { it.firstTagValue("d") ?: "" }.values.map { group -> group.maxBy { it.createdAt } }

internal fun newest(events: List<Event>): Event? = events.maxByOrNull { it.createdAt().asSecs() }

/**
 * Fetches [filter] from the session's relays and, side by side (spec perf 3.7: they ran one after another, 6 s +
 * 6 s), from the relays [extra] names (another account's write relays, the default relays), the latter attached
 * only for the call (spec §4.2). [extra] is computed inside its own leg, so a slow write relay lookup never holds
 * back the session relays. Never throws except on cancellation. True when a relay answered: events, or an end of
 * stored events before the timeout; false when every leg failed or ran into its timeout (offline, relays down).
 * With [exit] (spec wave 2 B4, foreign lists only) the first leg that meets it ends the other one; that counts as a reply.
 */
internal suspend fun fetchWide(
    engine: NostrEngine,
    filter: Filter,
    timeout: Duration,
    exit: Exit? = null,
    extra: suspend () -> List<String>,
): Boolean = coroutineScope {
    val metAny = java.util.concurrent.atomic.AtomicBoolean(false)
    // an early-exit leg ends at the engine's cap, so an empty end is judged against that, not the full timeout: a silent relay is no reply
    val replyTimeout = if (exit == null) timeout else engine.earlyExitCap?.let { cap -> if (cap < timeout) cap else timeout } ?: timeout
    lateinit var session: Deferred<Boolean>
    lateinit var others: Deferred<Boolean>
    session = async(start = CoroutineStart.LAZY) {
        var up = false
        legReplied(replyTimeout, { up }) {
            val events = if (exit == null) {
                engine.fetch(filter, timeout)
            } else {
                engine.fetchExit(null, filter, timeout, exit).also { if (it.met) { metAny.set(true); others.cancel() } }.events
            }
            up = engine.connectedNormalRelayUrls().isNotEmpty()
            events
        }
    }
    others = async(start = CoroutineStart.LAZY) {
        var up = false
        legReplied(replyTimeout, { up }) {
            val urls = extra().map { it.trim().trimEnd('/') }.distinct() - engine.relayUrls().toSet()
            if (urls.isEmpty()) return@legReplied null
            // NonCancellable: the other leg may end this one at any moment, and an attach cut off half way would leak its relays
            val held = withContext(NonCancellable) { engine.attachTemporary(urls) }
            try {
                val events = if (exit == null) {
                    engine.fetchFrom(urls, filter, timeout, attach = false)
                } else {
                    engine.fetchExit(urls, filter, timeout, exit).also { if (it.met) { metAny.set(true); session.cancel() } }.events
                }
                events.also { up = engine.connectedRelayUrls().any { it in urls } }
            } finally {
                engine.detachTemporary(held)
            }
        }
    }
    session.start()
    others.start()
    val a = session.awaitReply()
    val b = others.awaitReply()
    a || b || metAny.get()
}

/** A leg's answer; a leg the other one ended (its exit was met) gives no reply of its own. */
private suspend fun Deferred<Boolean>.awaitReply(): Boolean = try {
    await()
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    false
}

/**
 * One leg of [fetchWide]: events, or returning well before [timeout] while a relay it asked is [connected], count as
 * a reply (an unreachable relay may return empty at once too); [block] null means nothing to ask; a failure is no reply.
 */
internal suspend fun legReplied(timeout: Duration, connected: () -> Boolean, block: suspend () -> List<Event>?): Boolean {
    val started = System.nanoTime()
    var replied = false
    quietly {
        val events = block() ?: return@quietly
        val tookMs = (System.nanoTime() - started) / 1_000_000
        replied = events.isNotEmpty() || (connected() && tookMs < timeout.toMillis() - 250)
    }
    return replied
}

/** [fetchWide] with a fixed relay list (the tabs event, the own lists). */
internal suspend fun fetchWide(engine: NostrEngine, filter: Filter, extra: List<String>, timeout: Duration): Boolean = fetchWide(engine, filter, timeout) { extra }
