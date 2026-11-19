package com.monostr.app.ui.tips

import kotlin.random.Random
import com.monostr.tips.PaymentInfo
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import com.monostr.app.net.SendGate
import com.monostr.nostr.StartupGate
import com.monostr.nostr.PerfTrace
import com.monostr.app.data.PendingTip
import com.monostr.app.data.PendingTipStore
import com.monostr.app.data.PendingTips
import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.feed.target
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.NostrTipsRepository
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.PublishRepository
import com.monostr.nostr.repo.TipSummary
import com.monostr.nostr.repo.TipsRepository
import com.monostr.nostr.repo.WatcherFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sends the intent of [tip] to the watcher's relays unless one has it, and notes it when one takes it; true once one
 * has it. An anonymous intent travels over its own connection, and only until accepted: every send is another
 * connection from this device. A public one is sent to relays the user's own list may not contain. Nothing goes out unless
 * [send] allows it; a tip sent under Tor travels Tor only, even when the mode changes right after this look (a public one goes over
 * its session's engine, whose route never changes).
 */
internal suspend fun deliverToWatcher(tips: TipsRepository, pending: PendingTipStore, tip: PendingTip, send: SendGate): Boolean {
    if (tip.isDelivered) return true
    if (!send.mayResend(tip)) return false
    val anonymous = tip.anonEvent
    val accepted = when {
        anonymous != null -> tips.resendAnonymousIntent(anonymous, tip.anonRelays, torOnly = tip.underTor).sentToAny
        tip.watcherRelays.isEmpty() -> false
        else -> tips.resendIntentToWatcher(tip.intentId, tip.watcherRelays).reached(tip.watcherRelays)
    }
    if (accepted) pending.markDelivered(tip.intentId)
    return accepted
}

/** [name] is null for an anonymous tip; the UI shows "Anonymous" in its language. */
data class TipperUi(val name: String?, val amount: Long?, val comment: String, val createdAt: Long)

/**
 * Receipt sums and pending markers for the notes a screen shows (spec 5.5 step 5, 5.6): one
 * batched receipt subscription for the tracked notes, pending tips from the store, and a
 * settle step that drops a pending tip once a receipt names its intent. Intents stored while
 * offline are re-sent once per [start]. Profile tips are settled by `ProfileTipWatch`, not here.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TipDecorations(
    private val tips: TipsRepository,
    private val pending: PendingTipStore,
    private val profiles: ProfileRepository,
    private val publish: PublishRepository,
    private val scope: CoroutineScope,
    /** The logged-in user: their own notes get no tip action. */
    private val selfPubkey: String? = null,
    /** Spec perf2 A3: stored intents are re-sent after the home feed's first page is in. */
    private val gate: StartupGate = StartupGate.OPEN,
    /** Whether anything may leave now; open only in tests. */
    private val sendGate: SendGate = SendGate.OPEN,
    /** See [askWatchersForOwnNoteTips]; null = off (tests that do not care), the session passes [OWN_RECEIPTS_EVERY_MS]. */
    private val ownReceiptsEveryMs: Long? = null,
    /** Backlog 97: the session's [WatcherReceipts]; null = the note authors' watcher relays are never asked. */
    private val watcherReceipts: WatcherReceipts? = null,
    /** The «author relays» setting, read at each use (a privacy setting: never a seeded value). */
    private val authorRelaysEnabled: suspend () -> Boolean = { false },
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val tracked = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _summaries = MutableStateFlow<Map<String, TipSummary>>(emptyMap())
    /** Summaries of the [track]ed set and of every group [add] watches; [summaries] is their union. */
    private val trackedSummaries = MutableStateFlow<Map<String, TipSummary>>(emptyMap())
    private val addedSummaries = MutableStateFlow<Map<String, TipSummary>>(emptyMap())
    /** Targets [add] was given (id to author) in order, cut into [NostrTipsRepository.MAX_NOTES] slices; guarded by [groups]. */
    private val added = LinkedHashMap<String, String>()
    /** The running receipt subscription per live slice index of [added]; at most [MAX_LIVE_GROUPS]. */
    private val groups = HashMap<Int, Group>()
    private val _pendingNotes = MutableStateFlow<Set<String>>(emptySet())
    /** Bumped when [add] got a new target, so the watcher relays are asked about it. */
    private val addedTick = MutableStateFlow(0L)

    /** Valid receipts per target note id. */
    val summaries: StateFlow<Map<String, TipSummary>> = _summaries.asStateFlow()
    /** Target note ids with an active pending tip. */
    val pendingNotes: StateFlow<Set<String>> = _pendingNotes.asStateFlow()
    /** Fires whenever [summaries] or [pendingNotes] change; controllers re-decorate on it. */
    val changes: Flow<Unit> = combine(_summaries, _pendingNotes) { _, _ -> }

    fun start() {
        scope.launch { runCatching { pending.prune(now()) } }
        scope.launch { resendPending() }
        ownReceiptsEveryMs?.let { every -> scope.launch { askWatchersForOwnNoteTips(every) } }
        watcherReceipts?.let { w ->
            scope.launch {
                gate.await()
                // a running round is never cancelled (its notes are marked asked): the next one takes the latest set
                // also when anything may leave again (Tor connected); the setting is read in the round itself
                combine(tracked, addedTick, sendGate.canSend) { t, _, _ -> t }
                    .debounce(WATCHER_DEBOUNCE_MS)
                    .conflate()
                    .collect { t -> watcherRound(w, t + liveAddedTargets()) }
            }
        }
        scope.launch {
            tracked
                .flatMapLatest { notes -> receiptsOf(notes) }
                .collect { trackedSummaries.value = it }
        }
        scope.launch {
            combine(trackedSummaries, addedSummaries) { a, b -> a + b }.collect { s ->
                _summaries.value = s
                settle(s)
            }
        }
        scope.launch {
            pending.pending
                .catch { /* store read failed: keep the last markers */ }
                // a tip to a profile has no note to mark
                .collect { all -> _pendingNotes.value = PendingTips.waiting(all, now()).mapNotNull { it.noteId }.toSet() }
        }
    }

    /** Replaces the tracked set with the targets of [notes] (a repost counts for its original). */
    fun track(notes: List<Note>) {
        val map = notes.associate { n -> (n.repostOf ?: n).let { it.id to it.author } }
        if (map != tracked.value) tracked.value = map
    }

    /**
     * Spec perf2 A4: the stored sums of [notes] at once; the relay part (fetch + subscription) only after
     * [RECEIPTS_DEBOUNCE_MS] without a newer set, so the 4-8 set changes of a cold start make one or two REQs.
     */
    private fun receiptsOf(notes: Map<String, String>): Flow<Map<String, TipSummary>> = flow {
        emit(tips.localReceipts(notes))
        delay(RECEIPTS_DEBOUNCE_MS)
        emitAll(tips.observeReceipts(notes))
    }.catch { /* relay or db error: keep the last state */ }

    /**
     * Like [track], but only adds: the targets are kept in order and watched in slices of [NostrTipsRepository.MAX_NOTES]
     * with at most [MAX_LIVE_GROUPS] live subscriptions (see [liveSlices]). A call without new targets restarts nothing
     * (spec perf 3.5: the profile's 30 s refresh must not, each restart is a relay fetch); new targets replace only the
     * slice they land in, and a slice that failed is started again.
     */
    fun add(notes: List<Note>) {
        val map = notes.associate { n -> (n.repostOf ?: n).let { it.id to it.author } }
        val starts = ArrayList<Job>()
        var grew = false
        synchronized(groups) {
            for ((id, author) in map) if (id !in added) { added[id] = author; grew = true }
            // slices only ever grow at the end, so a new id restarts the last slice alone
            val slices = added.entries.chunked(NostrTipsRepository.MAX_NOTES)
            val live = liveSlices(slices.size)
            // a slice that leaves the live set keeps its last summaries; it had its fetch while it was live
            groups.keys.filter { it !in live }.forEach { groups.remove(it)?.job?.cancel() }
            for (i in live) {
                val slice = slices[i].associate { it.key to it.value }
                val running = groups[i]
                if (running != null && running.notes == slice && !running.failed) continue
                running?.job?.cancel()
                val group = Group(slice)
                group.job = scope.launch(start = CoroutineStart.LAZY) { observe(group) }
                groups[i] = group
                starts += group.job
            }
        }
        starts.forEach { it.start() }
        if (grew) addedTick.update { it + 1 }
    }

    /** The targets of the live slices of [add]: those a screen still shows; older slices had their turn while live. */
    private fun liveAddedTargets(): Map<String, String> = synchronized(groups) {
        groups.values.fold(LinkedHashMap<String, String>()) { all, g -> all.apply { putAll(g.notes) } }
    }

    /**
     * Backlog 97: a watcher publishes a receipt only to its own relays and the recipient's write relays, so a viewer
     * whose relays are elsewhere saw no tips on a note. The [targets] whose author's stored 10037 names a watcher are
     * asked once on that watcher's relays (one one-shot fetch per watcher and relay set, no subscription), at most every
     * [WatcherReceipts.ASK_EVERY_MS]; the stored sums of the asked notes are then read again. Nothing at all with the
     * «author relays» setting off or while nothing may leave (Tor not connected).
     */
    /** One round of [askWatcherRelays]; a failure of it (e.g. the settings store) ends the round, never the collector. */
    private suspend fun watcherRound(w: WatcherReceipts, targets: Map<String, String>) {
        try {
            askWatcherRelays(w, targets)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the next tracked change starts the next round
        }
    }

    private suspend fun askWatcherRelays(w: WatcherReceipts, targets: Map<String, String>) {
        if (targets.isEmpty() || !authorRelaysEnabled() || !sendGate.canSend.value) return
        val due = w.due(targets.keys)
        if (due.isEmpty()) return
        val byGroup = LinkedHashMap<Pair<String, List<String>>, MutableList<String>>()
        for ((author, ids) in due.groupBy { targets.getValue(it) }) {
            try {
                // local only: an author without a stored 10037 costs this one look
                val info = tips.cachedPaymentInfo(author) as? PaymentInfo.Enabled
                // marked too, so the next rounds skip even the look; a 10037 that comes later waits at most ASK_EVERY_MS
                if (info == null) { w.markAsked(ids); continue }
                val relays = w.relaysOf(info)
                if (relays.isEmpty()) { w.markAsked(ids); continue }
                byGroup.getOrPut(info.watcherPubkey to relays.sorted()) { ArrayList() } += ids
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a db error: this author is looked at again with the next set
            }
        }
        for ((key, ids) in byGroup) {
            val (watcherPubkey, relays) = key
            if (!sendGate.canSend.value) return
            try {
                w.markAsked(ids)
                PerfTrace.mark("watcher-receipts relays=${relays.size} notes=${ids.size}")
                // one fetch at a time session-wide; the gate is looked at again after the wait
                val result = w.oneFetchAtATime { if (sendGate.canSend.value) tips.fetchWatcherReceipts(relays, ids, watcherPubkey) else null }
                when (result) {
                    null -> { w.markRetry(ids); return }
                    WatcherFetch.Asked -> mergeStored(ids, targets)
                    // the budget was full (user-visible lookups first): asked again soon, not in five minutes
                    WatcherFetch.NoSlot -> w.markRetry(ids)
                    WatcherFetch.OwnRelaysOnly, WatcherFetch.Failed -> {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // relay down or db error: the read-relay counts stay; asked again after ASK_EVERY_MS
            }
        }
    }

    /** The stored sums of [ids] after a fetch, into the tracked and added summaries they belong to; no live subscription needed. */
    private suspend fun mergeStored(ids: List<String>, targets: Map<String, String>) {
        val fresh = tips.localReceipts(ids.associateWith { targets.getValue(it) })
        val askedTracked = ids.filterTo(HashSet()) { it in tracked.value }
        val askedAdded = synchronized(groups) { ids.filterTo(HashSet()) { it in added } }
        if (askedTracked.isNotEmpty()) trackedSummaries.update { cur -> cur.filterKeys { it !in askedTracked } + fresh.filterKeys { it in askedTracked } }
        if (askedAdded.isNotEmpty()) addedSummaries.update { cur -> cur.filterKeys { it !in askedAdded } + fresh.filterKeys { it in askedAdded } }
    }

    /**
     * Slice indexes that keep a live receipt subscription: the first one (the profile's newest notes, the likeliest to
     * be tipped) and the [MAX_LIVE_GROUPS] - 1 latest ones (where the user scrolled to); relays cap REQs per connection.
     */
    private fun liveSlices(count: Int): Set<Int> =
        if (count == 0) emptySet() else setOf(0) + (maxOf(1, count - (MAX_LIVE_GROUPS - 1)) until count)

    private suspend fun observe(group: Group) {
        try {
            // a replaced group's first emission is the stored receipts and its fetch, so a receipt landing during the swap is not lost
            tips.observeReceipts(group.notes).collect { s ->
                addedSummaries.update { all -> all.filterKeys { it !in group.notes } + s }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // relay or db error: keep the last state; the next add() starts this slice again
            group.failed = true
        }
    }

    /** One receipt subscription of [add]; [failed] marks one that ended with an error. */
    private class Group(val notes: Map<String, String>) {
        lateinit var job: Job
        @Volatile var failed = false
    }

    fun decorate(note: NoteUi): NoteUi {
        val tips = _summaries.value[note.target.id]
        val tipped = selfPubkey != null && tips?.receipts?.any { it.senderPubkey == selfPubkey } == true
        val pendingTip = note.target.id in _pendingNotes.value
        val canTip = note.target.author != selfPubkey
        val isOwn = note.target.author == selfPubkey
        // the same object when nothing changed: a copy recomposed the card (see mapKeep)
        return if (tips == note.tips && tipped == note.tipped && pendingTip == note.pendingTip && canTip == note.canTip && isOwn == note.isOwn) note
        else note.copy(tips = tips, tipped = tipped, pendingTip = pendingTip, canTip = canTip, isOwn = isOwn)
    }

    /** Tippers of the note's target with sender names, newest first. */
    suspend fun tippers(note: Note): List<TipperUi> {
        val target = note.repostOf ?: note
        val tippers = tips.tippers(target.id, target.author)
        // one read for all senders; unknown ones are queued in the repository and show their short key in this dialog
        val byKey = profiles.getAll(tippers.mapNotNull { it.receipt.senderPubkey })
        return tippers.map { t ->
            TipperUi(t.receipt.senderPubkey?.let { byKey.getValue(it).shownName }, t.receipt.amount, t.comment, t.receipt.createdAt)
        }
    }

    /**
     * Re-publishes the still active pending intents to the user's relays; an intent sent while offline would otherwise never reach the
     * watcher. Once per [start], and every screen with notes starts its own decorations: with Tor not connected nothing goes out, and
     * the next screen opened with Tor connected sends them, through Tor, as it does today. Nothing here waits for Tor.
     */
    private suspend fun resendPending() {
        dropLeftBehind()
        gate.await()
        val active = try {
            PendingTips.active(pending.pending.first(), now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        for (tip in active) {
            if (!sendGate.mayResend(tip)) continue
            try {
                // an anonymous intent never goes to the user's relays
                if (tip.anonEvent == null) publish.resend(tip.intentId)
                deliverToWatcher(tips, pending, tip, sendGate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // still offline or the event is gone locally; the next start tries again
            }
        }
    }

    /** In Direct, tips sent under Tor that are still stored (a crash in the middle of turning Tor off, a send that ended after it) leave without going anywhere. Needs no network, so it does not wait for the first page. */
    private suspend fun dropLeftBehind() {
        try {
            if (sendGate.direct && pending.pending.first().any { it.underTor }) pending.dropUnderTor()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // they stay stored, and every sender holds them back; the next start tries again
        }
    }

    /**
     * shortwavesurfer2009 2026-11-17: the watcher publishes a receipt only to its relays and the recipient's write
     * relays, so a tipper whose relays are elsewhere never saw it and the tip stayed «Waiting for payment». While own
     * note tips wait, their watcher relays are asked directly, about once a minute: anonymous tips over a connection of
     * their own (as the profile watch does), public ones over the session. A tip sent under Tor is asked about under Tor only.
     */
    private suspend fun askWatchersForOwnNoteTips(everyMs: Long) {
        gate.await()
        pending.pending
            .catch { /* store read failed: the markers expire after 24 h */ }
            .map { all -> PendingTips.waiting(all, now()).filter { it.noteId != null && it.recipient != null && relaysOf(it).isNotEmpty() } }
            .distinctUntilChanged()
            .collectLatest { own ->
                if (own.isEmpty()) return@collectLatest
                delay(OWN_RECEIPTS_FIRST_MS)
                while (true) {
                    askOnce(own)
                    // jitter: a fixed rhythm on the watcher relays would itself be a pattern
                    delay(everyMs / 2 + Random.nextLong(everyMs))
                }
            }
    }

    private fun relaysOf(tip: PendingTip): List<String> = (tip.anonRelays + tip.watcherRelays).distinct().sorted()

    private suspend fun askOnce(own: List<PendingTip>) {
        val confirmed = HashSet<String>()
        val askable = own.filter(sendGate::mayResend)
            // an anonymous tip is asked about only while its receipt can still come (the watcher retracts after 60 min)
            .filter { it.anonEvent == null || now() - it.createdAt <= OWN_ANON_ASK_WINDOW }
        // public tips of one recipient share a question; an anonymous tip always asks alone, over its own connection,
        // so that one question never ties two anonymous tips together
        val groups = askable.groupBy { Triple(it.recipient!!, if (it.anonEvent != null) it.intentId else "", relaysOf(it)) }
        for ((key, group) in groups) {
            val (recipient, anonKey, relays) = key
            val anonymous = anonKey.isNotEmpty()
            try {
                val info = tips.cachedPaymentInfo(recipient) as? PaymentInfo.Enabled ?: continue
                val since = group.minOf { it.createdAt } - OWN_RECEIPTS_SINCE_MARGIN
                confirmed += tips.noteReceiptsOn(relays, group.mapNotNull { it.noteId }.distinct(), info.watcherPubkey, since, detached = anonymous)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // this group is asked again next round
            }
        }
        val done = own.filter { it.intentId in confirmed }.map { it.intentId }
        if (done.isEmpty()) return
        try {
            pending.settle(done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the marker stays until the next round or its 24 h expiry
        }
    }

    private suspend fun settle(summaries: Map<String, TipSummary>) {
        val confirmed = summaries.values.flatMap { it.receipts }.map { it.intentId }.toSet()
        if (confirmed.isEmpty()) return
        try {
            val done = pending.pending.first().filter { it.intentId in confirmed }.map { it.intentId }
            if (done.isNotEmpty()) pending.settle(done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the marker stays until the next receipt or its 24 h expiry
        }
    }

    companion object {
        /** Own pending note tips: the first look at the watcher relays (a receipt follows the payment by a minute or so), then every [OWN_RECEIPTS_EVERY_MS]. */
        const val OWN_RECEIPTS_FIRST_MS = 30_000L
        const val OWN_RECEIPTS_EVERY_MS = 60_000L
        /** Receipts are asked for from shortly before the oldest waiting tip was sent. */
        const val OWN_RECEIPTS_SINCE_MARGIN = 600L
        /** How long after sending an anonymous tip its watcher relays are asked (seconds). */
        const val OWN_ANON_ASK_WINDOW = 7_200L

        /** Live receipt subscriptions [add] keeps at most (each is one REQ on every relay; strfry allows ~20 per connection). */
        const val MAX_LIVE_GROUPS = 3
        /** A tracked set must stay this long before its receipts are asked of the relays (spec perf2 A4). */
        const val RECEIPTS_DEBOUNCE_MS = 500L
        /** A tracked set, or the last [add], must stay this long before the note authors' watcher relays are asked (backlog 97). */
        const val WATCHER_DEBOUNCE_MS = 1_500L
    }
}
