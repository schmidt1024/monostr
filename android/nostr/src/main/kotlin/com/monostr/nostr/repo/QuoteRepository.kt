package com.monostr.nostr.repo

import com.monostr.tips.RelayHosts
import com.monostr.nostr.Exit
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

sealed interface QuoteResult {
    /** The quoted note, mapped without its own quote (spec 3: depth 1). */
    data class Found(val note: Note) : QuoteResult
    data object Missing : QuoteResult
}

interface QuoteRepository {
    /** Memory only: the remembered result, null when not loaded yet or the missing mark expired; also null for a Missing when [author] was not asked yet. */
    fun cached(id: String, author: String? = null): QuoteResult?
    /** Database, else the hint relays plus the normal relays (spec 3), then the [author]'s write relays (spec 2026-10-23 §6); at most one load per id at a time. */
    suspend fun get(id: String, hints: List<String> = emptyList(), author: String? = null): QuoteResult
}

/**
 * Spec 3/7: quote cards load each note at most once per process. Found notes stay in an LRU of
 * [capacity]; a miss is remembered for [missingSeconds] so a card never spins forever and never
 * asks again right away. Loads run in [scope], so a card scrolled away does not cancel a load that
 * other cards of the same note are waiting for.
 */
class NostrQuoteRepository(
    private val engine: NostrEngine,
    private val scope: CoroutineScope = engine.backgroundScope,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val timeout: Duration = Duration.ofSeconds(5),
    private val capacity: Int = 200,
    private val missingSeconds: Long = 600,
    /** Hints come from other people's notes: only public wss relays are contacted. */
    private val relayFilter: (String) -> Boolean = RelayHosts::isPublicWss,
    /** Stage 3 for a quote still missing after the own relays and the hints (spec 2026-10-23 §6); null: none. */
    private val locator: EventLocator? = null,
    /** Spec 2026-10-23 §3/§4.4: when set, the hint relays are leased from the app-wide budget (no wait) instead of attached on the side. */
    private val foreign: ForeignRelays? = null,
) : QuoteRepository {
    private val lock = Any()
    private val found = object : LinkedHashMap<String, Note>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Note>?): Boolean = size > capacity
    }
    private val missing = HashMap<String, Long>()
    private val inFlight = HashMap<String, Deferred<QuoteResult>>()
    /** Per id the (hints, author) inputs already asked: a new input lifts the Missing mark (spec 2026-10-23 §6). */
    private val tried = HashMap<String, MutableSet<Pair<List<String>, String?>>>()
    private val loadCount = AtomicInteger()

    /** Relay loads started so far (database hits do not count); for tests. */
    internal val loads: Int get() = loadCount.get()

    init {
        // Network back, pull-to-refresh or the switch: everything may be asked again.
        locator?.let { l ->
            scope.launch {
                l.retries.collect { synchronized(lock) { missing.clear(); tried.clear() } }
            }
        }
    }

    override fun cached(id: String, author: String?): QuoteResult? = synchronized(lock) {
        val m = memo(id)
        if (m === QuoteResult.Missing && author != null && tried[id].orEmpty().none { it.second == author }) null else m
    }

    override suspend fun get(id: String, hints: List<String>, author: String?): QuoteResult {
        val input = hints to author
        val job = synchronized(lock) {
            val m = memo(id)
            if (m is QuoteResult.Found) return m
            // A Missing stands only for inputs already asked; a new hint or author asks again.
            if (m != null && input in tried[id].orEmpty()) return m
            inFlight.getOrPut(id) {
                tried.getOrPut(id) { HashSet() } += input
                scope.async { load(id, hints, author) }
            }
        }
        return job.await()
    }

    /** Caller holds [lock]. */
    private fun memo(id: String): QuoteResult? {
        found[id]?.let { return QuoteResult.Found(it) }
        val at = missing[id] ?: return null
        if (at + missingSeconds > now()) return QuoteResult.Missing
        missing.remove(id)
        tried.remove(id)
        return null
    }

    private suspend fun load(id: String, hints: List<String>, author: String?): QuoteResult {
        // set by [lookup] when hint relays found no room in the foreign budget: such a miss is no answer
        var short = false
        val result = try {
            lookup(id, hints, author) { short = true }
        } catch (e: CancellationException) {
            synchronized(lock) { inFlight.remove(id) }
            throw e
        } catch (e: Exception) {
            QuoteResult.Missing
        }
        synchronized(lock) {
            inFlight.remove(id)
            when (result) {
                is QuoteResult.Found -> { found[id] = result.note; tried.remove(id) }
                // not every hint was asked: Missing for this call only, the same input asks again (final re-review)
                QuoteResult.Missing -> if (short) tried[id]?.remove(hints to author) else missing[id] = now()
            }
        }
        return result
    }

    /** [short]: called when some of the hint relays got no slot in the foreign budget. */
    private suspend fun lookup(id: String, hints: List<String>, author: String?, short: () -> Unit): QuoteResult {
        local(id)?.let { return QuoteResult.Found(it) }
        loadCount.incrementAndGet()
        val hinted = hints.filter(relayFilter).take(MAX_HINTS)
        // a full budget leaves the hints out: the own relays and the locator still look (final review M2)
        val held = if (foreign != null) foreign.lease(hinted) else engine.attachTemporary(hinted)
        if (foreign != null && held.size < hinted.size) short()
        try {
            if (held.isNotEmpty()) {
                withTimeoutOrNull(CONNECT_WAIT_MS) { while (!engine.connectedRelayUrls().containsAll(held)) delay(50) }
            }
            val relays = (held + engine.relayUrls()).distinct()
            if (relays.isNotEmpty()) {
                quietly { engine.fetchFrom(relays, Filter().kind(Kind(1u)).id(EventId.parse(id)).limit(1u), timeout, attach = false, exit = Exit.AllIds(setOf(id))) }
            }
        } finally {
            if (foreign != null) foreign.release(held) else engine.detachTemporary(held)
        }
        local(id)?.let { return QuoteResult.Found(it) }
        // Miss path only; the hints were asked above, the locator respects the author-relays switch itself.
        if (author != null && locator != null) {
            locator.find(listOf(Wanted(id, authors = listOf(author))))
            return local(id)?.let { QuoteResult.Found(it) } ?: QuoteResult.Missing
        }
        return QuoteResult.Missing
    }

    private suspend fun local(id: String): Note? =
        engine.eventById(id)?.takeIf { it.kind().asU16().toInt() == 1 && !Withdrawn.isWithdrawn(engine, it) }?.let { NoteMapper.note(it, withQuote = false) }

    private companion object {
        const val CONNECT_WAIT_MS = 2_000L
        /** A crafted note must not make every viewer connect to dozens of relays. */
        const val MAX_HINTS = 3
    }
}
