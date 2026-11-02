package com.monostr.nostr.repo

import com.monostr.nostr.Nip44UnsupportedException
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.PublishResult
import com.monostr.nostr.Signer
import com.monostr.nostr.StartupGate
import com.monostr.nostr.SigningRejectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.time.Duration

/** Ids of a private NIP-51 list (private + public entries of its tag), whether it may be written, whether load ran. */
data class ListState(
    val ids: List<String> = emptyList(),
    val writable: Boolean = false,
    val loaded: Boolean = false,
    /** Values of the list's second tag (bookmarks: `a` coordinates), private + public; empty for lists without one. */
    val extraIds: List<String> = emptyList(),
    /** Set by the list itself; null (hand-built states) = [ids] then [extraIds]. */
    private val order: List<Pair<String, String>>? = null,
) {
    /** Both tags' entries (tag to value) in list order: private ones newest first, then public ones. */
    val ordered: List<Pair<String, String>> get() = order ?: (ids.map { "e" to it } + extraIds.map { "a" to it })
}

sealed class ListOutcome {
    data object Ok : ListOutcome()
    data object PublishFailed : ListOutcome()
    data object Unsupported : ListOutcome()
    data object Rejected : ListOutcome()
    /** The list could not be loaded yet (no local copy and the relay fetch failed or there are no relays): retry later. */
    data object NotLoaded : ListOutcome()
}

/**
 * One replaceable NIP-51 list of the user ([kind]) whose entries of [tag] this app edits: public
 * ones in the event's tags, private ones NIP-44-encrypted to the user in `content`. Everything else
 * in the list (other tags, other apps' private entries) is preserved verbatim. Bookmarks (kind 10003,
 * `e`) and the mute list (kind 10000, `p`) are the two instances; see their repositories for the
 * load/toggle semantics, which live here unchanged from the bookmarks' original implementation.
 */
class NostrPrivateList(
    private val engine: NostrEngine,
    private val signer: Signer,
    private val kind: Int,
    private val tag: String,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val send: suspend (EventBuilder) -> PublishResult = { engine.signAndPublish(it) },
    private val fetchTimeout: Duration = Duration.ofSeconds(8),
    private val refreshTimeout: Duration = Duration.ofSeconds(3),
    private val watch: CoroutineScope? = null,
    /**
     * Called with every new [state] (from each emit and from [clear]): a hook for a wrapper that
     * derives its own view (the mute repository keeps a set of pubkeys). It runs inside the
     * list's mutex, so it must be cheap and must not throw.
     */
    private val onState: (ListState) -> Unit = {},
    /** Spec perf2 A3: the background refresh of a stored list waits until the home feed's first page is in; the inline fetch without a local copy does not. */
    private val gate: StartupGate = StartupGate.OPEN,
    /** A second tag whose entries are edited too, inside the encrypted part only when added by this app (bookmarks: `a` for articles). */
    private val extraTag: String? = null,
    /**
     * A write waits this long after the tap that started it, so a quick series of taps goes out as one list: one event per
     * tap got the user rate-limited and then banned by relay.damus.io (Pixel 2026-11-02). The screen shows each tap at once.
     */
    private val writeDelay: Duration = Duration.ofSeconds(2),
) {
    private val _state = MutableStateFlow(ListState())
    val state: StateFlow<ListState> = _state.asStateFlow()
    private val mutex = Mutex()
    private val privateTags = PrivateTags(signer)

    /** The parts of the list we preserve verbatim; only privateEntries is ours to edit. */
    private var publicTags: List<List<String>> = emptyList()
    private var privateOther: List<List<String>> = emptyList()
    /** Our tags' private entries as written (hints, markers and all); only [0] and [1] are used to identify one. */
    private var privateEntries: List<List<String>> = emptyList()
    private fun isMine(t: String) = t == tag || (extraTag != null && t == extraTag)

    /**
     * `created_at` of the last known list (loaded or published). The list's kind is replaceable
     * (NIP-01): relays and the local database keep only the highest `created_at`, tie-broken in a
     * way we do not control, so every publish must use a strictly higher timestamp than what came
     * before it - never just [now] as-is, which can tie or even trail an existing event.
     */
    private var lastCreatedAt: Long = 0

    /**
     * `created_at` of the list our in-memory state actually reflects: set only by [adopt] (a list
     * we read) and after a successful publish. Unlike [lastCreatedAt] a failed write never bumps
     * it, so [refreshLocked] still adopts a remote list that is newer than what we read even if
     * it is older than our failed attempt.
     */
    private var snapshotCreatedAt: Long = 0

    private fun filter() = Filter().kind(Kind(kind.toUShort())).author(PublicKey.parse(signer.pubkey)).limit(1u)

    /** Set on [Decrypt.NO_SILENT_ANSWER]; skips the silent signer on further implicit loads until an interactive call. */
    private var silentTried = false

    /** Whether the relays were asked for a newer copy of an existing local list this session (in the background, see [refreshLater]). */
    private var refreshed = false

    /** Loads once (semantics as documented on [BookmarksRepository.ensureLoaded]), then watches for newer lists. */
    suspend fun ensureLoaded(interactive: Boolean) {
        mutex.withLock { loadLocked(interactive) }
        startWatching()
    }

    private enum class Load { ALREADY, LOADED, LOCAL, NOT_LOADED, REJECTED }

    /** How [adopt] fared with a list's private part. */
    private enum class Decrypt { OK, NO_SILENT_ANSWER, REJECTED, UNREADABLE }

    private suspend fun loadLocked(interactive: Boolean): Load {
        if (interactive) silentTried = false
        if (_state.value.loaded) return Load.ALREADY
        if (!interactive && silentTried) return Load.NOT_LOADED
        var event = engine.query(filter()).firstOrNull()
        var fetched = false
        if (event == null) {
            // No local copy: a fetch that throws, or one that quietly reaches no relay at all
            // (a dead/unconfigured relay list returns an empty result without throwing - see
            // NostrEngine.fetch), must not be mistaken for "the user has no list": that would
            // make the very next toggle overwrite whatever is actually on the relays. Only a
            // fetch that some normal relay actually answered, even with nothing, proves there
            // really is no list yet - a connected search relay never received the fetch.
            try {
                engine.fetch(filter(), fetchTimeout)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(ListState(ids = emptyList(), writable = false, loaded = false))
                return Load.NOT_LOADED
            }
            fetched = true
            event = engine.query(filter()).firstOrNull()
            if (event == null && engine.connectedNormalRelayUrls().isEmpty()) {
                emit(ListState(ids = emptyList(), writable = false, loaded = false))
                return Load.NOT_LOADED
            }
        } else if (!refreshed) {
            // spec perf 3.3: the local copy is taken at once (the feed's first page already leaves the muted out);
            // the relays' copy is asked for behind it and taken over when newer. Only the first load per session asks,
            // the subscription keeps it fresh after.
            refreshed = true
            refreshLater()
        }
        if (event == null) {
            publish(writable = true, loaded = true)
            return Load.LOADED
        }
        val done = if (fetched) Load.LOADED else Load.LOCAL
        return when (adopt(event, interactive)) {
            Decrypt.OK -> { publish(writable = true, loaded = true); done }
            Decrypt.UNREADABLE -> { publish(writable = false, loaded = true); done }
            Decrypt.NO_SILENT_ANSWER -> { silentTried = true; publish(writable = false, loaded = false); Load.NOT_LOADED }
            Decrypt.REJECTED -> { publish(writable = false, loaded = false); Load.REJECTED }
        }
    }

    /**
     * The relays' copy of the list, off the caller's path; a newer one is adopted through [onListEvent] like one
     * from the subscription. Nothing to ask without relays.
     */
    private fun refreshLater() {
        (watch ?: engine.backgroundScope).launch {
            gate.await()
            if (engine.relayUrls().isEmpty()) return@launch
            quietly { engine.fetch(filter(), fetchTimeout) }
            askedAt = System.nanoTime()
            val newest = try {
                engine.query(filter()).firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@launch
            onListEvent(newest)
        }
    }

    @Volatile private var watching = false
    private val watchMutex = Mutex()

    /**
     * Subscribes to our own list once per session so a newer list from another device is adopted without polling.
     * [watching] is set only after the subscription succeeded, so a failed subscribe is retried on the next [ensureLoaded].
     */
    private suspend fun startWatching(): Unit = watchMutex.withLock {
        if (watching) return@withLock
        val scope = watch ?: return@withLock
        runCatching { engine.subscribe(filter()) }.getOrNull() ?: return@withLock
        // the subscription's first answer is the relays' newest list, like an ask
        askedAt = System.nanoTime()
        watching = true
        scope.launch {
            engine.incoming.collect { inc ->
                if (inc.json.kind == kind && inc.json.pubkey == signer.pubkey) onListEvent(inc.event)
            }
        }
    }

    /**
     * A list event of ours from a relay: adopted when newer than what we show (spec 7.3). A list
     * we cannot read silently is ignored until the next interactive load: [adopt] overwrites the
     * in-memory list before the decrypt outcome is known, so anything short of a readable result
     * must roll that back - otherwise the next [toggle] would publish the wiped list, and a
     * silently-unreadable newer list would look like an unchanged, still-loaded one.
     */
    internal suspend fun onListEvent(event: Event) = mutex.withLock {
        if (event.createdAt().asSecs().toLong() <= snapshotCreatedAt) return@withLock
        val prevPublicTags = publicTags
        val prevPrivateOther = privateOther
        val prevPrivateEntries = privateEntries
        val prevSnapshotCreatedAt = snapshotCreatedAt
        val prevLastCreatedAt = lastCreatedAt
        when (adopt(event, interactive = false)) {
            Decrypt.OK -> publish(writable = true, loaded = true)
            Decrypt.UNREADABLE -> publish(writable = false, loaded = true)
            else -> {
                // silent decrypt impossible: keep the current list, the next interactive load picks it up
                publicTags = prevPublicTags; privateOther = prevPrivateOther; privateEntries = prevPrivateEntries
                snapshotCreatedAt = prevSnapshotCreatedAt; lastCreatedAt = prevLastCreatedAt
            }
        }
    }

    /**
     * Takes [event] as the current list: its public tags always, its private part when it
     * decrypts. Anything short of [Decrypt.OK] clears the private part, so a stale private list
     * is never mixed with newer public tags.
     */
    private suspend fun adopt(event: Event, interactive: Boolean): Decrypt {
        val createdAt = event.createdAt().asSecs().toLong()
        snapshotCreatedAt = createdAt
        lastCreatedAt = maxOf(lastCreatedAt, createdAt)
        publicTags = event.tags().toVec().map { it.asVec() }
        privateEntries = emptyList(); privateOther = emptyList()
        val content = event.content()
        if (content.isBlank()) return Decrypt.OK
        val tags = when (val r = privateTags.decrypt(content, interactive)) {
            is Decrypted.Ok -> r.value
            Decrypted.NoSilentAnswer -> return Decrypt.NO_SILENT_ANSWER
            Decrypted.Rejected -> return Decrypt.REJECTED
            Decrypted.Unreadable -> return Decrypt.UNREADABLE
        }
        privateEntries = tags.filter { it.size >= 2 && isMine(it[0]) }
        privateOther = tags.filter { it.size < 2 || !isMine(it[0]) }
        return Decrypt.OK
    }

    /** One tap: put [id] (of tag [t]) in or take it out; done once [result] is set. */
    private class Op(val t: String, val id: String, val remove: Boolean) {
        val result = CompletableDeferred<ListOutcome>()
    }

    /**
     * Taps applied to the screen but not yet confirmed by a relay, oldest first (written under [mutex], replaced, never
     * mutated, so [clear] can drop it from outside). What the user sees is the list read last ([publicTags], [privateEntries])
     * with these applied on top, so a newer list from another device keeps them.
     */
    @Volatile private var pending: List<Op> = emptyList()
    /** The taps of the write that is out at the relays right now (under [mutex]). */
    @Volatile private var inFlight: List<Op> = emptyList()
    /** One write at a time; taps that come in meanwhile wait in [pending] and go out together as the next list. */
    private val writeMutex = Mutex()
    /** Bumped by [clear] (sign-out, account switch): a write that started before must not bring its list back. */
    @Volatile private var generation = 0
    /** [System.nanoTime] of the last time the relays were asked for the list; 0 = never this session. */
    @Volatile private var askedAt = 0L

    private fun applied(ops: List<Op>): Pair<List<List<String>>, List<List<String>>> {
        var pub = publicTags; var priv = privateEntries
        for (op in ops) {
            if (op.remove) {
                priv = priv.filterNot { it[0] == op.t && it[1] == op.id }
                pub = pub.filterNot { it.size >= 2 && it[0] == op.t && it[1] == op.id }
            } else if ((priv + pub.filter { it.size >= 2 && isMine(it[0]) }).none { it[0] == op.t && it[1] == op.id }) {
                priv = listOf(listOf(op.t, op.id)) + priv
            }
        }
        return pub to priv
    }

    /**
     * Flips [id]: takes it out of both parts when the list the user sees holds it, else adds it
     * privately. Semantics as documented on [BookmarksRepository.toggle].
     */
    suspend fun toggle(id: String, entryTag: String = tag): ListOutcome = change(entryTag, id, present = null)

    /** Puts [id] into the list ([present]) or takes it out of both parts; idempotent. Semantics as documented on [BookmarksRepository.toggle]. */
    suspend fun set(id: String, present: Boolean, entryTag: String = tag): ListOutcome = change(entryTag, id, present)

    /**
     * The screen flips at once (only the in-memory part runs under [mutex]); the relays get the list afterwards in [write].
     * A tap while an earlier write still waits for its relay OK no longer waits for it to show (Pixel 2026-11-02: a
     * bookmark set, taken out and another one set took seconds, each tap behind the previous one's 3 s refresh + publish).
     */
    private suspend fun change(t: String, id: String, present: Boolean?): ListOutcome {
        var load = Load.ALREADY
        var own = true
        val op = mutex.withLock {
            load = loadLocked(interactive = true)
            if (load == Load.REJECTED) return ListOutcome.Rejected
            if (!_state.value.loaded) return ListOutcome.NotLoaded
            if (!_state.value.writable) return ListOutcome.Unsupported
            // The direction comes from the list the user sees (read after the load, inside the lock).
            val shown = applied(pending)
            val wanted = present ?: (shown.first + shown.second).none { it.size >= 2 && it[0] == t && it[1] == id }
            val op = Op(t, id, remove = !wanted)
            if (applied(pending + op) == shown) {
                // Nothing changed (muting a muted account, unmuting one that is not): no signature, no publish. When an
                // unconfirmed tap put the list in this state, this call fares as that tap's write does.
                val earlier = pending.lastOrNull { it.t == t && it.id == id } ?: return ListOutcome.Ok
                own = false
                earlier
            } else {
                pending = pending + op
                publish(writable = true, loaded = true)
                if (PerfTrace.enabled) PerfTrace.mark("list $kind tap shown, ${pending.size} pending")
                op
            }
        }
        return try {
            write(op, load)
        } catch (e: CancellationException) {
            // the caller left (e.g. back from a thread during the refresh or before the relay OK): a change that
            // reached no relay must not stay in memory; one inside another caller's write is settled by that write
            if (own) withContext(NonCancellable) { mutex.withLock { dropUnsent(op) } }
            throw e
        } catch (e: Exception) {
            // the local read before the send failed: this tap is taken back, the others go out with their own calls
            if (own) withContext(NonCancellable) { mutex.withLock { dropUnsent(op) } }
            if (op.result.isCompleted) op.result.await() else ListOutcome.PublishFailed
        }
    }

    /** Takes [op] back unless a write already settled it or has it out at the relays; the caller holds [mutex]. */
    private fun dropUnsent(op: Op) {
        if (op.result.isCompleted || op in inFlight) return
        pending = pending - op
        op.result.complete(ListOutcome.PublishFailed)
        publish(writable = true, loaded = true)
    }

    /**
     * Sends the list with every pending tap (one write at a time) unless an earlier write already took [op] along.
     * Before writing a stored or long-loaded list the relays are asked for a newer copy, except within [ASK_AGAIN_NANOS]
     * of the last ask while the live subscription runs ([watching]): a newer list then arrives through [onListEvent], and
     * only the local copy is read.
     */
    private suspend fun write(op: Op, load: Load): ListOutcome = writeMutex.withLock {
        if (op.result.isCompleted) return@withLock op.result.await()
        // taps until the end of the delay join this write
        if (!writeDelay.isZero) delay(writeDelay.toMillis())
        val started = System.nanoTime()
        // A load that just fetched has the relays' copy already; a local-only load or an older snapshot needs the refresh.
        val recent = watching && askedAt != 0L && started - askedAt < ASK_AGAIN_NANOS
        if ((load == Load.ALREADY || load == Load.LOCAL) && !recent) {
            quietly { engine.fetch(filter(), refreshTimeout) }
            askedAt = System.nanoTime()
        }
        class Out(val batch: List<Op>, val pub: List<List<String>>, val other: List<List<String>>, val priv: List<List<String>>, val ts: Long, val generation: Int)
        val out = mutex.withLock {
            if (op.result.isCompleted) return@withLock null
            val failed = refreshLocked()
            if (failed != null) {
                pending.forEach { it.result.complete(failed) }
                pending = emptyList()
                publish(writable = failed != ListOutcome.Unsupported, loaded = failed != ListOutcome.Rejected)
                return@withLock null
            }
            val batch = pending
            val (pub, priv) = applied(batch)
            if (pub == publicTags && priv == privateEntries) {
                // the taps cancel out, or a newer list already holds them: nothing to send
                settle(batch, ListOutcome.Ok, writable = true)
                return@withLock null
            }
            inFlight = batch
            val ts = maxOf(now(), lastCreatedAt + 1)
            lastCreatedAt = ts
            Out(batch, pub, privateOther, priv, ts, generation)
        } ?: return@withLock op.result.await()
        val outcome = try {
            val t0 = System.nanoTime()
            val content = privateTags.encrypt(out.other + out.priv)
            val t1 = System.nanoTime()
            val builder = EventBuilder(Kind(kind.toUShort()), content).tags(out.pub.map { Tag.parse(it) }).customCreatedAt(Timestamp.fromSecs(out.ts.toULong()))
            val r = send(builder)
            if (PerfTrace.enabled) PerfTrace.mark("list $kind before send ${(t0 - started) / 1_000_000}ms, encrypt ${(t1 - t0) / 1_000_000}ms, send ${(System.nanoTime() - t1) / 1_000_000}ms ok=${r.successRelays} failed=${r.failedRelays}")
            if (r.sentToAny) ListOutcome.Ok else ListOutcome.PublishFailed
        } catch (e: CancellationException) {
            withContext(NonCancellable) { mutex.withLock { settle(out.batch, ListOutcome.PublishFailed, writable = true, out.generation) } }
            throw e
        } catch (e: SigningRejectedException) {
            ListOutcome.Rejected
        } catch (e: Nip44UnsupportedException) {
            ListOutcome.Unsupported
        } catch (e: Exception) {
            ListOutcome.PublishFailed
        }
        // settled even when the caller is cancelled meanwhile: its tap is out of reach of [dropUnsent]
        withContext(NonCancellable) {
            mutex.withLock {
                // our own list may already be back through the subscription; a newer one from another device wins
                if (outcome == ListOutcome.Ok && out.generation == generation && out.ts > snapshotCreatedAt) {
                    publicTags = out.pub; privateOther = out.other; privateEntries = out.priv; snapshotCreatedAt = out.ts
                }
                settle(out.batch, outcome, writable = outcome != ListOutcome.Unsupported, out.generation)
            }
        }
        if (PerfTrace.enabled) PerfTrace.mark("list $kind write ${(System.nanoTime() - started) / 1_000_000}ms, ${out.batch.size} taps, $outcome")
        op.result.await()
    }

    /**
     * Ends a write: its taps leave [pending] (a failed one is thereby taken back) and get [outcome]; the caller holds [mutex].
     * After a [clear] since the write began ([gen] is older) only the waiting callers are answered.
     */
    private fun settle(batch: List<Op>, outcome: ListOutcome, writable: Boolean, gen: Int = generation) {
        if (gen != generation) { batch.forEach { it.result.complete(ListOutcome.NotLoaded) }; return }
        val sent = batch.toSet()
        pending = pending.filterNot { it in sent }
        inFlight = emptyList()
        batch.forEach { it.result.complete(outcome) }
        publish(writable = writable, loaded = true)
    }

    /**
     * Before a write: picks up a newer list another device published since our read (the newest local copy, which a
     * refresh fetch or the subscription keeps current). Null to go on, or the outcome that stops the write; the pending
     * taps stay on top of an adopted list. A list that cannot be read leaves the one we had.
     */
    private suspend fun refreshLocked(): ListOutcome? {
        val newest = engine.query(filter()).firstOrNull() ?: return null
        if (newest.createdAt().asSecs().toLong() <= snapshotCreatedAt) return null
        val pub = publicTags; val other = privateOther; val priv = privateEntries
        val failed = when (adopt(newest, interactive = true)) {
            Decrypt.OK -> return null
            Decrypt.UNREADABLE -> ListOutcome.Unsupported
            Decrypt.NO_SILENT_ANSWER, Decrypt.REJECTED -> ListOutcome.Rejected
        }
        publicTags = pub; privateOther = other; privateEntries = priv
        return failed
    }

    fun clear() {
        generation++
        publicTags = emptyList(); privateOther = emptyList(); privateEntries = emptyList(); lastCreatedAt = 0; snapshotCreatedAt = 0
        val dropped = pending
        pending = emptyList(); inFlight = emptyList(); askedAt = 0L
        dropped.forEach { it.result.complete(ListOutcome.NotLoaded) }
        emit(ListState())
    }

    /** Emits the list the user sees: the one read last with the [pending] taps on top. */
    private fun publish(writable: Boolean, loaded: Boolean) {
        val (pub, priv) = applied(pending)
        val all = (priv + pub.filter { it.size >= 2 && isMine(it[0]) }).map { it[0] to it[1] }.distinct()
        emit(ListState(
            ids = all.filter { it.first == tag }.map { it.second },
            writable = writable, loaded = loaded,
            extraIds = all.filter { it.first != tag }.map { it.second },
            order = all,
        ))
    }

    private fun emit(state: ListState) {
        _state.value = state
        onState(state)
    }

    private companion object {
        /** A write asks the relays for a newer list at most this often while the live subscription runs. */
        const val ASK_AGAIN_NANOS = 5 * 60 * 1_000_000_000L
    }
}
