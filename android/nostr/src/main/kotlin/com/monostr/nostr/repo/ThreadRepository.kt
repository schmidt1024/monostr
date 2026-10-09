package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Alphabet
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.SingleLetterTag

/** A thread: the root note and every reply that references it, oldest first. [gone]: the focused note has been withdrawn by its author (NIP-09). */
data class ThreadView(val root: Note?, val focused: Note, val replies: List<Note>, val gone: Boolean = false)

interface ThreadRepository {
    /** Loads the note, resolves its root, fetches replies and keeps listening for new ones. Empty flow when the note cannot be found. */
    fun observe(noteId: String): Flow<ThreadView>

    /** One note from the local database, fetched once when missing; null when unknown. */
    suspend fun note(id: String): Note?
}

class NostrThreadRepository(private val engine: NostrEngine) : ThreadRepository {
    override fun observe(noteId: String): Flow<ThreadView> = flow {
        val focused = loadNote(noteId) ?: return@flow
        val rootId = focused.rootId ?: focused.id
        val root = if (rootId == focused.id) focused else loadNote(rootId)
        emit(build(root, focused, repliesOf(rootId)))
        // replies, and a deletion request that names the root
        val filter = Filter().kinds(listOf(Kind(1u), Kind(5u))).customTag(SingleLetterTag.lowercase(Alphabet.E), rootId)
        quietly { engine.fetch(filter) }
        // deletion requests for everything shown, at most 100 ids per request
        val shown = (listOf(rootId, focused.id) + repliesOf(rootId).map { it.id }).distinct()
        for (ids in shown.chunked(100)) quietly { engine.fetch(Filter().kind(Kind(5u)).events(ids.map { EventId.parse(it) })) }
        emit(current(root, focused, rootId))
        val subId = engine.subscribe(filter)
        try {
            engine.events.collect { ev ->
                val tags = ev.tags().toVec().map { it.asVec() }
                if (tags.any { it.size >= 2 && it[0] == "e" && it[1] == rootId }) emit(current(root, focused, rootId))
            }
        } finally {
            withContext(NonCancellable) { quietly { engine.unsubscribe(subId) } }
        }
    }

    override suspend fun note(id: String): Note? = loadNote(id)

    private suspend fun loadNote(id: String): Note? {
        val local = engine.eventById(id)
        val ev = local ?: run {
            quietly { engine.fetch(Filter().id(EventId.parse(id))) }
            engine.eventById(id)
        }
        return ev?.takeUnless { Withdrawn.isWithdrawn(engine, it) }?.let { NoteMapper.note(it) }
    }

    private suspend fun repliesOf(rootId: String): List<Note> =
        Withdrawn.filter(engine, engine.query(Filter().kind(Kind(1u)).customTag(SingleLetterTag.lowercase(Alphabet.E), rootId)))
            .mapNotNull { NoteMapper.note(it) }
            .filter { it.id != rootId }
            .sortedBy { it.createdAt }

    private fun build(root: Note?, focused: Note, replies: List<Note>) = ThreadView(root, focused, replies)

    /** The thread as the database has it now: a withdrawn root is no longer shown, a withdrawn focused note is reported. */
    private suspend fun current(root: Note?, focused: Note, rootId: String): ThreadView {
        val rootNow = root?.takeIf { engine.eventById(it.id) != null }
        return ThreadView(rootNow, focused, repliesOf(rootId), gone = engine.eventById(focused.id) == null)
    }
}
