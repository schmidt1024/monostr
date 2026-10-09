package com.monostr.app.dm

import com.monostr.app.data.DmSettingsStore
import com.monostr.app.data.dm.DmMessage
import com.monostr.app.data.dm.DmStatus
import com.monostr.app.data.dm.DmStore
import com.monostr.app.data.dm.DropReason
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.repo.DmRelaysRepository
import com.monostr.nostr.repo.DmRepository
import com.monostr.nostr.repo.OwnDmRelays
import com.monostr.nostr.repo.PublishRepository
import com.monostr.nostr.repo.Unwrap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Event
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [authFailed]: own inbox relays that refused the DM subscription or fetch for AUTH reasons (spec 3.4 hint).
 * [authRejected]: own inbox relays that asked for AUTH and then refused our AUTH event itself, with the
 * relay's message: the user cannot heal that, so they neither block nor are asked again in a fetch.
 */
data class DmSyncState(val authFailed: Set<String> = emptySet(), val syncing: Boolean = false, val authRejected: Map<String, String> = emptyMap()) {
    /** Spec 7.1 of Plan 10d: while an own inbox relay refuses AUTH, lastSync stays where it is. */
    val authBlocked: Boolean get() = authFailed.isNotEmpty()
}

/**
 * The DM orchestrator (spec §3): keeps the own inbox relays (kind 10050) attached and subscribed
 * while started (from session start on, so live messages and the unread badge work before the
 * Messages tab is opened), fetches and unwraps gift wraps into [store], and sends messages. The
 * own kind 10050 is looked up and published separately, by [adoptOwnList]. An own relay that asks
 * for AUTH and refuses ours is left out of the fetches and named in [DmSyncState.authRejected].
 *
 * A wrap is unwrapped silently (no signer prompt) unless the caller is interactive; a wrap the
 * silent signer cannot open is parked as pending and retried by [drainPending] on a user action.
 */
class DmSync(
    private val repo: DmRepository,
    private val relays: DmRelaysRepository,
    private val store: DmStore,
    private val settings: DmSettingsStore,
    private val publish: PublishRepository,
    /** Attaches the relays for this run; returns the ones it took a reference on (what [detach] later gets). */
    private val attach: suspend (List<String>) -> List<String>,
    private val detach: suspend (List<String>) -> Unit,
    private val closed: Flow<NostrEngine.ClosedSubscription>,
    private val scope: CoroutineScope,
    /** Reads a stored wrap from the engine database by id (for [drainPending]). */
    private val loadWrap: suspend (String) -> Event?,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Relays that accepted our NIP-42 AUTH ([NostrEngine.authenticated]); heals [DmSyncState.authFailed]. */
    private val authOk: Flow<String> = emptyFlow(),
    /** [NostrEngine.authOkSeq]: a CLOSED older than the relay's latest AUTH OK is already healed and must not mark it. */
    private val authOkSeq: (String) -> Long? = { null },
    /** [NostrEngine.authRejected]: a relay answered our AUTH with OK false. */
    private val authRejected: Flow<NostrEngine.AuthRejection> = emptyFlow(),
    /** [NostrEngine.authRejection]: the relay's latest refused AUTH, null once it accepted one. */
    private val authRejection: (String) -> NostrEngine.AuthRejection? = { null },
) {
    private val _state = MutableStateFlow(DmSyncState())
    val state: StateFlow<DmSyncState> = _state.asStateFlow()

    /** One started period: its coroutines (children of [job]) and what it holds on the engine. */
    private class Run(val job: Job) {
        /** The own inbox relays this run subscribes to. */
        @Volatile var attached: List<String> = emptyList()
        /** Those of [attached] that [attach] took a reference on: exactly what [stop] releases. */
        @Volatile var held: List<String> = emptyList()
        /** Written under [closedLock], together with the check of [early]. */
        @Volatile var subId: String? = null
        val closedLock = Any()
        /** Refusals seen before [subId] is known: a relay can answer the REQ with CLOSED before subscribeWraps returns. */
        val early = ArrayList<NostrEngine.ClosedSubscription>()
    }

    private val lock = Any()
    private var current: Run? = null // guarded by [lock]
    /** Guards [ownListSettled]: the own-list check runs until it once reaches a definite answer (then persisted per account in [settings]). */
    private val ownListMutex = Mutex()
    private var ownListSettled = false // guarded by [ownListMutex]
    private val staleFailed = AtomicBoolean(false)
    /** Relay → the AUTH OK seq a live subscription was last renewed for ([subscriptionRefused]); one renewal per OK. */
    private val renewedFor = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Rumor ids replaced by a [retry]: a late self-copy of one must not bring the old row back. */
    private val superseded: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    private val activeSyncs = AtomicInteger(0)

    /** Spec 7.2: bumped when the own inbox list changes (adoption or edit); a round begun under an older value discards its result. */
    private val generation = AtomicInteger(0)

    /** Makes (generation check + lastSync write) of [sync] and (bump + lastSync reset) of [ownRelaysChanged] one step. */
    private val syncMark = Mutex()

    /** Serialises the check-unwrap-store sequence of [handle] between the live collector, [sync] and [drainPending]. */
    private val handling = Mutex()

    /**
     * Attaches the own DM relays stored in [settings], starts the live collector, fetches silently
     * since lastSync−2d (first run: −30d) and subscribes; idempotent. Never looks up or publishes
     * kind 10050 and never prompts the signer, so it is safe at session start, Amber included.
     */
    fun start() {
        val run = synchronized(lock) {
            if (current != null) return
            Run(SupervisorJob(scope.coroutineContext[Job])).also { current = it }
        }
        // Stale hints go; the new subscription's CLOSED frames report the relays that still refuse.
        _state.update { it.copy(authFailed = emptySet(), authRejected = emptyMap()) }
        val runScope = CoroutineScope(scope.coroutineContext + run.job)
        runScope.launch {
            closed.collect { c ->
                // only NIP-42's auth-required can be healed by an AUTH; restricted etc. leave the round incomplete, nothing more
                if (!NostrEngine.isAuthRequired(c.message)) return@collect
                // Only the DM subscription counts: feed subscriptions are refused for their own reasons.
                val ours = synchronized(run.closedLock) {
                    val id = run.subId
                    if (id == null) {
                        if (run.early.size < EARLY_CLOSED_MAX) run.early += c
                        false
                    } else {
                        c.subscriptionId == id
                    }
                }
                if (ours) subscriptionRefused(c.relay, c.seq)
            }
        }
        runScope.launch { authOk.collect { relay -> onAuthenticated(relay) } }
        runScope.launch { authRejected.collect { r -> onAuthRejected(r) } }
        runScope.launch { repo.incomingWraps().collect { wrap -> bestEffort { handle(wrap, interactive = false) } } }
        runScope.launch { setUp(run) }
    }

    /** Re-reads [settings]'s relay list into a fresh run (an edited inbox list takes effect at once). */
    fun restart() { stop(); start() }

    /**
     * Settles the account's own kind 10050 (see [ensureOwnList]) in the background; idempotent, and
     * once settled for an account it never asks again. Called where the user deals with DMs (the
     * Messages tab, a chat) rather than at session start: publishing the list signs an event.
     */
    fun adoptOwnList() {
        scope.launch { bestEffort { ensureOwnList() } }
    }

    /**
     * The effective own inbox list changed (adopted from kind 10050 or edited in Settings): lastSync
     * goes back to 0 so the next round fetches the whole 30-day window from the new relays (wraps
     * already stored are skipped by id), then the run restarts on them.
     */
    suspend fun ownRelaysChanged() {
        syncMark.withLock {
            generation.incrementAndGet()
            store.setLastSync(0)
        }
        restart()
    }

    /** Unsubscribes and releases the relays (logout). A later [start] begins a new period. */
    fun stop() {
        val run = synchronized(lock) { current.also { current = null } } ?: return
        run.job.cancel()
        scope.launch {
            withContext(NonCancellable) {
                run.job.join()
                run.subId?.let { id -> bestEffort { repo.unsubscribe(id) } }
                if (run.held.isNotEmpty()) bestEffort { detach(run.held) }
            }
        }
    }

    /**
     * The live DM subscription got an auth-required CLOSED from [relay]. The relay dropped that REQ
     * either way: when its AUTH OK is already known to be newer than the CLOSED (the REQ overtook
     * our AUTH on a fresh connection), nothing will heal it later, so the subscription is renewed
     * now, once per AUTH OK; otherwise the relay is marked and [onAuthenticated] renews it.
     */
    private suspend fun subscriptionRefused(relay: String, closedSeq: Long) {
        val ok = authOkSeq(relay) ?: 0L
        if (ok <= closedSeq) return markAuthFailed(relay, closedSeq)
        if (renewedFor.put(relay, ok) != ok) resubscribe()
    }

    private fun markAuthFailed(relay: String, closedSeq: Long) {
        // the AUTH OK came before this CLOSED was processed (parked, or a race): nothing left to heal later
        if ((authOkSeq(relay) ?: 0L) > closedSeq) return
        // the relay refused our AUTH itself: no signer permission changes that, so it must not block
        authRejection(relay)?.let { return markRejected(relay, it.message) }
        _state.update { it.copy(authFailed = it.authFailed + relay) }
    }

    private fun markRejected(relay: String, message: String) {
        _state.update { it.copy(authFailed = it.authFailed - relay, authRejected = it.authRejected + (relay to message)) }
    }

    /**
     * [r]'s relay answered our AUTH with OK false after it had refused the DM subscription or fetch:
     * it leaves the block (the user cannot heal it) and, once nothing blocks any more, the round the
     * block held back runs once, without that relay.
     */
    private suspend fun onAuthRejected(r: NostrEngine.AuthRejection) {
        if (r.relay !in _state.value.authFailed) return
        markRejected(r.relay, r.message)
        if (!_state.value.authBlocked) bestEffort { sync(interactive = false) }
    }

    /**
     * Spec 7.1/7.5: [relay] accepted our AUTH. It leaves the hint at once (the state comes from the
     * engine's AUTH event, not from a later error path); once no own relay refuses any more, the DM
     * subscription is renewed and the round the block held back runs once.
     */
    private suspend fun onAuthenticated(relay: String) {
        val s = _state.value
        if (relay !in s.authFailed && relay !in s.authRejected) return
        _state.update { it.copy(authFailed = it.authFailed - relay, authRejected = it.authRejected - relay) }
        if (_state.value.authBlocked) return
        // spec 7.4: parked wraps may open silently again, and the summary may be posted again
        bestEffort {
            store.resetPendingAttempts()
            store.setPendingSummaryShown(false)
        }
        resubscribe()
        bestEffort { sync(interactive = false) }
    }

    /**
     * Clears [DmSyncState.authFailed] and replaces the run's wrap subscription: a relay that still
     * refuses answers CLOSED on the new id and marks itself again. Runs as a child of the run's job
     * and non-cancellable, so a concurrent [stop] waits for it and then releases the new id.
     */
    private suspend fun resubscribe() {
        val run = synchronized(lock) { current } ?: return
        if (run.attached.isEmpty()) return
        CoroutineScope(scope.coroutineContext + run.job).launch {
            withContext(NonCancellable) {
                val old = synchronized(run.closedLock) {
                    val id = run.subId ?: return@withContext // setup has not subscribed yet
                    run.subId = null // frames from here on are held until the new id is known
                    run.early.clear()
                    id
                }
                _state.update { it.copy(authFailed = emptySet()) }
                bestEffort { repo.unsubscribe(old) }
                bestEffort {
                    val id = repo.subscribeWraps(run.attached, sinceFor(store.lastSync()))
                    val early = synchronized(run.closedLock) {
                        run.subId = id
                        run.early.filter { it.subscriptionId == id }.also { run.early.clear() }
                    }
                    // like setUp: a frame the relay's AUTH OK already overtook means the new REQ was dropped, so renew it
                    early.forEach { subscriptionRefused(it.relay, it.seq) }
                }
            }
        }.join()
    }

    private suspend fun setUp(run: Run) {
        // Once per session, before anything else: a SENDING row left by a previous process can never finish.
        if (staleFailed.compareAndSet(false, true)) bestEffort { store.failStaleSending() }
        val own = settings.relays.first()
        if (own.isEmpty()) return
        // Attach and subscribe are non-cancellable so that a concurrent [stop] always sees exactly
        // what this run holds and releases it (a lost reference would keep the relay forever).
        withContext(NonCancellable) {
            run.held = attach(own)
            run.attached = own
        }
        bestEffort { sync(interactive = false) }
        bestEffort {
            val since = sinceFor(store.lastSync())
            withContext(NonCancellable) {
                val id = repo.subscribeWraps(own, since)
                val early = synchronized(run.closedLock) {
                    run.subId = id
                    run.early.filter { it.subscriptionId == id }.also { run.early.clear() }
                }
                early.forEach { subscriptionRefused(it.relay, it.seq) }
            }
        }
    }

    /**
     * Until settled: publishes the stored list when the account has no kind 10050 ([OwnDmRelays.None]),
     * or adopts the published one when it differs (then [ownRelaysChanged]). [OwnDmRelays.Unknown]
     * (no completed lookup) changes nothing and is retried by the next [adoptOwnList] or interactive
     * [sync], and so is a publish that no relay accepted. An empty published list is neither adopted
     * (it would leave no inbox relay) nor overwritten. Settled is persisted ([DmSettingsStore.listAdopted]),
     * so a later process does not look the list up again for the same account.
     */
    private suspend fun ensureOwnList() {
        val changed = ownListMutex.withLock {
            if (ownListSettled) return
            if (settings.listAdopted.first()) {
                ownListSettled = true
                return
            }
            val stored = settings.relays.first()
            var changed = false
            when (val published = relays.ownDmRelays()) {
                OwnDmRelays.Unknown -> return
                OwnDmRelays.None -> if (stored.isNotEmpty() && !publish.dmRelayList(stored).sentToAny) return
                is OwnDmRelays.Found ->
                    if (published.relays.isNotEmpty() && published.relays.toSet() != stored.toSet()) {
                        settings.setRelays(published.relays)
                        changed = true
                    }
            }
            settings.setListAdopted(true)
            ownListSettled = true
            changed
        }
        if (changed) ownRelaysChanged()
    }

    private fun sinceFor(lastSync: Long): Long =
        if (lastSync == 0L) now() - 30 * DAY else maxOf(0L, lastSync - 2 * DAY)

    /**
     * One fetch + unwrap round (worker and pull-to-refresh). [interactive] = may prompt the signer.
     * Returns the NEW incoming messages stored by this round (for notifications). lastSync moves
     * only after a completed fetch; a fetch that throws, times out or is refused by an own relay
     * leaves it, so the next round fetches the same window again.
     */
    suspend fun sync(interactive: Boolean): List<DmMessage> {
        if (interactive) {
            bestEffort { ensureOwnList() }
            // authFailed reflects the live DM subscription only (this round's fetch uses another id):
            // renewing that subscription is what lets a relay that now accepts drop out of the hint.
            // a relay that rejected our AUTH gets a new chance through the renewed subscription
            if (_state.value.authFailed.isNotEmpty() || _state.value.authRejected.isNotEmpty()) resubscribe()
        }
        // spec 7.1: the 30-day window is fetched once AUTH works again, not every round while it is refused
        if (_state.value.authBlocked && store.lastSync() == 0L) return emptyList()
        if (activeSyncs.incrementAndGet() == 1) _state.update { it.copy(syncing = true) }
        try {
            val round = generation.get()
            val own = settings.relays.first()
            // a relay that refuses our AUTH itself answers every fetch with CLOSED: asking it only keeps the round incomplete
            val rejected = _state.value.authRejected.keys
            val asked = own.filter { it.trim().trimEnd('/') !in rejected }
            val startedAt = now()
            val fetch = repo.fetchWraps(asked, sinceFor(store.lastSync()))
            // only own inbox relays count (a foreign relay's refusal neither blocks nor holds lastSync)
            val ownNormalised = own.map { it.trim().trimEnd('/') }.toSet()
            // each with its CLOSED's sequence: an AUTH OK that landed after the refusal already healed it
            fetch.authRefused.filterKeys { it.trimEnd('/') in ownNormalised }.forEach { (relay, seq) -> markAuthFailed(relay, seq) }
            val fresh = fetch.wraps.mapNotNull { handle(it, interactive) }.filter { it.isNew && !it.message.outgoing }.map { it.message }
            // spec 7.2: the list changed while this round ran; its window belonged to the old relays
            // The round's start time, not its end: a wrap stored while this round ran is inside the next window.
            val stale = syncMark.withLock {
                if (generation.get() != round) return@withLock true
                if (fetch.completed && !_state.value.authBlocked) store.setLastSync(startedAt)
                false
            }
            return if (stale) emptyList() else fresh
        } finally {
            if (activeSyncs.decrementAndGet() == 0) _state.update { it.copy(syncing = false) }
        }
    }

    /** Tries the parked wraps interactively (tab opened). Returns how many were unwrapped. */
    suspend fun drainPending(): Int {
        var unwrapped = 0
        for (id in store.pendingWraps()) {
            val wrap = try {
                loadWrap(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue // unreadable right now; stays parked
            }
            if (wrap == null || store.hasWrap(id) || store.isDropped(id)) {
                store.removePendingWrap(id)
                continue
            }
            if (handle(wrap, interactive = true) != null) unwrapped++
        }
        return unwrapped
    }

    private class Handled(val message: DmMessage, val isNew: Boolean)

    /** Null when the wrap was already stored or dropped, or could not be unwrapped. */
    private suspend fun handle(wrap: Event, interactive: Boolean): Handled? = handling.withLock {
        val wrapId = wrap.id().toHex()
        // Stored, or known never to yield a message: not decrypted again (spec §3.2).
        if (store.hasWrap(wrapId) || store.isDropped(wrapId)) return@withLock null
        // spec 7.4: after SILENT_ATTEMPTS silent failures a parked wrap waits for a user action or a healed AUTH
        if (!interactive && store.pendingAttempts(wrapId) >= SILENT_ATTEMPTS) return@withLock null
        val dm = when (val outcome = repo.unwrap(wrap, silent = !interactive)) {
            is Unwrap.Ok -> outcome.message
            Unwrap.Locked -> {
                // Silent: park it for [drainPending]; a wrap parked before keeps its first receipt
                // time. Interactive: the signer said no; the next silent round parks it again.
                if (interactive) store.removePendingWrap(wrapId) else store.addPendingWrap(wrapId, now())
                return@withLock null
            }
            Unwrap.Rejected -> {
                // Not a message for us (invalid rumor, forged id, not our key): never "waiting for Amber".
                store.markDropped(wrapId, now(), DropReason.REJECTED)
                store.removePendingWrap(wrapId)
                return@withLock null
            }
        }
        if (dm.rumorId in superseded) {
            store.markDropped(wrapId, now(), DropReason.SUPERSEDED)
            store.removePendingWrap(wrapId)
            return@withLock null
        }
        val isNew = store.message(dm.rumorId) == null
        val receivedAt = now()
        val message = DmMessage(
            // A sender controls created_at: one dated far ahead would stay the newest and unread
            // (read markers are capped at now) for good. At most a day ahead of its receipt.
            rumorId = dm.rumorId, peer = dm.peer, outgoing = dm.outgoing, content = dm.content, createdAt = minOf(dm.createdAt, receivedAt + DAY),
            receivedAt = receivedAt, status = if (dm.outgoing) DmStatus.SENT else DmStatus.RECEIVED, wrapId = dm.wrapId,
        )
        store.upsert(message)
        store.removePendingWrap(wrapId)
        Handled(message, isNew)
    }

    /**
     * Stores SENDING at once, wraps and sends, then SENT or FAILED. The work runs on DmSync's own
     * scope and the caller only awaits it: a caller that goes away (the chat closed) does not
     * cancel the delivery.
     */
    suspend fun send(peer: String, text: String): DmStatus = scope.async {
        val tempId = "$PENDING_PREFIX${UUID.randomUUID()}"
        val at = now()
        store.upsert(DmMessage(tempId, peer, outgoing = true, content = text, createdAt = at, receivedAt = at, status = DmStatus.SENDING, wrapId = null))
        deliver(tempId, peer, text)
    }.await()

    /**
     * Sends a FAILED own message again (a new rumor, so the row moves to the new id). Other rows are
     * left as they are. Like [send], the delivery outlives a cancelled caller.
     */
    suspend fun retry(rumorId: String): DmStatus = scope.async {
        val m = store.message(rumorId) ?: return@async DmStatus.FAILED
        if (!m.outgoing || m.status != DmStatus.FAILED) return@async m.status
        store.setStatus(rumorId, DmStatus.SENDING)
        // The retry is a new rumor; the old one's self-copy may still arrive and must not revive it.
        superseded += rumorId
        deliver(rumorId, m.peer, m.content)
    }.await()

    private suspend fun deliver(rowId: String, peer: String, text: String): DmStatus {
        try {
            val result = repo.send(peer, text, relays.dmRelays(peer), settings.relays.first())
            val status = if (result.sentToPeer) DmStatus.SENT else DmStatus.FAILED
            withContext(NonCancellable) { store.replaceId(rowId, result.rumorId, status) }
            // Our verdict stands even when a self-copy arrived first: it proves nothing about the peer wrap.
            return status
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { store.setStatus(rowId, DmStatus.FAILED) } }
            throw e
        } catch (e: Exception) {
            store.setStatus(rowId, DmStatus.FAILED)
            return DmStatus.FAILED
        }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // relay or signer trouble: the next start/sync tries again
        }
    }

    companion object {
        private const val DAY = 86_400L
        const val SILENT_ATTEMPTS = 3
        private const val EARLY_CLOSED_MAX = 32
        const val PENDING_PREFIX = "pending-"
    }
}
