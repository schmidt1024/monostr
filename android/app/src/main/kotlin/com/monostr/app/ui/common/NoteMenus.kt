package com.monostr.app.ui.common

import com.monostr.app.session.Ready
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.ListState

/** The note menu of a screen (spec 2 and 9.4), built the same way on every screen. */
fun Ready.noteMenu(delete: (Note) -> Unit, mute: (Note) -> Unit): NoteMenu = NoteMenu(
    link = publish::noteLink,
    delete = delete,
    hasServerPictures = { pictures.candidates(it).isNotEmpty() },
    mute = mute,
    canMute = { offersMute(it.author, pubkey, this.mute.state.value) },
)

/**
 * Spec 9.1: own notes are never muted; a list known to be read-only offers nothing. A list not loaded
 * yet (Amber before an interactive load, offline at start) still offers it: the write loads it first
 * and reports through [MuteActions] when it cannot.
 */
fun offersMute(author: String, self: String, list: ListState): Boolean = author != self && (list.writable || !list.loaded)
