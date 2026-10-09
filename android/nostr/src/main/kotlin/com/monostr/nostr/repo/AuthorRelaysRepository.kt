package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl
import java.time.Duration

/** Spec 5: an author's NIP-65 write relays, attached temporarily while their profile is open. */
interface AuthorRelaysRepository {
    /** Up to three write relays not in the user's own list; empty when unknown. Cached ten minutes, also when empty. */
    suspend fun writeRelays(pubkey: String): List<String>
    suspend fun attach(relays: List<String>)
    suspend fun detach(relays: List<String>)
    /** Pulls the author's kind 1/6 from [relays] into the database (spec 5.2); never throws except on cancellation. */
    suspend fun fetchNotes(relays: List<String>, pubkey: String, limit: Int = 50)
}

class NostrAuthorRelaysRepository(
    private val engine: NostrEngine,
    private val indexers: List<String> = INDEXERS,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val maxAgeSeconds: Long = 600,
    private val limit: Int = 3,
    private val fetchTimeout: Duration = Duration.ofSeconds(4),
    private val notesFetchTimeout: Duration = Duration.ofSeconds(8),
) : AuthorRelaysRepository {
    private val mutex = Mutex()
    private val cache = HashMap<String, Pair<Long, List<String>>>()

    override suspend fun writeRelays(pubkey: String): List<String> {
        mutex.withLock { cache[pubkey]?.let { (at, list) -> if (at + maxAgeSeconds > now()) return list } }
        val filter = Filter().kind(Kind(10002u)).author(PublicKey.parse(pubkey)).limit(1u)
        var event = engine.query(filter).firstOrNull()
        if (event == null) {
            val sources = (indexers + engine.relayUrls()).distinct()
            if (sources.isNotEmpty()) {
                val held = engine.attachTemporary(sources)
                try {
                    quietly { engine.fetchFrom(sources, filter, fetchTimeout, attach = false) }
                } finally {
                    engine.detachTemporary(held)
                }
            }
            event = engine.query(filter).firstOrNull()
        }
        val own = engine.relayUrls().toSet()
        val list = event?.tags()?.toVec()?.map { it.asVec() }
            ?.filter { it.size >= 2 && it[0] == "r" && (it.getOrNull(2) == null || it[2] == "write") }
            ?.mapNotNull { runCatching { RelayUrl.parse(it[1].trim()) }.getOrNull()?.toString()?.trimEnd('/') }
            ?.filter { it.startsWith("wss://") && it !in own }
            ?.distinct()?.take(limit) ?: emptyList()
        mutex.withLock { cache[pubkey] = now() to list }
        return list
    }

    override suspend fun attach(relays: List<String>) { engine.attachTemporary(relays) }
    override suspend fun detach(relays: List<String>) = engine.detachTemporary(relays)

    override suspend fun fetchNotes(relays: List<String>, pubkey: String, limit: Int) {
        if (relays.isEmpty()) return
        try {
            quietly {
                val filter = Filter().kinds(listOf(Kind(1u), Kind(6u))).author(PublicKey.parse(pubkey)).limit(limit.toULong())
                engine.fetchFrom(relays, filter, notesFetchTimeout)
            }
        } finally {
            engine.detachTemporary(relays)
        }
    }

    companion object {
        val INDEXERS = listOf("wss://relay.monostr.com", "wss://purplepag.es")
    }
}
