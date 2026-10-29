package com.monostr.nostr.repo

import com.monostr.nostr.Exit
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.AddressRef
import com.monostr.nostr.model.AddressableEvent
import com.monostr.nostr.model.LiveStream
import com.monostr.tips.RelayHosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import rust.nostr.sdk.Event
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

sealed interface AddressResult {
    data class Found(val event: AddressableEvent) : AddressResult
    data object Missing : AddressResult
    /** A deletion request (kind 5) by the author, not older than the event, names it (or names it with no event left). */
    data object Deleted : AddressResult
}

interface AddressableRepository {
    /**
     * Memory only: the remembered result, null when not loaded yet or the missing mark expired. A stale live event is still
     * returned. A miss counts only for the hints (and [allowForeign]) already asked: new ones read as not loaded.
     */
    fun cached(ref: AddressRef, allowForeign: Boolean = true): AddressResult?
    /**
     * Database, else the hint relays plus the normal relays, then the author's write relays from a stored kind 10002; at
     * most one load per address at a time. A live event older than the refresh age is asked again. [allowForeign] false
     * (DM cards, spec 8): only the user's own relays are asked, no hint and no author relay.
     */
    suspend fun get(ref: AddressRef, allowForeign: Boolean = true): AddressResult
}

/**
 * Spec 2026-10-29 naddr: cards for `nostr:naddr1…` load each address at most once per process, like
 * [NostrQuoteRepository]. Found and Deleted stay in an LRU of [capacity]; a miss is remembered for
 * [missingSeconds]. A kind 30311 (live) result older than [liveRefreshSeconds] is fetched again by [get].
 * The event and the author's deletion requests are two relay requests started together on the same relays
 * (the engine fetches one filter at a time): the deletion one is best effort, waited for only [DELETION_GRACE_MS]
 * and then left to finish in [scope] so its answer is in the database next time. A miss on the own relays and the
 * hints asks up to two write relays of the author's stored kind 10002 (the outbox), leased like the hints.
 */
class NostrAddressableRepository(
    private val engine: NostrEngine,
    private val scope: CoroutineScope = engine.backgroundScope,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val timeout: Duration = Duration.ofSeconds(5),
    private val capacity: Int = 200,
    private val missingSeconds: Long = 600,
    private val liveRefreshSeconds: Long = 60,
    /** Hints come from other people's notes: only public wss relays are contacted. */
    private val relayFilter: (String) -> Boolean = RelayHosts::isPublicWss,
    /** Spec 2026-10-23 §3/§4.4: when set, the hint relays are leased from the app-wide budget (no wait) instead of attached on the side. */
    private val foreign: ForeignRelays? = null,
    /** Only its [EventLocator.retries] is used: network back, pull-to-refresh or the switch lift the miss marks (spec 2026-10-23 §6). */
    locator: EventLocator? = null,
) : AddressableRepository {
    private class Entry(val result: AddressResult, val loadedAt: Long)

    private val lock = Any()
    private val known = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > capacity
    }
    private val missing = HashMap<String, Long>()
    /** Per coordinate the (hints, allowForeign) inputs already asked: a new input lifts the miss mark, like [NostrQuoteRepository]. */
    private val tried = HashMap<String, MutableSet<Pair<List<String>, Boolean>>>()
    private val inFlight = HashMap<String, Deferred<AddressResult>>()
    private val loadCount = AtomicInteger()

    /** Relay loads started so far (database hits do not count); for tests. */
    internal val loads: Int get() = loadCount.get()

    init {
        locator?.let { l -> scope.launch { l.retries.collect { synchronized(lock) { missing.clear(); tried.clear() } } } }
    }

    override fun cached(ref: AddressRef, allowForeign: Boolean): AddressResult? = synchronized(lock) {
        memo(ref.coordinate)?.takeIf { it.result != AddressResult.Missing || asked(ref, allowForeign) }?.result
    }

    override suspend fun get(ref: AddressRef, allowForeign: Boolean): AddressResult {
        val key = ref.coordinate
        val job = synchronized(lock) {
            val m = memo(key)
            // a miss stands only for inputs already asked; new hints ask again
            if (m != null && !stale(m) && (m.result != AddressResult.Missing || asked(ref, allowForeign))) return m.result
            inFlight.getOrPut(key) {
                tried.getOrPut(key) { HashSet() } += ref.relays to allowForeign
                scope.async { load(ref, allowForeign, refresh = m?.result is AddressResult.Found) }
            }
        }
        return job.await()
    }

    /** Caller holds [lock]. A lookup with foreign relays covers the same hints without them. */
    private fun asked(ref: AddressRef, allowForeign: Boolean): Boolean {
        val t = tried[ref.coordinate] ?: return false
        return (ref.relays to true) in t || (!allowForeign && (ref.relays to false) in t)
    }

    private fun stale(e: Entry): Boolean =
        e.result is AddressResult.Found && e.result.event.kind == LiveStream.KIND && e.loadedAt + liveRefreshSeconds <= now()

    /** Caller holds [lock]. */
    private fun memo(key: String): Entry? {
        known[key]?.let { return it }
        val at = missing[key] ?: return null
        if (at + missingSeconds > now()) return Entry(AddressResult.Missing, at)
        missing.remove(key)
        tried.remove(key)
        return null
    }

    private suspend fun load(ref: AddressRef, allowForeign: Boolean, refresh: Boolean): AddressResult {
        val key = ref.coordinate
        // set by [lookup] when hint or outbox relays found no room in the foreign budget: such a miss is no answer
        var short = false
        val result = try {
            lookup(ref, allowForeign, refresh) { short = true }
        } catch (e: CancellationException) {
            synchronized(lock) { inFlight.remove(key) }
            throw e
        } catch (e: Exception) {
            AddressResult.Missing
        }
        synchronized(lock) {
            inFlight.remove(key)
            when (result) {
                // not every relay was asked: a miss for this call only, the same input asks again
                AddressResult.Missing -> if (short) tried[key]?.remove(ref.relays to allowForeign) else missing[key] = now()
                else -> { known[key] = Entry(result, now()); missing.remove(key); tried.remove(key) }
            }
        }
        return result
    }

    /** [short]: called when some of the hint or outbox relays got no slot in the foreign budget. */
    private suspend fun lookup(ref: AddressRef, allowForeign: Boolean, refresh: Boolean, short: () -> Unit): AddressResult {
        if (!refresh) {
            val local = newest(ref)
            if (local != null) return resolve(ref, local)
        }
        loadCount.incrementAndGet()
        val own = engine.relayUrls()
        val hinted = if (allowForeign) ref.relays.filter(relayFilter).take(MAX_HINTS) else emptyList()
        ask(ref, hinted, own, short)
        val first = resolve(ref, newest(ref))
        if (first != AddressResult.Missing || !allowForeign) return first
        // spec 2026-10-29 naddr (final review I4): the author's outbox, read from a stored kind 10002 only
        val outbox = writeRelays(ref.pubkey).filter { it !in own && it !in hinted }.take(MAX_OUTBOX)
        if (outbox.isEmpty()) return first
        ask(ref, outbox, emptyList(), short)
        return resolve(ref, newest(ref))
    }

    /**
     * One round on [extra] (leased or attached on the side) plus [own]: the event with an early exit on the author's
     * newest version, the deletion requests in [scope] with a short grace. The extra relays are given back when the
     * deletion fetch ends, so it may finish after the card is answered.
     */
    private suspend fun ask(ref: AddressRef, extra: List<String>, own: List<String>, short: () -> Unit) {
        val held = if (foreign != null) foreign.lease(extra) else engine.attachTemporary(extra)
        if (foreign != null && held.size < extra.size) short()
        var handedOff = false
        try {
            if (held.isNotEmpty()) {
                withTimeoutOrNull(CONNECT_WAIT_MS) { while (!engine.connectedRelayUrls().containsAll(held)) delay(50) }
            }
            val relays = (held + own).distinct()
            if (relays.isEmpty()) return
            // best effort: a failing or slow deletion request must not cost the event, nor hold up the card
            val deletions = scope.launch {
                try {
                    quietly { engine.fetchFrom(relays, deletionFilter(ref), timeout, attach = false) }
                } finally {
                    withContext(NonCancellable) { giveBack(held) }
                }
            }
            handedOff = true
            // the newest version, not the first a fast relay sends (final review I2)
            quietly { engine.fetchFrom(relays, eventFilter(ref), timeout, attach = false, exit = Exit.NewestPerAuthor(setOf(ref.pubkey))) }
            withTimeoutOrNull(DELETION_GRACE_MS) { deletions.join() }
        } finally {
            if (!handedOff) withContext(NonCancellable) { giveBack(held) }
        }
    }

    private suspend fun giveBack(held: List<String>) {
        if (held.isEmpty()) return
        try {
            if (foreign != null) foreign.release(held) else engine.detachTemporary(held)
        } catch (e: Exception) {
            // the engine may be closed by now
        }
    }

    /** Write relays (no marker or `write`) of the author's newest stored kind 10002, public wss only; the database alone is read. */
    private suspend fun writeRelays(pubkey: String): List<String> {
        val list = engine.query(Filter().kind(Kind(10002u)).authors(listOf(PublicKey.parse(pubkey))).limit(1u))
            .filter { it.author().toHex() == pubkey }.maxByOrNull { it.createdAt().asSecs() } ?: return emptyList()
        return list.tags().toVec().map { it.asVec() }
            .filter { it.size >= 2 && it[0] == "r" && (it.getOrNull(2) == null || it[2] == "write") }
            .mapNotNull { runCatching { RelayUrl.parse(it[1].trim()) }.getOrNull()?.toString()?.trimEnd('/') }
            .filter(relayFilter).distinct()
    }

    /** Found, Deleted or Missing from what the database holds now. */
    private suspend fun resolve(ref: AddressRef, event: Event?): AddressResult {
        val at = event?.createdAt()?.asSecs()?.toLong()
        val requests = engine.query(deletionFilter(ref)) + (event?.let { engine.query(deletionFilter(ref, "#e", it.id().toHex())) } ?: emptyList())
        val deleted = requests.any {
            it.author().toHex() == ref.pubkey && (at == null || it.createdAt().asSecs().toLong() >= at)
        }
        if (deleted) return AddressResult.Deleted
        if (event == null || at == null) return AddressResult.Missing
        return AddressResult.Found(
            AddressableEvent(event.id().toHex(), ref.kind, ref.pubkey, ref.identifier, at, event.tags().toVec().map { it.asVec() }, event.content()),
        )
    }

    private suspend fun newest(ref: AddressRef): Event? =
        engine.query(eventFilter(ref)).filter { it.kind().asU16().toInt() == ref.kind && it.author().toHex() == ref.pubkey }
            .maxByOrNull { it.createdAt().asSecs() }

    private fun eventFilter(ref: AddressRef): Filter = Filter.fromJson(
        buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(ref.kind))))
            put("authors", JsonArray(listOf(JsonPrimitive(ref.pubkey))))
            put("#d", JsonArray(listOf(JsonPrimitive(ref.identifier))))
            put("limit", JsonPrimitive(1))
        }.toString(),
    )

    /** Kind 5 by the address's author naming the coordinate in `a` or, for a known event, its id in `e`. */
    private fun deletionFilter(ref: AddressRef, tag: String = "#a", value: String = ref.coordinate): Filter = Filter.fromJson(
        buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(5))))
            put("authors", JsonArray(listOf(JsonPrimitive(ref.pubkey))))
            put(tag, JsonArray(listOf(JsonPrimitive(value))))
        }.toString(),
    )

    private companion object {
        const val CONNECT_WAIT_MS = 2_000L
        const val MAX_HINTS = 3
        /** Author write relays asked on a miss. */
        const val MAX_OUTBOX = 2
        /** How long the card waits for the deletion fetch once the event fetch ended. */
        const val DELETION_GRACE_MS = 300L
    }
}
