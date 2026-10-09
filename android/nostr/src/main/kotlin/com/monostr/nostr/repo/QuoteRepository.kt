package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
    /** Memory only: the remembered result, null when not loaded yet or the missing mark expired. */
    fun cached(id: String): QuoteResult?
    /** Database, else the hint relays plus the normal relays (spec 3); at most one load per id at a time. */
    suspend fun get(id: String, hints: List<String> = emptyList()): QuoteResult
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
) : QuoteRepository {
    private val lock = Any()
    private val found = object : LinkedHashMap<String, Note>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Note>?): Boolean = size > capacity
    }
    private val missing = HashMap<String, Long>()
    private val inFlight = HashMap<String, Deferred<QuoteResult>>()
    private val loadCount = AtomicInteger()

    /** Relay loads started so far (database hits do not count); for tests. */
    internal val loads: Int get() = loadCount.get()

    override fun cached(id: String): QuoteResult? = synchronized(lock) { memo(id) }

    override suspend fun get(id: String, hints: List<String>): QuoteResult {
        val job = synchronized(lock) {
            memo(id)?.let { return it }
            inFlight.getOrPut(id) { scope.async { load(id, hints) } }
        }
        return job.await()
    }

    /** Caller holds [lock]. */
    private fun memo(id: String): QuoteResult? {
        found[id]?.let { return QuoteResult.Found(it) }
        val at = missing[id] ?: return null
        if (at + missingSeconds > now()) return QuoteResult.Missing
        missing.remove(id)
        return null
    }

    private suspend fun load(id: String, hints: List<String>): QuoteResult {
        val result = try {
            lookup(id, hints)
        } catch (e: CancellationException) {
            synchronized(lock) { inFlight.remove(id) }
            throw e
        } catch (e: Exception) {
            QuoteResult.Missing
        }
        synchronized(lock) {
            inFlight.remove(id)
            when (result) {
                is QuoteResult.Found -> found[id] = result.note
                QuoteResult.Missing -> missing[id] = now()
            }
        }
        return result
    }

    private suspend fun lookup(id: String, hints: List<String>): QuoteResult {
        local(id)?.let { return QuoteResult.Found(it) }
        loadCount.incrementAndGet()
        val held = engine.attachTemporary(hints.take(MAX_HINTS))
        try {
            if (held.isNotEmpty()) {
                withTimeoutOrNull(CONNECT_WAIT_MS) { while (!engine.connectedRelayUrls().containsAll(held)) delay(50) }
            }
            val relays = (held + engine.relayUrls()).distinct()
            if (relays.isNotEmpty()) {
                quietly { engine.fetchFrom(relays, Filter().kind(Kind(1u)).id(EventId.parse(id)).limit(1u), timeout, attach = false) }
            }
        } finally {
            engine.detachTemporary(held)
        }
        return local(id)?.let { QuoteResult.Found(it) } ?: QuoteResult.Missing
    }

    private suspend fun local(id: String): Note? =
        engine.eventById(id)?.takeIf { it.kind().asU16().toInt() == 1 && !Withdrawn.isWithdrawn(engine, it) }?.let { NoteMapper.note(it, withQuote = false) }

    private companion object {
        const val CONNECT_WAIT_MS = 2_000L
        /** A crafted note must not make every viewer connect to dozens of relays. */
        const val MAX_HINTS = 3
    }
}
