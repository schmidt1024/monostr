package com.monostr.nostr

import com.monostr.nostr.model.RelayInfo
import com.monostr.nostr.model.RelayMode
import com.monostr.nostr.model.RelayState
import com.monostr.tips.RelayHosts
import com.monostr.tips.event.EventJson
import com.monostr.tips.event.Event as JsonEvent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.monostr.nostr.net.addRoutedRelay
import com.monostr.nostr.net.addRoutedWriteRelay
import com.monostr.nostr.net.awaitConnected
import com.monostr.nostr.net.addTemporaryRelay
import com.monostr.nostr.net.throughSocks
import rust.nostr.sdk.Client
import rust.nostr.sdk.ClientBuilder
import rust.nostr.sdk.ClientOptions
import rust.nostr.sdk.CustomNostrSigner
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.HandleNotification
import rust.nostr.sdk.Kind
import rust.nostr.sdk.NostrDatabase
import rust.nostr.sdk.NostrSdkException
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayMessage
import rust.nostr.sdk.RelayMessageEnum
import rust.nostr.sdk.RelayStatus
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.SignerBackend
import rust.nostr.sdk.UnsignedEvent
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin wrapper around the rust-nostr [Client]: one LMDB database, one relay
 * pool, one signer. Repositories read from [query] and listen on [incoming];
 * they never touch the rust-nostr client directly.
 */
class NostrEngine private constructor(
    internal val client: Client,
    private val signer: Signer?,
    private val scope: CoroutineScope,
    /** The client's signer (interactive NIP-44 for Amber); null without a signer. Same instance as the one handed to the rust-nostr [Client] in [create]. */
    val nostrSigner: NostrSigner?,
    /** Ids of the NIP-42 AUTH events our signer signed (SignerAdapter); an OK for one of them is a successful AUTH. */
    private val authIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
    /**
     * Where every call into rust-nostr runs (see [ffi]). Never the caller's dispatcher: on Main
     * (`viewModelScope` is `Main.immediate`) a rust poll can resume another uniffi future inline,
     * whose nested FFI call then blocks the main thread for good (the Plan 10d device-suite hangs).
     */
    val ffiDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** The SOCKS port from [create]; every relay added with its own options has to be told again. */
    private val socksPort: Int? = null,
    held: Boolean = false,
) {
    val pubkey: String? = signer?.pubkey

    /** May this engine reach for a socket; the first thing to change, either way. */
    private val route = MutableStateFlow(!held)
    private val told = MutableStateFlow(!held)

    /**
     * False while this engine may reach for no socket (Tor plan Task 6): our SOCKS port is ours only while Tor is connected, at any
     * other time another local app could be listening on it, and it would hear the relays' host names. The database stays readable,
     * a publish fails with [NOT_CONNECTED] and so on, as on a device without network. Always true for an engine not [held]. True only
     * once the relays were told to connect, so whoever waits for it finds rust-nostr ready to take a request.
     */
    val routeOpen: StateFlow<Boolean> = told

    /** Orders [holdConnections]' disconnect against [openConnections]' connect: the latest state wins, whichever runs last. */
    private val routeTurn = Mutex()

    /** A fetch came back empty because the route stayed closed; whoever showed that hears of it through [reopened]. */
    private val missedFetch = AtomicBoolean(false)
    private val _reopened = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The route opened after a fetch gave up on it: what is on screen lacks that answer. */
    val reopened: SharedFlow<Unit> = _reopened

    /**
     * Stops every new connection at once, without suspending, and ends the running ones right behind that: rust-nostr's own retries
     * cannot be asked, so its pool must not stay connected or trying. Nothing here waits for rust, a caller can go on immediately.
     */
    fun holdConnections() {
        if (!route.compareAndSet(true, false)) return
        told.value = false
        scope.launch(ffiDispatcher) { disconnectIfHeld() }
    }

    /**
     * Hands the pool back to rust-nostr and asks there what was subscribed meanwhile. False when nothing changed: open already, or
     * the engine is closing (a late call starts nothing after logout).
     */
    suspend fun openConnections(): Boolean {
        if (closing || !route.compareAndSet(false, true)) return false
        // the first minute's grace of the fetches counts from this connection, not from the session's start: after a hold every
        // relay connects anew, and a fetch asked before that is answered empty at once (Pixel 2026-11-08, worker after Tor came back)
        connectedAt = System.nanoTime()
        resting.clear()
        ffi {
            routeTurn.withLock {
                if (closing || !route.value) return@withLock
                poolProbe?.invoke("opening")
                ignoringErrors { client.connect() } // under routeTurn: a dial would wait for itself
                told.value = true
                if (!route.value) told.value = false // a hold came in between, its disconnect waits for this lock
                // a subscription of an earlier run is stored in the relay and asked again by the pool: the same id replaces it
                val read = readParsed()
                active.forEach { (id, filter) -> ignoringErrors { client.subscribeWithIdTo(read, id, filter, null) } }
            }
        }
        if (missedFetch.compareAndSet(true, false)) _reopened.tryEmit(Unit)
        return true
    }

    private suspend fun disconnectIfHeld() {
        if (route.value) return
        routeTurn.withLock { if (!route.value) runCatching { client.disconnect() } }
    }

    /**
     * Every connection this engine asks for goes through here (`NoBareClientsTest`). The second look covers a hold that came while
     * [block] ran: the disconnect of that hold may have been before it.
     */
    private suspend inline fun dial(block: () -> Unit) {
        if (!route.value) return
        block()
        disconnectIfHeld()
    }

    /**
     * What is left of [timeout] once the route is open; null: nothing was asked, too little time left to get an answer, and
     * [reopened] will tell.
     */
    private suspend fun awaitRoute(timeout: Duration): Duration? {
        if (told.value) return timeout
        val began = System.nanoTime()
        val opened = withTimeoutOrNull(timeout.toMillis()) { told.first { it } } != null
        val left = timeout.minusNanos(System.nanoTime() - began)
        if (opened && left >= MIN_FETCH_LEFT) return left
        missedFetch.set(true)
        // it opened just now: openConnections may have looked already
        if (told.value && missedFetch.compareAndSet(true, false)) _reopened.tryEmit(Unit)
        return null
    }

    /** Runs [block] (rust-nostr calls) on [ffiDispatcher], whatever the caller's dispatcher. */
    private suspend fun <T> ffi(block: suspend () -> T): T = withContext(ffiDispatcher) { block() }

    /** Same signer, but NIP-44 decryption goes through [Signer.nip44DecryptSilent] and throws [SilentDecryptUnavailable] when there is no silent answer (worker, implicit loads). */
    val silentNostrSigner: NostrSigner? = signer?.let { NostrSigner.custom(SilentSignerAdapter(it)) }

    /**
     * A signer for opening one gift wrap: like [nostrSigner] ([silent] = false) or [silentNostrSigner]
     * ([silent] = true), but it reports through [onLocked] when NIP-44 decryption failed on the
     * signer's side (no silent answer, declined, no NIP-44) rather than on the payload, so the caller
     * can tell a wrap that may open later from one that never will. Null without a signer.
     */
    fun unwrapSigner(silent: Boolean, onLocked: () -> Unit): NostrSigner? =
        signer?.let { NostrSigner.custom(UnwrapSignerAdapter(it, silent, onLocked)) }

    private val started = AtomicBoolean(false)

    /** [System.nanoTime] of the first [connect], or of the latest [openConnections]; 0 before it. [fetchSettled] works around rust-nostr's first minute only. */
    @Volatile private var connectedAt = 0L

    /** Normalised URLs [restFailing] took out of the pool's retries, with the [System.nanoTime] they began to rest. */
    private val resting = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 0.24.3: a relay of the normal set that has not connected once in this session and failed [restAttempts] times is
     * disconnected (rust-nostr would retry it every 10 to 60 s for as long as the app runs, which costs battery and radio
     * time) and tried again once every [restPeriodMs]; when that try fails it rests again. A relay that connected once is
     * never rested: it is only away for a while. Nothing rests while no other relay of the set has connected in this
     * session: then the device is offline (or was at the start), and rust's own retries must bring the relays back when
     * the network returns. Fetches leave a resting relay out (TERMINATED).
     */
    private suspend fun restFailing() {
        while (true) {
            delay(restCheckMs)
            if (route.value) ignoringErrors {
                val set = snapshot()
                resting.keys.retainAll(set.normalSet)
                connectedOnce.retainAll(set.normalSet)
                ffi {
                    val relays = set.normal.mapNotNull { key -> set.pool[key]?.let { key to it } }
                    val now = System.nanoTime()
                    for ((key, relay) in relays) {
                        val since = resting[key] ?: continue
                        if (relay.status() == RelayStatus.TERMINATED && now - since >= restPeriodMs * 1_000_000) {
                            resting.remove(key)
                            dial { ignoringErrors { relay.connect() } }
                        }
                    }
                    val online = relays.any { (key, relay) -> key in connectedOnce || relay.stats().success() > 0uL }
                    if (!online) return@ffi
                    for ((key, relay) in relays) {
                        if (resting.containsKey(key)) continue
                        val status = relay.status()
                        if (status == RelayStatus.TERMINATED || status.name !in LOST) continue
                        val stats = relay.stats()
                        if (stats.success() == 0uL && key !in connectedOnce && stats.attempts() >= restAttempts.toULong()) {
                            resting[key] = now
                            ignoringErrors { relay.disconnect() }
                        }
                    }
                }
            }
        }
    }

    /** A relay of the normal set rests ([restFailing]) after this many failed attempts without one success in the session; tests lower it. */
    @Volatile internal var restAttempts: Int = REST_ATTEMPTS

    /**
     * Normalised URLs of normal relays that connected in this session before [renew] replaced their pool relay (whose
     * stats start over): [restFailing] never rests them and [relayStates] shows them as connected once (spec 2026-10-23 §7).
     */
    private val connectedOnce: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** How often [restFailing] looks at the relays; tests shorten it. */
    @Volatile internal var restCheckMs: Long = REST_CHECK_MS

    /** How long a relay rests before [restFailing] tries it again; tests shorten it. */
    @Volatile internal var restPeriodMs: Long = REST_PERIOD_MS

    /** rust-nostr 0.44 keeps a relay whose connections fail in every fetch for this long after it started (DEFAULT_CONNECTION_TIMEOUT in ensure_operational); tests shorten it. */
    @Volatile internal var startWindow: Duration = Duration.ofSeconds(60)

    /** Spec 2026-10-23 §8: how long after [attachTemporary] added a relay [reachable] still waits for it as in [startWindow]; tests shorten it. */
    @Volatile internal var lateWindow: Duration = Duration.ofSeconds(10)

    /** What [HandleNotification.handle] hands over: nothing read yet (spec wave 2 B2: no FFI and no parsing in the callback). */
    private class RawIncoming(val relay: RelayUrl, val subscriptionId: String, val event: Event)

    /** Events between the rust callback and the decoder; a burst larger than this drops the oldest (as the old raw flow did). */
    private val raw = Channel<RawIncoming>(capacity = RAW_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = INCOMING_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every event delivered by any subscription or fetch, decoded once (spec wave 2 B2). Collectors filter on [Incoming.json] in Kotlin. */
    val incoming: SharedFlow<Incoming> = _incoming

    private val _published = MutableSharedFlow<JsonEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Backlog 40: every own event right after it was stored locally. The relays' echo of it never reaches [incoming]
     * (the pool knows the event already), so an open screen that waits for it learns of the event from here.
     */
    val published: SharedFlow<JsonEvent> = _published

    private fun announce(event: Event) {
        runCatching { EventJson.decode(event.asJson()) }.getOrNull()?.let { _published.tryEmit(it) }
    }

    /** How many events the decoder read across the FFI (tests: one per event, whatever the number of collectors). */
    internal val incomingDecodes = java.util.concurrent.atomic.AtomicLong(0)

    /** The only consumer of [raw]: reads each event's JSON once, in arrival order; an event that does not decode is skipped. */
    private suspend fun decodeIncoming() {
        for (r in raw) {
            incomingDecodes.incrementAndGet()
            val json = runCatching { EventJson.decode(r.event.asJson()) }.getOrNull() ?: continue
            val relay = runCatching { r.relay.toString().trimEnd('/') }.getOrDefault("")
            _incoming.tryEmit(Incoming(r.event, json, relay, r.subscriptionId))
        }
    }

    /** Tests only: runs inside [HandleNotification.handle] before the event is queued (a held callback, as in Task 0 (f2)). */
    @Volatile internal var callbackHold: (suspend () -> Unit)? = null

    /** How often the notification loop was restarted after rust-nostr ended it while the engine was open. */
    internal val notificationRestarts = java.util.concurrent.atomic.AtomicLong(0)

    /** Set by [close] before the client goes: from then on a returning notification loop is not restarted. */
    @Volatile private var closing = false

    /**
     * Adds relays; a malformed URL (e.g. corrupted stored config) is skipped rather than aborting
     * the rest. A URL currently held as a temporary search relay (see [attachTemporary]) is
     * promoted here: removed from the temporary set, force-removed from the pool, then re-added
     * with default (read+write) flags, so it survives a later [detachTemporary] and appears in
     * [relayUrls] from now on. If re-adding it fails, the promotion rolls back (re-attached as a
     * temporary relay) rather than silently dropping it from every set.
     *
     * Every relay that joins the normal set here (new or promoted) receives the live subscriptions
     * ([active]): [subscribe] targets explicit relays, and rust-nostr only hands pool-wide
     * subscriptions to relays added later, so without the replay a relay added after the feed
     * subscribed would never deliver live events.
     */
    suspend fun addRelays(urls: List<String>) {
        try {
        ffi {
        urls.forEach { url ->
            val parsed = runCatching { RelayUrl.parse(url) }.getOrNull() ?: return@forEach
            // a hidden service is dialled through Tor only: without a proxy it is never added, never resolved (spec 2026-11-06 Tor 7.4)
            if (socksPort == null && RelayHosts.isOnionHost(parsed.toString())) return@forEach
            val norm = parsed.toString().trimEnd('/')
            // The whole promotion (including the network calls and a failed rollback) runs under
            // temporaryMutex: releasing it between the `temporary.remove` and the rollback would let
            // a concurrent attachTemporary/detachTemporary for the same URL observe or create an
            // inconsistent state (see task-10 review round 1).
            val promoted = temporaryMutex.withLock {
                if (norm !in temporary) return@withLock false
                val previousRefs = temporaryRefs[norm] ?: 1
                // unmarked only after the relay left the pool (a refresh in between must not cache it as normal)
                ignoringErrors { client.forceRemoveRelay(parsed) }
                temporary.remove(norm)
                temporaryRefs.remove(norm)
                try {
                    client.addRoutedRelay(parsed, socksPort)
                    poolProbe?.invoke("added")
                    replaySubscriptions(parsed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Rollback: promotion failed, keep the relay reachable as a temporary one
                    // (with its prior reference count restored) instead of losing it from both
                    // `temporary` and the pool.
                    // marked before the relay re-enters the pool: no refresh may see it unmarked (spec wave 2 B1)
                    temporary += norm
                    temporaryRefs[norm] = previousRefs
                    ignoringErrors { client.addTemporaryRelay(parsed, socksPort) }
                }
                true
            }
            if (!promoted) {
                val added = try {
                    client.addRoutedRelay(parsed, socksPort)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                poolProbe?.invoke("added")
                if (added) replaySubscriptions(parsed)
            }
        }
        }
        } finally {
            withContext(NonCancellable) { refreshRelaySet() }
        }
    }

    /** Parses each URL, silently skipping ones that fail to parse and, without a proxy, hidden services (judged on rust's spelling): nothing may be asked of them. */
    private fun List<String>.toRelayUrls(): List<RelayUrl> =
        mapNotNull { runCatching { RelayUrl.parse(it.trim()) }.getOrNull() }.filter { socksPort != null || !RelayHosts.isOnionHost(it.toString()) }

    /**
     * [toRelayUrls] for callers that name relays they do not own: a hidden service counts only when it is in the normal set (the user's
     * own list, held only through Tor), a hint never reaches a relay the pool does not have ("relay not found").
     */
    private suspend fun List<String>.toHeldRelayUrls(): List<RelayUrl> {
        val parsed = toRelayUrls()
        if (parsed.none { RelayHosts.isOnionHost(it.toString()) }) return parsed
        val normal = snapshot().normal.toSet()
        return parsed.filter { !RelayHosts.isOnionHost(it.toString()) || it.toString().trimEnd('/') in normal }
    }

    /** Live subscriptions by id ([subscribe] adds, [unsubscribe] removes), replayed to relays that join the normal set later. */
    private val active = java.util.concurrent.ConcurrentHashMap<String, Filter>()

    /**
     * Best-effort: a relay that cannot take the subscription now simply misses it, like any failed
     * REQ. rust-nostr refuses a relay subscription before the relay has been told to connect, so
     * the relay is connected first (the session's reconnect after a relay list change would do
     * the same a moment later). A write-only relay connects like any other and gets no subscription.
     */
    private suspend fun replaySubscriptions(url: RelayUrl) {
        if (active.isEmpty()) return
        ffi {
            dial { ignoringErrors { client.connectRelay(url) } }
            if (isWriteOnly(url)) return@ffi
            active.forEach { (id, filter) -> ignoringErrors { client.subscribeWithIdTo(listOf(url), id, filter, null) } }
        }
    }

    /** Removes relays; a malformed URL is skipped rather than throwing. */
    suspend fun removeRelays(urls: List<String>) {
        try {
            // under temporaryMutex: a renew running meanwhile must not add a removed relay back (spec 2026-10-23 §7)
            temporaryMutex.withLock { ffi { urls.forEach { url -> runCatching { client.removeRelay(RelayUrl.parse(url)) } } } }
            poolProbe?.invoke("removed")
        } finally {
            withContext(NonCancellable) { refreshRelaySet() }
        }
    }

    /**
     * Guards every compound read-modify-write over [temporary] + [temporaryRefs] (attach, detach,
     * and the promotion/rollback branch of [addRelays]): without it, concurrent callers (e.g. a
     * profile's author relays and a search running at once, Task 11) can race each other and
     * diverge the two from the actual pool state (a detach racing an attach dropping a still-held
     * relay, two first-attaches losing a ref, ...).
     */
    private val temporaryMutex = Mutex()

    /** Search relays attached for the duration of a search session (spec 3.4); normalised URLs. Thread-safe set so unlocked readers ([relayUrls], [relayStates], [temporaryRelayUrls]) never see a torn iteration while a writer holds [temporaryMutex]. */
    private val temporary: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Reference counts for [temporary], touched only under [temporaryMutex]: a URL is only removed from the pool once its count drops to zero. */
    private val temporaryRefs = HashMap<String, Int>()

    /** When [attachTemporary] added each relay of [temporary] ([System.nanoTime]); read by [reachable] without the mutex (spec 2026-10-23 §8). */
    private val attachedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Spec wave 2 B1: the pool as the engine last set it up, read once per change instead of once per operation.
     * [normal]/[parsed]: the normal set (never a temporary relay), in pool order; [pool]: every pool relay by
     * normalised URL, temporary ones included, never one [sendTo] added for a single send ([transient]).
     */
    private class RelaySet(val normal: List<String>, val parsed: List<RelayUrl>, val pool: Map<String, rust.nostr.sdk.Relay>) {
        val normalSet: Set<String> = normal.toHashSet()
    }

    @Volatile private var relaySet = RelaySet(emptyList(), emptyList(), emptyMap())

    /** Orders snapshot reads with their writes: the last refresh always reads the pool after every finished change. */
    private val relaySetMutex = Mutex()

    /** Normalised URLs [sendTo] added to the pool for one send; a refresh in that window leaves them out. */
    private val transient: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Normalised URLs [detachTemporary] is removing: kept out of the normal list until its trailing refresh ran, so a refresh that read the pool before the removal cannot cache them. */
    private val leaving: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Removes [url] from the pool if it is there (harmless when it is not); cancellation cannot interrupt it. For a pool add that was cancelled or failed midway: rust may have inserted the relay already. */
    private suspend fun dropFromPool(url: RelayUrl) {
        withContext(NonCancellable + ffiDispatcher) {
            if (runCatching { client.relay(url) }.isSuccess) ignoringErrors { client.forceRemoveRelay(url) }
        }
    }

    /** Set when a refresh failed: the next read recomputes from the pool before it answers ([snapshot]). */
    @Volatile private var relaySetStale = false

    /** Test hooks: [poolProbe] runs right after a pool change ("added", "removed") and right before a relay is force-removed ("removing"); [failNextRefresh] makes the next refresh throw. */
    @Volatile internal var poolProbe: (suspend (String) -> Unit)? = null
    @Volatile internal var failNextRefresh = false

    internal suspend fun refreshRelaySetForTest() = refreshRelaySet()

    /** The cached set; recomputed first when the last refresh failed, so a read never answers from a snapshot the pool has left behind. */
    private suspend fun snapshot(): RelaySet {
        if (relaySetStale) refreshRelaySet()
        return relaySet
    }

    /** Recomputes [relaySet] from the pool, at the end of every method that changes it (in `finally`, NonCancellable). Never patched; a refresh that fails marks the snapshot stale, the next read retries. */
    private suspend fun refreshRelaySet() {
        relaySetMutex.withLock {
            val next = try {
                ffi {
                    if (failNextRefresh) { failNextRefresh = false; throw IllegalStateException("refresh failed") }
                    val entries = client.relays().entries
                        .map { (url, relay) -> Triple(url.toString().trimEnd('/'), url, relay) }
                    poolProbe?.invoke("read")
                    val entries2 = entries.filter { it.first !in transient }
                    val normal = entries2.filter { it.first !in temporary && it.first !in leaving }
                    RelaySet(normal.map { it.first }, normal.map { it.second }, entries2.associate { it.first to it.third })
                }
            } catch (e: CancellationException) {
                relaySetStale = true
                throw e
            } catch (e: Exception) {
                null
            }
            if (next != null) { relaySet = next; relaySetStale = false } else relaySetStale = true
        }
    }

    /** Currently configured relay URLs without the temporary search relays, trailing slash normalised: the cached set, no FFI (spec wave 2 B1). */
    suspend fun relayUrls(): List<String> = snapshot().normal

    /** Normalised URLs the user only writes to: nothing here reads from them, except [fetchWriteOnly] (spec 2026-11-05). Empty by default. */
    @Volatile private var writeOnly: Set<String> = emptySet()

    /** Normalised URLs the user only reads from: no event of a publish goes there. Empty by default. */
    @Volatile private var readOnly: Set<String> = emptySet()

    /**
     * What the user uses each relay for; a relay not named here reads and writes. Only routing changes: no network, no
     * pool change, a repeated call does nothing. A one-way relay stays in the normal set: it connects, shows its status,
     * is woken and rested like any other, and [fetchFrom], [subscribeTo] and [sendTo] still reach it. The subscriptions
     * a relay already carries stay as they are: for a running pool use [applyRelayModes], this one is for an engine
     * that has not connected yet.
     */
    fun setRelayModes(modes: Map<String, RelayMode>) {
        fun urls(mode: RelayMode) = modes.filterValues { it == mode }.keys.mapTo(HashSet(), ::relayKey)
        writeOnly = urls(RelayMode.WRITE)
        readOnly = urls(RelayMode.READ)
    }

    /**
     * [setRelayModes] for a running pool, on the connections there are (spec 2026-11-05: a mode change drops none). A
     * relay of the normal set that stops reading loses the live subscriptions ([active]) and keeps the ones that name it
     * ([subscribeTo], a DM inbox relay that is also in the main list); one that starts reading gets the live ones. A
     * relay whose reading did not change sees nothing. Runs to its end once started, so subscriptions match the modes.
     */
    suspend fun applyRelayModes(modes: Map<String, RelayMode>) {
        val before = writeOnly
        setRelayModes(modes)
        val after = writeOnly
        if (before == after) return
        withContext(NonCancellable + ffiDispatcher) {
            // wakeMutex: a renewal in progress may have read the old modes, so what it hands over is looked at after it
            val started = wakeMutex.withLock {
                val set = snapshot()
                for (key in set.normal) {
                    if (key !in after || key in before) continue
                    val relay = set.pool[key] ?: continue
                    var stored: Set<String> = emptySet()
                    ignoringErrors { stored = relay.subscriptions().keys }
                    stored.filter { active.containsKey(it) }.forEach { id -> ignoringErrors { relay.unsubscribe(id) } }
                }
                set.parsed.filterIndexed { i, _ -> set.normal[i] in before && set.normal[i] !in after }
            }
            started.forEach { replaySubscriptions(it) }
        }
    }

    /** The spelling of the normal set, so a URL the pool writes differently still matches. */
    private fun relayKey(url: String): String =
        (runCatching { RelayUrl.parse(url.trim()).toString() }.getOrDefault(url)).trim().trimEnd('/')

    /**
     * [urls] (a fixed relay list, an indexer, a hint from someone's note) without the user's write-only relays: for a
     * request that is not about the user's own events. [urls] itself, untouched, without such a relay.
     */
    fun withoutWriteOnly(urls: List<String>): List<String> {
        val left = writeOnly
        return if (left.isEmpty()) urls else urls.filter { relayKey(it) !in left }
    }

    /** The relays of [set] without [left], in pool order; [RelaySet.parsed] itself when nothing is left out (the default). */
    private fun targetsWithout(set: RelaySet, left: Set<String>): List<RelayUrl> =
        if (left.isEmpty()) set.parsed else set.parsed.filterIndexed { i, _ -> set.normal[i] !in left }

    /** The normal set's relays that read. */
    private suspend fun readParsed(): List<RelayUrl> = targetsWithout(snapshot(), writeOnly)

    private fun isWriteOnly(url: RelayUrl): Boolean {
        val left = writeOnly
        return left.isNotEmpty() && url.toString().trimEnd('/') in left
    }

    /** [relayUrls] without the write-only relays: where requests that are not about the user's own events go. No FFI. */
    suspend fun readRelayUrls(): List<String> {
        val left = writeOnly
        val normal = snapshot().normal
        return if (left.isEmpty()) normal else normal.filter { it !in left }
    }

    /**
     * Asks only the write-only relays of the normal set, for the user's own events (spec 2026-11-05: that is where they
     * live, and [fetch] does not ask there). Stored by the client like every fetch. Empty at once, no request, without
     * such a relay.
     */
    suspend fun fetchWriteOnly(filter: Filter, timeout: Duration = Duration.ofSeconds(8)): List<Event> {
        val only = writeOnly
        if (only.isEmpty()) return emptyList()
        @Suppress("NAME_SHADOWING") val timeout = awaitRoute(timeout) ?: return emptyList()
        val set = snapshot()
        val asked = set.parsed.filterIndexed { i, _ -> set.normal[i] in only }
        if (asked.isEmpty()) return emptyList()
        val targets = reachable(asked)
        return ffi { client.fetchEventsFrom(targets, filter, timeout).toVec() }
    }

    /** [answered]: every write-only relay gave its answer, so [events] is what they hold (see [askWriteOnly]). */
    internal class WriteOnlyAnswer(val events: List<Event>, val answered: Boolean)

    /**
     * [fetchWriteOnly] for a read-before-write, where its silence must be known (spec 2026-11-05 goal 5): such a relay
     * may hold the only copy of the newest version. Answered means: each of them was connected before and after, the fetch
     * ended before its timeout, so on their EOSE, and none sent a CLOSED; a relay that asked for AUTH first gets the
     * request once more after it. Never throws except on cancellation. Without such a relay: answered, no request.
     */
    internal suspend fun askWriteOnly(filter: Filter, timeout: Duration): WriteOnlyAnswer {
        val only = writeOnly
        if (only.isEmpty()) return WriteOnlyAnswer(emptyList(), answered = true)
        val set = snapshot()
        val asked = set.parsed.filterIndexed { i, _ -> set.normal[i] in only }
        if (asked.isEmpty()) return WriteOnlyAnswer(emptyList(), answered = true)
        val keys = set.normal.filter { it in only }
        val mark = closedMark()
        val first = askWriteOnlyOnce(asked, keys, filter, timeout, mark)
        if (first.answered || !awaitAuthAfterRefusal(mark, keys, WRITE_ONLY_AUTH_WAIT)) return first
        return askWriteOnlyOnce(asked, keys, filter, timeout, closedMark())
    }

    private suspend fun askWriteOnlyOnce(asked: List<RelayUrl>, keys: List<String>, filter: Filter, timeout: Duration, mark: Long): WriteOnlyAnswer {
        val connected = HashSet<String>()
        val targets = reachable(asked, connected)
        val started = System.nanoTime()
        val events = try {
            ffi { client.fetchEventsFrom(targets, filter, timeout).toVec() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return WriteOnlyAnswer(emptyList(), answered = false)
        }
        val inTime = System.nanoTime() - started < timeout.toNanos() - WRITE_ONLY_MARGIN_NANOS
        if (!inTime || !connected.containsAll(keys) || closedSince(mark, keys)) return WriteOnlyAnswer(events, answered = false)
        // a refusal comes back with the fetch: the notification loop gets a moment to record its CLOSED
        withContext(Dispatchers.Default) { delay(WRITE_ONLY_CLOSED_SETTLE_MS) }
        return WriteOnlyAnswer(events, answered = !closedSince(mark, keys) && connectedNormalRelayUrls().containsAll(keys))
    }

    /** [failure]: the read relays' leg failed; [events] holds what the other leg brought. */
    internal class OwnEvents(val events: List<Event>, val writeOnlyAnswered: Boolean, val failure: Exception?)

    /**
     * [fetchOwnEvents] for a read-before-write: says whether the write-only relays answered ([askWriteOnly]). Never
     * throws except on cancellation. Without such a relay it is one [fetch] and counts as answered.
     */
    internal suspend fun askOwnEvents(filter: Filter, timeout: Duration): OwnEvents {
        suspend fun read(): Result<List<Event>> = try {
            Result.success(fetch(filter, timeout))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (writeOnly.isEmpty()) return read().let { OwnEvents(it.getOrDefault(emptyList()), true, it.exceptionOrNull() as Exception?) }
        return coroutineScope {
            val only = async { askWriteOnly(filter, timeout) }
            val fromRead = read()
            val other = only.await()
            OwnEvents(fromRead.getOrDefault(emptyList()) + other.events, other.answered, fromRead.exceptionOrNull() as Exception?)
        }
    }

    /** The write-only relays as of now, normalised. No FFI. */
    internal val writeOnlyRelayUrls: Set<String> get() = writeOnly

    /** True while the user has a write-only relay: the extra leg for the user's own events is worth starting. No FFI. */
    internal val hasWriteOnly: Boolean get() = writeOnly.isNotEmpty()

    /**
     * [fetch] and [fetchWriteOnly] side by side, both awaited: a read of the user's own events where finding the newest
     * matters more than speed (read-before-write). Only for filters with the user as the only author. Without a
     * write-only relay it is [fetch]. A failed leg does not cut the other one short; its failure is thrown when both ended.
     */
    internal suspend fun fetchOwnEvents(filter: Filter, timeout: Duration = Duration.ofSeconds(8)): List<Event> {
        if (writeOnly.isEmpty()) return fetch(filter, timeout)
        suspend fun leg(block: suspend () -> List<Event>): Result<List<Event>> = try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        return coroutineScope {
            val only = async { leg { fetchWriteOnly(filter, timeout) } }
            val read = leg { fetch(filter, timeout) }
            val other = only.await()
            read.getOrThrow() + other.getOrThrow()
        }
    }

    /** A copy through `toArray`: `toList()` reads the size and then iterates, which throws on a concurrent removal. */
    fun temporaryRelayUrls(): List<String> = ArrayList(temporary)

    /** Normalised URLs (trailing slash trimmed) of all pool relays, temporary included, whose status is CONNECTED; one `status()` per cached relay. */
    suspend fun connectedRelayUrls(): Set<String> {
        val pool = snapshot().pool
        return ffi { pool.filterValues { it.status().name == RelayStatus.CONNECTED.name }.keys.toSet() }
    }

    /** [connectedRelayUrls] restricted to the normal set ([relayUrls]): a connected search relay never counts. */
    suspend fun connectedNormalRelayUrls(): Set<String> {
        val set = snapshot()
        return ffi { set.pool.filter { (url, relay) -> url in set.normalSet && relay.status().name == RelayStatus.CONNECTED.name }.keys.toSet() }
    }

    /** [connectedNormalRelayUrls] without the write-only relays: where other people's data is asked (spec 2026-11-05). */
    suspend fun connectedReadRelayUrls(): Set<String> {
        val connected = connectedNormalRelayUrls()
        val left = writeOnly
        return if (left.isEmpty()) connected else connected.filter { it !in left }.toSet()
    }

    /**
     * Adds [urls] as relays that never receive the normal feed's subscriptions and connects them.
     * URLs already in the pool are left alone and never counted as temporary. Malformed URLs are
     * skipped.
     *
     * Ruling (see task-2 report, network probe): a relay added with `read(false).write(false)` is
     * invisible to [rust.nostr.sdk.Client.relays] in rust-nostr 0.44.8 (confirmed locally without
     * network: `addRelayWithOpts` returns `true` and `client.relay(url)` resolves it, but it is
     * absent from `client.relays()`), and `fetchEventsFrom` against such a relay returns nothing
     * even for a live relay (network probe, see below). We therefore add temporary relays with
     * `read(true).write(false)` so they are visible and fetchable, and instead keep the "never
     * receive the feed's subscriptions" guarantee by having [subscribe] target only
     * [readRelayUrls] (which excludes temporary relays) rather than the whole pool.
     *
     * Invariant: a temporary relay is reachable only through calls that name it ([fetchFrom],
     * [subscribeTo], [sendTo]). [subscribe] and [fetch] always target
     * [readRelayUrls] and the engine's own sends the writing relays of [relayUrls], explicitly, and therefore never reach it, even though the underlying rust-nostr
     * pool marks it `read`-enabled. If a temporary relay is later added to the normal set (see
     * [addRelays]), it is promoted and never removed by [detachTemporary].
     *
     * Returns the normalised URLs this call took a reference on (newly attached or already
     * temporary): exactly what the caller must hand to [detachTemporary] later. A URL of the normal
     * set, a malformed one or one that could not be added is not in it.
     */
    suspend fun attachTemporary(urls: List<String>): List<String> {
        val held: List<String>
        try { held = ffi {
        val known = snapshot().pool.keys + transient
        val taken = ArrayList<String>()
        for (raw in urls) {
            val url = runCatching { RelayUrl.parse(raw.trim()) }.getOrNull() ?: continue
            // only the user's own list names a hidden service ([addRelays]); here every address is someone else's hint (Tor 7.4)
            if (RelayHosts.isOnionHost(url.toString())) continue
            val norm = url.toString().trimEnd('/')
            temporaryMutex.withLock {
                if (norm in temporary) { temporaryRefs.merge(norm, 1, Int::plus); taken += norm; return@withLock }
                if (norm in known) return@withLock
                // marked before the relay enters the pool: a refresh in between must not cache it as a normal relay
                temporary += norm
                temporaryRefs[norm] = 1
                val added = try {
                    client.addTemporaryRelay(url, socksPort).also { poolProbe?.invoke("add-returned") }
                } catch (e: CancellationException) {
                    // rust may have inserted the relay before the cancellation landed: out of the pool first, unmarked after
                    dropFromPool(url)
                    temporary.remove(norm); temporaryRefs.remove(norm)
                    throw e
                } catch (e: Exception) {
                    dropFromPool(url)
                    false
                }
                if (!added) { temporary.remove(norm); temporaryRefs.remove(norm); return@withLock }
                poolProbe?.invoke("added")
                taken += norm
                attachedAt[norm] = System.nanoTime()
                dial { ignoringErrors { client.connectRelay(url) } }
            }
        }
        taken
        } } finally {
            withContext(NonCancellable) { refreshRelaySet() }
        }
        return held
    }

    /** Removes those of [urls] that [attachTemporary] added; relays of the normal set are untouched. */
    suspend fun detachTemporary(urls: List<String>) {
        // NonCancellable as a whole: callers release from `finally` blocks, where a cancellable
        // mutex lock() would throw and skip the release, leaking a reference (and the relay).
        withContext(NonCancellable + ffiDispatcher) {
            val gone = ArrayList<String>()
            try {
                for (raw in urls) {
                    val url = runCatching { RelayUrl.parse(raw.trim()) }.getOrNull() ?: continue
                    val norm = url.toString().trimEnd('/')
                    temporaryMutex.withLock {
                        if (norm !in temporary) return@withLock
                        val left = temporaryRefs.merge(norm, -1, Int::plus) ?: 0
                        if (left > 0) return@withLock
                        // unmarked only after the relay left the pool: a refresh in between must not cache it as normal;
                        // `leaving` covers a refresh that read the pool before the removal and filters after the unmark
                        leaving += norm
                        gone += norm
                        poolProbe?.invoke("removing")
                        ignoringErrors { client.forceRemoveRelay(url) }
                        temporaryRefs.remove(norm)
                        temporary.remove(norm)
                        attachedAt.remove(norm)
                    }
                }
                refreshRelaySet()
            } finally {
                gone.forEach { leaving.remove(it) }
            }
        }
    }

    /** Runs [block], letting [CancellationException] propagate but otherwise swallowing any exception. */
    private inline fun ignoringErrors(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    /**
     * Fetches [filter] only from [relays] (spec 3.4): with [attach], missing relays are attached
     * temporarily and stay until [detachTemporary]; a caller that attached them itself (and so knows
     * what [attachTemporary] took) passes false. Results are stored in the database. Throws when the
     * fetch itself fails (callers treat that as a relay error); cancellation is always propagated.
     * [reached] (spec 2026-10-23 §4.3) collects the normalised URLs of [relays] that were connected when the request
     * went out (after the connection grace of [reachable]): only those were really asked.
     */
    suspend fun fetchFrom(relays: List<String>, filter: Filter, timeout: Duration = Duration.ofSeconds(8), attach: Boolean = true, exit: Exit? = null, reached: MutableSet<String>? = null): List<Event> {
        @Suppress("NAME_SHADOWING") val timeout = awaitRoute(timeout) ?: return emptyList()
        if (attach) attachTemporary(relays)
        if (exit != null) return fetchExit(relays, filter, timeout, exit, reached).events
        val targets = reachable(relays.toHeldRelayUrls(), reached)
        if (targets.isEmpty()) return emptyList()
        // the client stores every event it receives in the database already (rust-nostr); saving them again here
        // cost a few milliseconds per event on a phone, seconds per refresh (hundreds of known deletion requests)
        return ffi { client.fetchEventsFrom(targets, filter, timeout).toVec() }
    }

    /**
     * NIP-45: asks [relay] alone how many events match [filter]. With [attach] the relay is attached for the call
     * only (an engine relay stays); without, it must be in the pool already (a caller that holds it) and stays.
     * Null when it does not answer in [timeout], refuses COUNT, or cannot be reached; cancellation is propagated.
     */
    suspend fun countFrom(relay: String, filter: Filter, timeout: Duration, attach: Boolean = true): Long? {
        // NonCancellable: a cancellation landing on the attach's own return would lose `held` and leak the relay
        val held = if (attach) withContext(NonCancellable) { attachTemporary(listOf(relay)) } else emptyList()
        try {
            val url = runCatching { RelayUrl.parse(relay.trim()) }.getOrNull() ?: return null
            return ffi {
                try {
                    client.relay(url).countEvents(filter, timeout).toLong()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        } finally {
            if (held.isNotEmpty()) detachTemporary(held)
        }
    }

    /** Connects (or reconnects after a relay list change); the notification loop is started only once per engine. */
    suspend fun connect() {
        ffi { dial { client.connect() } }
        if (started.compareAndSet(false, true)) {
            if (route.value) connectedAt = System.nanoTime()
            scope.launch(Dispatchers.Default) { decodeIncoming() }
            notifications = scope.launch(ffiDispatcher) { runNotifications() }
            scope.launch { restFailing() }
        }
    }

    /**
     * Runs rust-nostr's notification loop for as long as the engine is open. rust-nostr 0.44.8 returns from
     * [Client.handleNotifications] without an error when the callback falls behind its broadcast channel (Task 0
     * (f2)); a loop that stayed ended would lose every OK, AUTH and live event for the session, so it is started
     * again at once; only a second quick end in a row waits [RESTART_MIN_MS], doubling up to [RESTART_MAX_MS] (a run that
     * lasted longer than that starts over with the immediate restart), with one log line per restart. Ends once [close] has begun.
     */
    private suspend fun runNotifications() {
        var wait = 0L // the first restart after a run is immediate
        while (!closing) {
            val began = System.currentTimeMillis()
            val failure = try {
                client.handleNotifications(notificationHandler)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            if (closing) return
            if (System.currentTimeMillis() - began > RESTART_MAX_MS) wait = 0L
            Log.w(LOG_TAG, "notification loop ended (${failure?.message ?: "returned"}), restarting in $wait ms")
            if (wait > 0) delay(wait)
            wait = if (wait == 0L) RESTART_MIN_MS else (wait * 2).coerceAtMost(RESTART_MAX_MS)
            if (!closing) notificationRestarts.incrementAndGet() // counted when the loop starts again, not when it ended
        }
    }

    /** The callback of [runNotifications]; one instance for every (re)start of the loop. */
    private val notificationHandler = object : HandleNotification {
        override suspend fun handle(relayUrl: RelayUrl, subscriptionId: String, event: Event) {
            callbackHold?.invoke()
            // spec wave 2 B2: no FFI and no parsing here. rust-nostr awaits this callback before it takes the
            // next notification (CLOSED and OK included); the decoder reads the event on its own coroutine.
            raw.trySend(RawIncoming(relayUrl, subscriptionId, event))
        }

        override suspend fun handleMsg(relayUrl: RelayUrl, msg: RelayMessage) {
            // rust-nostr calls this for every relay message, EVENT included (Pixel 2026-11-02: ~4300 in the first 8 s
            // after a start, each crossing JNA several times): one FFI call to read the message, the relay's name only for
            // the few kinds that need it, and the handles freed here instead of by the Cleaner thread later
            val m = msg.asEnum()
            msg.destroy()
            if (m is RelayMessageEnum.EventMsg) m.event.destroy()
            if (m !is RelayMessageEnum.Closed && m !is RelayMessageEnum.Ok) {
                relayUrl.destroy()
                return
            }
            val relay = relayUrl.toString().trimEnd('/')
            relayUrl.destroy()
            when (m) {
                is RelayMessageEnum.Closed -> {
                    // recorded before the emit, so a caller checking closedSince right after its own call sees it
                    val seq = closedSeq.incrementAndGet()
                    val auth = isAuthRequired(m.message)
                    // one write: a reader never sees the CLOSED without its reason (see ClosedSeqs)
                    lastClosed.compute(relay) { _, old -> ClosedSeqs(seq, if (auth) seq else old?.auth) }
                    _closed.tryEmit(ClosedSubscription(relay, m.subscriptionId, m.message, seq))
                }
                is RelayMessageEnum.Ok -> {
                    // a publish that waits for this event's OKs (signAndPublishUnstored); the seq orders the OK against AUTH OKs
                    okWaiters[m.eventId.toHex()]?.put(relay, OkAnswer(m.status, m.message, closedSeq.incrementAndGet()))
                    if (authIds.remove(m.eventId.toHex())) {
                        // same counter as the CLOSED frames, so their order is known
                        val seq = closedSeq.incrementAndGet()
                        if (m.status) {
                            authRejections.remove(relay)
                            authOkSeq[relay] = seq
                            authenticatedSet += relay
                            _authenticated.tryEmit(relay)
                        } else {
                            val rejection = AuthRejection(relay, m.message, seq)
                            authRejections[relay] = rejection
                            _authRejected.tryEmit(rejection)
                        }
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Subscribes on [readRelayUrls] only, never on a temporary search relay (see [attachTemporary]'s
     * doc comment for why the exclusion happens here rather than via a read/write flag).
     */
    suspend fun subscribe(filter: Filter): String {
        // rust-nostr refuses a subscription on a relay not told to connect; [openConnections] asks it
        if (!told.value) {
            val id = java.util.UUID.randomUUID().toString()
            active[id] = filter
            // opened meanwhile: its replay may have gone over the list already
            if (told.value) ignoringErrors { ffi { client.subscribeWithIdTo(readParsed(), id, filter, null) } }
            return id
        }
        val targets = readParsed()
        val id = ffi { client.subscribeTo(targets, filter, null) }.id
        active[id] = filter
        return id
    }

    suspend fun unsubscribe(id: String) {
        active.remove(id)
        ffi { client.unsubscribe(id) }
    }

    /** Ids of the live subscriptions replayed to relays that join the normal set later (test-only introspection). */
    internal fun activeSubscriptionIds(): Set<String> = active.keys.toSet()

    /**
     * Subscribes on exactly [relays] (e.g. temporary DM relays), never on [relayUrls] as a whole
     * and never replayed by [replaySubscriptions]: a relay added to the normal set later does not
     * inherit it, unlike [subscribe].
     */
    suspend fun subscribeTo(relays: List<String>, filter: Filter): String {
        val targets = relays.toHeldRelayUrls()
        require(targets.isNotEmpty()) { "no relays" }
        if (!told.value) throw java.io.IOException("connections are held")
        return ffi { client.subscribeTo(targets, filter, null) }.id
    }

    data class ClosedSubscription(val relay: String, val subscriptionId: String, val message: String, val seq: Long = 0L)

    private val _closed = MutableSharedFlow<ClosedSubscription>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** `CLOSED` frames from relays (e.g. `auth-required: …`), for the DM sync's auth hint. */
    val closed: SharedFlow<ClosedSubscription> = _closed

    private val closedSeq = java.util.concurrent.atomic.AtomicLong(0)
    /**
     * What a relay's CLOSED frames amount to: the [closedSeq] value of its latest CLOSED ([any]) and
     * of its latest CLOSED that asked for AUTH ([auth], see [isAuthRequired]). Kept as one value per
     * relay: the fetch that was refused returns at the same moment the notification loop records the
     * frame, and with two maps written one after the other it could see "closed" and not yet "for
     * AUTH" (a DM fetch then gave the refusal up instead of asking again after the AUTH OK).
     */
    private class ClosedSeqs(val any: Long, val auth: Long?)

    /** Normalised relay URL → its CLOSED frames so far. */
    private val lastClosed = java.util.concurrent.ConcurrentHashMap<String, ClosedSeqs>()

    /** A mark for [closedSince]: take it before a call, check after it. */
    fun closedMark(): Long = closedSeq.get()

    /** True when one of [relays] (normalised URLs) sent a CLOSED frame, for any subscription, after [mark]. */
    fun closedSince(mark: Long, relays: Collection<String>): Boolean = relays.any { (lastClosed[it]?.any ?: 0L) > mark }

    /** Normalised relay URL → [closedSeq] value of its latest accepted AUTH (same counter as the CLOSED frames). */
    private val authOkSeq = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The [closedSeq] value at which [relay] last accepted our AUTH, or null; a CLOSED with a smaller seq is already healed. */
    fun authOkSeq(relay: String): Long? = authOkSeq[relay]

    /**
     * Those of [relays] that refused a subscription with `auth-required` after [mark] and have not
     * accepted an AUTH since (spec 7.1 of Plan 10d). Other CLOSED reasons (`restricted`, errors) never count.
     */
    fun authRefusedSince(mark: Long, relays: Collection<String>): Set<String> = authRefusalsSince(mark, relays).keys

    /** [authRefusedSince] with the [closedSeq] value of each relay's refusing CLOSED. */
    fun authRefusalsSince(mark: Long, relays: Collection<String>): Map<String, Long> =
        relays.mapNotNull { r -> lastClosed[r]?.auth?.takeIf { c -> c > mark && (authOkSeq[r] ?: 0L) < c }?.let { r to it } }.toMap()

    /**
     * Waits (in real time, at most [timeout]) until each of [relays] that refused with `auth-required`
     * after [mark] has accepted an AUTH since that refusal. True when there was such a refusal and all
     * of them healed in time, so a repeated request may now be served; false otherwise, and at once
     * when a relay answered the AUTH itself with OK false (no AUTH OK can follow that).
     */
    suspend fun awaitAuthAfterRefusal(mark: Long, relays: Collection<String>, timeout: Duration): Boolean {
        val refused = relays.mapNotNull { r -> lastClosed[r]?.auth?.takeIf { it > mark }?.let { r to it } }
        if (refused.isEmpty()) return false
        fun healed() = refused.all { (r, c) -> (authOkSeq[r] ?: 0L) > c }
        // a relay that answered our AUTH with OK false has answered too, whether before the refusal (challenge
        // on connect) or after it: without a new challenge no AUTH OK follows
        fun settled() = refused.all { (r, c) -> (authOkSeq[r] ?: 0L) > c || authRejections[r] != null }
        return withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeout.toMillis()) { while (!settled()) delay(AUTH_POLL_MS) }
            healed()
        }
    }

    private val _authenticated = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Relays that accepted our NIP-42 AUTH (normalised URL), as it happens; the DM sync heals its hint from this. */
    val authenticated: SharedFlow<String> = _authenticated
    private val authenticatedSet: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Relays that accepted our AUTH since the engine started. */
    fun authenticatedRelays(): Set<String> = authenticatedSet.toSet()

    /** A relay answered our NIP-42 AUTH with `OK false`: nothing the signer does can sign us in there. */
    data class AuthRejection(val relay: String, val message: String, val seq: Long)

    private val _authRejected = MutableSharedFlow<AuthRejection>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Relays that refused our AUTH event itself, as it happens (normalised URL, the relay's own message). */
    val authRejected: SharedFlow<AuthRejection> = _authRejected
    private val authRejections = java.util.concurrent.ConcurrentHashMap<String, AuthRejection>()

    /** The latest refusal of our AUTH by [relay]; null once it accepted a later one. */
    fun authRejection(relay: String): AuthRejection? = authRejections[relay]

    /** Local database query. */
    suspend fun query(filter: Filter): List<Event> = ffi { client.database().query(filter).toVec() }

    /** Removes what [filter] matches from the database (best-effort): events fetched for a moment only, like followers' contact lists. */
    suspend fun forget(filter: Filter) {
        runCatching { ffi { client.database().delete(filter) } }
    }

    /** Local database query, each event read once in Kotlin (spec wave 2 B3): one `asJson` per event, no tag reads over the FFI. */
    suspend fun queryDecoded(filter: Filter): List<JsonEvent> =
        ffi { client.database().query(filter).toVec().map { EventJson.decode(it.asJson()) } }

    /** [queryDecoded] with each rust event kept beside its decoded form ([com.monostr.nostr.repo.Withdrawn.filterDecoded], NoteMapper). */
    suspend fun queryBoth(filter: Filter): List<Pair<Event, JsonEvent>> =
        ffi { client.database().query(filter).toVec().map { it to EventJson.decode(it.asJson()) } }

    suspend fun eventById(idHex: String): Event? = ffi { client.database().eventById(rust.nostr.sdk.EventId.parse(idHex)) }

    suspend fun save(event: Event) {
        ffi { client.database().saveEvent(event) }
    }

    /**
     * Fetches from [readRelayUrls] (never a temporary search relay, see [attachTemporary]) with a
     * timeout; results are stored in the database. An empty relay list yields an empty result
     * without throwing.
     */
    suspend fun fetch(filter: Filter, timeout: Duration = Duration.ofSeconds(8), exit: Exit? = null): List<Event> {
        @Suppress("NAME_SHADOWING") val timeout = awaitRoute(timeout) ?: return emptyList()
        if (exit != null) return fetchExit(null, filter, timeout, exit).events
        val targets = reachable(readParsed())
        if (targets.isEmpty()) return emptyList()
        // stored by the client on receipt, see fetchFrom
        return ffi { client.fetchEventsFrom(targets, filter, timeout).toVec() }
    }

    /**
     * [fetch] for a page that should not wait for a relay whose connection failed. rust-nostr 0.44 keeps a relay that
     * refuses every connection in a fetch for the first 60 s after the start, and the fetch then waits for it until
     * [timeout] (Pixel 2026-10-21: the first fresh page after 8.5 s instead of 2 s in half the cold starts). Within
     * [startWindow] after [connect] this asks the reading relays of the normal set that are connected once none of them is
     * setting its connection up any more (at most [CONNECT_GRACE]); a relay still connecting then is asked in the
     * background once it is connected, within [LATE_WAIT] (stored like every fetch's events, nothing returned). None
     * connected after the grace: every relay that has not failed is asked. After the window it is [fetch]: rust-nostr
     * leaves a relay that keeps failing out itself, and a page never waits for the grace.
     */
    suspend fun fetchSettled(filter: Filter, timeout: Duration = Duration.ofSeconds(8)): List<Event> {
        @Suppress("NAME_SHADOWING") val timeout = awaitRoute(timeout) ?: return emptyList()
        val targets = readParsed()
        val since = connectedAt
        if (targets.isEmpty() || since == 0L || System.nanoTime() - since > startWindow.toNanos()) return fetch(filter, timeout)
        val states = settledStates(targets)
        fun state(url: RelayUrl) = states[url.toString().trimEnd('/')]
        val ready = targets.filter { state(it) == RelayStatus.CONNECTED.name }
        if (ready.isEmpty()) {
            val trying = targets.filter { state(it) !in LOST }.ifEmpty { targets }
            return ffi { client.fetchEventsFrom(trying, filter, timeout).toVec() }
        }
        // only the ones still connecting: a relay whose attempt failed is left to the next fetch (a dead one would be polled for nothing)
        val late = targets.filter { state(it) in SETTING_UP }.map { it.toString().trimEnd('/') }
        if (late.isNotEmpty()) scope.launch { askWhenConnected(late, filter, timeout) }
        return ffi { client.fetchEventsFrom(ready, filter, timeout).toVec() }
    }

    /** [fetchSettled]'s second part: each of [urls] is asked once it is connected, while it is still in the normal set, reads and is not a temporary relay; gives up after [LATE_WAIT]. */
    private suspend fun askWhenConnected(urls: List<String>, filter: Filter, timeout: Duration) {
        val waiting = urls.toMutableSet()
        val deadline = System.nanoTime() + LATE_WAIT.toNanos()
        while (waiting.isNotEmpty() && System.nanoTime() < deadline) {
            val set = snapshot()
            waiting.retainAll(set.normalSet)
            waiting.removeAll(temporary)
            waiting.removeAll(writeOnly)
            val now = ffi { waiting.filter { set.pool[it]?.status()?.name == RelayStatus.CONNECTED.name } }
            if (now.isNotEmpty()) {
                waiting.removeAll(now.toSet())
                // checked again right before the send: a relay re-attached as a search relay or set to write only meanwhile gets nothing
                val asked = now.filter { it !in temporary && it !in writeOnly }
                if (asked.isNotEmpty()) ignoringErrors { ffi { client.fetchEventsFrom(asked.toRelayUrls(), filter, timeout) } }
            } else {
                delay(LATE_POLL_MS)
            }
        }
    }

    /** Cap on an early-exit fetch's timeout (spec D8); tests change it. */
    @Volatile internal var earlyExitCap: Duration? = EARLY_EXIT_CAP

    /** Early-exit fetches running right now (tests: none left after cancellation or close). */
    internal val openExitFetches = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Spec wave 2 B4: a fetch that may end before every relay answered, see [Exit]. [relays] null = the reading relays of the normal set
     * (as [fetch]); otherwise exactly [relays], already attached by the caller. The client stores what arrives, as with
     * every fetch. [ExitFetch.met]: the condition held (fetchWide ends its other leg on it). The work runs on the
     * engine's scope, so [close] (logout) ends it; the caller's cancellation ends it too and is rethrown.
     */
    internal suspend fun fetchExit(relays: List<String>?, filter: Filter, timeout: Duration, exit: Exit, reached: MutableSet<String>? = null): ExitFetch {
        @Suppress("NAME_SHADOWING") val timeout = awaitRoute(timeout) ?: return ExitFetch(emptyList(), met = false)
        val targets = reachable(relays?.toHeldRelayUrls() ?: readParsed(), reached)
        if (targets.isEmpty()) return ExitFetch(emptyList(), met = false)
        val progress = ExitProgress(exit)
        if (progress.empty) return ExitFetch(ffi { client.fetchEventsFrom(targets, filter, timeout).toVec() }, met = false)
        val capped = earlyExitCap?.let { cap -> if (cap < timeout) cap else timeout } ?: timeout
        val work = scope.async(ffiDispatcher) { fetchUntil(targets, filter, capped, exit, progress) }
        return try {
            work.await()
        } catch (e: CancellationException) {
            // the caller left (rethrown below) or the engine closed (logout): the stream goes now
            withContext(NonCancellable) { work.cancelAndJoin() }
            currentCoroutineContext().ensureActive()
            ExitFetch(emptyList(), met = false)
        }
    }

    /** One `next()` of a stream; a null [event] means the stream ended. */
    private class Next(val event: Event?)

    /**
     * Spec wave 2 B4, Path S: [targets] over one rust-nostr stream until [progress] is met (for [Exit.NewestPerAuthor]
     * plus its grace), every relay ended, or [timeout]. Runs on [ffiDispatcher] in the engine scope; the stream is
     * closed in every case.
     */
    private suspend fun fetchUntil(targets: List<RelayUrl>, filter: Filter, timeout: Duration, exit: Exit, progress: ExitProgress): ExitFetch {
        val started = System.nanoTime()
        val stream = client.streamEventsFrom(targets, filter, timeout)
        openExitFetches.incrementAndGet()
        val events = ArrayList<Event>()
        var cut = false
        try {
            var graceEnd = 0L
            while (true) {
                val next: Next? = if (graceEnd == 0L) {
                    Next(stream.next())
                } else {
                    val leftMs = (graceEnd - System.nanoTime()) / 1_000_000
                    if (leftMs <= 0) null else withTimeoutOrNull(leftMs) { Next(stream.next()) }
                }
                if (next == null) {
                    cut = true // the grace is over
                    break
                }
                val ev = next.event ?: break // every relay ended, or the timeout
                val json = runCatching { EventJson.decode(ev.asJson()) }.getOrNull() ?: continue
                if (progress.add(json)) events += ev
                if (progress.met) {
                    // a grace lets the other relays' answers in flight still arrive
                    val grace = when (exit) {
                        is Exit.NewestPerAuthor -> exit.grace
                        is Exit.AtLeast -> exit.grace
                        else -> null
                    }
                    if (grace == null) {
                        cut = true
                        break
                    }
                    if (graceEnd == 0L) graceEnd = System.nanoTime() + grace.toNanos()
                }
            }
        } finally {
            withContext(NonCancellable) { runCatching { stream.close() } }
            openExitFetches.decrementAndGet()
        }
        if (cut) PerfTrace.mark("early exit ${exit.label} ${(System.nanoTime() - started) / 1_000_000}")
        return ExitFetch(events, progress.met)
    }

    /**
     * Signs with the configured signer, stores the event locally, then sends it. Signing
     * happens in Kotlin (not through the rust-nostr callback), so signer exceptions such as
     * [SigningRejectedException] reach the caller unchanged and nothing is stored.
     */
    suspend fun signAndSend(builder: EventBuilder): PublishResult {
        val event = sign(builder)
        save(event)
        announce(event)
        return send(event)
    }

    /** Signs [builder] with our own signer in Kotlin (not through the rust callback), so signer exceptions reach the caller. */
    private suspend fun sign(builder: EventBuilder): Event = when (val s = signer) {
        null -> ffi { builder.sign(client.signer()) }
        else -> {
            val unsigned = builder.build(PublicKey.parse(s.pubkey))
            RustConvert.toRust(s.sign(RustConvert.toTipsUnsigned(unsigned)))
        }
    }

    /** Signs and sends an event given in the `:tips` model (payment info, tip intents). */
    suspend fun signAndSend(unsigned: com.monostr.tips.event.UnsignedEvent): PublishResult =
        signAndSend(RustConvert.toBuilder(unsigned))

    /**
     * Signs and sends like [signAndSend], but only stores the event locally when at least one
     * relay accepted it ([PublishResult.sentToAny]). For a replaceable list (kind 10000-19999,
     * e.g. bookmarks) [signAndSend]'s store-before-send would leave a rejected write sitting in
     * the local database, where it resurrects the next time the list is loaded even though no
     * relay ever saw it.
     */
    suspend fun signAndPublish(builder: EventBuilder): PublishResult {
        val event = sign(builder)
        // rust-nostr's pool stores an event on send even when every relay refuses it, and a replaceable
        // one displaces its predecessor: remember that one so a refused write can put it back
        val previous = replacedBy(event)
        val result = try {
            send(event)
        } catch (e: CancellationException) {
            // the pool stored the event as soon as the send began: a cancelled publish must not leave
            // it behind (an unconfirmed kind 10050 could be adopted later, a kind 3 would be the newest)
            withContext(NonCancellable + ffiDispatcher) { rollBack(event, previous) }
            throw e
        }
        if (result.sentToAny) {
            ffi { client.database().saveEvent(event) }
            announce(event)
        } else {
            withContext(NonCancellable + ffiDispatcher) { rollBack(event, previous) }
        }
        return result
    }

    /** One relay's `OK` for an event; [seq] is from the [closedSeq] counter, so it can be ordered against AUTH OKs. */
    private data class OkAnswer(val accepted: Boolean, val message: String, val seq: Long)

    /** Event id to relay to the OK seen for an event whose publisher is waiting for them. */
    private val okWaiters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, OkAnswer>>()

    /**
     * Signs and sends like [signAndPublish], but the event reaches the relays as a raw client message
     * and is never stored by the pool; it is saved only when at least one relay answered `OK true`.
     * For an event whose mere storing has an irreversible effect in the local database, like a NIP-09
     * deletion request: the database would drop the named notes (and refuse them later) even if every
     * relay refused the request. Goes over the same pool connections to the connected writing relays of the normal set only;
     * the others are reported as [NOT_CONNECTED]. A relay that refuses with `auth-required` gets the frame
     * once more after it accepted our AUTH (the pool's own send would do that); that second answer is final.
     * Waits until every target answered or [timeout] passed, but at most [OK_GRACE] after the first
     * `OK true`; a relay without an answer counts as failed ([NO_ANSWER]). A cancellation after the frames
     * went out, or an OK that arrives after the wait, leaves the note visible locally although a relay may
     * have accepted the request. A failing local save after an accepted request still returns the success:
     * the relays hold the request, and the next fetch stores it.
     */
    suspend fun signAndPublishUnstored(builder: EventBuilder, timeout: Duration = Duration.ofSeconds(8)): PublishResult {
        val event = sign(builder)
        val id = event.id().toHex()
        val kind = event.kind().asU16().toInt()
        val set = snapshot()
        val all = targetsWithout(set, readOnly)
        wake(set.parsed, needed = all)
        val connected = connectedOnceSettled(all)
        val (ready, skipped) = all.partition { it.toString().trimEnd('/') in connected }
        val notSent = skipped.associate { it.toString() to NOT_CONNECTED }
        if (ready.isEmpty()) return PublishResult(id, kind, emptyList(), notSent).also { logFailures(it) }
        val answers = java.util.concurrent.ConcurrentHashMap<String, OkAnswer>()
        okWaiters[id] = answers
        try {
            val byName = ready.associateBy { it.toString().trimEnd('/') }
            val failedToSend = java.util.concurrent.ConcurrentHashMap<String, String>()
            // Relay.sendMsg queues the frame on the relay's own connection and stores nothing (the pool's
            // sendEventTo and sendMsgTo both store the event first)
            suspend fun sendFrame(url: RelayUrl) {
                try {
                    ffi { client.relay(url).sendMsg(rust.nostr.sdk.ClientMessage.event(event)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedToSend[url.toString().trimEnd('/')] = e.message ?: "send failed"
                }
            }
            ready.forEach { sendFrame(it) }
            val retried = HashSet<String>()
            // a final answer: OK true, a refusal that is not about AUTH, a refusal after the one resend, or one no AUTH can heal
            fun final(r: String): Boolean {
                if (failedToSend.containsKey(r)) return true
                val a = answers[r] ?: return false
                return a.accepted || !isAuthRequired(a.message) || r in retried || authRejection(r) != null
            }
            val end = System.nanoTime() + timeout.toNanos()
            var deadline = end
            withContext(Dispatchers.Default) {
                while (System.nanoTime() < deadline && byName.keys.any { !final(it) }) {
                    byName.forEach { (r, url) ->
                        val a = answers[r]
                        // refused for AUTH, and an AUTH newer than that refusal is accepted: ask once more
                        if (a != null && !a.accepted && isAuthRequired(a.message) && r !in retried && authRejection(r) == null && (authOkSeq[r] ?: 0L) > a.seq) {
                            retried += r
                            answers.remove(r)
                            sendFrame(url)
                        }
                    }
                    if (deadline == end && answers.values.any { it.accepted }) deadline = minOf(end, System.nanoTime() + OK_GRACE.toNanos())
                    delay(OK_POLL_MS)
                }
            }
            val success = byName.keys.filter { answers[it]?.accepted == true }
            val failed = byName.keys.filter { it !in success }.associate { r -> r to (answers[r]?.message ?: failedToSend[r] ?: NO_ANSWER) }
            if (success.isNotEmpty()) {
                try {
                    ffi { client.database().saveEvent(event) }
                    announce(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the relays have the request; the next fetch brings it into the database
                }
            }
            return PublishResult(id, kind, success, failed + notSent).also { logFailures(it) }
        } finally {
            okWaiters.remove(id)
        }
    }

    /**
     * Takes a publish that no relay confirmed out of the database and puts [previous] (the event it
     * replaced) back. Best-effort: a database error leaves the caller with its not-accepted result
     * (or its cancellation), never a raw database exception.
     */
    private suspend fun rollBack(event: Event, previous: Event?) {
        runCatching { client.database().delete(Filter().id(event.id())) }
        previous?.let { runCatching { client.database().saveEvent(it) } }
    }

    /** The stored event [event] would replace (replaceable and addressable kinds), if any. */
    private suspend fun replacedBy(event: Event): Event? {
        val kind = event.kind().asU16().toInt()
        val filter = when (kind) {
            0, 3, in 10000..19999 -> Filter().kind(event.kind()).author(event.author())
            in 30000..39999 -> {
                val d = event.tags().toVec().map { it.asVec() }.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: ""
                Filter().kind(event.kind()).author(event.author()).identifier(d)
            }
            else -> return null
        }
        return query(filter.limit(1u)).firstOrNull()
    }

    /**
     * Sends the stored event [idHex] to [relays] in addition to whatever [signAndSend] did
     * (spec 3.2: an intent also goes to the watcher's relays). Relays missing from the pool are
     * added as temporary write relays for this call and removed afterwards. Unparseable URLs are
     * skipped. Never throws except on cancellation.
     *
     * A relay held by [attachTemporary] (e.g. an own DM inbox relay) is read-only in the pool, and
     * rust-nostr refuses any write to it, even one addressed to that relay alone ("write actions are
     * disabled"). Such a relay gets the WRITE flag added here. rust-nostr 0.44.8 has no call that
     * takes the flag away again, so it keeps it until [detachTemporary] removes the relay; that is
     * harmless because pool-wide writes ([send]) target [relayUrls] explicitly and never reach a
     * temporary relay, whatever its flags.
     */
    suspend fun sendTo(relays: List<String>, idHex: String, connectTimeout: Duration = Duration.ofSeconds(5)): PublishResult {
        val event = eventById(idHex) ?: return PublishResult(idHex, 0, emptyList(), mapOf("*" to NOTE_NOT_IN_DATABASE))
        val kind = event.kind().asU16().toInt()
        val known = snapshot().pool.keys
        val targets = ArrayList<RelayUrl>()
        val added = ArrayList<RelayUrl>()
        // every relay whose add was tried: cleaned up even when the add was cancelled midway
        val attempted = ArrayList<RelayUrl>()
        val attemptedNames = ArrayList<String>()
        var touched = false
        try {
            for (raw in relays) {
                val url = runCatching { RelayUrl.parse(raw.trim()) }.getOrNull() ?: continue
                if (RelayHosts.isOnionHost(url.toString())) continue // a watcher's relay: not the user's own list (Tor 7.4)
                val norm = url.toString().trimEnd('/')
                // Under the mutex: a concurrent detachTemporary must not remove the relay between the
                // check and the upgrade (addWriteRelay would then add it back as a permanent relay).
                val held = temporaryMutex.withLock {
                    if (norm !in temporary) return@withLock false
                    ffi { runCatching { client.addRoutedWriteRelay(url, socksPort) } }
                    true
                }
                if (!held && norm !in known) {
                    // spec wave 2 B1 (G9): marked before the pool has it, so no refresh in between takes it into the
                    // normal set and a concurrent fetch or subscribe never REQs the watcher's relay
                    transient += norm
                    touched = true
                    attempted += url
                    attemptedNames += norm
                    if (!ffi { runCatching { client.addRoutedWriteRelay(url, socksPort).also { poolProbe?.invoke("add-returned") } }.getOrDefault(false) }) continue
                    added += url
                }
                targets += url
            }
            if (targets.isEmpty()) return PublishResult(idHex, kind, emptyList(), emptyMap())
            // Only the relays added here are dialled: client.tryConnect would dial every relay of the pool, and behind SOCKS it
            // ignores its timeout, so one host that takes no tor circuit held a tip for tor's two minutes (Pixel 2026-11-07). The send
            // goes out once one target is connected (awaitConnected); the added relays are removed below, so one still connecting costs nothing.
            if (added.isNotEmpty()) ffi { dial { added.forEach { url -> runCatching { client.relay(url).connect() } } } }
            // on the ffi dispatcher: a wall-clock wait whatever dispatcher the caller is on (a test scheduler skips delays)
            if (added.isNotEmpty()) ffi { awaitConnected(targets, connectTimeout) { url -> runCatching { client.relay(url).isConnected() }.getOrDefault(false) } }
            val ready = targets.filter { url -> ffi { runCatching { client.relay(url).isConnected() }.getOrDefault(false) } }
            val unreached = (targets - ready.toSet()).associate { it.toString() to NOT_CONNECTED }
            if (ready.isEmpty()) return PublishResult(idHex, kind, emptyList(), unreached).also { logFailures(it) }
            val out = ffi { client.sendEventTo(ready, event) }
            return PublishResult(idHex, kind, out.success.map { it.toString() }, out.failed.mapKeys { it.key.toString() } + unreached).also { logFailures(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PublishResult(idHex, kind, emptyList(), mapOf("*" to (e.message ?: "send failed")))
        } finally {
            withContext(NonCancellable + ffiDispatcher) {
                try {
                    attempted.forEach { runCatching { if (runCatching { client.relay(it) }.isSuccess) client.forceRemoveRelay(it) } }
                    if (touched) refreshRelaySet()
                } finally {
                    // unmarked only after the trailing refresh
                    attemptedNames.forEach { transient.remove(it) }
                }
            }
        }
    }

    /**
     * The user's NIP-65 relay list (kind 10002): local database first, one relay fetch when
     * missing. With [fresh] the relays are asked first, so a list changed in another app wins
     * over the stored one (backlog 36); the database keeps the newer of the two. An `r` tag
     * without marker counts for both lists. Null when the user has none.
     *
     * Bug 54: when neither has one, [extra] (index relays) are asked last, so a list that lives elsewhere (only on
     * the user's own relays, mirrored by an indexer) is not taken for "none" and overwritten. Only then: they are
     * extra connections, and their operators see the user's address.
     */
    suspend fun ownRelayList(fresh: Boolean = false, extra: List<String> = emptyList()): RelayList? {
        val pk = pubkey ?: return null
        val filter = Filter().kind(Kind(10002u)).author(PublicKey.parse(pk)).limit(1u)
        if (fresh) ignoringErrors { fetchOwnEvents(filter, OWN_LIST_TIMEOUT) }
        var event = query(filter).firstOrNull()
        if (event == null && !fresh) {
            ignoringErrors { fetchOwnEvents(filter) }
            event = query(filter).firstOrNull()
        }
        val others = extra.map { it.trim().trimEnd('/') }.distinct() - relayUrls().toSet()
        if (event == null && others.isNotEmpty()) {
            // NonCancellable attach: a cut-off attach would leak the relays it took
            val held = withContext(NonCancellable) { attachTemporary(others) }
            try {
                ignoringErrors { fetchFrom(others, filter, OWN_LIST_TIMEOUT, attach = false) }
            } finally {
                withContext(NonCancellable) { detachTemporary(held) }
            }
            event = query(filter).firstOrNull()
        }
        val r = event?.tags()?.toVec()?.map { it.asVec() }?.filter { it.size >= 2 && it[0] == "r" } ?: return null
        return RelayList(
            read = r.filter { it.getOrNull(2) == null || it[2] == "read" }.map { it[1].trimEnd('/') }.distinct(),
            write = r.filter { it.getOrNull(2) == null || it[2] == "write" }.map { it[1].trimEnd('/') }.distinct(),
        )
    }

    /** Sends an already stored event again (e.g. after an offline post) without signing a new one. */
    suspend fun resend(idHex: String): PublishResult {
        val event = requireNotNull(eventById(idHex)) { NOTE_NOT_IN_DATABASE }
        return send(event)
    }

    /**
     * Sends to the writing relays of the normal set ([relayUrls]) only: never pool-wide, where a temporary
     * relay that [sendTo] gave the WRITE flag would receive the user's posts too, and never to a read-only relay,
     * also when it is the only one connected.
     */
    private suspend fun send(event: Event): PublishResult = try {
        val set = snapshot()
        val all = targetsWithout(set, readOnly)
        wake(set.parsed, needed = all)
        // A relay that is not connected only makes the publish wait for its timeout (about 10 s) and
        // fails anyway: leave it out while another relay is there. With none connected the event goes
        // to all of them as before, and the caller gets rust-nostr's own failure per relay.
        val connected = connectedOnceSettled(all)
        val (ready, skipped) = all.partition { it.toString().trimEnd('/') in connected }
        val targets = if (ready.isEmpty()) all else ready
        val sendStart = System.nanoTime()
        val (ok, failed) = sendEach(targets, event)
        if (PerfTrace.enabled) PerfTrace.mark("send kind ${event.kind().asU16()} ${(System.nanoTime() - sendStart) / 1_000_000}ms, ${ok.size} ok, failed=$failed")
        val notSent = if (ready.isEmpty()) emptyMap() else skipped.associate { it.toString() to NOT_CONNECTED }
        PublishResult(event.id().toHex(), event.kind().asU16().toInt(), ok, failed + notSent).also { logFailures(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PublishResult(event.id().toHex(), event.kind().asU16().toInt(), emptyList(), mapOf("*" to (e.message ?: "send failed"))).also { logFailures(it) }
    }

    /**
     * Sends [event] to each of [targets] on its own, side by side, on the engine's scope. Returns once every relay answered,
     * or [OK_GRACE] after the first `OK true`: rust-nostr's one send to all of them waits for the slowest relay, and a
     * connected relay that takes events without ever answering held every publish up for its 10 s timeout (Pixel
     * 2026-11-02: relay.snort.social, a bookmark write took 10 s each time). A relay that has not answered by then is
     * reported as [NO_ANSWER]; its send goes on in the background and may still deliver the event.
     */
    private suspend fun sendEach(targets: List<RelayUrl>, event: Event): Pair<List<String>, Map<String, String>> {
        val answers = Channel<Pair<String, String?>>(Channel.UNLIMITED) // relay to error, null = accepted
        targets.forEach { url ->
            scope.launch {
                val error = try {
                    val out = ffi { client.sendEventTo(listOf(url), event) }
                    if (out.success.isNotEmpty()) null else out.failed.values.firstOrNull() ?: "send failed"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: "send failed"
                }
                answers.trySend(url.toString() to error)
            }
        }
        val ok = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        var deadline = 0L
        while (ok.size + failed.size < targets.size) {
            val next = if (deadline == 0L) answers.receive()
            else withTimeoutOrNull(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0)) { answers.receive() } ?: break
            val error = next.second
            if (error == null) {
                ok += next.first
                if (deadline == 0L) deadline = System.nanoTime() + OK_GRACE.toNanos()
            } else {
                failed[next.first] = error
            }
        }
        targets.map { it.toString() }.filter { it !in ok && it !in failed }.forEach { failed[it] = NO_ANSWER }
        return ok to failed
    }

    /**
     * The relays of [targets] that are connected. While one of them is still setting its connection
     * up (after the start, or back from the background when another relay was faster), this waits
     * for it, at most [CONNECT_GRACE]: left out, a replaceable event (follow list, profile, payment
     * info) would reach the one fast relay only, and nothing sends it to the others later.
     */
    private suspend fun connectedOnceSettled(targets: List<RelayUrl>): Set<String> =
        settledStates(targets).filterValues { it == RelayStatus.CONNECTED.name }.keys

    /**
     * [targets] without the relays whose connection failed (DISCONNECTED, TERMINATED, SLEEPING): rust-nostr 0.44 keeps
     * such a relay in a fetch for the first 60 s after the start, and later too when it was a good relay before it
     * dropped, and the fetch then waits for it until its timeout. Within [startWindow] after [connect] the states are
     * read once one of [targets] is connected or none is setting its connection up any more (at most [CONNECT_GRACE]):
     * nothing can arrive before the first connection, and a refused attempt has failed by then; an early exit is not
     * held back for the slowest relay. Later one read, no wait. A relay not in the pool stays; none left: [targets].
     * [connected] collects the ones of the final read that are connected (the `none left` fallback adds none).
     */
    private suspend fun reachable(targets: List<RelayUrl>, connected: MutableSet<String>? = null): List<RelayUrl> {
        if (targets.isEmpty()) return targets
        val keys = targets.map { it.toString().trimEnd('/') }
        val since = connectedAt
        val now = System.nanoTime()
        // spec 2026-10-23 §8: a relay attached a moment ago gets the same grace as the relays right after the start
        val late = lateWindow.toNanos()
        val waits = (since != 0L && now - since <= startWindow.toNanos()) ||
            keys.any { key -> attachedAt[key]?.let { now - it <= late } == true }
        val deadline = System.nanoTime() + CONNECT_GRACE.toNanos()
        while (true) {
            val pool = snapshot().pool
            val states = ffi { keys.mapNotNull { key -> pool[key]?.let { key to it.status().name } }.toMap() }
            val settled = states.values.none { it in SETTING_UP } || states.values.any { it == RelayStatus.CONNECTED.name }
            if (!waits || settled || System.nanoTime() >= deadline) {
                connected?.addAll(states.filterValues { it == RelayStatus.CONNECTED.name }.keys)
                return targets.filterIndexed { i, _ -> states[keys[i]] !in LOST }.ifEmpty { targets }
            }
            delay(REACHABLE_POLL_MS)
        }
    }

    /** The status of each relay of [targets] in the pool (by normalised URL), read once none of them is setting its connection up any more, at most [CONNECT_GRACE]. */
    private suspend fun settledStates(targets: List<RelayUrl>): Map<String, String> {
        val wanted = targets.map { it.toString().trimEnd('/') }.toSet()
        val deadline = System.nanoTime() + CONNECT_GRACE.toNanos()
        while (true) {
            val states = ffi { snapshot().pool.filterKeys { it in wanted }.mapValues { (_, relay) -> relay.status().name } }
            if (states.values.none { it in SETTING_UP } || System.nanoTime() >= deadline) return states
            ffi { delay(CONNECT_POLL_MS) }
        }
    }

    /**
     * Reconnects now when every relay of the normal set lost its connection: what the app finds
     * when it returns from the background. rust-nostr retries on its own schedule only, so a
     * publish in that window would wait and fail although the relays are reachable (v0.8.1: a
     * Monero setup failed four times with "no relay" right after the user came back from copying
     * the seed). Returns once the relays are connected or [RECONNECT_WAIT] passed.
     */
    suspend fun reconnectIfLost() = wake(snapshot().parsed)

    /**
     * Reconnects every relay of the normal set, connected or not, and asks their subscriptions again (spec 2026-10-23
     * §7): after a switch of the default network sockets can be dead but still marked connected, which
     * [reconnectIfLost] leaves alone. A temporary relay is renewed too when it carries a subscription (the DM inbox
     * relays DmSync holds, final review I4), still temporary with its references; the others are not touched. Returns
     * once the relays are connected or [RECONNECT_WAIT] passed.
     */
    suspend fun reconnectAll() = wake(snapshot().parsed, force = true)

    private val wakeMutex = Mutex()

    /**
     * See [reconnectIfLost]. Nothing happens while any relay of [needed] is connected: a working
     * connection is never torn down for the sake of a dead relay. A publish needs a writing relay, so a connected
     * read-only relay does not keep the write relays waiting for their retry; that one is left alone, only lost relays
     * are woken. With [force] ([reconnectAll]) every relay of [targets] is reconnected: a lost one as without [force],
     * any other one is replaced by a fresh one ([renew]).
     */
    private suspend fun wake(targets: List<RelayUrl>, force: Boolean = false, needed: List<RelayUrl> = targets) {
        val wanted = targets.map { it.toString().trimEnd('/') }.toSet()
        val neededKeys = if (needed === targets) wanted else needed.map { it.toString().trimEnd('/') }.toSet()
        // a network change racing logout: no relay is renewed or connected while (or after) close() shuts the client down
        if (wanted.isEmpty() || neededKeys.isEmpty() || closing || !route.value) return
        wakeMutex.withLock {
            if (closing || !route.value) return
            ffi {
                val pool = snapshot().pool
                val mine = pool.filterKeys { it in wanted }.values
                val deciding = if (neededKeys === wanted) mine else pool.filterKeys { it in neededKeys }.values
                if (mine.isEmpty() || (!force && deciding.any { it.isConnected() })) return@ffi
                // forced only: one subscriptions() per temporary relay, those without one (searches, lookups) stay as they are
                val held = if (!force) emptyList() else pool.filterKeys { it in temporary }.values.filter { relay ->
                    runCatching { relay.subscriptions().isNotEmpty() }.getOrDefault(false)
                }
                // a relay waiting for its next retry ignores a connect request: end that wait first
                // (one that is connecting right now is left alone, unless forced)
                val lost = mine.filter { it.status().name in LOST }
                // From here on the caller's cancellation has to wait (at most RECONNECT_WAIT): a relay whose
                // retry wait was ended has no retry of its own until connect() hands it back to the pool,
                // and a tryConnect dropped half way leaves the relay stuck in CONNECTING for good.
                withContext(NonCancellable) {
                    if (force) renew(mine.filter { it.status().name !in LOST }, held)
                    lost.forEach { runCatching { it.disconnect() } }
                    try {
                        dial { ignoringErrors { client.tryConnect(RECONNECT_WAIT) } }
                        // unlike the pool's own reconnect, tryConnect does not ask the relay's subscriptions
                        // again (they are still stored in the relay)
                        for (relay in lost) {
                            if (!relay.isConnected()) continue
                            var stored: Map<String, List<Filter>> = emptyMap()
                            ignoringErrors { stored = relay.subscriptions() }
                            // a write-only relay gets no live subscription; one that names it ([subscribeTo], DM inbox) stays
                            if (isWriteOnly(relay.url())) stored = stored.filterKeys { !active.containsKey(it) }
                            for ((id, filters) in stored) {
                                // one filter per subscription in this engine; a second REQ under the same id would replace the first
                                val filter = filters.singleOrNull() ?: continue
                                ignoringErrors { client.subscribeWithIdTo(listOf(relay.url()), id, filter, null) }
                            }
                        }
                    } finally {
                        dial { runCatching { client.connect() } }
                    }
                }
            }
        }
    }

    /**
     * Spec 2026-10-23 §7: replaces each of [relays] (normal set, connected or connecting) by a fresh pool relay with
     * the same URL and hands it the old one's subscriptions at once, so rust-nostr holds them for every later
     * connection, also one after [RECONNECT_WAIT]. A `disconnect()` followed by a connect is not enough for a relay
     * that is connected: in rust-nostr 0.44 the new socket is then sometimes closed at once while the relay keeps
     * reading CONNECTED (EngineReconnectTest under load), the very state a network switch leaves behind. A relay that
     * connected before goes to [connectedOnce] (its new stats start at zero). [held]: temporary relays with a
     * subscription, renewed the same way but added back as temporary ones (read only, as [attachTemporary] adds them),
     * their [temporary] membership and [temporaryRefs] untouched; one released meanwhile is skipped. A write-only relay of
     * the normal set comes back without the live subscriptions ([active]), so rust-nostr does not ask them there again; those
     * that name it ([subscribeTo], a DM inbox relay that is also in the main list) stay. Not cancellable by the caller.
     */
    private suspend fun renew(relays: List<rust.nostr.sdk.Relay>, held: List<rust.nostr.sdk.Relay> = emptyList()) {
        if (relays.isEmpty() && held.isEmpty()) return
        val renewed = ArrayList<Pair<RelayUrl, Map<String, List<Filter>>>>()
        try {
            // temporaryMutex: neither a concurrent attachTemporary (taking the URL as a new temporary relay while it is
            // out of the pool) nor removeRelays (a relay the user removed must not come back) runs in between;
            // relaySetMutex: no refresh can cache a set without the relay between the removal and the add
            temporaryMutex.withLock {
                relaySetMutex.withLock {
                    for (relay in relays + held) {
                        val url = relay.url()
                        val norm = url.toString().trimEnd('/')
                        val temp = relay in held
                        if (runCatching { client.relay(url) }.isFailure) continue // removed meanwhile
                        // a temporary relay detached meanwhile is gone; one promoted to the normal set is no longer ours here
                        if (temp && norm !in temporary) continue
                        var stored: Map<String, List<Filter>> = emptyMap()
                        ignoringErrors { stored = relay.subscriptions() }
                        if (!temp && norm in writeOnly) stored = stored.filterKeys { !active.containsKey(it) }
                        if (!temp && runCatching { relay.stats().success() > 0uL }.getOrDefault(false)) connectedOnce += norm
                        ignoringErrors { client.forceRemoveRelay(url) }
                        suspend fun add() = runCatching {
                            if (temp) client.addTemporaryRelay(url, socksPort) else client.addRoutedRelay(url, socksPort)
                        }.isSuccess
                        var added = add()
                        if (!added) {
                            Log.d(LOG_TAG, "renew: re-adding $url failed, retrying once")
                            added = add()
                        }
                        // a renewed temporary relay is a fresh connection: [reachable] gives it the late grace again
                        if (added && temp) attachedAt[norm] = System.nanoTime()
                        if (added) renewed += url to stored else Log.d(LOG_TAG, "renew: $url lost from the pool")
                    }
                }
            }
        } finally {
            refreshRelaySet()
        }
        for ((url, stored) in renewed) {
            dial { ignoringErrors { client.connectRelay(url) } }
            for ((id, filters) in stored) {
                // one filter per subscription in this engine; rust stores it and asks it on every connection of the relay
                val filter = filters.singleOrNull() ?: continue
                ignoringErrors { client.subscribeWithIdTo(listOf(url), id, filter, null) }
            }
        }
    }

    /** Debug builds log where an event went and which relay rejected it and why (URLs + relay message only, never content); R8 strips it in release. */
    private fun logFailures(result: PublishResult) {
        Log.d(LOG_TAG, "publish kind ${result.kind} ${result.eventId.take(8)}: ok=${result.successRelays} failed=${result.failedRelays.keys}")
        result.failedRelays.forEach { (url, err) -> Log.d(LOG_TAG, "publish kind ${result.kind} rejected by $url: $err") }
    }

    /** Relay states, refreshed every two seconds while collected. */
    fun relayStates(): Flow<List<RelayInfo>> = flow {
        while (true) {
            emit(
                client.relays().filterKeys { it.toString().trimEnd('/') !in temporary }.map { (url, relay) ->
                    val status = relay.status()
                    val key = url.toString().trimEnd('/')
                    RelayInfo(url.toString(), status.toState(), everConnected = key in connectedOnce || relay.stats().success() > 0uL, resting = status == RelayStatus.TERMINATED && resting.containsKey(url.toString().trimEnd('/')))
                },
            )
            delay(2000)
        }
    }.flowOn(ffiDispatcher)

    /** Engine-lifetime scope for repositories that need a background collector; cancelled by [close]. */
    val backgroundScope: CoroutineScope get() = scope

    /** The [HandleNotification] loop started by [connect]; [close] waits for it to end before the scope goes. */
    @Volatile private var notifications: Job? = null

    /**
     * Disconnects and shuts the client down, waits (at most [CLOSE_JOIN_MS], real time) for the
     * notification loop to return, then releases the scope; each step is best-effort so logout never
     * throws. Joining first means no rust callback is still running into a cancelled scope while
     * the client goes away.
     */
    suspend fun close() {
        closing = true
        withContext(NonCancellable + ffiDispatcher) {
            runCatching { client.disconnect() }
            runCatching { client.shutdown() }
            notifications?.let { job -> withTimeoutOrNull(CLOSE_JOIN_MS) { job.join() } }
        }
        scope.cancel()
    }

    companion object {
        const val LOG_TAG = "Monostr"
        /** Backlog 36: how long a fresh read of the own relay list waits for the relays (the caller shows busy meanwhile). */
        private val OWN_LIST_TIMEOUT: Duration = Duration.ofSeconds(5)
        /** A write-only relay's fetch that ended this close to its timeout was ended by the timeout, not by an EOSE. */
        private const val WRITE_ONLY_MARGIN_NANOS = 250_000_000L
        /** The notification loop that records CLOSED gets this long to land a frame that came in just before the fetch returned. */
        private const val WRITE_ONLY_CLOSED_SETTLE_MS = 100L
        /** How long a read-before-write waits for a write-only relay that asked for AUTH to accept it. */
        private val WRITE_ONLY_AUTH_WAIT: Duration = Duration.ofSeconds(5)

        /** A CLOSED/OK message prefix that means "authenticate first" (NIP-42) or "not for you". */
        fun isAuthRefusal(message: String): Boolean =
            machinePrefixed(message).let { it.startsWith("auth-required", ignoreCase = true) || it.startsWith("restricted", ignoreCase = true) }

        /** NIP-42's own prefix: only this one asks for AUTH; the fetch path treats nothing else as an AUTH block. */
        fun isAuthRequired(message: String): Boolean = machinePrefixed(message).startsWith("auth-required", ignoreCase = true)

        /** The message from its machine-readable prefix on: strfry puts "ERROR: " in front ("ERROR: auth-required: …"). */
        private fun machinePrefixed(message: String): String {
            val m = message.trim()
            return if (m.startsWith("ERROR:", ignoreCase = true)) m.substring("ERROR:".length).trim() else m
        }

        private const val AUTH_POLL_MS = 50L
        /** Less than this after waiting for the route is no time for a relay to answer: the fetch gives up and [reopened] tells. */
        private val MIN_FETCH_LEFT: Duration = Duration.ofMillis(500)
        /** Undecoded events the callback may queue (spec wave 2 B2). */
        private const val RAW_CAPACITY = 2048
        /** Decoded events a slow collector may lag behind. */
        private const val INCOMING_BUFFER = 512
        /** Wait before the second quick restart of the notification loop in a row (the first is immediate); doubles up to [RESTART_MAX_MS]. */
        private const val RESTART_MIN_MS = 100L
        private const val RESTART_MAX_MS = 5_000L
        private const val CLOSE_JOIN_MS = 2_000L
        /** Spec D8: an early-exit fetch whose stream sends no CLOSE when dropped ends after at most 5 s (Task 0 results). */
        internal val EARLY_EXIT_CAP: Duration? = Duration.ofSeconds(5)
        /** How long a publish waits for lost relay connections to come back before it sends. */
        private val RECONNECT_WAIT: Duration = Duration.ofSeconds(5)
        /** How long a publish waits for a relay that is setting its connection up right now. */
        private val CONNECT_GRACE: Duration = Duration.ofSeconds(2)
        private const val CONNECT_POLL_MS = 50L
        private const val REACHABLE_POLL_MS = 100L
        /** Three failed attempts: rust-nostr retries after 10 s, then longer, so a relay rests about half a minute after the start. */
        private const val REST_ATTEMPTS = 3
        private const val REST_CHECK_MS = 10_000L
        private const val REST_PERIOD_MS = 10 * 60_000L
        /** How long [fetchSettled] keeps waiting in the background for a relay that was not connected yet (rust retries a refused one after 10 s). */
        private val LATE_WAIT: Duration = Duration.ofSeconds(15)
        private const val LATE_POLL_MS = 250L
        /** Relay states in which a connection attempt is under way. */
        private val SETTING_UP = setOf(RelayStatus.CONNECTING.name, RelayStatus.PENDING.name)
        /** Relay states in which rust-nostr is not trying to connect right now. */
        private val LOST = setOf(RelayStatus.DISCONNECTED.name, RelayStatus.TERMINATED.name, RelayStatus.SLEEPING.name)

        /**
         * [ffi]: where every rust-nostr call runs (injectable for tests); never Main. [socksPort]: the loopback SOCKS port every
         * relay connection of this engine goes through, by host name, for its whole life; null connects directly. [held]: starts
         * with [routeOpen] false, until [openConnections].
         */
        suspend fun create(dbPath: String, signer: Signer?, relays: List<String>, ffi: CoroutineDispatcher = Dispatchers.IO, socksPort: Int? = null, held: Boolean = false): NostrEngine = withContext(ffi) {
            val database = NostrDatabase.lmdb(dbPath)
            // The adapter is created once here and handed to both the Client and the engine's
            // `nostrSigner` field, so the two are always the same NostrSigner instance.
            val authIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
            val nostrSigner = signer?.let { NostrSigner.custom(SignerAdapter(it) { id -> authIds += id }) }
            var builder = ClientBuilder().database(database).opts(ClientOptions().automaticAuthentication(true).throughSocks(socksPort))
            if (nostrSigner != null) builder = builder.signer(nostrSigner)
            val client = builder.build()
            // The handler drops exceptions: a failure in handleNotifications must not take the process down.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
            val engine = NostrEngine(client, signer, scope, nostrSigner, authIds, ffi, socksPort, held)
            engine.addRelays(relays)
            engine
        }
    }
}


/** Failure reported for a relay a publish left out because it was not connected. */
const val NOT_CONNECTED = "not connected"
/** A relay that did not answer a publish in time. */
const val NO_ANSWER = "no answer"
private const val OK_POLL_MS = 25L
private val OK_GRACE: Duration = Duration.ofSeconds(1)

/** Message of the exception thrown when an action needs an event that is not in the local database. */
const val NOTE_NOT_IN_DATABASE = "Note nicht in der Datenbank"

/** Outcome of a publish: the new event's id and which relays accepted it. Plain Kotlin so `:app` needs no rust-nostr types. */
data class PublishResult(val eventId: String, val kind: Int, val successRelays: List<String>, val failedRelays: Map<String, String>) {
    val sentToAny: Boolean get() = successRelays.isNotEmpty()

    /** One of [relays] accepted the event. */
    fun reached(relays: List<String>): Boolean {
        val accepted = successRelays.map { it.trimEnd('/') }.toSet()
        return relays.any { it.trimEnd('/') in accepted }
    }
}

/** The user's NIP-65 relay list. */
data class RelayList(val read: List<String>, val write: List<String>)

private fun RelayStatus.toState(): RelayState = when (this) {
    RelayStatus.CONNECTED -> RelayState.CONNECTED
    RelayStatus.CONNECTING, RelayStatus.PENDING, RelayStatus.INITIALIZED -> RelayState.CONNECTING
    else -> RelayState.DISCONNECTED
}

/**
 * Lets rust-nostr sign through our [Signer] (local key or Amber). rust-nostr only asks this signer
 * for a signature on its own for NIP-42 AUTH (kind 22242, automatic authentication); that one is
 * signed silently, so a relay's challenge never opens Amber. Without a silent answer the AUTH
 * fails and the DM auth hint shows instead.
 */
internal class SignerAdapter(private val signer: Signer, private val onAuthSigned: (String) -> Unit = {}) : CustomNostrSigner {
    override fun backend(): SignerBackend = SignerBackend.Custom("monostr")
    override suspend fun getPublicKey(): PublicKey? = PublicKey.parse(signer.pubkey)
    override suspend fun signEvent(unsignedEvent: UnsignedEvent): Event? {
        val unsigned = RustConvert.toTipsUnsigned(unsignedEvent)
        return plainErrors {
            if (unsigned.kind == AUTH_KIND) {
                RustConvert.toRust(signer.signEventSilent(unsigned)).also { onAuthSigned(it.id().toHex()) }
            } else {
                RustConvert.toRust(signer.sign(unsigned))
            }
        }
    }

    private companion object {
        const val AUTH_KIND = 22242
    }
    override suspend fun nip04Encrypt(publicKey: PublicKey, content: String): String = throw UnsupportedOperationException("nip04")
    override suspend fun nip04Decrypt(publicKey: PublicKey, encryptedContent: String): String = throw UnsupportedOperationException("nip04")
    override suspend fun nip44Encrypt(publicKey: PublicKey, content: String): String = plainErrors { signer.nip44Encrypt(publicKey.toHex(), content) }
    override suspend fun nip44Decrypt(publicKey: PublicKey, payload: String): String = plainErrors { signer.nip44Decrypt(publicKey.toHex(), payload) }
}

/** Thrown by [SilentSignerAdapter.nip44Decrypt] when [Signer.nip44DecryptSilent] has no answer without user interaction. */
class SilentDecryptUnavailable : Exception("no silent NIP-44 answer")

/** Like [SignerAdapter], but NIP-44 decryption never prompts: it fails instead of asking the user (implicit loads, e.g. a worker). */
internal class SilentSignerAdapter(private val signer: Signer) : CustomNostrSigner {
    override fun backend(): SignerBackend = SignerBackend.Custom("monostr-silent")
    override suspend fun getPublicKey(): PublicKey? = PublicKey.parse(signer.pubkey)
    override suspend fun signEvent(unsignedEvent: UnsignedEvent): Event? =
        plainErrors { RustConvert.toRust(signer.sign(RustConvert.toTipsUnsigned(unsignedEvent))) }
    override suspend fun nip04Encrypt(publicKey: PublicKey, content: String): String = throw UnsupportedOperationException("nip04")
    override suspend fun nip04Decrypt(publicKey: PublicKey, encryptedContent: String): String = throw UnsupportedOperationException("nip04")
    override suspend fun nip44Encrypt(publicKey: PublicKey, content: String): String = plainErrors { signer.nip44Encrypt(publicKey.toHex(), content) }
    override suspend fun nip44Decrypt(publicKey: PublicKey, payload: String): String =
        plainErrors { signer.nip44DecryptSilent(publicKey.toHex(), payload) } ?: throw SilentDecryptUnavailable()
}

/** Decryption-only signer for [NostrEngine.unwrapSigner]: calls [onLocked] before rethrowing a signer-side failure. */
internal class UnwrapSignerAdapter(private val signer: Signer, private val silent: Boolean, private val onLocked: () -> Unit) : CustomNostrSigner {
    override fun backend(): SignerBackend = SignerBackend.Custom("monostr-unwrap")
    override suspend fun getPublicKey(): PublicKey? = PublicKey.parse(signer.pubkey)
    override suspend fun signEvent(unsignedEvent: UnsignedEvent): Event? = throw UnsupportedOperationException("unwrap signer does not sign")
    override suspend fun nip04Encrypt(publicKey: PublicKey, content: String): String = throw UnsupportedOperationException("nip04")
    override suspend fun nip04Decrypt(publicKey: PublicKey, encryptedContent: String): String = throw UnsupportedOperationException("nip04")
    override suspend fun nip44Encrypt(publicKey: PublicKey, content: String): String = throw UnsupportedOperationException("unwrap signer does not encrypt")
    override suspend fun nip44Decrypt(publicKey: PublicKey, payload: String): String = try {
        if (silent) {
            plainErrors { signer.nip44DecryptSilent(publicKey.toHex(), payload) } ?: run { onLocked(); throw SilentDecryptUnavailable() }
        } else {
            plainErrors { signer.nip44Decrypt(publicKey.toHex(), payload) }
        }
    } catch (e: SigningRejectedException) {
        onLocked(); throw e
    } catch (e: Nip44UnsupportedException) {
        onLocked(); throw e
    }
}

/** Thrown in place of a [NostrSdkException] raised inside a signer callback, see [plainErrors]. */
class SignerCallbackException(message: String?) : Exception(message)

/**
 * Runs a signer call made from inside a rust-nostr callback. A [NostrSdkException] (e.g. a local key's
 * NIP-44 decrypt of a payload that is not for it) must not cross back into Rust as such: uniffi
 * cannot lift that flat error there and the process aborts ("Can't lift flat errors"). It is
 * rethrown as a plain exception, which rust-nostr receives as an ordinary callback error.
 */
internal inline fun <T> plainErrors(block: () -> T): T = try {
    block()
} catch (e: NostrSdkException) {
    throw SignerCallbackException(e.message)
}
