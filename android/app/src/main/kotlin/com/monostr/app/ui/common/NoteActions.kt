package com.monostr.app.ui.common

import com.monostr.app.R
import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.feed.target
import com.monostr.nostr.PublishResult
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.CountsRepository
import com.monostr.nostr.repo.PublishRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Like, repost and deletion for the note lists: like and repost mark the target optimistically,
 * publish, and report the result through `onResult`. A failure removes the mark again;
 * "no relay" keeps it, since the event is stored locally.
 */
class NoteActions(
    private val publish: PublishRepository,
    private val scope: CoroutineScope,
    private val counts: CountsRepository? = null,
    private val deletions: NoteDeletions = NoteDeletions(),
    /** Runs after an accepted deletion request (the note's pictures, spec 4.3); a returned text is reported as a second message. */
    private val afterDelete: suspend (Note) -> UiText? = { null },
) {
    private val likedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val repostedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val _changes = MutableStateFlow(0)

    /** Bumps whenever a like/repost flag is set or reverted, so screens re-flag at once instead of after the relay round trip. */
    val changes: Flow<Int> = _changes.asStateFlow()

    fun like(target: Note, onResult: (UiText) -> Unit) = act(target, likedIds, 7, uiText(R.string.action_like_sent), onResult) { publish.like(target) }

    fun repost(target: Note, onResult: (UiText) -> Unit) = act(target, repostedIds, 6, uiText(R.string.action_repost_sent), onResult) { publish.repost(target) }

    /** [note] with its liked/reposted flags as far as this session knows them, and whether its deletion is on its way. */
    fun flags(note: NoteUi): NoteUi =
        note.copy(liked = note.target.id in likedIds, reposted = note.target.id in repostedIds, deleting = note.target.id in deletions.pending.value)

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

    private fun act(target: Note, ids: MutableSet<String>, kind: Int, okMessage: UiText, onResult: (UiText) -> Unit, block: suspend () -> PublishResult) {
        val added = ids.add(target.id)
        if (added) { _changes.update { it + 1 }; counts?.bump(target.id, kind, +1) }
        scope.launch {
            val message = try {
                if (block().sentToAny) okMessage else uiText(R.string.action_no_relay_local)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (added) { ids.remove(target.id); _changes.update { it + 1 }; counts?.bump(target.id, kind, -1) }
                e.userMessage()
            }
            onResult(message)
        }
    }
}
