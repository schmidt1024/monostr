package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.NoteMapper
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import java.time.Duration

interface ProfileRepository {
    fun observe(pubkey: String): Flow<Profile>
    suspend fun get(pubkey: String, maxAgeSeconds: Long = 3600): Profile
    suspend fun prefetch(pubkeys: Collection<String>)
    /** Stored profiles of [pubkeys] from the database only (spec 4.2: no network while typing); keys without a kind 0 are left out. */
    suspend fun local(pubkeys: Collection<String>): List<Profile>
    /** Raw content of the newest stored kind 0 of [pubkey]; without one, one relay fetch bounded by [timeout]; null when none exists. */
    suspend fun rawMetadata(pubkey: String, timeout: Duration = Duration.ofSeconds(3)): String?
    /** Spec 8 (10c backlog): the newest kind 0 right before a save; always one relay fetch bounded by [timeout], then the database (fallback). */
    suspend fun freshMetadata(pubkey: String, timeout: Duration = Duration.ofSeconds(3)): String?
    /** Re-reads [pubkey] from the database into the cache, so [observe] and [get] show an own new kind 0 at once. */
    suspend fun invalidate(pubkey: String): Profile
}

/**
 * Profiles (kind 0) from the local database, fetched from relays when missing or stale. A profile
 * the user's relays do not have is looked up once on [indexRelays] (relays that collect profiles
 * of everybody): many people publish their kind 0 somewhere else than on the relays this user reads.
 * [get] and the profile editor wait for that lookup; [prefetch] (lists) starts it and returns.
 */
class NostrProfileRepository(
    private val engine: NostrEngine,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val missingRetrySeconds: Long = 600,
    /** Empty by default so that tests never reach the network; the session passes [INDEX_RELAYS]. */
    private val indexRelays: List<String> = emptyList(),
    private val indexTimeout: Duration = Duration.ofSeconds(4),
) : ProfileRepository {
    private val cache = MutableStateFlow<Map<String, Profile>>(emptyMap())
    private val fetchedAt = HashMap<String, Long>()
    private val mutex = Mutex()

    override fun observe(pubkey: String): Flow<Profile> = cache.asStateFlow().map { it[pubkey] ?: Profile.empty(pubkey) }

    /** Local-first lookup; fetches from relays when nothing is cached or the entry is older than [maxAgeSeconds]. */
    override suspend fun get(pubkey: String, maxAgeSeconds: Long): Profile {
        val local = fromDatabase(pubkey)
        val last = mutex.withLock { fetchedAt[pubkey] ?: 0L }
        val stale = last + maxAgeSeconds < now()
        val retryDue = last + missingRetrySeconds <= now()
        if ((local == null && retryDue) || (local != null && stale)) {
            quietly { engine.fetch(Filter().kind(Kind(0u)).author(PublicKey.parse(pubkey)).limit(1u)) }
            if (local == null && fromDatabase(pubkey) == null) fetchFromIndex(listOf(pubkey))
            mutex.withLock { fetchedAt[pubkey] = now() }
        }
        // read under the lock, like every cache write: a writer that read earlier must not put an
        // older state over what a background index lookup found in between
        return mutex.withLock { (fromDatabase(pubkey) ?: Profile.empty(pubkey)).also { cache.value = cache.value + (pubkey to it) } }
    }

    /**
     * Loads several profiles at once (one relay request), for feed avatars. What the user's relays
     * do not have is looked up on the index relays in the background: a list must not wait for a
     * relay outside the user's set. A profile found there shows through [observe] and at the next [get].
     */
    override suspend fun prefetch(pubkeys: Collection<String>) {
        val distinct = pubkeys.distinct()
        val t = now()
        val stamps = mutex.withLock { HashMap(fetchedAt) }
        val present = HashSet<String>()
        val missing = ArrayList<String>()
        for (pk in distinct) {
            if (fromDatabase(pk) != null) present += pk
            else if ((stamps[pk] ?: Long.MIN_VALUE) + missingRetrySeconds <= t) missing += pk
        }
        if (missing.isNotEmpty()) quietly { engine.fetch(Filter().kind(Kind(0u)).authors(missing.map { PublicKey.parse(it) })) }
        val stillMissing = mutex.withLock {
            // Stamp everything this call resolved: profiles already local (so the next get() does not treat them
            // as stale and refetch) and the ones just fetched. Ids skipped by the retry window keep their stamp.
            present.forEach { if (it !in fetchedAt) fetchedAt[it] = t }
            missing.forEach { fetchedAt[it] = t }
            // read under the lock, like every cache write: see get()
            val loaded = distinct.associateWith { fromDatabase(it) }
            cache.value = cache.value + loaded.mapValues { (pk, profile) -> profile ?: Profile.empty(pk) }
            missing.filter { loaded[it] == null }
        }
        // only now: what the lookup finds must come after this call's own cache write
        fetchFromIndexLater(stillMissing)
    }

    /** Last relay fetch time for [pubkey], for tests. */
    internal suspend fun lastFetchAt(pubkey: String): Long? = mutex.withLock { fetchedAt[pubkey] }

    override suspend fun local(pubkeys: Collection<String>): List<Profile> = pubkeys.distinct().mapNotNull { pk ->
        try {
            fromDatabase(pk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // not a valid key
        }
    }

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
            quietly { engine.fetchFrom(sources, Filter().kind(Kind(0u)).authors(pubkeys.map { PublicKey.parse(it) }), timeout, attach = false) }
        } finally {
            engine.detachTemporary(held)
        }
    }

    /** [fetchFromIndex] on the engine's scope; what it found is put into the cache. */
    private fun fetchFromIndexLater(pubkeys: List<String>) {
        if (pubkeys.isEmpty() || indexRelays.isEmpty()) return
        engine.backgroundScope.launch {
            quietly {
                fetchFromIndex(pubkeys)
                mutex.withLock {
                    val found = pubkeys.mapNotNull { pk -> fromDatabase(pk)?.let { pk to it } }
                    if (found.isNotEmpty()) cache.value = cache.value + found
                }
            }
        }
    }

    override suspend fun invalidate(pubkey: String): Profile {
        val profile = fromDatabase(pubkey) ?: Profile.empty(pubkey)
        mutex.withLock {
            fetchedAt[pubkey] = now()
            cache.value = cache.value + (pubkey to profile)
        }
        return profile
    }

    companion object {
        /** Relays that keep everybody's profile and relay list; asked only for profiles the user's relays lack. */
        val INDEX_RELAYS = listOf("wss://purplepag.es")
    }

    private suspend fun fromDatabase(pubkey: String): Profile? =
        withContext(engine.ffiDispatcher) { engine.client.database().metadata(PublicKey.parse(pubkey)) }?.let { NoteMapper.profile(pubkey, it) }
}
