package com.monostr.app.ui.common

import com.monostr.app.ui.feed.NoteUi

/** Spec 9.3: what a muted account takes out of a list — its notes, its reposts, and reposts of its notes by others. */
object Muted {
    /** [notes] without what [muted] accounts wrote or reposted; [keep] (the focused note of a thread) always stays. */
    fun visible(notes: List<NoteUi>, muted: Set<String>, keep: String? = null): List<NoteUi> {
        if (muted.isEmpty()) return notes
        return notes.filter { n -> n.note.id == keep || (n.note.author !in muted && n.note.repostOf?.author !in muted) }
    }
}
