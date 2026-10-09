package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.Timestamp
import java.time.Duration

/** Search results together with a relay error, so local hits and a failed relay round trip arrive at once (spec 3.2). */
data class SearchOutcome<T>(val items: List<T>, val error: Throwable? = null)

/** Raised when no search relay is reachable. */
class SearchRelayUnreachableException(message: String = "no search relay reachable") : Exception(message)

interface SearchRepository {
    /** kind 0 in the local database whose name, display_name or nip05 contains [query] (case-insensitive). */
    suspend fun profilesLocal(query: String, limit: Int = 20): List<Profile>
    /** Local people first, then NIP-50 hits from [relays] merged by pubkey; empty [relays] = local only. */
    suspend fun profiles(query: String, relays: List<String>, limit: Int = 20): SearchOutcome<Profile>
    /** NIP-50 kind 1 from [relays], newest first; empty [relays] = no notes, no error. */
    suspend fun notes(query: String, relays: List<String>, limit: Int = 30): SearchOutcome<Note>
    /** `#t` filter on the normal relays, then the local database, newest first. */
    suspend fun hashtag(tag: String, until: Long? = null, limit: Int = 50): SearchOutcome<Note>
    suspend fun attach(relays: List<String>)
    suspend fun detach(relays: List<String>)
}

class NostrSearchRepository(private val engine: NostrEngine, private val timeout: Duration = Duration.ofSeconds(8)) : SearchRepository {

    override suspend fun profilesLocal(query: String, limit: Int): List<Profile> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@withContext emptyList()
        engine.query(Filter().kind(Kind(0u)))
            .mapNotNull { ev ->
                val meta = runCatching { Metadata.fromJson(ev.content()) }.getOrNull() ?: return@mapNotNull null
                val p = NoteMapper.profile(ev.author().toHex(), meta)
                if (listOfNotNull(p.name, p.displayName, p.nip05).any { it.lowercase().contains(q) }) p else null
            }
            .distinctBy { it.pubkey }
            .sortedBy { it.shownName.lowercase() }
            .take(limit)
    }

    override suspend fun profiles(query: String, relays: List<String>, limit: Int): SearchOutcome<Profile> {
        val local = profilesLocal(query, limit)
        if (relays.isEmpty()) return SearchOutcome(local)
        // fetchFrom attaches [relays] temporarily (refcounted); this call's own attach is
        // released again here so a caller's outer attach()/detach() pair (SearchController)
        // stays balanced regardless of how many searches run in between (spec 3.4).
        try {
            val remote = engine.fetchFrom(relays, Filter().kind(Kind(0u)).search(query.trim()).limit(limit.toULong()), timeout)
                .mapNotNull { ev -> runCatching { NoteMapper.profile(ev.author().toHex(), Metadata.fromJson(ev.content())) }.getOrNull() }
            val reachable = relays.map { it.trim().trimEnd('/') }.any { it in engine.connectedRelayUrls() }
            val error = if (!reachable) SearchRelayUnreachableException() else null
            return SearchOutcome((local + remote).distinctBy { it.pubkey }, error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SearchOutcome(local, e)
        } finally {
            engine.detachTemporary(relays)
        }
    }

    override suspend fun notes(query: String, relays: List<String>, limit: Int): SearchOutcome<Note> {
        if (relays.isEmpty()) return SearchOutcome(emptyList())
        // See profiles(): releases this call's own fetchFrom attach again.
        try {
            val events = engine.fetchFrom(relays, Filter().kind(Kind(1u)).search(query.trim()).limit(limit.toULong()), timeout)
            val reachable = relays.map { it.trim().trimEnd('/') }.any { it in engine.connectedRelayUrls() }
            val error = if (!reachable) SearchRelayUnreachableException() else null
            return SearchOutcome(NoteLoader.notes(engine, events).sortedByDescending { it.createdAt }, error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SearchOutcome(emptyList(), e)
        } finally {
            engine.detachTemporary(relays)
        }
    }

    override suspend fun hashtag(tag: String, until: Long?, limit: Int): SearchOutcome<Note> = withContext(Dispatchers.IO) {
        var filter = Filter().kind(Kind(1u)).hashtag(tag.lowercase()).limit(limit.toULong())
        if (until != null) filter = filter.until(Timestamp.fromSecs(until.toULong()))
        var error: Throwable? = null
        try {
            engine.fetch(filter, timeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
        SearchOutcome(NoteLoader.notes(engine, engine.query(filter)).sortedByDescending { it.createdAt }, error)
    }

    override suspend fun attach(relays: List<String>) { engine.attachTemporary(relays) }
    override suspend fun detach(relays: List<String>) = engine.detachTemporary(relays)
}
