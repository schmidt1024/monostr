package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.Signer
import com.monostr.nostr.model.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import java.time.Duration

/** Bookmarked note ids (private + public), whether the list may be written, whether load ran. */
typealias BookmarksState = ListState

/** One bookmark; [note] is null when the note is not available locally or from the relays. */
data class BookmarkEntry(val id: String, val note: Note?)

/**
 * The bookmarks' name for [ListOutcome], one case each. Not a typealias: Kotlin does not resolve
 * nested objects through one (`BookmarkOutcome.Ok`), and callers use exactly that.
 */
sealed class BookmarkOutcome {
    data object Ok : BookmarkOutcome()
    data object PublishFailed : BookmarkOutcome()
    data object Unsupported : BookmarkOutcome()
    data object Rejected : BookmarkOutcome()
    /** The list could not be loaded yet (no local copy and the relay fetch failed or there are no relays): retry later. */
    data object NotLoaded : BookmarkOutcome()
}

private fun ListOutcome.toBookmarkOutcome(): BookmarkOutcome = when (this) {
    ListOutcome.Ok -> BookmarkOutcome.Ok
    ListOutcome.PublishFailed -> BookmarkOutcome.PublishFailed
    ListOutcome.Unsupported -> BookmarkOutcome.Unsupported
    ListOutcome.Rejected -> BookmarkOutcome.Rejected
    ListOutcome.NotLoaded -> BookmarkOutcome.NotLoaded
}

/** NIP-51 kind 10003 with private `e` tags encrypted NIP-44 to the user (spec 4). */
interface BookmarksRepository {
    val state: StateFlow<BookmarksState>
    /**
     * Loads once: local kind 10003, one relay fetch, decrypt. Later calls return at once.
     * [interactive] false (implicit loads on feed/thread/profile/search open) never asks an
     * external signer: without a silent answer only the public entries show, read-only, and the
     * list stays not loaded so a later interactive call (bookmarks screen, toggle) retries. A
     * dismissed signer prompt likewise leaves it not loaded - never a sticky read-only state.
     */
    suspend fun ensureLoaded(interactive: Boolean = true)
    /**
     * Optimistic add/remove; reverted when no relay accepted the new list. Loads interactively
     * first ([BookmarkOutcome.Rejected] when the user dismisses the decrypt prompt, nothing is
     * published) and applies the change on top of a newer list another device wrote meanwhile.
     */
    suspend fun toggle(noteId: String): BookmarkOutcome
    /** Notes for [BookmarksState.ids] in list order; missing ones fetched in chunks of 50. */
    suspend fun entries(): List<BookmarkEntry>
    fun clear()
}

/** Kind 10003 with `e` tags: the list logic lives in [NostrPrivateList]; this adds [entries]. */
class NostrBookmarksRepository(
    private val engine: NostrEngine,
    signer: Signer,
    now: () -> Long = { System.currentTimeMillis() / 1000 },
    send: suspend (EventBuilder) -> PublishResult = { engine.signAndPublish(it) },
    fetchTimeout: Duration = Duration.ofSeconds(8),
    refreshTimeout: Duration = Duration.ofSeconds(3),
    watch: CoroutineScope? = null,
) : BookmarksRepository {
    private val list = NostrPrivateList(engine, signer, kind = 10003, tag = "e", now, send, fetchTimeout, refreshTimeout, watch)
    override val state: StateFlow<BookmarksState> get() = list.state

    override suspend fun ensureLoaded(interactive: Boolean) = list.ensureLoaded(interactive)

    override suspend fun toggle(noteId: String): BookmarkOutcome = list.toggle(noteId).toBookmarkOutcome()

    /** A kind 10003 of ours from a relay; see [NostrPrivateList.onListEvent]. */
    internal suspend fun onListEvent(event: Event) = list.onListEvent(event)

    override fun clear() = list.clear()

    override suspend fun entries(): List<BookmarkEntry> {
        val ids = list.state.value.ids
        val found = HashMap<String, Event>() // keyed by lowercase hex id
        for (chunk in ids.chunked(CHUNK)) {
            val parsedIds = chunk.mapNotNull { runCatching { EventId.parse(it) }.getOrNull() }
            if (parsedIds.isEmpty()) continue
            val f = Filter().ids(parsedIds)
            var events = engine.query(f)
            if (events.size < parsedIds.size) {
                quietly { engine.fetch(f) }
                events = engine.query(f)
            }
            events.forEach { found[it.id().toHex().lowercase()] = it }
        }
        val notes = NoteLoader.notes(engine, ids.mapNotNull { found[it.lowercase()] }).associateBy { it.id.lowercase() }
        return ids.map { BookmarkEntry(it, notes[it.lowercase()]) }
    }

    companion object {
        /** Relays cap filter ids (same limit as the tipper intents). */
        const val CHUNK = 50
    }
}
