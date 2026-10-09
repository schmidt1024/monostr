package com.monostr.app.ui.tips

import com.monostr.app.data.PendingTipStore
import com.monostr.app.data.PendingTips
import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.feed.target
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.PublishRepository
import com.monostr.nostr.repo.TipSummary
import com.monostr.nostr.repo.TipsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** [name] is null for an anonymous tip; the UI shows "Anonymous" in its language. */
data class TipperUi(val name: String?, val amount: Long, val comment: String, val createdAt: Long)

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
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val tracked = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _summaries = MutableStateFlow<Map<String, TipSummary>>(emptyMap())
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
                .flatMapLatest { notes -> tips.observeReceipts(notes).catch { /* relay or db error: keep the last state */ } }
                .collect { s ->
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

    fun decorate(note: NoteUi): NoteUi =
        note.copy(tips = _summaries.value[note.target.id], pendingTip = note.target.id in _pendingNotes.value, canTip = note.target.author != selfPubkey, isOwn = note.target.author == selfPubkey)

    /** Tippers of the note's target with sender names, newest first. */
    suspend fun tippers(note: Note): List<TipperUi> {
        val target = note.repostOf ?: note
        val tippers = tips.tippers(target.id, target.author)
        // one request for all senders; without it every unknown sender would wait for its own lookup
        profiles.prefetch(tippers.mapNotNull { it.receipt.senderPubkey })
        return tippers.map { t ->
            TipperUi(t.receipt.senderPubkey?.let { profiles.get(it).shownName }, t.receipt.amount, t.comment, t.receipt.createdAt)
        }
    }

    /** Re-publishes the still active pending intents to the user's relays; an intent sent while offline would otherwise never reach the watcher. */
    private suspend fun resendPending() {
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
}
