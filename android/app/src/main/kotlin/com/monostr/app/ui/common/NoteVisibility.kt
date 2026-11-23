package com.monostr.app.ui.common

import com.monostr.app.ui.feed.NoteUi
import com.monostr.nostr.model.Note
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * The one filter every note list applies (spec 9.3 and 4.2): deleted notes (this session), muted
 * accounts, muted conversations and the proof of work minimum. [changes] fires when any of them changes, so a list re-filters what it shows.
 */
class NoteVisibility(
    val deletions: NoteDeletions,
    val muted: StateFlow<Set<String>>,
    val pow: StateFlow<PowFilter> = MutableStateFlow(PowFilter.OFF),
    /** Backlog 81: thread root ids (lower-case) of muted conversations; their notes leave every list except the thread screen. */
    val mutedThreads: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    /** The logged-in user: their own notes stay in the lists when their conversation is muted (null = no exemption). */
    val ownPubkey: String? = null,
) {
    /** The thread screen shows a muted conversation: same filter without the thread rule. */
    fun withoutThreadMute(): NoteVisibility = NoteVisibility(deletions, muted, pow)

    /** A note of a muted conversation: its root, a reply below it or a repost of either (set lookups only). */
    private fun inMutedThread(n: Note, t: Set<String>): Boolean =
        n.id in t || (n.rootId?.let { it in t } == true) || (n.repostOf?.let { r -> r.id in t || (r.rootId?.let { it in t } == true) } == true)

    /** Fires when the minimum drops: notes already taken out are asked for again (like an unmute). */
    val relaxed: Flow<Unit> = flow {
        var last = pow.value.minBits
        pow.drop(1).collect { now ->
            if (now.minBits < last) emit(Unit)
            last = now.minBits
        }
    }

    /** [visible] without the proof of work filter: what the user chose to keep or that gives a note its context (bookmarks, the notes above a reply); the thread rule (backlog 81) is not applied either. */
    fun visibleAnyPow(notes: List<NoteUi>, keep: String? = null): List<NoteUi> = Muted.visible(deletions.visible(notes), muted.value, keep)

    /** [notes] without deleted notes, muted accounts and too little proof of work; [keep] stays even when its author is muted, [powKeep] whatever its proof of work. */
    fun visible(notes: List<NoteUi>, keep: String? = null, powKeep: Set<String> = emptySet()): List<NoteUi> {
        val base = visibleAnyPow(notes, keep)
        val t = mutedThreads.value
        val shown = if (t.isEmpty()) base else base.filter { n -> n.note.id == keep || (ownPubkey != null && n.note.author == ownPubkey) || !inMutedThread(n.note, t) }
        val p = pow.value
        if (!p.on) return shown
        return shown.filter { n -> n.note.id == keep || n.note.id in powKeep || !p.hides(n.note.author, n.note.powBits) }
    }

    /** The notes of [notes] that only a mute hides (not deleted, not [keep]): a conversation shows them as "Note from a muted account · Show". */
    fun mutedOnly(notes: List<NoteUi>, keep: String? = null): List<NoteUi> {
        val m = muted.value
        if (m.isEmpty()) return emptyList()
        return deletions.visible(notes).filter { n -> n.note.id != keep && (n.note.author in m || n.note.repostOf?.author in m) }
    }

    // drop(1): the current values are applied when a list is built, only later changes re-filter it
    val changes: Flow<Unit> = merge(deletions.deleted.drop(1), muted.drop(1), pow.drop(1), mutedThreads.drop(1)).map { }
}
