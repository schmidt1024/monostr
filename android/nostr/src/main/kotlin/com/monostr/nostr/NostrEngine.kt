package com.monostr.nostr

import com.monostr.nostr.model.RelayInfo
import com.monostr.nostr.model.RelayState
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import rust.nostr.sdk.RelayOptions
import rust.nostr.sdk.RelayStatus
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.SignerBackend
import rust.nostr.sdk.UnsignedEvent
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin wrapper around the rust-nostr [Client]: one LMDB database, one relay
 * pool, one signer. Repositories read from [query] and listen on [events];
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
) {
    val pubkey: String? = signer?.pubkey

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

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val started = AtomicBoolean(false)

    /** Every event delivered by any subscription. */
    val events: SharedFlow<Event> = _events

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
    suspend fun addRelays(urls: List<String>): Unit = ffi {
        urls.forEach { url ->
            val parsed = runCatching { RelayUrl.parse(url) }.getOrNull() ?: return@forEach
            val norm = parsed.toString().trimEnd('/')
            // The whole promotion (including the network calls and a failed rollback) runs under
            // temporaryMutex: releasing it between the `temporary.remove` and the rollback would let
            // a concurrent attachTemporary/detachTemporary for the same URL observe or create an
            // inconsistent state (see task-10 review round 1).
            val promoted = temporaryMutex.withLock {
                if (norm !in temporary) return@withLock false
                val previousRefs = temporaryRefs[norm] ?: 1
                temporary.remove(norm)
                temporaryRefs.remove(norm)
                ignoringErrors { client.forceRemoveRelay(parsed) }
                try {
                    client.addRelay(parsed)
                    replaySubscriptions(parsed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Rollback: promotion failed, keep the relay reachable as a temporary one
                    // (with its prior reference count restored) instead of losing it from both
                    // `temporary` and the pool.
                    ignoringErrors { client.addRelayWithOpts(parsed, RelayOptions().read(true).write(false)) }
                    temporary += norm
                    temporaryRefs[norm] = previousRefs
                }
                true
            }
            if (!promoted) {
                val added = try {
                    client.addRelay(parsed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (added) replaySubscriptions(parsed)
            }
        }
    }

    /** Live subscriptions by id ([subscribe] adds, [unsubscribe] removes), replayed to relays that join the normal set later. */
    private val active = java.util.concurrent.ConcurrentHashMap<String, Filter>()

    /**
     * Best-effort: a relay that cannot take the subscription now simply misses it, like any failed
     * REQ. rust-nostr refuses a relay subscription before the relay has been told to connect, so
     * the relay is connected first (the session's reconnect after a relay list change would do
     * the same a moment later).
     */
    private suspend fun replaySubscriptions(url: RelayUrl) {
        if (active.isEmpty()) return
        ffi {
            ignoringErrors { client.connectRelay(url) }
            active.forEach { (id, filter) -> ignoringErrors { client.subscribeWithIdTo(listOf(url), id, filter, null) } }
        }
    }

    /** Removes relays; a malformed URL is skipped rather than throwing. */
    suspend fun removeRelays(urls: List<String>) {
        ffi { urls.forEach { url -> runCatching { client.removeRelay(RelayUrl.parse(url)) } } }
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

    /** Currently configured relay URLs without the temporary search relays, trailing slash normalised. */
    suspend fun relayUrls(): List<String> = ffi { client.relays() }.keys.map { it.toString().trimEnd('/') }.filter { it !in temporary }

    fun temporaryRelayUrls(): List<String> = temporary.toList()

    /** Normalised URLs (trailing slash trimmed) of all pool relays, temporary included, whose status is CONNECTED. */
    suspend fun connectedRelayUrls(): Set<String> =
        ffi {
            client.relays().filterValues { it.status().name == RelayStatus.CONNECTED.name }
                .keys.map { it.toString().trimEnd('/') }.toSet()
        }

    /** [connectedRelayUrls] restricted to the normal set ([relayUrls]): a connected search relay never counts. */
    suspend fun connectedNormalRelayUrls(): Set<String> = connectedRelayUrls() intersect relayUrls().toSet()

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
     * [relayUrls] (which excludes temporary relays) rather than the whole pool.
     *
     * Invariant: a temporary relay is reachable only through calls that name it ([fetchFrom],
     * [subscribeTo], [sendTo]). [subscribe], [fetch] and the engine's own sends always target
     * [relayUrls] explicitly and therefore never reach it, even though the underlying rust-nostr
     * pool marks it `read`-enabled. If a temporary relay is later added to the normal set (see
     * [addRelays]), it is promoted and never removed by [detachTemporary].
     *
     * Returns the normalised URLs this call took a reference on (newly attached or already
     * temporary): exactly what the caller must hand to [detachTemporary] later. A URL of the normal
     * set, a malformed one or one that could not be added is not in it.
     */
    suspend fun attachTemporary(urls: List<String>): List<String> = ffi {
        val known = client.relays().keys.map { it.toString().trimEnd('/') }.toSet()
        val held = ArrayList<String>()
        for (raw in urls) {
            val url = runCatching { RelayUrl.parse(raw.trim()) }.getOrNull() ?: continue
            val norm = url.toString().trimEnd('/')
            temporaryMutex.withLock {
                if (norm in temporary) { temporaryRefs.merge(norm, 1, Int::plus); held += norm; return@withLock }
                if (norm in known) return@withLock
                val added = try {
                    client.addRelayWithOpts(url, RelayOptions().read(true).write(false))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (!added) return@withLock
                temporary += norm
                temporaryRefs[norm] = 1
                held += norm
                ignoringErrors { client.connectRelay(url) }
            }
        }
        held
    }

    /** Removes those of [urls] that [attachTemporary] added; relays of the normal set are untouched. */
    suspend fun detachTemporary(urls: List<String>) {
        // NonCancellable as a whole: callers release from `finally` blocks, where a cancellable
        // mutex lock() would throw and skip the release, leaking a reference (and the relay).
        withContext(NonCancellable + ffiDispatcher) {
            for (raw in urls) {
                val url = runCatching { RelayUrl.parse(raw.trim()) }.getOrNull() ?: continue
                val norm = url.toString().trimEnd('/')
                temporaryMutex.withLock {
                    if (norm !in temporary) return@withLock
                    val left = temporaryRefs.merge(norm, -1, Int::plus) ?: 0
                    if (left > 0) return@withLock
                    temporaryRefs.remove(norm)
                    temporary.remove(norm)
                    ignoringErrors { client.forceRemoveRelay(url) }
                }
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
     */
    suspend fun fetchFrom(relays: List<String>, filter: Filter, timeout: Duration = Duration.ofSeconds(8), attach: Boolean = true): List<Event> {
        if (attach) attachTemporary(relays)
        val targets = relays.toRelayUrls()
        if (targets.isEmpty()) return emptyList()
        return ffi {
            val events = client.fetchEventsFrom(targets, filter, timeout).toVec()
            events.forEach { runCatching { client.database().saveEvent(it) } }
            events
        }
    }

    /**
     * NIP-45: asks [relay] alone how many events match [filter]. The relay is attached for the call
     * only (an engine relay stays). Null when it does not answer in [timeout], refuses COUNT, or
     * cannot be reached; cancellation is propagated.
     */
    suspend fun countFrom(relay: String, filter: Filter, timeout: Duration): Long? {
        // NonCancellable: a cancellation landing on the attach's own return would lose `held` and leak the relay
        val held = withContext(NonCancellable) { attachTemporary(listOf(relay)) }
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
            detachTemporary(held)
        }
    }

    /** Connects (or reconnects after a relay list change); the notification loop is started only once per engine. */
    suspend fun connect() {
        ffi { client.connect() }
        if (started.compareAndSet(false, true)) {
            notifications = scope.launch(ffiDispatcher) {
                client.handleNotifications(object : HandleNotification {
                    override suspend fun handle(relayUrl: RelayUrl, subscriptionId: String, event: Event) {
                        // Drop oldest if buffer full; repositories re-query the database on any event,
                        // so dropped notifications are harmless (re-querying always gets all events).
                        _events.tryEmit(event)
                    }

                    override suspend fun handleMsg(relayUrl: RelayUrl, msg: RelayMessage) {
                        val relay = relayUrl.toString().trimEnd('/')
                        when (val m = msg.asEnum()) {
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
                })
            }
        }
    }

    /**
     * Subscribes on [relayUrls] only, never on a temporary search relay (see [attachTemporary]'s
     * doc comment for why the exclusion happens here rather than via a read/write flag).
     */
    suspend fun subscribe(filter: Filter): String {
        val targets = relayUrls().toRelayUrls()
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
        val targets = relays.toRelayUrls()
        require(targets.isNotEmpty()) { "no relays" }
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

    suspend fun eventById(idHex: String): Event? = ffi { client.database().eventById(rust.nostr.sdk.EventId.parse(idHex)) }

    suspend fun save(event: Event) {
        ffi { client.database().saveEvent(event) }
    }

    /**
     * Fetches from [relayUrls] (never a temporary search relay, see [attachTemporary]) with a
     * timeout; results are stored in the database. An empty relay list yields an empty result
     * without throwing.
     */
    suspend fun fetch(filter: Filter, timeout: Duration = Duration.ofSeconds(8)): List<Event> {
        val targets = relayUrls().toRelayUrls()
        if (targets.isEmpty()) return emptyList()
        return ffi {
            val events = client.fetchEventsFrom(targets, filter, timeout).toVec()
            events.forEach { runCatching { client.database().saveEvent(it) } }
            events
        }
    }

    /**
     * Signs with the configured signer, stores the event locally, then sends it. Signing
     * happens in Kotlin (not through the rust-nostr callback), so signer exceptions such as
     * [SigningRejectedException] reach the caller unchanged and nothing is stored.
     */
    suspend fun signAndSend(builder: EventBuilder): PublishResult {
        val event = sign(builder)
        save(event)
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
     * relay refused the request. Goes over the same pool connections to the connected normal relays only;
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
        val all = relayUrls().toRelayUrls()
        wake(all)
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
        val known = ffi { client.relays() }.keys.map { it.toString().trimEnd('/') }.toSet()
        val targets = ArrayList<RelayUrl>()
        val added = ArrayList<RelayUrl>()
        for (raw in relays) {
            val url = runCatching { RelayUrl.parse(raw.trim()) }.getOrNull() ?: continue
            val norm = url.toString().trimEnd('/')
            // Under the mutex: a concurrent detachTemporary must not remove the relay between the
            // check and the upgrade (addWriteRelay would then add it back as a permanent relay).
            val held = temporaryMutex.withLock {
                if (norm !in temporary) return@withLock false
                ffi { runCatching { client.addWriteRelay(url) } }
                true
            }
            if (!held && norm !in known) {
                if (!ffi { runCatching { client.addWriteRelay(url) }.getOrDefault(false) }) continue
                added += url
            }
            targets += url
        }
        if (targets.isEmpty()) return PublishResult(idHex, kind, emptyList(), emptyMap())
        try {
            val out = ffi {
                if (added.isNotEmpty()) runCatching { client.tryConnect(connectTimeout) }
                client.sendEventTo(targets, event)
            }
            return PublishResult(idHex, kind, out.success.map { it.toString() }, out.failed.mapKeys { it.key.toString() }).also { logFailures(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PublishResult(idHex, kind, emptyList(), mapOf("*" to (e.message ?: "send failed")))
        } finally {
            withContext(NonCancellable + ffiDispatcher) { added.forEach { runCatching { client.forceRemoveRelay(it) } } }
        }
    }

    /**
     * The user's NIP-65 relay list (kind 10002): local database first, one relay fetch when
     * missing. An `r` tag without marker counts for both lists. Null when the user has none.
     */
    suspend fun ownRelayList(): RelayList? {
        val pk = pubkey ?: return null
        val filter = Filter().kind(Kind(10002u)).author(PublicKey.parse(pk)).limit(1u)
        var event = query(filter).firstOrNull()
        if (event == null) {
            runCatching { fetch(filter) }
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
     * Sends to the normal set ([relayUrls]) only: never pool-wide, where a temporary relay that
     * [sendTo] gave the WRITE flag would receive the user's posts too.
     */
    private suspend fun send(event: Event): PublishResult = try {
        val all = relayUrls().toRelayUrls()
        wake(all)
        // A relay that is not connected only makes the publish wait for its timeout (about 10 s) and
        // fails anyway: leave it out while another relay is there. With none connected the event goes
        // to all of them as before, and the caller gets rust-nostr's own failure per relay.
        val connected = connectedOnceSettled(all)
        val (ready, skipped) = all.partition { it.toString().trimEnd('/') in connected }
        val targets = if (ready.isEmpty()) all else ready
        val out = ffi { client.sendEventTo(targets, event) }
        val notSent = if (ready.isEmpty()) emptyMap() else skipped.associate { it.toString() to NOT_CONNECTED }
        PublishResult(event.id().toHex(), event.kind().asU16().toInt(), out.success.map { it.toString() }, out.failed.mapKeys { it.key.toString() } + notSent).also { logFailures(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PublishResult(event.id().toHex(), event.kind().asU16().toInt(), emptyList(), mapOf("*" to (e.message ?: "send failed"))).also { logFailures(it) }
    }

    /**
     * The relays of [targets] that are connected. While one of them is still setting its connection
     * up (after the start, or back from the background when another relay was faster), this waits
     * for it, at most [CONNECT_GRACE]: left out, a replaceable event (follow list, profile, payment
     * info) would reach the one fast relay only, and nothing sends it to the others later.
     */
    private suspend fun connectedOnceSettled(targets: List<RelayUrl>): Set<String> {
        val wanted = targets.map { it.toString().trimEnd('/') }.toSet()
        val deadline = System.nanoTime() + CONNECT_GRACE.toNanos()
        while (true) {
            val states = ffi { client.relays().map { (url, relay) -> url.toString().trimEnd('/') to relay.status().name } }.filter { it.first in wanted }
            if (states.none { it.second in SETTING_UP } || System.nanoTime() >= deadline) {
                return states.filter { it.second == RelayStatus.CONNECTED.name }.map { it.first }.toSet()
            }
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
    suspend fun reconnectIfLost() = wake(relayUrls().toRelayUrls())

    private val wakeMutex = Mutex()

    /**
     * See [reconnectIfLost]. Nothing happens while any relay of [targets] is connected: a working
     * connection is never torn down for the sake of a dead relay.
     */
    private suspend fun wake(targets: List<RelayUrl>) {
        val wanted = targets.map { it.toString().trimEnd('/') }.toSet()
        if (wanted.isEmpty()) return
        wakeMutex.withLock {
            ffi {
                val mine = client.relays().filterKeys { it.toString().trimEnd('/') in wanted }.values
                if (mine.isEmpty() || mine.any { it.isConnected() }) return@ffi
                // a relay waiting for its next retry ignores a connect request: end that wait first
                // (one that is connecting right now is left alone)
                val lost = mine.filter { it.status().name in LOST }
                // From here on the caller's cancellation has to wait (at most RECONNECT_WAIT): a relay whose
                // retry wait was ended has no retry of its own until connect() hands it back to the pool,
                // and a tryConnect dropped half way leaves the relay stuck in CONNECTING for good.
                withContext(NonCancellable) {
                    lost.forEach { runCatching { it.disconnect() } }
                    try {
                        ignoringErrors { client.tryConnect(RECONNECT_WAIT) }
                        // unlike the pool's own reconnect, tryConnect does not ask the relay's subscriptions
                        // again (they are still stored in the relay)
                        for (relay in lost) {
                            if (!relay.isConnected()) continue
                            var stored: Map<String, List<Filter>> = emptyMap()
                            ignoringErrors { stored = relay.subscriptions() }
                            for ((id, filters) in stored) {
                                // one filter per subscription in this engine; a second REQ under the same id would replace the first
                                val filter = filters.singleOrNull() ?: continue
                                ignoringErrors { client.subscribeWithIdTo(listOf(relay.url()), id, filter, null) }
                            }
                        }
                    } finally {
                        runCatching { client.connect() }
                    }
                }
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
            emit(client.relays().filterKeys { it.toString().trimEnd('/') !in temporary }.map { (url, relay) -> RelayInfo(url.toString(), relay.status().toState()) })
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
        withContext(NonCancellable + ffiDispatcher) {
            runCatching { client.disconnect() }
            runCatching { client.shutdown() }
            notifications?.let { job -> withTimeoutOrNull(CLOSE_JOIN_MS) { job.join() } }
        }
        scope.cancel()
    }

    companion object {
        const val LOG_TAG = "Monostr"

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
        private const val CLOSE_JOIN_MS = 2_000L
        /** How long a publish waits for lost relay connections to come back before it sends. */
        private val RECONNECT_WAIT: Duration = Duration.ofSeconds(5)
        /** How long a publish waits for a relay that is setting its connection up right now. */
        private val CONNECT_GRACE: Duration = Duration.ofSeconds(2)
        private const val CONNECT_POLL_MS = 50L
        /** Relay states in which a connection attempt is under way. */
        private val SETTING_UP = setOf(RelayStatus.CONNECTING.name, RelayStatus.PENDING.name)
        /** Relay states in which rust-nostr is not trying to connect right now. */
        private val LOST = setOf(RelayStatus.DISCONNECTED.name, RelayStatus.TERMINATED.name, RelayStatus.SLEEPING.name)

        /** [ffi]: where every rust-nostr call runs (injectable for tests); never Main. */
        suspend fun create(dbPath: String, signer: Signer?, relays: List<String>, ffi: CoroutineDispatcher = Dispatchers.IO): NostrEngine = withContext(ffi) {
            val database = NostrDatabase.lmdb(dbPath)
            // The adapter is created once here and handed to both the Client and the engine's
            // `nostrSigner` field, so the two are always the same NostrSigner instance.
            val authIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
            val nostrSigner = signer?.let { NostrSigner.custom(SignerAdapter(it) { id -> authIds += id }) }
            var builder = ClientBuilder().database(database).opts(ClientOptions().automaticAuthentication(true))
            if (nostrSigner != null) builder = builder.signer(nostrSigner)
            val client = builder.build()
            // The handler drops exceptions: a failure in handleNotifications must not take the process down.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
            val engine = NostrEngine(client, signer, scope, nostrSigner, authIds, ffi)
            engine.addRelays(relays)
            engine
        }
    }
}

/** Parses each URL, silently skipping ones that fail to parse. */
private fun List<String>.toRelayUrls(): List<RelayUrl> = mapNotNull { runCatching { RelayUrl.parse(it.trim()) }.getOrNull() }

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
