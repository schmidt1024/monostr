package com.monostr.app.ui.tips

import com.monostr.nostr.StartupGate
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** [name] is null for an anonymous tip; the UI shows "Anonymous" in its language. */
data class TipperUi(val name: String?, val amount: Long?, val comment: String, val createdAt: Long)

/**
 * Receipt sums and pending markers for the notes a screen shows (spec 5.5 step 5, 5.6): one
 * batched receipt subscription for the tracked notes, pending tips from the store, and a
 * settle step that drops a pending tip once a receipt names its intent. Intents stored while
 * offline are re-sent once per [start]. Profile tips are settled by `ProfileTipWatch`, not here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    /** Valid receipts per target note id. */
    val summaries: StateFlow<Map<String, TipSummary>> = _summaries.asStateFlow()
    /** Target note ids with an active pending tip. */
    val pendingNotes: StateFlow<Set<String>> = _pendingNotes.asStateFlow()
    /** Fires whenever [summaries] or [pendingNotes] change; controllers re-decorate on it. */
    val changes: Flow<Unit> = combine(_summaries, _pendingNotes) { _, _ -> }

    fun start() {
        scope.launch { runCatching { pending.prune(now()) } }
        scope.launch { resendPending() }
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
                .collect { all -> _pendingNotes.value = PendingTips.active(all, now()).mapNotNull { it.noteId }.toSet() }
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
        synchronized(groups) {
            for ((id, author) in map) if (id !in added) added[id] = author
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

    fun decorate(note: NoteUi): NoteUi =
        note.copy(tips = _summaries.value[note.target.id], tipped = selfPubkey != null && _summaries.value[note.target.id]?.receipts?.any { it.senderPubkey == selfPubkey } == true, pendingTip = note.target.id in _pendingNotes.value, canTip = note.target.author != selfPubkey, isOwn = note.target.author == selfPubkey)

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

    /** Re-publishes the still active pending intents to the user's relays; an intent sent while offline would otherwise never reach the watcher. */
    private suspend fun resendPending() {
        gate.await()
        val active = try {
            PendingTips.active(pending.pending.first(), now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        for (tip in active) {
            try {
                // an anonymous intent only ever travels over its own connection to the watcher relays,
                // and only until one accepted it: every resend is another connection from this device
                val anonymous = tip.anonEvent
                if (anonymous == null) {
                    publish.resend(tip.intentId)
                } else if (!tip.anonDelivered) {
                    if (tips.resendAnonymousIntent(anonymous, tip.anonRelays).sentToAny) pending.markDelivered(tip.intentId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // still offline or the event is gone locally; the next start tries again
            }
        }
    }

    private suspend fun settle(summaries: Map<String, TipSummary>) {
        val confirmed = summaries.values.flatMap { it.receipts }.map { it.intentId }.toSet()
        if (confirmed.isEmpty()) return
        try {
            val done = pending.pending.first().filter { it.intentId in confirmed }.map { it.intentId }
            if (done.isNotEmpty()) pending.remove(done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the marker stays until the next receipt or its 24 h expiry
        }
    }

    companion object {
        /** Live receipt subscriptions [add] keeps at most (each is one REQ on every relay; strfry allows ~20 per connection). */
        const val MAX_LIVE_GROUPS = 3
        /** A tracked set must stay this long before its receipts are asked of the relays (spec perf2 A4). */
        const val RECEIPTS_DEBOUNCE_MS = 500L
    }
}
