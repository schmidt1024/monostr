package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.NoteCounts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import com.monostr.tips.Kinds
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.NostrSdkException
import rust.nostr.sdk.RelayUrl
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A COUNT that got no answer in time (a silent or stalled relay), as opposed to a CLOSED or another error. */
class CountTimeoutException(cause: Throwable? = null) : Exception("COUNT timed out", cause)

/**
 * What a failed `Relay.countEvents` means. rust-nostr reports its own timeout as `NostrSdkException.Generic` with the
 * message exactly "timeout" (plan B Task 0); a failure that only comes at the deadline ([elapsedNanos] of
 * [timeout], 90 %) is a timeout too. Anything else (CLOSED, refused) stays as it is.
 */
internal fun countFailure(e: Exception, elapsedNanos: Long, timeout: Duration): Exception {
    val rustTimeout = e is NostrSdkException.Generic && e.message == "timeout"
    val late = elapsedNanos >= timeout.toNanos() * 9 / 10
    return if (rustTimeout || late) CountTimeoutException(e) else e
}

/** Like/repost/reply counts per note via NIP-45 COUNT (spec 4.1). */
interface CountsRepository {
    val counts: StateFlow<Map<String, NoteCounts>>
    /**
     * Visible notes; ids with a fresh count are skipped, the rest is counted in batches. [priority] (a thread's
     * focused note, spec perf2 A6) skips the batch delay and runs beside a normal batch instead of behind it.
     */
    fun request(noteIds: Collection<String>, priority: Boolean = false)
    /**
     * An article (kind 30023): its counts land under [noteId] in [counts] but come from its [coordinate] (`#a`/`#A`),
     * so every version and every client's reaction counts once.
     */
    fun requestArticle(noteId: String, coordinate: String, priority: Boolean = false)
    /** Optimistic change after an own like (kind 7) or repost (kind 6); the next request recounts. */
    fun bump(noteId: String, kind: Int, delta: Long)
}

/**
 * Spec perf2 A6: a batch asks every note × kind of every relay at once, at most [CountLimiter.PER_RELAY] per relay
 * (shared [limiter]); a relay's answer for a note shows as soon as its three kinds are in. Not behind the startup gate.
 */
class NostrCountsRepository(
    private val engine: NostrEngine,
    private val scope: CoroutineScope,
    private val relays: suspend () -> List<String> = { engine.connectedNormalRelayUrls().toList() },
    /**
     * The session's normal relay set, connected or not (the app passes `engine.relayUrls()`): a batch asked without one
     * of them is stamped short and filled in when it connects. The default (= [relays]) never misses one.
     */
    private val sessionRelays: suspend () -> List<String> = relays,
    /** Throws [CountTimeoutException] when the relay gave no answer in time; any other exception is a refusal or error. */
    private val counter: suspend (relayUrl: String, filter: Filter) -> Long = { url, filter ->
        withContext(engine.ffiDispatcher) {
            val start = System.nanoTime()
            try {
                engine.client.relay(RelayUrl.parse(url)).countEvents(filter, COUNT_TIMEOUT).toLong()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw countFailure(e, System.nanoTime() - start, COUNT_TIMEOUT)
            }
        }
    },
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val maxAgeSeconds: Long = 300,
    private val batchDelayMs: Long = 150,
    private val batchSize: Int = 20,
    private val limiter: CountLimiter = CountLimiter(),
    /** With no connected relay a batch waits this long for one (polling every [RELAY_POLL_MS]) before its ids go unstamped. */
    private val relayWaitMs: Long = 2_000,
) : CountsRepository {
    private val _counts = MutableStateFlow<Map<String, NoteCounts>>(emptyMap())
    override val counts: StateFlow<Map<String, NoteCounts>> = _counts.asStateFlow()

    private val mutex = Mutex()
    // ConcurrentHashMap so bump() can clear a note's timestamp synchronously (finding 5): a request()
    // called right after bump() must see the note as stale immediately.
    private val fetchedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = HashSet<String>() // guarded by [mutex]

    /** One drain loop per lane; the priority lane has no batch delay. Queue and worker are guarded by [mutex]. */
    private class Lane(val priority: Boolean) {
        val queue = LinkedHashSet<String>()
        var worker: Job? = null
    }

    private val normal = Lane(priority = false)
    private val urgent = Lane(priority = true)

    /** Relays that failed [MAX_FAILURES] rounds in a row: not asked again this session. */
    private val unsupported = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap<String, AtomicInteger>()

    // Late relays (final review I2), all guarded by [mutex]:
    /** The ids of the latest normal and priority request: what is on screen now. */
    private var visibleNormal: Set<String> = emptySet()
    private var visiblePriority: Set<String> = emptySet()
    /** Ids asked while a session relay was not connected, with the relays asked for them so far. */
    private val partial = HashMap<String, MutableSet<String>>()
    /** Ids no relay could be asked for (none connected): asked again as soon as one connects. */
    private val parked = LinkedHashSet<String>()
    /** Session relays that stayed away for a whole watch: no longer make a batch partial until they connect. */
    private val gaveUp = HashSet<String>()
    private var watcher: Job? = null
    private var watchPollsLeft = 0

    /** Note id -> coordinate for article ids: [countRound] counts those by coordinate. */
    private val coordinates = ConcurrentHashMap<String, String>()

    override fun requestArticle(noteId: String, coordinate: String, priority: Boolean) {
        coordinates[noteId] = coordinate
        request(listOf(noteId), priority)
    }

    override fun request(noteIds: Collection<String>, priority: Boolean) = enqueue(noteIds, priority, onScreen = true)

    /** [onScreen]: a caller's request (the visible notes); the late-relay watch re-queues without changing them. */
    private fun enqueue(noteIds: Collection<String>, priority: Boolean, onScreen: Boolean) {
        scope.launch {
            mutex.withLock {
                val t = now()
                val lane = if (priority) urgent else normal
                if (onScreen) { if (priority) visiblePriority = noteIds.toSet() else visibleNormal = noteIds.toSet() }
                for (id in noteIds) {
                    if (id in inFlight || (fetchedAt[id] ?: Long.MIN_VALUE) + maxAgeSeconds > t) continue
                    // a priority id leaves the normal queue; a normal request never demotes a queued priority id
                    if (priority) normal.queue.remove(id) else if (id in urgent.queue) continue
                    lane.queue += id
                }
                if (lane.worker == null && lane.queue.isNotEmpty()) lane.worker = scope.launch { drain(lane) }
            }
        }
    }

    override fun bump(noteId: String, kind: Int, delta: Long) {
        _counts.update { m ->
            val c = m[noteId] ?: NoteCounts.EMPTY
            val bumped = when (kind) {
                7 -> c.copy(likes = ((c.likes ?: 0) + delta).coerceAtLeast(0))
                6, 16 -> c.copy(reposts = ((c.reposts ?: 0) + delta).coerceAtLeast(0))
                1 -> c.copy(replies = ((c.replies ?: 0) + delta).coerceAtLeast(0))
                else -> c
            }
            m + (noteId to bumped)
        }
        // Synchronous (finding 5): a request() issued right after this call must see the note as stale.
        fetchedAt.remove(noteId)
    }

    private suspend fun drain(lane: Lane) {
        while (true) {
            if (!lane.priority) delay(batchDelayMs)
            // taking the batch and giving the lane up happen under one lock: a request meanwhile starts a new worker
            val batch = mutex.withLock {
                val b = lane.queue.take(batchSize)
                lane.queue.removeAll(b.toSet()); inFlight += b
                if (b.isEmpty()) lane.worker = null
                b
            }
            if (batch.isEmpty()) return
            var asked: Asked? = null
            try {
                asked = countBatch(batch, lane.priority)
            } finally {
                mutex.withLock {
                    val t = now()
                    for (id in batch) {
                        inFlight.remove(id)
                        val a = asked
                        when {
                            // spec perf2 A6 (G4): not stamped when no relay was asked; asked again once one connects
                            a == null -> parked += id
                            // a session relay still connecting: a short stamp, and it fills in once it is up (final review I2)
                            a.missing -> { parked.remove(id); fetchedAt[id] = t - maxAgeSeconds + LATE_STAMP_SECONDS; partial[id] = a.relays.toHashSet() }
                            // a connected relay missed it (timeout, cut off, refused): asked again soon (re-review B1)
                            id in a.missed -> { parked.remove(id); fetchedAt[id] = t - maxAgeSeconds + LATE_STAMP_SECONDS; partial.remove(id) }
                            else -> { parked.remove(id); fetchedAt[id] = t; partial.remove(id) }
                        }
                    }
                    if (asked?.missing != false) watchLateRelays()
                }
            }
            val exit = mutex.withLock { if (lane.queue.isEmpty()) { lane.worker = null; true } else false }
            if (exit) return
        }
    }

    /** The relays a batch asked; [missing] when a session relay was not connected yet; [missed]: ids a relay did not answer. */
    private class Asked(val relays: Set<String>, val missing: Boolean, val missed: Set<String>)

    /** Null when no relay could be asked (none connected within [relayWaitMs]). */
    private suspend fun countBatch(ids: List<String>, priority: Boolean): Asked? {
        val connected = connectedRelays()
        if (connected.isEmpty()) return null
        val session = sessionRelays()
        val missing = mutex.withLock { gaveUp.removeAll(connected.toSet()); session.any { it !in connected && it !in unsupported && it !in gaveUp } }
        val targets = connected.filter { it !in unsupported }
        // every connected relay refused COUNT this session: nothing can count these notes, they count as asked
        val missed = if (targets.isNotEmpty()) countRound(ids, targets, priority, raiseOnly = false) else emptySet()
        return Asked(targets.toSet(), missing, missed)
    }

    /**
     * One round over [targets]; [raiseOnly] (a late relay's follow-up) never lowers a shown count at its end, nor does
     * the result of a note some relay did not answer. Returns those notes.
     */
    private suspend fun countRound(ids: List<String>, targets: List<String>, priority: Boolean, raiseOnly: Boolean): Set<String> {
        val round = Round()
        coroutineScope {
            for (url in targets) for (id in ids) {
                val eid = runCatching { EventId.parse(id) }.getOrNull() ?: continue
                launch {
                    val coordinate = coordinates[id]
                    val answers = (if (coordinate != null) articleFilters(coordinate) else noteFilters(eid)).map { fs ->
                        async {
                            // a kind slot may need several COUNTs (an article's replies); one missing answer misses the slot
                            val parts = fs.map { f -> async { countOrNull(url, f, priority, round) } }.awaitAll()
                            if (parts.any { it == null }) null else parts.sumOf { it!! }
                        }
                    }.awaitAll()
                    // a relay that missed one kind gives nothing for this note this round (no half-known counts)
                    if (answers.any { it == null }) { round.missed += id; return@launch }
                    merge(round, id, NoteCounts(answers[0], answers[1], answers[2]))
                }
            }
        }
        // the round's result lands once: a real drop (an own un-like, a deletion) shows now, not as a dip on the way
        val finished = synchronized(round.best) { HashMap(round.best) }
        if (finished.isNotEmpty()) _counts.update { m -> m + finished.mapValues { (id, c) -> if (raiseOnly || id in round.missed) raise(m[id], c) else c } }
        for (url in targets) settle(url, round)
        return round.missed
    }

    /** Starts (or extends) the watch for late relays; called under [mutex]. */
    private fun watchLateRelays() {
        watchPollsLeft = (LATE_WATCH_MS / RELAY_POLL_MS).toInt()
        if (watcher == null) watcher = scope.launch { watch() }
    }

    /**
     * Polls the connected relays for a bounded time: parked ids are asked again when the first relay is up, and a
     * relay that connects late gets one follow-up round for the visible notes it has not answered (final review I2).
     */
    private suspend fun watch() {
        while (true) {
            delay(RELAY_POLL_MS)
            val up = relays().toSet()
            val session = sessionRelays()
            var requeue: List<String> = emptyList()
            var followIds: List<String> = emptyList()
            var followRelays: List<String> = emptyList()
            val done = mutex.withLock {
                val visible = visibleNormal + visiblePriority
                if (up.isNotEmpty()) { requeue = parked.filter { it in visible }; parked.clear() }
                val open = partial.filterKeys { it in visible && it !in inFlight }
                // partial[id] holds the relays asked for it: each late relay is asked at most once per note
                followRelays = up.filter { url -> url !in unsupported && open.values.any { url !in it } }
                if (followRelays.isNotEmpty()) {
                    followIds = open.filterValues { asked -> followRelays.any { it !in asked } }.keys.toList()
                    inFlight += followIds
                }
                val over = --watchPollsLeft <= 0 || (parked.isEmpty() && partial.isEmpty())
                if (over) {
                    watcher = null
                    gaveUp += session.filter { it !in up }
                    // short stamps stay: the next request after them asks every relay again
                    partial.clear(); parked.clear()
                }
                over
            }
            if (requeue.isNotEmpty()) enqueue(requeue, priority = false, onScreen = false)
            if (followIds.isNotEmpty()) followUp(followIds, followRelays)
            if (done) return
        }
    }

    /** A late relay's round; an id every session relay has answered now keeps the normal stamp. */
    private suspend fun followUp(ids: List<String>, urls: List<String>) {
        try {
            countRound(ids, urls, priority = false, raiseOnly = true)
        } finally {
            val session = sessionRelays()
            mutex.withLock {
                val t = now()
                for (id in ids) {
                    inFlight.remove(id)
                    val asked = partial[id] ?: continue
                    asked += urls
                    if (session.all { it in asked || it in unsupported }) { partial.remove(id); if (fetchedAt.containsKey(id)) fetchedAt[id] = t }
                }
            }
        }
    }

    /** What one batch collected: the best answer per note so far and which relays failed or answered. */
    private class Round {
        val best = HashMap<String, NoteCounts>()
        val failed = ConcurrentHashMap.newKeySet<String>()
        val answered = ConcurrentHashMap.newKeySet<String>()
        /** Relays that timed out in this round: their remaining calls are skipped. */
        val timedOut = ConcurrentHashMap.newKeySet<String>()
        /** Notes some relay gave no full answer for (failed, timed out or skipped). */
        val missed = ConcurrentHashMap.newKeySet<String>()
    }

    /** All failed calls of a relay in one round are one failure; [MAX_FAILURES] rounds in a row make it unsupported, any answer resets. */
    private fun settle(url: String, round: Round) {
        val streak = failures.computeIfAbsent(url) { AtomicInteger() }
        when {
            url in round.answered -> streak.set(0)
            url in round.failed -> if (streak.incrementAndGet() >= MAX_FAILURES) unsupported += url
        }
    }

    /** During a round the shown value never drops below what was shown (relays only raise it); the round's end publishes the result. */
    private fun merge(round: Round, id: String, c: NoteCounts) = synchronized(round.best) {
        val best = raise(round.best[id], c)
        round.best[id] = best
        _counts.update { m -> m + (id to raise(m[id], best)) }
    }

    private fun raise(shown: NoteCounts?, c: NoteCounts): NoteCounts =
        if (shown == null) c else NoteCounts(maxOf(shown.likes, c.likes), maxOf(shown.reposts, c.reposts), maxOf(shown.replies, c.replies))

    /** The connected normal relays; with none yet (TLS still running, offline) waits up to [relayWaitMs] for one. */
    private suspend fun connectedRelays(): List<String> {
        var waited = 0L
        while (true) {
            val up = relays()
            if (up.isNotEmpty() || waited >= relayWaitMs) return up
            delay(RELAY_POLL_MS)
            waited += RELAY_POLL_MS
        }
    }

    /**
     * Null drops this call; the failure is only noted for the round (see [settle]). Once a call of [url] timed out in
     * this round, its remaining calls are skipped: a silent relay costs one timeout per round (final review I1). A
     * CLOSED or another error drops only that call (re-review B1).
     */
    private suspend fun countOrNull(url: String, filter: Filter, priority: Boolean, round: Round): Long? {
        if (url in round.timedOut) return null
        return limiter.withPermit(url, priority) { ask(url, filter, round) }
    }

    private suspend fun ask(url: String, filter: Filter, round: Round): Long? {
        if (url in unsupported || url in round.timedOut) return null
        return try {
            counter(url, filter).also { round.answered += url }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CountTimeoutException) {
            round.timedOut += url
            round.failed += url
            null
        } catch (e: Exception) {
            round.failed += url
            null
        }
    }

    private fun noteFilters(eid: EventId): List<List<Filter>> =
        KINDS.map { k -> listOf(Filter().kinds(k.map { Kind(it) }).event(eid)) }

    /** Likes kind 7 `#a`; reposts kinds 6 and 16 `#a`; replies kind 1111 `#A` plus kind 1 `#a`. */
    private fun articleFilters(coordinate: String): List<List<Filter>> = listOf(
        listOf(coordinateFilter(listOf(7), coordinate)),
        listOf(coordinateFilter(listOf(6, 16), coordinate)),
        listOf(coordinateFilter(listOf(Kinds.COMMENT), coordinate, "A"), coordinateFilter(listOf(1), coordinate)),
    )

    private fun maxOf(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> kotlin.math.max(a, b)
    }

    private companion object {
        /** Likes, reposts, replies (kind 1 and NIP-22 comments, backlog 31): the order of [NoteCounts], one COUNT each. */
        val KINDS = listOf(listOf<UShort>(7u), listOf<UShort>(6u), listOf<UShort>(1u, Kinds.COMMENT.toUShort()))
        const val MAX_FAILURES = 2
        const val RELAY_POLL_MS = 250L
        val COUNT_TIMEOUT: Duration = Duration.ofSeconds(5)
        /** How long late relays are watched after a batch that missed one (or found none). */
        const val LATE_WATCH_MS = 15_000L
        /** Stamp of a note asked while a session relay was missing. */
        const val LATE_STAMP_SECONDS = 20L
    }
}
