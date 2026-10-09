package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rust.nostr.sdk.Event
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl
import java.time.Duration

/** Outcome of looking up the account's own kind 10050. */
sealed class OwnDmRelays {
    data class Found(val relays: List<String>) : OwnDmRelays()
    /** Looked up with relays connected and none exists: safe to publish one. */
    data object None : OwnDmRelays()
    /** Nothing local and no normal relay connected: silence is not proof that no list exists. */
    data object Unknown : OwnDmRelays()
}

/** NIP-17 inbox relays (kind 10050), spec 3.3/3.4. */
interface DmRelaysRepository {
    /** The user's own kind 10050 list from DB/relays; see [OwnDmRelays] for None vs Unknown. */
    suspend fun ownDmRelays(): OwnDmRelays
    /** Peer's inbox relays: kind 10050 → kind 10002 write relays → [FALLBACK]; always contains FALLBACK; max 4; cached 10 min. */
    suspend fun dmRelays(pubkey: String): List<String>
    companion object { const val FALLBACK = "wss://relay.monostr.com"; const val MAX = 4 }
}

class NostrDmRelaysRepository(
    private val engine: NostrEngine,
    private val authorRelays: AuthorRelaysRepository,
    private val indexers: List<String> = listOf("wss://relay.monostr.com", "wss://purplepag.es"),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val maxAgeSeconds: Long = 600,
    private val fetchTimeout: Duration = Duration.ofSeconds(4),
) : DmRelaysRepository {
    private val mutex = Mutex()
    private val cache = HashMap<String, Pair<Long, List<String>>>()

    override suspend fun ownDmRelays(): OwnDmRelays {
        val pk = engine.pubkey ?: return OwnDmRelays.Unknown
        val lookup = lookup10050(pk, fetch = true)
        lookup.urls?.let { return OwnDmRelays.Found(it) }
        // Absence is only proven by a fetch that finished (every source answered) before the
        // timeout without throwing, with a normal relay connected (the rule of
        // NostrBookmarksRepository.loadLocked). Anything else is silence, and publishing on
        // silence could replace a list made elsewhere.
        if (!lookup.fetchCompleted) return OwnDmRelays.Unknown
        return if (engine.connectedNormalRelayUrls().isEmpty()) OwnDmRelays.Unknown else OwnDmRelays.None
    }

    override suspend fun dmRelays(pubkey: String): List<String> {
        mutex.withLock { cache[pubkey]?.let { (at, list) -> if (at + maxAgeSeconds > now()) return list } }
        val fromList = lookup10050(pubkey, fetch = true).urls ?: emptyList()
        val relays = if (fromList.isNotEmpty()) fromList else authorRelays.writeRelays(pubkey)
        val result = (relays.take(DmRelaysRepository.MAX - 1) + DmRelaysRepository.FALLBACK).distinct().take(DmRelaysRepository.MAX)
        mutex.withLock { cache[pubkey] = now() to result }
        return result
    }

    /**
     * [urls]: `relay` tags of the newest kind 10050, null when there is none. [fetchCompleted]:
     * a relay fetch ran and returned before [fetchTimeout] without throwing (false when the list
     * came from the database, when no fetch ran, or when it failed or ran into the timeout).
     */
    private class Lookup(val urls: List<String>?, val fetchCompleted: Boolean)

    /** DB first, one indexer/normal-relay fetch when [fetch]. */
    private suspend fun lookup10050(pubkey: String, fetch: Boolean): Lookup {
        val filter = Filter().kind(Kind(10050u)).author(PublicKey.parse(pubkey)).limit(1u)
        var event = engine.query(filter).firstOrNull()
        var completed = false
        if (event == null && fetch) {
            val sources = (indexers + engine.relayUrls()).distinct()
            if (sources.isNotEmpty()) {
                val started = System.nanoTime()
                val held = engine.attachTemporary(sources)
                completed = try {
                    engine.fetchFrom(sources, filter, fetchTimeout, attach = false)
                    // rust-nostr returns what it has when the timeout ends the fetch; only an
                    // earlier return means every source answered (EOSE).
                    System.nanoTime() - started < fetchTimeout.toNanos()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                } finally {
                    engine.detachTemporary(held)
                }
            }
            event = engine.query(filter).firstOrNull()
        }
        return Lookup(parseRelayTags(event), completed)
    }

    private fun parseRelayTags(event: Event?): List<String>? {
        val urls = event?.tags()?.toVec()?.map { it.asVec() }
            ?.filter { it.size >= 2 && it[0] == "relay" }
            ?.mapNotNull { runCatching { RelayUrl.parse(it[1].trim()) }.getOrNull()?.toString()?.trimEnd('/') }
            ?.filter { it.startsWith("wss://") }?.distinct() ?: return null
        return urls
    }
}
