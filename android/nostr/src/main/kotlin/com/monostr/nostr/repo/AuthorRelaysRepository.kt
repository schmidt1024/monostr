package com.monostr.nostr.repo

import com.monostr.tips.RelayHosts
import com.monostr.nostr.Exit
import com.monostr.nostr.NostrEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rust.nostr.sdk.Event
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Spec 5: an author's NIP-65 write relays, attached temporarily while their profile is open. */
interface AuthorRelaysRepository {
    /** Up to three write relays not in the user's own list; empty when unknown. Cached ten minutes, also when empty. */
    suspend fun writeRelays(pubkey: String): List<String>
    /** Spec 2026-10-23 §10: [writeRelays] for many authors with one database read and one fetch for the unknown ones. */
    suspend fun writeRelaysMany(pubkeys: Collection<String>): Map<String, List<String>> = pubkeys.associateWith { writeRelays(it) }
    /** Up to three NIP-65 read relays (no marker or `read`) not in the own list; same cache rules. */
    suspend fun readRelays(pubkey: String): List<String> = emptyList()
    /** [writeRelays] for routing (a DM): asks every relay and takes the newest list, never an early exit on a fast relay's older one. */
    suspend fun writeRelaysExact(pubkey: String): List<String> = writeRelays(pubkey)
    suspend fun attach(relays: List<String>)
    suspend fun detach(relays: List<String>)
    /** Pulls the author's kind 1/6 from [relays] into the database (spec 5.2); never throws except on cancellation. */
    suspend fun fetchNotes(relays: List<String>, pubkey: String, limit: Int = 50): Set<String>
    /** Pulls the author's articles (kind 30023) from [relays] into the database (article reader, final review M2); true when any came. Never throws except on cancellation. */
    suspend fun fetchArticles(relays: List<String>, pubkey: String, limit: Int): Boolean = false
}

class NostrAuthorRelaysRepository(
    private val engine: NostrEngine,
    private val indexers: List<String> = INDEXERS,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val maxAgeSeconds: Long = 600,
    private val limit: Int = 3,
    private val fetchTimeout: Duration = Duration.ofSeconds(4),
    private val notesFetchTimeout: Duration = Duration.ofSeconds(8),
    /** Spec 2026-10-23 §3: the app-wide budget for foreign relays; when set, the indexers are leased from it instead of attached on the side. */
    private val foreign: ForeignRelays? = null,
    /** Which relay URLs may be returned; tests pass `{ true }` for loopback relays. */
    private val relayFilter: (String) -> Boolean = RelayHosts::isPublicWss,
) : AuthorRelaysRepository {
    private val mutex = Mutex()
    private val cache = HashMap<String, Pair<Long, List<String>>>()

    override suspend fun writeRelays(pubkey: String): List<String> = resolve(listOf(pubkey), exact = false, read = false)[pubkey] ?: emptyList()

    override suspend fun writeRelaysExact(pubkey: String): List<String> = resolve(listOf(pubkey), exact = true, read = false)[pubkey] ?: emptyList()

    override suspend fun writeRelaysMany(pubkeys: Collection<String>): Map<String, List<String>> {
        val valid = pubkeys.filter { VALID_KEY.matches(it) }.distinct()
        // the locator's batch (its round leaves a slot for the indexers, spec §4.2): a short wait for room is worth it there
        val found = resolve(valid, exact = false, read = false, wait = LOCATOR_WAIT)
        return pubkeys.associateWith { found[it] ?: emptyList() }
    }

    override suspend fun readRelays(pubkey: String): List<String> = resolve(listOf(pubkey), exact = false, read = true)[pubkey] ?: emptyList()

    /**
     * Spec 2026-10-23 §10: the lists of [pubkeys] with one database read and, for the ones without a stored list, one fetch.
     * [exact]: no early exit, and a cache of its own (an early answer must not serve routing). [read]: read instead of write
     * relays, cached under its own key from the same stored or fetched kind 10002. [wait]: how long the indexer lease may
     * wait for room in the foreign budget; zero for profile pages, follow sets and DM routing (final review I2: a full
     * budget must not hold them up), short for the locator's batch. Lists of a fetch whose indexer lease came back short
     * are returned but not cached: the next lookup with a free slot asks the indexers.
     */
    private suspend fun resolve(pubkeys: List<String>, exact: Boolean, read: Boolean, wait: kotlin.time.Duration = kotlin.time.Duration.ZERO): Map<String, List<String>> {
        val keys = pubkeys.filter { VALID_KEY.matches(it) }.distinct()
        if (keys.isEmpty()) return emptyMap()
        fun keyOf(pk: String) = (if (read) "read:" else if (exact) "exact:" else "") + pk
        val result = HashMap<String, List<String>>()
        val missing = mutex.withLock {
            keys.filter { pk ->
                val hit = cache[keyOf(pk)]?.takeIf { (at, _) -> at + maxAgeSeconds > now() }
                if (hit != null) result[pk] = hit.second
                hit == null
            }
        }
        if (missing.isEmpty()) return result
        val events = HashMap<String, Event>()
        suspend fun load(of: List<String>) {
            val filter = Filter().kind(Kind(10002u)).authors(of.map { PublicKey.parse(it) }).limit(of.size.toULong())
            engine.query(filter).forEach { e -> events.merge(e.author().toHex(), e) { x, y -> if (y.createdAt().asSecs() > x.createdAt().asSecs()) y else x } }
        }
        load(missing)
        val unknown = missing.filter { it !in events }
        var complete = true
        if (unknown.isNotEmpty()) {
            complete = fetchLists(unknown, exact, wait)
            load(unknown)
        }
        val own = engine.relayUrls().toSet()
        val lists = missing.associateWith { pk -> events[pk]?.let { parse(it, read, own) } ?: emptyList() }
        // a short indexer lease: what was fetched is used, the lists of that fetch are not cached (the stored ones are)
        val keep = if (complete) lists else lists.filterKeys { it !in unknown }
        mutex.withLock { keep.forEach { (pk, list) -> cache[keyOf(pk)] = now() to list } }
        return result + lists
    }

    /** The r tags of a kind 10002 as normalised public relay URLs not in [own]: write relays (no marker or `write`) or read relays (no marker or `read`). */
    private fun parse(event: Event, read: Boolean, own: Set<String>): List<String> {
        val marker = if (read) "read" else "write"
        return event.tags().toVec().map { it.asVec() }
            .filter { it.size >= 2 && it[0] == "r" && (it.getOrNull(2) == null || it[2] == marker) }
            .mapNotNull { runCatching { RelayUrl.parse(it[1].trim()) }.getOrNull()?.toString()?.trimEnd('/') }
            .filter { relayFilter(it) && it !in own }
            .distinct().take(limit)
    }

    /**
     * One fetch of the lists of [authors] from the indexers and the own relays; with [foreign] the indexers count toward
     * its budget (spec §3), leased with up to [wait] for room. False when fewer indexers were leased than asked for.
     */
    private suspend fun fetchLists(authors: List<String>, exact: Boolean, wait: kotlin.time.Duration): Boolean {
        val filter = Filter().kind(Kind(10002u)).authors(authors.map { PublicKey.parse(it) }).limit(authors.size.toULong())
        val exit = if (exact) null else Exit.NewestPerAuthor(authors.toSet())
        if (foreign != null) {
            val leased = foreign.lease(indexers, wait = wait)
            try {
                val sources = (leased + engine.relayUrls()).distinct()
                if (sources.isNotEmpty()) quietly { engine.fetchFrom(sources, filter, fetchTimeout, attach = false, exit = exit) }
            } finally {
                foreign.release(leased)
            }
            return leased.size >= indexers.distinct().size
        }
        val sources = (indexers + engine.relayUrls()).distinct()
        if (sources.isEmpty()) return true
        val held = engine.attachTemporary(sources)
        try {
            quietly { engine.fetchFrom(sources, filter, fetchTimeout, attach = false, exit = exit) }
        } finally {
            engine.detachTemporary(held)
        }
        return true
    }

    override suspend fun attach(relays: List<String>) { engine.attachTemporary(relays) }
    override suspend fun detach(relays: List<String>) = engine.detachTemporary(relays)

    override suspend fun fetchNotes(relays: List<String>, pubkey: String, limit: Int): Set<String> {
        if (relays.isEmpty()) return emptySet()
        var ids: Set<String> = emptySet()
        try {
            quietly {
                val filter = Filter().kinds(listOf(Kind(1u), Kind(6u))).author(PublicKey.parse(pubkey)).limit(limit.toULong())
                ids = engine.fetchFrom(relays, filter, notesFetchTimeout).map { it.id().toHex() }.toSet()
            }
        } finally {
            engine.detachTemporary(relays)
        }
        return ids
    }

    override suspend fun fetchArticles(relays: List<String>, pubkey: String, limit: Int): Boolean {
        if (relays.isEmpty()) return false
        var any = false
        try {
            quietly {
                val filter = Filter().kind(Kind(30023u)).author(PublicKey.parse(pubkey)).limit(limit.toULong())
                any = engine.fetchFrom(relays, filter, notesFetchTimeout).isNotEmpty()
            }
        } finally {
            engine.detachTemporary(relays)
        }
        return any
    }

    companion object {
        private val VALID_KEY = Regex("[0-9a-f]{64}")
        val INDEXERS = listOf("wss://relay.monostr.com", "wss://purplepag.es")
        /** How long the locator's batch waits for room in the foreign budget for the indexers (final review I2). */
        private val LOCATOR_WAIT = 500.milliseconds
    }
}
