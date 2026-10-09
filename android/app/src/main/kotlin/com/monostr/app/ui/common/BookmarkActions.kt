package com.monostr.app.ui.common

import com.monostr.app.R
import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.feed.target
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.BookmarkOutcome
import com.monostr.nostr.repo.BookmarksRepository
import com.monostr.nostr.repo.BookmarksState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Bookmark flags and toggles shared by every screen that shows a [NoteCard] (spec 4.3). */
class BookmarkActions(
    private val bookmarks: BookmarksRepository,
    private val scope: CoroutineScope,
    /**
     * The scope [toggle] writes run on, so they survive the screen that started them (a cancelled write is taken
     * back by the list); null (tests) = [scope]. The result callback may then run after the screen is gone: it
     * only updates the screen's state flow.
     */
    private val background: CoroutineScope? = null,
) {
    /** Emits on every change of the bookmark list; controllers re-flag their notes on it. */
    val changes: Flow<BookmarksState> = bookmarks.state

    /**
     * Loads the list in the background. Screens that merely show notes keep [interactive] false,
     * so opening them never pops up an external signer (spec 4.2); only the bookmarks screen asks.
     */
    fun ensureLoaded(interactive: Boolean = false) {
        scope.launch { runCatching { bookmarks.ensureLoaded(interactive) } }
    }

    fun flags(note: NoteUi): NoteUi {
        val s = bookmarks.state.value
        return note.copy(bookmarked = note.target.id in s.ids, bookmarkEnabled = s.writable || !s.loaded)
    }

    /** Toggles [target]; success and a user's own rejection are silent, everything else gets a message. */
    fun toggle(target: Note, onResult: (UiText?) -> Unit) {
        (background ?: scope).launch {
            val message = try {
                when (bookmarks.toggle(target.id)) {
                    BookmarkOutcome.Ok, BookmarkOutcome.Rejected -> null
                    BookmarkOutcome.PublishFailed, BookmarkOutcome.NotLoaded -> uiText(R.string.bookmark_failed)
                    BookmarkOutcome.Unsupported -> uiText(R.string.bookmark_nip44_unsupported)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.userMessage()
            }
            onResult(message)
        }
    }
}
