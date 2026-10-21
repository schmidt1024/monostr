package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import com.monostr.nostr.model.Profile
import com.monostr.nostr.model.ProfileJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp
import java.time.Duration

/** Search results together with a relay error, so local hits and a failed relay round trip arrive at once (spec 3.2). */
data class SearchOutcome<T>(val items: List<T>, val error: Throwable? = null)

/** Raised when no search relay is reachable. */
class SearchRelayUnreachableException(message: String = "no search relay reachable") : Exception(message)

interface SearchRepository {
    /** People on the device whose name, display_name or nip05 contains [query] (case-insensitive): an in-memory index built once per session (spec perf 3.8). */
    suspend fun profilesLocal(query: String, limit: Int = 20): List<Profile>
    /** Local people first, then NIP-50 hits from [relays] merged by pubkey; empty [relays] = local only. */
    suspend fun profiles(query: String, relays: List<String>, limit: Int = 20): SearchOutcome<Profile>
    /** NIP-50 kind 1 from [relays], newest first; empty [relays] = no notes, no error. */
    suspend fun notes(query: String, relays: List<String>, limit: Int = 30): SearchOutcome<Note>
    /** `#t` filter on the normal relays, then the local database, newest first. */
    suspend fun hashtag(tag: String, until: Long? = null, limit: Int = 50): SearchOutcome<Note>
    /** `#t` from the local database only, newest first (spec perf 3.8: shown before the relays answer). */
    suspend fun hashtagLocal(tag: String, until: Long? = null, limit: Int = 50): List<Note>
    /** Starts building the people index behind the screen (spec perf 3.8); otherwise the first search builds it. */
    fun warmUp() {}
    suspend fun attach(relays: List<String>)
    suspend fun detach(relays: List<String>)
}

class NostrSearchRepository(
    private val engine: NostrEngine,
    private val timeout: Duration = Duration.ofSeconds(8),
    /** Profiles that changed after the index was built (the profile queue, an own edit): `ProfileRepository.updates`. */
    profileUpdates: Flow<Map<String, Profile>> = emptyFlow(),
    /** Where the index follows [profileUpdates] and [warmUp] builds it (session: the engine's background scope); null = built by the first search. */
    private val scope: CoroutineScope? = null,
) : SearchRepository {
    private val people = PeopleIndex { storedPeople() }

    init {
        scope?.launch { profileUpdates.collect { people.add(it.values) } }
    }

    override fun warmUp() {
        // a failed build is retried by the next search
        scope?.launch { runCatching { people.ensureBuilt() } }
    }

    /** Every stored kind 0, read once in Kotlin, the newest per pubkey (F6: Metadata.fromJson and an author FFI call per profile, twice per search). */
    private suspend fun storedPeople(): List<Profile> = withContext(engine.ffiDispatcher) {
        engine.query(Filter().kind(Kind(0u))).map { NoteMapper.decoded(it) }
            .groupBy { it.pubkey }
            .mapNotNull { (pk, events) -> ProfileJson.profile(pk, events.maxBy { it.createdAt }.content) }
    }

    private suspend fun storedProfiles(pubkeys: List<String>): List<Profile> {
        if (pubkeys.isEmpty()) return emptyList()
        val authors = pubkeys.mapNotNull { runCatching { PublicKey.parse(it) }.getOrNull() }
        return withContext(engine.ffiDispatcher) {
            engine.query(Filter().kind(Kind(0u)).authors(authors)).map { NoteMapper.decoded(it) }
                .groupBy { it.pubkey }
                .mapNotNull { (pk, events) -> ProfileJson.profile(pk, events.maxBy { it.createdAt }.content) }
        }
    }

    override suspend fun profilesLocal(query: String, limit: Int): List<Profile> = people.search(query, limit)

    override suspend fun profiles(query: String, relays: List<String>, limit: Int): SearchOutcome<Profile> {
        val local = profilesLocal(query, limit)
        if (relays.isEmpty()) return SearchOutcome(local)
        // fetchFrom attaches [relays] temporarily (refcounted); this call's own attach is
        // released again here so a caller's outer attach()/detach() pair (SearchController)
        // stays balanced regardless of how many searches run in between (spec 3.4).
        try {
            val hits = engine.fetchFrom(relays, Filter().kind(Kind(0u)).search(query.trim()).limit(limit.toULong()), timeout)
                .map { NoteMapper.decoded(it).pubkey }.distinct()
            // the newest stored kind 0 of the hits, not the raw relay events: a relay may hold someone's old profile
            val remote = storedProfiles(hits)
            people.add(remote)
            val connected = engine.connectedRelayUrls()
            val reachable = relays.map { it.trim().trimEnd('/') }.any { it in connected }
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
            val connected = engine.connectedRelayUrls()
            val reachable = relays.map { it.trim().trimEnd('/') }.any { it in connected }
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

    private fun hashtagFilter(tag: String, until: Long?, limit: Int): Filter {
        var filter = Filter().kind(Kind(1u)).hashtag(tag.lowercase()).limit(limit.toULong())
        if (until != null) filter = filter.until(Timestamp.fromSecs(until.toULong()))
        return filter
    }

    override suspend fun hashtagLocal(tag: String, until: Long?, limit: Int): List<Note> = withContext(engine.ffiDispatcher) {
        NoteLoader.notes(engine, engine.query(hashtagFilter(tag, until, limit))).sortedByDescending { it.createdAt }
    }

    override suspend fun hashtag(tag: String, until: Long?, limit: Int): SearchOutcome<Note> = withContext(Dispatchers.IO) {
        val filter = hashtagFilter(tag, until, limit)
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
