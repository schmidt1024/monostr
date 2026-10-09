package com.monostr.app.ui.common

import com.monostr.app.ui.feed.NoteUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * The one filter every note list applies (spec 9.3 and 4.2): deleted notes (this session) and
 * muted accounts. [changes] fires when either set changes, so a list re-filters what it shows.
 */
class NoteVisibility(val deletions: NoteDeletions, val muted: StateFlow<Set<String>>) {
    /** [notes] without deleted notes and without muted accounts; [keep] stays even when its author is muted. */
    fun visible(notes: List<NoteUi>, keep: String? = null): List<NoteUi> = Muted.visible(deletions.visible(notes), muted.value, keep)

    // drop(1): the current values are applied when a list is built, only later changes re-filter it
    val changes: Flow<Unit> = merge(deletions.deleted.drop(1), muted.drop(1)).map { }
}
