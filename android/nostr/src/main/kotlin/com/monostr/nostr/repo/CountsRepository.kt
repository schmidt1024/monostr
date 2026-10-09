package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.NoteCounts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.RelayUrl
import java.time.Duration

/** Like/repost/reply counts per note via NIP-45 COUNT (spec 4.1). */
interface CountsRepository {
    val counts: StateFlow<Map<String, NoteCounts>>
    /** Visible notes; ids with a fresh count are skipped, the rest is counted in batches. */
    fun request(noteIds: Collection<String>)
    /** Optimistic change after an own like (kind 7) or repost (kind 6); the next request recounts. */
    fun bump(noteId: String, kind: Int, delta: Long)
}

class NostrCountsRepository(
    private val engine: NostrEngine,
    private val scope: CoroutineScope,
    private val relays: suspend () -> List<String> = { engine.connectedNormalRelayUrls().toList() },
    private val counter: suspend (relayUrl: String, filter: Filter) -> Long = { url, filter ->
        withContext(engine.ffiDispatcher) { engine.client.relay(RelayUrl.parse(url)).countEvents(filter, Duration.ofSeconds(5)).toLong() }
    },
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val maxAgeSeconds: Long = 300,
    private val batchDelayMs: Long = 300,
    private val batchSize: Int = 20,
) : CountsRepository {
    private val _counts = MutableStateFlow<Map<String, NoteCounts>>(emptyMap())
    override val counts: StateFlow<Map<String, NoteCounts>> = _counts.asStateFlow()

    private val mutex = Mutex()
    // ConcurrentHashMap so bump() can clear a note's timestamp synchronously (finding 5): a request()
    // called right after bump() must see the note as stale immediately, not after some later coroutine
    // hop takes the mutex. Other accesses stay under the mutex for consistency with queue/inFlight.
    private val fetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val queue = LinkedHashSet<String>()
    private val inFlight = HashSet<String>()
    /** Relays whose COUNT failed once; never asked again this session (spec 4.1). */
    private val unsupported = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // null means "no drain loop owns the queue right now". drain() clears it back to null, under the
    // same mutex as the emptiness check that decides it is done, so request() and drain() never
    // disagree about whether a worker is still going to pick up newly queued ids (finding 1).
    private var worker: Job? = null

    override fun request(noteIds: Collection<String>) {
        scope.launch {
            mutex.withLock {
                val t = now()
                val fresh = noteIds.filter { id -> id !in inFlight && id !in queue && (fetchedAt[id] ?: Long.MIN_VALUE) + maxAgeSeconds <= t }
                queue += fresh
                if (worker == null && queue.isNotEmpty()) worker = scope.launch { drain() }
            }
        }
    }

    override fun bump(noteId: String, kind: Int, delta: Long) {
        _counts.update { m ->
            val c = m[noteId] ?: NoteCounts.EMPTY
            val bumped = when (kind) {
                7 -> c.copy(likes = ((c.likes ?: 0) + delta).coerceAtLeast(0))
                6 -> c.copy(reposts = ((c.reposts ?: 0) + delta).coerceAtLeast(0))
                1 -> c.copy(replies = ((c.replies ?: 0) + delta).coerceAtLeast(0))
                else -> c
            }
            m + (noteId to bumped)
        }
        // Synchronous (finding 5): a request() issued right after this call must see the note as stale.
        fetchedAt.remove(noteId)
    }

    private suspend fun drain() {
        while (true) {
            delay(batchDelayMs)
            val batch = mutex.withLock {
                val b = queue.take(batchSize)
                queue.removeAll(b.toSet()); inFlight += b
                b
            }
            if (batch.isEmpty()) {
                mutex.withLock { worker = null }
                return
            }
            try {
                countBatch(batch)
            } finally {
                // Bookkeeping only — no return here (finding 4), so an exception from countBatch
                // (in practice only a propagating CancellationException; ordinary relay failures are
                // caught in countOrNull) keeps propagating instead of being swallowed by this block.
                mutex.withLock {
                    val t = now()
                    // Every id in the batch is stamped, even ones no relay answered for this round
                    // (finding 3, kept deliberately): this avoids re-querying a relay that just failed
                    // on every subsequent request until maxAgeSeconds passes, at the cost of an
                    // undercount that lasts at most one cache window.
                    batch.forEach { fetchedAt[it] = t; inFlight.remove(it) }
                }
            }
            // Exit decision and clearing `worker` happen in the same lock acquisition (finding 1).
            val exit = mutex.withLock { if (queue.isEmpty()) { worker = null; true } else false }
            if (exit) return
        }
    }

    /** One coroutine per relay, sequential inside a relay; the merge keeps the maximum per kind. */
    private suspend fun countBatch(ids: List<String>) {
        val targets = relays().filter { it !in unsupported }
        if (targets.isEmpty()) return
        val results = targets.map { url ->
            scope.async {
                val out = HashMap<String, NoteCounts>()
                for (id in ids) {
                    val eid = runCatching { EventId.parse(id) }.getOrNull() ?: continue
                    val likes = countOrNull(url, Filter().kind(Kind(7u)).event(eid)) ?: return@async out
                    val reposts = countOrNull(url, Filter().kind(Kind(6u)).event(eid)) ?: return@async out
                    val replies = countOrNull(url, Filter().kind(Kind(1u)).event(eid)) ?: return@async out
                    out[id] = NoteCounts(likes, reposts, replies)
                }
                out
            }
        }.awaitAll()
        // Only ids some relay actually answered for this round are reset before merging (finding 2):
        // a note nobody answered for in this batch (e.g. the relay died partway through an earlier id)
        // keeps its previous, possibly bumped, value instead of being wiped — spec 4.1: a recount
        // replaces only what a relay actually answered.
        val answered = results.flatMap { it.keys }.toSet()
        if (answered.isNotEmpty()) {
            _counts.update { m ->
                val merged = HashMap(m); answered.forEach { merged.remove(it) }
                for (r in results) for ((id, c) in r) {
                    val prev = merged[id] ?: NoteCounts.EMPTY
                    merged[id] = NoteCounts(maxOf(prev.likes, c.likes), maxOf(prev.reposts, c.reposts), maxOf(prev.replies, c.replies))
                }
                merged
            }
        }
    }

    /** Null marks the relay unsupported (error or timeout) — its partial answers for this batch are dropped. */
    private suspend fun countOrNull(url: String, filter: Filter): Long? = try {
        counter(url, filter)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        unsupported += url
        null
    }

    private fun maxOf(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> kotlin.math.max(a, b)
    }
}
