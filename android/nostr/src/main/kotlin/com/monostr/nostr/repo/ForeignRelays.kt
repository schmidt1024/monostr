package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.RelayUrl
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Spec 2026-10-23 §4.4/§10: the one app-wide budget for foreign relays (relays outside the normal
 * set, attached only to look something up). At most [cap] are attached at once, held and lingering
 * ones together. A relay nobody holds any more stays attached for [lingerMs] so the next page reuses
 * its connection, then is detached; lingering relays are the first to go when a lease needs room.
 *
 * The pool holds exactly one [NostrEngine.attachTemporary] reference per relay it attached and gives
 * it back exactly once (linger expiry, eviction or [close]); its own holders are counted here. Engine
 * calls run outside [mutex], and in [NonCancellable] wherever a half-done step could leak a reference.
 */
class ForeignRelays(
    private val engine: NostrEngine,
    private val scope: CoroutineScope = engine.backgroundScope,
    /** Most foreign relays attached at once; the locator keeps one of them free for the indexers (spec §4.2). */
    val cap: Int = 8,
    private val lingerMs: Long = 60_000,
) {
    private val mutex = Mutex()
    /** Holders per relay, attached or being attached (then also in [attaching]). */
    private val holders = HashMap<String, Int>()
    /** Attached relays without a holder, oldest first, with their expiry timer. */
    private val lingering = LinkedHashMap<String, Job>()
    /** Relays whose attach is in flight: a second lease of one waits for the outcome. */
    private val attaching = HashMap<String, CompletableDeferred<Boolean>>()
    /** Bumped by [close]: an attach that started before it gives its reference straight back. */
    private var epoch = 0L
    /** Bumped whenever a slot may have come free (release, expiry, failed attach, close): wakes waiting leases. */
    private val freed = MutableStateFlow(0L)
    @Volatile private var view: Set<String> = emptySet()

    /**
     * Takes a reference on those of [urls] that fit the budget (relays already held or lingering first, then new ones in order); waits up to [wait] for a free slot when none fits. Normal-set relays pass through uncounted. Returns the URLs the caller now holds (normalised).
     *
     * "None fits" means the result would be empty (spec §4.4: wait, or take only the attached ones that fit). A lease
     * cancelled at any point keeps nothing: whatever it already took is released before the cancellation propagates.
     */
    suspend fun lease(urls: List<String>, wait: Duration = Duration.ZERO): List<String> {
        val wanted = urls.mapNotNull(::normalise).distinct()
        if (wanted.isEmpty()) return emptyList()
        val normal = engine.relayUrls().toSet()
        val through = wanted.filter { it in normal }.toMutableList()
        var pending = wanted.filterNot { it in normal }
        val acquired = ArrayList<String>()
        val start = TimeSource.Monotonic.markNow()
        try {
            while (pending.isNotEmpty()) {
                val round = withContext(NonCancellable) { place(pending, acquired) }
                // withContext(NonCancellable) on the same dispatcher returns without a cancellation check:
                // a lease cancelled meanwhile must not hand its references to a caller that is gone
                currentCoroutineContext().ensureActive()
                through += round.promoted
                pending = round.noRoom
                if (pending.isEmpty() || acquired.isNotEmpty() || through.isNotEmpty()) break
                val left = wait - start.elapsedNow()
                if (!left.isPositive()) break
                withTimeoutOrNull(left) { freed.first { it != round.seen } } ?: break
            }
        } catch (e: Throwable) {
            release(acquired)
            throw e
        }
        return wanted.filter { it in through || it in acquired }
    }

    private class Round(val noRoom: List<String>, val promoted: List<String>, val seen: Long)

    /** One placement pass over [urls]; every reference taken is added to [acquired] the moment it is taken. */
    private suspend fun place(urls: List<String>, acquired: MutableList<String>): Round {
        val evicted = ArrayList<String>()
        val toAttach = LinkedHashMap<String, CompletableDeferred<Boolean>>()
        val inFlight = ArrayList<Pair<String, CompletableDeferred<Boolean>>>()
        val noRoom = ArrayList<String>()
        val seen: Long
        val started: Long
        mutex.withLock {
            seen = freed.value
            started = epoch
            // relays already attached (held or lingering) first: they cost nothing
            for (url in urls) {
                val linger = lingering.remove(url)
                when {
                    linger != null -> { linger.cancel(); holders[url] = 1; acquired += url }
                    url in holders -> {
                        holders.merge(url, 1, Int::plus)
                        val pending = attaching[url]
                        if (pending != null) inFlight += url to pending else acquired += url
                    }
                }
            }
            for (url in urls) {
                if (url in holders) continue
                if (holders.size + lingering.size >= cap) {
                    val oldest = lingering.keys.firstOrNull()
                    if (oldest == null) { noRoom += url; continue }
                    lingering.remove(oldest)?.cancel()
                    evicted += oldest
                }
                holders[url] = 1
                val outcome = CompletableDeferred<Boolean>()
                attaching[url] = outcome
                toAttach[url] = outcome
            }
            publish()
        }
        // an engine failure must not leave the reservations behind: waiters on them would hang and the slots stay taken
        if (evicted.isNotEmpty()) runCatching { engine.detachTemporary(evicted) }
        val taken = if (toAttach.isEmpty()) emptySet() else runCatching { engine.attachTemporary(toAttach.keys.toList()).toSet() }.getOrDefault(emptySet())
        val promoted = ArrayList<String>()
        val stale = ArrayList<String>()
        mutex.withLock {
            for ((url, outcome) in toAttach) {
                // after a close a newer lease may be attaching the same relay: its entry is not ours to drop
                if (attaching[url] === outcome) attaching.remove(url)
                val ok = url in taken && epoch == started
                if (ok) acquired += url
                else {
                    if (url in taken) stale += url
                    if (epoch == started) { holders.remove(url); freed.value++ }
                    // ruled out: a normal-set relay (promoted meanwhile) or a malformed/unaddable one
                    if (url !in taken) promoted += url
                }
                outcome.complete(ok)
            }
            publish()
        }
        if (stale.isNotEmpty()) engine.detachTemporary(stale)
        // a relay another lease was attaching: ours once that attach succeeded; on failure its entry is gone already
        for ((url, outcome) in inFlight) if (outcome.await()) acquired += url
        val normal = if (promoted.isEmpty()) emptySet() else engine.relayUrls().toSet()
        return Round(noRoom, promoted.filter { it in normal }, seen)
    }

    /** Gives the references back; a relay with no holder left lingers [lingerMs], then is detached. */
    suspend fun release(urls: List<String>) {
        if (urls.isEmpty()) return
        // NonCancellable as a whole: callers release from `finally` blocks (see NostrEngine.detachTemporary)
        withContext(NonCancellable) {
            val norm = urls.mapNotNull(::normalise)
            mutex.withLock {
                for (url in norm) {
                    val left = holders[url] ?: continue // normal-set, unknown or already closed
                    if (url in attaching) continue // never handed out yet: not the caller's to give back
                    if (left > 1) { holders[url] = left - 1; continue }
                    holders.remove(url)
                    lingering[url] = scope.launch { delay(lingerMs); expire(url) }
                    freed.value++ // a lingering relay is a slot a lease may take
                }
                publish()
            }
        }
    }

    private suspend fun expire(url: String) {
        val me = currentCoroutineContext().job
        withContext(NonCancellable) {
            val gone = mutex.withLock {
                // reused or evicted meanwhile: its timer was replaced or cancelled, the relay is not ours to detach
                if (lingering[url] !== me) return@withLock false
                lingering.remove(url)
                freed.value++
                publish()
                true
            }
            if (gone) engine.detachTemporary(listOf(url))
        }
    }

    /**
     * Detaches every lingering relay now; held ones stay until their holders release them (their round ends). After a
     * network switch a lingering relay's socket may be dead but still read connected (spec 2026-10-23 §7), and with the
     * author-relays switch turned off no foreign relay may stay connected for its linger (§3).
     */
    suspend fun dropLingering() {
        withContext(NonCancellable) {
            val gone = mutex.withLock {
                if (lingering.isEmpty()) return@withLock emptyList()
                lingering.values.forEach { it.cancel() }
                val gone = lingering.keys.toList()
                lingering.clear()
                freed.value++
                publish()
                gone
            }
            if (gone.isNotEmpty()) engine.detachTemporary(gone)
        }
    }

    /** Foreign relays attached now (held + lingering); for tests and PerfTrace. */
    fun attached(): Set<String> = view

    /** Detaches everything at once (logout). */
    suspend fun close() {
        withContext(NonCancellable) {
            val all = mutex.withLock {
                epoch++
                lingering.values.forEach { it.cancel() }
                // relays still being attached are given back by their own lease once the attach returns (epoch check)
                val all = (holders.keys - attaching.keys) + lingering.keys
                holders.clear(); lingering.clear(); attaching.clear()
                freed.value++
                publish()
                all.toList()
            }
            if (all.isNotEmpty()) engine.detachTemporary(all)
        }
    }

    /** Under [mutex]: the attached set for [attached]. */
    private fun publish() {
        view = (holders.keys - attaching.keys) + lingering.keys
    }

    /** The engine's form of a relay URL (as [NostrEngine.attachTemporary] returns it), null when malformed. */
    private fun normalise(raw: String): String? =
        runCatching { RelayUrl.parse(raw.trim()).toString().trimEnd('/') }.getOrNull()
}
