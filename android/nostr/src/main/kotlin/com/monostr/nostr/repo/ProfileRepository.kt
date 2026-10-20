package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.NoteMapper
import com.monostr.nostr.model.Profile
import com.monostr.nostr.model.ProfileJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

interface ProfileRepository {
    /** The cached profile of [pubkey] and every later change; [Profile.empty] until it is known. */
    fun observe(pubkey: String): Flow<Profile>

    /**
     * The profiles a background refresh (or [invalidate]) changed, only those, once the cache holds them. Hot,
     * no replay: a screen subscribes before its first [get]/[getAll] and merges what arrives (spec perf 3.2).
     */
    val updates: SharedFlow<Map<String, Profile>>

    /**
     * At once, never waiting for a relay (spec perf 3.2): the cached profile, else the stored one, else
     * [Profile.empty]. A missing one, or one not asked of the relays for [maxAgeSeconds] in this process, is
     * queued for the batched background refresh; what it finds arrives through [observe] and [updates].
     */
    suspend fun get(pubkey: String, maxAgeSeconds: Long = 3600): Profile

    /** [get] for many, with one database query; every distinct key of [pubkeys] is in the result, in order. */
    suspend fun getAll(pubkeys: Collection<String>, maxAgeSeconds: Long = 3600): Map<String, Profile> =
        pubkeys.distinct().associateWith { get(it, maxAgeSeconds) }

    /** Queues the missing and stale ones of [pubkeys] for the background refresh and returns at once. */
    fun prefetch(pubkeys: Collection<String>)

    /** Stored profiles of [pubkeys] from the database only (spec 4.2: no network while typing); keys without a kind 0 are left out. */
    suspend fun local(pubkeys: Collection<String>): List<Profile>

    /**
     * Background work only (a worker that names a sender in a notification), never a screen: waits at most
     * [timeout] for a relay lookup of a missing or stale [pubkey], then answers like [get].
     */
    suspend fun fetched(pubkey: String, timeout: Duration = Duration.ofSeconds(8)): Profile = get(pubkey)

    /** Raw content of the newest stored kind 0 of [pubkey]; without one, one relay fetch bounded by [timeout]; null when none exists. */
    suspend fun rawMetadata(pubkey: String, timeout: Duration = Duration.ofSeconds(3)): String?

    /** Spec 8 (10c backlog): the newest kind 0 right before a save; always one relay fetch bounded by [timeout], then the database (fallback). */
    suspend fun freshMetadata(pubkey: String, timeout: Duration = Duration.ofSeconds(3)): String?

    /** Re-reads [pubkey] from the database into the cache, so [observe], [updates] and [get] show an own new kind 0 at once. */
    suspend fun invalidate(pubkey: String): Profile
}

/**
 * Profiles (kind 0): memory, then the database, decoded in Kotlin; never a relay on the caller's path (spec perf
 * 3.2). What is missing or stale goes into a queue that waits [batchDelayMs] for more and then asks the user's
 * relays once per batch ([AUTHOR_CHUNK] authors per filter); what they do not have is looked up once on
 * [indexRelays] (relays that collect everybody's profiles). Found profiles land in the cache and on [updates].
 */
class NostrProfileRepository(
    private val engine: NostrEngine,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val missingRetrySeconds: Long = 600,
    /** Empty by default so that tests never reach the network; the session passes [INDEX_RELAYS]. */
    private val indexRelays: List<String> = emptyList(),
    private val indexTimeout: Duration = Duration.ofSeconds(4),
    /** How long the queue gathers lookups before one batched request (spec perf 3.2: ~150 ms). */
    private val batchDelayMs: Long = 150,
) : ProfileRepository {
    /** Stored profiles read so far; only found ones (a missing one is read from the database again). */
    private val cache = MutableStateFlow<Map<String, Profile>>(emptyMap())
    /** When each pubkey was last asked of the relays in this process, set when its batch starts. */
    private val fetchedAt = ConcurrentHashMap<String, Long>()
    private val _updates = MutableSharedFlow<Map<String, Profile>>(extraBufferCapacity = 16)
    override val updates: SharedFlow<Map<String, Profile>> = _updates.asSharedFlow()
    /** Cache write and announcement as one step: [updates] arrive in the order the cache took them. */
    private val publishLock = Mutex()
    /** Pubkeys waiting for the next batch; guarded by itself, like [drain]. */
    private val pending = LinkedHashSet<String>()
    private var drain: Job? = null

    override fun observe(pubkey: String): Flow<Profile> = cache.map { it[pubkey] ?: Profile.empty(pubkey) }.distinctUntilChanged()

    override suspend fun get(pubkey: String, maxAgeSeconds: Long): Profile = getAll(listOf(pubkey), maxAgeSeconds).getValue(pubkey)

    override suspend fun getAll(pubkeys: Collection<String>, maxAgeSeconds: Long): Map<String, Profile> {
        val distinct = pubkeys.distinct()
        val cached = cache.value
        val loaded = readLocal(distinct.filter { it !in cached })
        remember(loaded)
        val t = now()
        enqueue(distinct.filter { due(it, present = it in cached || it in loaded, maxAgeSeconds, t) })
        // the cache again, not the snapshot from before the read: a batch that landed during it is newer than the read
        val current = cache.value
        return distinct.associateWith { current[it] ?: loaded[it] ?: Profile.empty(it) }
    }

    override fun prefetch(pubkeys: Collection<String>) {
        val cached = cache.value
        val t = now()
        enqueue(pubkeys.distinct().filter { due(it, present = it in cached, DEFAULT_MAX_AGE, t) })
    }

    override suspend fun local(pubkeys: Collection<String>): List<Profile> {
        val distinct = pubkeys.distinct()
        val cached = cache.value
        val loaded = readLocal(distinct.filter { it !in cached })
        remember(loaded)
        // like getAll: what landed during the read wins over it
        val current = cache.value
        return distinct.mapNotNull { current[it] ?: loaded[it] }
    }

    override suspend fun fetched(pubkey: String, timeout: Duration): Profile {
        val present = pubkey in cache.value || readLocal(listOf(pubkey)).isNotEmpty()
        if (pubkey.matches(HEX64) && due(pubkey, present, DEFAULT_MAX_AGE, now())) {
            stamp(listOf(pubkey))
            // the lookup runs on the engine's scope and finishes whether the worker waits or not: its stamp stays
            // truthful and what it finds lands through updates
            val lookup = engine.backgroundScope.launch { refreshQuietly(listOf(pubkey)) }
            // withTimeoutOrNull, never withTimeout: a worker gets what is there when the relays are slow. On the
            // engine's dispatcher, so the bound is real time whatever clock the caller's dispatcher keeps
            withContext(engine.ffiDispatcher) { withTimeoutOrNull(timeout.toMillis()) { lookup.join() } }
        }
        return get(pubkey)
    }

    /** When [pubkey] was last asked of the relays in this process (the stamp is set when its batch starts), for tests. */
    internal fun lastFetchAt(pubkey: String): Long? = fetchedAt[pubkey]

    override suspend fun rawMetadata(pubkey: String, timeout: Duration): String? {
        val filter = Filter().kind(Kind(0u)).author(PublicKey.parse(pubkey)).limit(1u)
        engine.query(filter).firstOrNull()?.let { return it.content() }
        quietly { engine.fetch(filter, timeout) }
        engine.query(filter).firstOrNull()?.let { return it.content() }
        fetchFromIndex(listOf(pubkey), timeout)
        return engine.query(filter).firstOrNull()?.content()
    }

    override suspend fun freshMetadata(pubkey: String, timeout: Duration): String? {
        val filter = Filter().kind(Kind(0u)).author(PublicKey.parse(pubkey)).limit(1u)
        quietly { engine.fetch(filter, timeout) }
        engine.query(filter).firstOrNull()?.let { return it.content() }
        // nothing anywhere the user reads: the editor must not start empty when the profile exists elsewhere
        fetchFromIndex(listOf(pubkey), timeout)
        return engine.query(filter).firstOrNull()?.content()
    }

    override suspend fun invalidate(pubkey: String): Profile {
        val profile = readLocal(listOf(pubkey))[pubkey]
        fetchedAt[pubkey] = now()
        if (profile != null) publish(mapOf(pubkey to profile))
        return profile ?: Profile.empty(pubkey)
    }

    /** Due for a relay lookup: never asked in this process, or a present one older than [maxAgeSeconds], or a missing one past the retry window. */
    private fun due(pubkey: String, present: Boolean, maxAgeSeconds: Long, t: Long): Boolean {
        val last = fetchedAt[pubkey] ?: return true
        return if (present) last + maxAgeSeconds < t else last + missingRetrySeconds <= t
    }

    private fun stamp(batch: List<String>) {
        val t = now()
        batch.forEach { fetchedAt[it] = t }
    }

    private fun enqueue(pubkeys: List<String>) {
        val valid = pubkeys.filter { it.matches(HEX64) }
        if (valid.isEmpty()) return
        synchronized(pending) {
            pending += valid
            if (drain == null) drain = engine.backgroundScope.launch { drainQueue() }
        }
    }

    /**
     * Every [batchDelayMs] takes what gathered as one batch, until the queue stays empty. A batch is stamped
     * while it is taken (a lookup of the same key in between finds it asked) and runs on its own: a slow relay
     * holds up its batch only, never the next one.
     */
    private suspend fun drainQueue() {
        while (true) {
            delay(batchDelayMs)
            val batch = synchronized(pending) {
                if (pending.isEmpty()) {
                    drain = null
                    return
                }
                pending.toList().also { pending.clear(); stamp(it) }
            }
            engine.backgroundScope.launch { refreshQuietly(batch) }
        }
    }

    private suspend fun refreshQuietly(batch: List<String>) {
        try {
            refresh(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a database error costs this batch only; the next lookup queues it again after the retry window
        }
    }

    /**
     * One relay request per [AUTHOR_CHUNK] of [batch] (side by side), the index relays for what is still missing,
     * then the cache. What was cached when the batch started is the base for [updates]: a screen's read in between
     * puts a fetched profile into the cache, and it must still be announced.
     */
    private suspend fun refresh(batch: List<String>) {
        val before = cache.value.let { m -> batch.mapNotNull { pk -> m[pk]?.let { pk to it } }.toMap() }
        coroutineScope {
            batch.chunked(AUTHOR_CHUNK).map { chunk -> async { quietly { engine.fetch(metadataFilter(chunk)) } } }.awaitAll()
        }
        var found = readLocal(batch)
        val missing = batch.filter { it !in found }
        if (missing.isNotEmpty() && indexRelays.isNotEmpty()) {
            fetchFromIndex(missing)
            found = found + readLocal(missing)
        }
        publish(found, before)
    }

    /**
     * Puts [found] into the cache and then announces the ones that differ from the cache now or from [before]
     * (the cache when the caller's work started). Announcing one twice is harmless: screens merge idempotently.
     */
    private suspend fun publish(found: Map<String, Profile>, before: Map<String, Profile>? = null) {
        if (found.isEmpty()) return
        publishLock.withLock {
            var changed: Map<String, Profile> = emptyMap()
            cache.update { m -> changed = found.filter { (pk, p) -> m[pk] != p || (before != null && before[pk] != p) }; m + found }
            if (changed.isNotEmpty()) _updates.emit(changed)
        }
    }

    /** Database reads only add: what a refresh put into the cache in between is never replaced by an older read. */
    private fun remember(loaded: Map<String, Profile>) {
        if (loaded.isNotEmpty()) cache.update { m -> m + loaded.filterKeys { it !in m } }
    }

    /** The stored kind 0 of [pubkeys] with ONE query, decoded in Kotlin; malformed keys and unreadable contents are left out. */
    private suspend fun readLocal(pubkeys: List<String>): Map<String, Profile> {
        val keys = pubkeys.filter { it.matches(HEX64) }
        if (keys.isEmpty()) return emptyMap()
        val read = withContext(engine.ffiDispatcher) {
            engine.query(metadataFilter(keys)).map { NoteMapper.decoded(it) }
                .groupBy { it.pubkey }
                .mapNotNull { (pk, events) -> ProfileJson.profile(pk, events.maxBy { it.createdAt }.content)?.let { pk to it } }
                .toMap()
        }
        afterLocalRead?.invoke()
        return read
    }

    /** Tests only: runs after a database read has its result, to land a batch while the reader is still suspended. */
    internal var afterLocalRead: (suspend () -> Unit)? = null

    /** Kind 0 by [pubkeys] built from JSON in one call (a [PublicKey.parse] per key was the slow part). */
    private fun metadataFilter(pubkeys: List<String>): Filter = kindsByAuthorsFilter(listOf(0), pubkeys)

    /**
     * Asks the index relays for the kind 0 of [pubkeys] (the ones the user's relays did not have);
     * what they send lands in the database. The relays are attached for the lookup only. An index
     * relay that is one of the user's relays was asked already and is left out.
     */
    private suspend fun fetchFromIndex(pubkeys: List<String>, timeout: Duration = indexTimeout) {
        if (pubkeys.isEmpty() || indexRelays.isEmpty()) return
        val sources = indexRelays.map { it.trim().trimEnd('/') } - engine.relayUrls().toSet()
        if (sources.isEmpty()) return
        // exactly what this call took is released: the relay may be held by another lookup (relay lists, DM relays)
        val held = engine.attachTemporary(sources)
        try {
            quietly { engine.fetchFrom(sources, metadataFilter(pubkeys), timeout, attach = false) }
        } finally {
            engine.detachTemporary(held)
        }
    }

    companion object {
        /** Relays that keep everybody's profile and relay list; asked only for profiles the user's relays lack. */
        val INDEX_RELAYS = listOf("wss://purplepag.es")
        private const val DEFAULT_MAX_AGE = 3600L
        private val HEX64 = Regex("[0-9a-f]{64}")
    }
}
