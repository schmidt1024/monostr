package com.monostr.app.ui.common

import com.monostr.app.R
import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.feed.target
import com.monostr.nostr.PublishResult
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.CountsRepository
import com.monostr.nostr.repo.MemoryOwnReactions
import com.monostr.nostr.repo.OwnReactions
import com.monostr.nostr.repo.PublishRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Like, repost and deletion for the note lists: like and repost mark the target optimistically,
 * publish, and report the result through `onResult`. A failure removes the mark again;
 * "no relay" keeps it, since the event is stored locally. The marks live in [own], one per session,
 * so every screen shows them and a note liked or reposted already is never published twice.
 */
class NoteActions(
    private val publish: PublishRepository,
    private val scope: CoroutineScope,
    private val counts: CountsRepository? = null,
    private val deletions: NoteDeletions = NoteDeletions(),
    /** Runs after an accepted deletion request (the note's pictures, spec 4.3); a returned text is reported as a second message. */
    private val afterDelete: suspend (Note) -> UiText? = { null },
    /** The session's own likes and reposts (stored kind 7/6 plus marks set meanwhile); a fresh one in tests. */
    private val own: OwnReactions = MemoryOwnReactions(),
) {
    private val _changes = MutableStateFlow(0)

    /** Emits whenever a like/repost flag (also one set on another screen or loaded from the database) or a deletion changes, so screens re-flag at once. */
    val changes: Flow<Any> = merge(_changes, own.liked, own.reposted)

    fun like(target: Note, onResult: (UiText) -> Unit) = act(target, 7, uiText(R.string.action_like_sent), onResult) { publish.like(target) }

    fun repost(target: Note, onResult: (UiText) -> Unit) = act(target, 6, uiText(R.string.action_repost_sent), onResult) { publish.repost(target) }

    /** [note] with the user's own like and repost of its target, and whether its deletion is on its way. */
    fun flags(note: NoteUi): NoteUi =
        note.copy(liked = note.target.id in own.liked.value, reposted = note.target.id in own.reposted.value, deleting = note.target.id in deletions.pending.value)

    /**
     * Spec 4.1: asks the relays to delete [note]. It counts as deleted only once a relay accepted the
     * request; otherwise nothing changes and the usual error text is reported. No second attempt.
     * The texts go to [NoteDeletions.messages], not to the calling screen, which may be gone by then.
     */
    fun delete(note: Note) {
        if (!deletions.begin(note.id)) return
        _changes.update { it + 1 }
        (deletions.background ?: scope).launch {
            var accepted = false
            val message = try {
                accepted = publish.delete(note).sentToAny
                if (accepted) uiText(R.string.note_deleted) else uiText(R.string.error_send_failed)
            } catch (e: CancellationException) {
                deletions.end(note.id, deleted = false)
                throw e
            } catch (e: Exception) {
                e.userMessage()
            }
            deletions.end(note.id, accepted)
            _changes.update { it + 1 }
            deletions.post(message)
            if (accepted) {
                try {
                    afterDelete(note)?.let(deletions::post)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the deletion itself succeeded; its pictures then stay on the server
                }
            }
        }
    }

    // once only: a note the user liked or reposted already gets no second event
    private fun act(target: Note, kind: Int, okMessage: UiText, onResult: (UiText) -> Unit, block: suspend () -> PublishResult) {
        if (!own.add(kind, target.id)) return
        counts?.bump(target.id, kind, +1)
        scope.launch {
            val message = try {
                if (block().sentToAny) okMessage else uiText(R.string.action_no_relay_local)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                own.remove(kind, target.id)
                counts?.bump(target.id, kind, -1)
                e.userMessage()
            }
            onResult(message)
        }
    }
}
