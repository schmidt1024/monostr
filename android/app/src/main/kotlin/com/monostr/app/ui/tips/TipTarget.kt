package com.monostr.app.ui.tips

import com.monostr.nostr.model.Note as NoteModel

/** What a tip is for: a note, or a person's profile (protocol 0.2: an intent without `e`). */
sealed interface TipTarget {
    /** Pubkey of the person who receives the tip. */
    val recipient: String
    /** The tipped note; null for a profile tip. */
    val noteId: String?
    /** Identity of the open sheet: a late result of one target must not touch the sheet of another. */
    val key: String

    data class Note(val note: NoteModel) : TipTarget {
        override val recipient: String get() = note.author
        override val noteId: String get() = note.id
        override val key: String get() = note.id
    }

    data class Profile(val pubkey: String) : TipTarget {
        override val recipient: String get() = pubkey
        override val noteId: String? get() = null
        override val key: String get() = "profile:$pubkey"
    }
}
