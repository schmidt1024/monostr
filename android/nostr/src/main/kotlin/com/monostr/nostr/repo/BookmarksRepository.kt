package com.monostr.nostr.repo

import com.monostr.nostr.Exit
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.RefreshGate
import com.monostr.nostr.Signer
import com.monostr.nostr.StartupGate
import com.monostr.nostr.model.AddressRef
import com.monostr.nostr.model.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.StateFlow
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import java.time.Duration

/** Bookmarked note ids (private + public), whether the list may be written, whether load ran. */
typealias BookmarksState = ListState

/**
 * One bookmark; [note] is null when the note is not available locally or from the relays. An address entry (`a` tag, e.g. an
 * article) has [article] set, [id] is its coordinate and [note] stays null: the screen's card loads the event itself.
 */
data class BookmarkEntry(val id: String, val note: Note?, val article: AddressRef? = null)

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
    /**
     * Same for an article (`30023:<pubkey>:<d>`): monostr's own additions go into the encrypted part as `["a", coordinate]`,
     * never public; an `a` entry another client added (public or private) counts as bookmarked and is removed from where it is.
     */
    suspend fun toggleArticle(coordinate: String): BookmarkOutcome
    /** [entries] from the database only (spec perf 3.8): the stored notes at once, the others as placeholders. */
    suspend fun localEntries(): List<BookmarkEntry>
    /** Notes for [BookmarksState.ids] in list order; missing ones fetched in chunks of 50 side by side, an id no relay had is not asked again for 10 minutes. */
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
    /** A chunk of ids is not asked of the relays again for this long (spec perf 3.8). */
    private val missRetrySeconds: Long = 600,
    /** One chunk's relay lookup. */
    private val entriesTimeout: Duration = Duration.ofSeconds(8),
    gate: StartupGate = StartupGate.OPEN,
) : BookmarksRepository {
    private val list = NostrPrivateList(engine, signer, kind = 10003, tag = "e", now, send, fetchTimeout, refreshTimeout, watch, gate = gate, extraTag = "a")
    /** Runs (and joins) the relay lookups on the engine's scope so a left screen does not cancel them (spec 3.1); freshness is [missedAt], not the gate. */
    private val lookups = RefreshGate<String>(engine.backgroundScope, 0, now)
    private val clock = now
    /** Lower-case ids no relay had, with the time they were last asked (spec perf 3.8). */
    private val missedAt = ConcurrentHashMap<String, Long>()
    override val state: StateFlow<BookmarksState> get() = list.state

    override suspend fun ensureLoaded(interactive: Boolean) = list.ensureLoaded(interactive)

    override suspend fun toggle(noteId: String): BookmarkOutcome = list.toggle(noteId).toBookmarkOutcome()

    override suspend fun toggleArticle(coordinate: String): BookmarkOutcome = list.toggle(coordinate, entryTag = "a").toBookmarkOutcome()

    /** A kind 10003 of ours from a relay; see [NostrPrivateList.onListEvent]. */
    internal suspend fun onListEvent(event: Event) = list.onListEvent(event)

    override fun clear() {
        list.clear()
        missedAt.clear()
    }

    override suspend fun localEntries(): List<BookmarkEntry> = resolve(list.state.value.ordered, fetch = false)

    override suspend fun entries(): List<BookmarkEntry> = resolve(list.state.value.ordered, fetch = true)

    /**
     * The notes of [ids] from the database; with [fetch] the missing ones are asked of the relays in chunks of
     * [CHUNK] side by side (spec perf 3.8: one after another, 8 s each, before), except ids no relay had less than
     * [missRetrySeconds] ago. A lookup while no relay is connected is not remembered.
     */
    private suspend fun resolve(ordered: List<Pair<String, String>>, fetch: Boolean): List<BookmarkEntry> {
        val ids = ordered.filter { it.first == "e" }.map { it.second }
        val parsed = ids.mapNotNull { id -> runCatching { EventId.parse(id) }.getOrNull()?.let { id.lowercase() to it } }.distinctBy { it.first }
        val found = HashMap<String, Event>() // keyed by lowercase hex id
        suspend fun read(chunk: List<Pair<String, EventId>>) =
            engine.query(Filter().ids(chunk.map { it.second })).forEach { found[it.id().toHex().lowercase()] = it }
        parsed.chunked(CHUNK).forEach { read(it) }
        if (fetch) {
            val t = clock()
            val missing = parsed.filter { (id, _) -> id !in found && missedAt[id]?.let { t - it < missRetrySeconds } != true }
            if (missing.isNotEmpty()) {
                coroutineScope {
                    missing.chunked(CHUNK).map { chunk ->
                        async {
                            val key = chunk.joinToString(",") { it.first }
                            lookups.awaitStamped(key, force = true) {
                                quietly { engine.fetch(Filter().ids(chunk.map { it.second }), entriesTimeout, Exit.AllIds(chunk.map { it.first }.toSet())) }
                                val have = engine.query(Filter().ids(chunk.map { it.second })).map { it.id().toHex().lowercase() }.toSet()
                                val online = engine.connectedNormalRelayUrls().isNotEmpty()
                                val stamp = clock()
                                chunk.forEach { (id, _) -> if (id in have) missedAt.remove(id) else if (online) missedAt[id] = stamp }
                                online
                            }
                            lookups.invalidate(key) // the gate only joins runs; nothing to keep per chunk
                        }
                    }.awaitAll()
                }
                missing.chunked(CHUNK).forEach { read(it) }
            }
        }
        val notes = NoteLoader.notes(engine, ids.mapNotNull { found[it.lowercase()] }).associateBy { it.id.lowercase() }
        // addresses keep their place in the list; one that is not a valid coordinate is dropped
        return ordered.mapNotNull { (t, v) ->
            if (t == "e") BookmarkEntry(v, notes[v.lowercase()]) else addressOf(v)?.let { BookmarkEntry(v, null, it) }
        }
    }

    companion object {
        private val PUBKEY = Regex("[0-9a-f]{64}") // regex

        /** The address a bookmarked `a` coordinate (`kind:pubkey:d`, the identifier may hold colons) points at; null for anything else. */
        internal fun addressOf(coordinate: String): AddressRef? {
            val parts = coordinate.split(':', limit = 3)
            if (parts.size != 3 || !PUBKEY.matches(parts[1])) return null
            val kind = parts[0].toIntOrNull()?.takeIf { it in 30000..39999 } ?: return null
            return AddressRef(kind, parts[1], parts[2])
        }

        /** Relays cap filter ids (same limit as the tipper intents). */
        const val CHUNK = 50
    }
}
