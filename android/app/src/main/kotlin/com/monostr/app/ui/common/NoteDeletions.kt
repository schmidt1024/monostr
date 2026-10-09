package com.monostr.app.ui.common

import com.monostr.app.ui.feed.NoteUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Own notes whose deletion request (NIP-09) a relay accepted in this session, and the ones whose
 * request is on its way. One instance per session: every list filters against [deleted], so a note
 * deleted in the thread also leaves the feed below it, and a search relay that still serves it
 * (spec 12) cannot bring it back. The lists apply it through [NoteVisibility], together with the mute list.
 */
class NoteDeletions(
    /** The scope deletion requests run on, so they outlive the screen that started them; null (tests) = the caller's scope. */
    val background: CoroutineScope? = null,
) {
    private val _deleted = MutableStateFlow<Set<String>>(emptySet())
    val deleted: StateFlow<Set<String>> = _deleted.asStateFlow()
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()
    private val _messages = MutableStateFlow<List<UiText>>(emptyList())

    /**
     * What became of deletion requests, oldest first, until a screen showed it ([consume]). Kept here and not
     * in the screen that started the request: that screen may be gone when the answer comes (a deleted
     * thread closes, the user moves on), so the message shows on whatever screen is in front then.
     */
    val messages: StateFlow<List<UiText>> = _messages.asStateFlow()

    fun post(message: UiText) = _messages.update { it + message }

    /** Removes the first entry equal to [message]; a later, equal message stays for its own showing. */
    fun consume(message: UiText) = _messages.update { list ->
        val i = list.indexOf(message)
        if (i < 0) list else list.toMutableList().apply { removeAt(i) }
    }

    /** False when a request for [id] is already running (no second request, spec 4.1). */
    fun begin(id: String): Boolean {
        var added = false
        _pending.update { p -> if (id in p) p else (p + id).also { added = true } }
        return added
    }

    fun end(id: String, deleted: Boolean) {
        if (deleted) _deleted.update { it + id }
        _pending.update { it - id }
    }

    /** [notes] without the deleted ones and without reposts of them. */
    fun visible(notes: List<NoteUi>): List<NoteUi> {
        val gone = _deleted.value
        return if (gone.isEmpty()) notes else notes.filterNot { it.note.id in gone || it.note.repostOf?.id in gone }
    }
}
