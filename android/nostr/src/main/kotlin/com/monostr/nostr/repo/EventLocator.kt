package com.monostr.nostr.repo

import com.monostr.tips.Kinds
import com.monostr.tips.RelayHosts
import com.monostr.nostr.Exit
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PerfTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import rust.nostr.sdk.RelayUrl
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** One missing note and where it may be: hint relays and candidate authors (spec 2026-10-23 §4.1). */
data class Wanted(val id: String, val relays: List<String> = emptyList(), val authors: List<String> = emptyList())

interface EventLocator {
    /** False when the switch is off or [id] is marked "searched, not found" (§4.3). */
    suspend fun searchable(id: String): Boolean
    /** [searchable] for many ids at once: those of [ids] (as given) that may be searched; the switch is read once (final review M4). */
    suspend fun searchable(ids: Collection<String>): Set<String> = ids.filterTo(HashSet()) { searchable(it) }
    /** Stage 3 for [wanted]: hint relays at once, author write relays after one batched lookup; returns the ids now in the database. Joins a running search of the same id; marks what stays missing. */
    suspend fun find(wanted: Collection<Wanted>): Set<String>
    /** Replies (kind 1 tagging [tagIds]) from up to three read relays of [author] (§5.3); true when a relay was asked. */
    suspend fun repliesFrom(author: String, tagIds: List<String>): Boolean
    /**
     * Clears all marks; with [notify] also emits on [retries] (network back, switch). A pull-to-refresh passes false:
     * its screen walks the open chains itself, and a retry signal would walk them (and every other open list) again.
     */
    fun forget(notify: Boolean = true)
    val retries: SharedFlow<Unit>
}

/**
 * Spec 2026-10-23 §4.2–4.4: stage 3 of the lookup ladder for missing kind-1 notes, after the database and the own
 * relays. Hint relays are asked at once while the authors' NIP-65 write relays are looked up in one batch (§10), then
 * the write relays are asked; at most [perId] foreign relays per id, all of them within the app-wide [foreign] budget,
 * one request per relay for every id it may have. An id still missing afterwards is marked for [missingSeconds]
 * (memory only); the same id never runs twice at once: a second caller joins the running search, which runs in
 * [scope] so a caller that leaves does not cancel it for the others.
 */
class NostrEventLocator(
    private val engine: NostrEngine,
    private val authorRelays: AuthorRelaysRepository,
    private val foreign: ForeignRelays,
    /** The `authorRelays` switch; read once per round, so a round that started finishes (Review Focus 2). */
    private val enabled: suspend () -> Boolean,
    private val scope: CoroutineScope = engine.backgroundScope,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val missingSeconds: Long = 600,
    private val perId: Int = 3,
    private val timeout: Duration = Duration.ofSeconds(5),
    /** Hints come from other people's notes: only public wss relays are contacted. */
    private val relayFilter: (String) -> Boolean = RelayHosts::isPublicWss,
) : EventLocator {
    private val lock = Any()
    private val missing = HashMap<String, Long>()
    private val inFlight = HashMap<String, Deferred<Unit>>()
    private val _retries = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val retries: SharedFlow<Unit> = _retries

    override suspend fun searchable(id: String): Boolean {
        val key = id.lowercase()
        return HEX.matches(key) && enabled() && synchronized(lock) { !marked(key) }
    }

    override suspend fun searchable(ids: Collection<String>): Set<String> {
        val valid = ids.filter { HEX.matches(it.lowercase()) }
        if (valid.isEmpty() || !enabled()) return emptySet()
        return synchronized(lock) { valid.filterTo(HashSet()) { !marked(it.lowercase()) } }
    }

    override fun forget(notify: Boolean) {
        synchronized(lock) { missing.clear() }
        if (notify) _retries.tryEmit(Unit)
    }

    override suspend fun find(wanted: Collection<Wanted>): Set<String> {
        // one entry per valid id; the same id wanted twice pools its hints and candidates
        val byId = LinkedHashMap<String, Wanted>()
        for (w in wanted) {
            val id = w.id.lowercase()
            if (!HEX.matches(id)) continue
            val had = byId[id]
            byId[id] = if (had == null) Wanted(id, w.relays, w.authors) else Wanted(id, had.relays + w.relays, had.authors + w.authors)
        }
        if (byId.isEmpty()) return emptySet()
        val stored = inDatabase(byId.keys)
        val open = synchronized(lock) { byId.filterKeys { it !in stored && !marked(it) } }
        if (open.isEmpty() || !enabled()) return stored
        val jobs = ArrayList<Deferred<Unit>>()
        val mine = LinkedHashMap<String, Wanted>()
        val job = synchronized(lock) {
            for ((id, w) in open) {
                val running = inFlight[id]
                if (running != null) { if (running !in jobs) jobs += running } else mine[id] = w
            }
            if (mine.isEmpty()) null
            else scope.async(start = CoroutineStart.LAZY) { round(mine) }.also { j ->
                mine.keys.forEach { inFlight[it] = j }
                jobs += j
                // also when the scope is cancelled before the round ever ran: no id may stay joined to a dead round
                j.invokeOnCompletion { synchronized(lock) { mine.keys.forEach { if (inFlight[it] === j) inFlight.remove(it) } } }
            }
        }
        job?.start()
        try {
            jobs.awaitAll()
        } catch (e: CancellationException) {
            // a joined round cancelled (engine closing) leaves this caller with what the database has
            currentCoroutineContext().ensureActive()
        }
        return stored + inDatabase(open.keys)
    }

    /** One round for [open] (ids this round owns): asks, then marks what stays missing; an error marks nothing. */
    private suspend fun round(open: Map<String, Wanted>) {
        val started = System.nanoTime()
        val asked = Asked()
        try {
            lookup(open, asked)
            val found = inDatabase(open.keys)
            // spec §4.3: "searched, not found" only when every relay planned for the id was asked while connected (a full
            // budget, a relay that could not be reached or an offline round is no answer)
            val tried = synchronized(asked) {
                asked.planned.filter { (id, plan) -> plan.isNotEmpty() && asked.reached[id].orEmpty().containsAll(plan) }.keys
            }
            synchronized(lock) { open.keys.filter { it !in found && it in tried }.forEach { missing[it] = now() } }
            PerfTrace.mark("locator found ${found.size}/${open.size} in ${(System.nanoTime() - started) / 1_000_000}ms relays ${synchronized(asked) { asked.relays.size }}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // best effort: the next round may try again
        }
    }

    /**
     * What a round asked, guarded by itself: the relays; per id the relays planned for it (hints + write relays, at most
     * [perId]; absent when its author lookup failed) and the ones that were connected when they were asked for it.
     */
    private class Asked {
        val relays = HashSet<String>()
        val planned = HashMap<String, List<String>>()
        val reached = HashMap<String, MutableSet<String>>()
    }

    /**
     * The two phases of a round: the hint relays are leased (no wait; at most `cap - 1`, the ones most open ids name
     * first, so a slot stays free for the indexers, spec §4.2) and asked at once while the write relays of the
     * candidate authors are looked up in one batch. Phase 2 then leases what is left for the ids still missing (write
     * relays, and hint relays that found no room in phase 1), the ones most open ids need first, waiting for room in the
     * budget; lease wait and fetches share one deadline of [timeout]. Every lease is given back in `finally`.
     */
    private suspend fun lookup(open: Map<String, Wanted>, asked: Asked) {
        val hints = open.mapValues { (_, w) -> w.relays.mapNotNull(::normalise).filter(relayFilter).distinct().take(perId) }
        // authors only for ids whose hints leave room (at most [perId] relays per id), at most three per id
        val authors = open.mapValues { (id, w) ->
            if (hints.getValue(id).size >= perId) emptyList() else w.authors.map { it.lowercase() }.filter { HEX.matches(it) }.distinct().take(MAX_AUTHORS)
        }
        val held = ArrayList<String>()
        try {
            coroutineScope {
                val hintRelays = LinkedHashMap<String, MutableList<String>>()
                for ((id, rs) in hints) for (r in rs) hintRelays.getOrPut(r) { ArrayList() } += id
                val firstWanted = hintRelays.entries.sortedByDescending { it.value.size }.take(foreign.cap - 1).map { it.key }
                val first = if (firstWanted.isEmpty()) emptyList() else foreign.lease(firstWanted)
                synchronized(held) { held += first }
                val firstSet = first.toSet()
                val second = launch {
                    val wanted = authors.values.flatten().distinct()
                    var failed = false
                    val lists = if (wanted.isEmpty()) emptyMap() else try {
                        authorRelays.writeRelaysMany(wanted)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed = true
                        emptyMap()
                    }
                    val byRelay = LinkedHashMap<String, MutableList<String>>()
                    for (id in open.keys - inDatabase(open.keys)) {
                        val hinted = hints.getValue(id)
                        val writes = authors.getValue(id).flatMap { lists[it].orEmpty() }.filter(relayFilter)
                        val plan = (hinted + writes).distinct().take(perId)
                        // a failed author lookup leaves the plan unknown: such an id is never marked
                        if (!failed || authors.getValue(id).isEmpty()) synchronized(asked) { asked.planned[id] = plan }
                        // a hint relay phase 1 holds is asking for this id already
                        for (r in plan) if (r !in hinted || r !in firstSet) byRelay.getOrPut(r) { ArrayList() } += id
                    }
                    if (byRelay.isEmpty()) return@launch
                    val deadline = TimeSource.Monotonic.markNow() + timeout.toMillis().milliseconds
                    val ordered = byRelay.entries.sortedByDescending { it.value.size }.map { it.key }
                    val leased = foreign.lease(ordered, wait = -deadline.elapsedNow())
                    synchronized(held) { held += leased }
                    val left = -deadline.elapsedNow()
                    if (left.isPositive()) ask(leased, byRelay, asked, left.inWholeMilliseconds.let(Duration::ofMillis))
                }
                if (first.isNotEmpty()) {
                    ask(first, hintRelays, asked, timeout)
                    // spec §10 «Hinweis zuerst»: everything came through the hints, the author lookup is not waited for
                    if (inDatabase(open.keys).size == open.size) second.cancel()
                }
            }
        } finally {
            foreign.release(synchronized(held) { held.toList() })
        }
    }

    /**
     * One request per relay of [relays] (in parallel) for the ids [byRelay] gives it, within [limit]; records them in
     * [asked], as reached only when the relay was connected when the request went out (spec §4.3).
     */
    private suspend fun ask(relays: List<String>, byRelay: Map<String, List<String>>, asked: Asked, limit: Duration) = coroutineScope {
        for (r in relays) {
            val ids = byRelay[r] ?: continue
            synchronized(asked) { asked.relays += r }
            launch {
                val reached = HashSet<String>(1)
                quietly { engine.fetchFrom(listOf(r), idsFilter(ids), limit, attach = false, exit = Exit.AllIds(ids.toSet()), reached = reached) }
                if (r in reached) synchronized(asked) { for (id in ids) asked.reached.getOrPut(id) { HashSet() } += r }
            }
        }
    }

    override suspend fun repliesFrom(author: String, tagIds: List<String>): Boolean {
        val ids = tagIds.map { it.lowercase() }.filter { HEX.matches(it) }.distinct()
        if (ids.isEmpty() || !enabled()) return false
        val relays = authorRelays.readRelays(author.lowercase()).filter(relayFilter).take(perId)
        if (relays.isEmpty()) return false
        val leased = foreign.lease(relays)
        try {
            if (leased.isEmpty()) return false
            quietly { engine.fetchFrom(leased, taggedFilter(listOf(1), ids), timeout, attach = false) }
            return true
        } finally {
            foreign.release(leased)
        }
    }

    /** Caller holds [lock]: whether [id] is marked and the mark has not expired (an expired one is dropped). */
    private fun marked(id: String): Boolean {
        val at = missing[id] ?: return false
        if (at + missingSeconds > now()) return true
        missing.remove(id)
        return false
    }

    /** Those of [ids] the database has as kind 1, in one read. */
    private suspend fun inDatabase(ids: Collection<String>): Set<String> =
        if (ids.isEmpty()) emptySet() else engine.queryDecoded(idsFilter(ids)).filter { it.kind == 1 || it.kind == Kinds.COMMENT }.mapTo(HashSet()) { it.id }

    /** The engine's form of a relay URL (as [ForeignRelays.lease] returns it), null when malformed. */
    private fun normalise(raw: String): String? =
        runCatching { RelayUrl.parse(raw.trim()).toString().trimEnd('/') }.getOrNull()

    private companion object {
        val HEX = Regex("[0-9a-f]{64}")
        /** Candidate authors per wanted note that reach the batched kind 10002 lookup (spec §4.1). */
        const val MAX_AUTHORS = 3
    }
}
