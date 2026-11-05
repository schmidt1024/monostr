package com.monostr.app.ui.common

import com.monostr.app.ui.feed.NoteUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * The one filter every note list applies (spec 9.3 and 4.2): deleted notes (this session), muted
 * accounts and the proof of work minimum. [changes] fires when any of them changes, so a list re-filters what it shows.
 */
class NoteVisibility(
    val deletions: NoteDeletions,
    val muted: StateFlow<Set<String>>,
    val pow: StateFlow<PowFilter> = MutableStateFlow(PowFilter.OFF),
) {
    /** Fires when the minimum drops: notes already taken out are asked for again (like an unmute). */
    val relaxed: Flow<Unit> = flow {
        var last = pow.value.minBits
        pow.drop(1).collect { now ->
            if (now.minBits < last) emit(Unit)
            last = now.minBits
        }
    }

    /** [visible] without the proof of work filter: what the user chose to keep or that gives a note its context (bookmarks, the notes above a reply). */
    fun visibleAnyPow(notes: List<NoteUi>, keep: String? = null): List<NoteUi> = Muted.visible(deletions.visible(notes), muted.value, keep)

    /** [notes] without deleted notes, muted accounts and too little proof of work; [keep] stays even when its author is muted, [powKeep] whatever its proof of work. */
    fun visible(notes: List<NoteUi>, keep: String? = null, powKeep: Set<String> = emptySet()): List<NoteUi> {
        val shown = visibleAnyPow(notes, keep)
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
    val changes: Flow<Unit> = merge(deletions.deleted.drop(1), muted.drop(1), pow.drop(1)).map { }
}
