package com.monostr.nostr.repo

import rust.nostr.sdk.EventId
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.StartupGate
import com.monostr.nostr.SyncMarks
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp
import java.time.Duration

/**
 * The notes the user has liked (kind 7, not "-") and reposted (kind 6), for the filled heart and repost
 * icons on every screen. One instance per session: a like on one screen shows on all of them.
 */
interface OwnReactions {
    /** The user's reaction per note: "+" for a like, else the emoji (NIP-25). */
    val reactions: StateFlow<Map<String, String>>
    val liked: StateFlow<Set<String>>
    val reposted: StateFlow<Set<String>>

    /** Marks [noteId] for [kind] (7 with [content], or 6); false when it was marked already, so the caller publishes nothing. */
    fun add(kind: Int, noteId: String, content: String = LIKE): Boolean

    /** Takes a mark back (a publish that failed). */
    fun remove(kind: Int, noteId: String)

    /** Notes on screen: their own reactions and reposts are looked up, also older ones than [NostrOwnReactions.LIMIT] covers. */
    fun check(noteIds: Collection<String>) {}
}

/** In memory only: tests, and screens built without a session. */
open class MemoryOwnReactions : OwnReactions {
    protected val _reactions = MutableStateFlow<Map<String, String>>(emptyMap())
    protected val _liked = MutableStateFlow<Set<String>>(emptySet())
    protected val _reposted = MutableStateFlow<Set<String>>(emptySet())
    override val reactions: StateFlow<Map<String, String>> = _reactions.asStateFlow()
    override val liked: StateFlow<Set<String>> = _liked.asStateFlow()
    override val reposted: StateFlow<Set<String>> = _reposted.asStateFlow()

    override fun add(kind: Int, noteId: String, content: String): Boolean {
        var added = false
        if (kind == 7) {
            // the map first: whoever sees the id in [liked] finds its content
            _reactions.update { m -> if (noteId in m) m else { added = true; m + (noteId to content) } }
            if (added) _liked.update { it + noteId }
        } else {
            _reposted.update { set -> if (noteId in set) set else { added = true; set + noteId } }
        }
        return added
    }

    override fun remove(kind: Int, noteId: String) {
        if (kind == 7) {
            _liked.update { it - noteId }
            _reactions.update { it - noteId }
        } else {
            _reposted.update { it - noteId }
        }
    }
}

/**
 * Reads the user's own kind 7 and kind 6 events from the database, after a relay fetch of the latest
 * ones when [load] is told to (a new install, another client). The target is the last `e` tag (NIP-25);
 * a reaction or repost the user withdrew (NIP-09) does not count. Spec perf2 A5: each event is read once
 * in Kotlin; the start fetch asks only from a day before the last completed one for this relay set, and
 * only after the [gate] opened (the database marks show at once).
 */
class NostrOwnReactions(
    private val engine: NostrEngine,
    private val limit: Int = LIMIT,
    /** Where [check] runs its lookups; null (tests of [load] alone) skips them. */
    private val scope: CoroutineScope? = null,
    /** The relay legs wait until the home feed's first page is in (spec perf2 A3). */
    private val gate: StartupGate = StartupGate.OPEN,
    /** When the start fetch last completed per relay set; null (tests, no session) = always the whole window. */
    private val marks: SyncMarks? = null,
    private val fetchTimeout: Duration = Duration.ofSeconds(8),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : MemoryOwnReactions() {
    private val checked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val pubkey = requireNotNull(engine.pubkey) { "own reactions need a logged-in user" }

    private fun ownFilter() = Filter().kinds(listOf(Kind(7u), Kind(6u), Kind(16u))).author(PublicKey.parse(pubkey))

    /**
     * The newest [limit] own reactions come with [load]; a note on screen whose reaction is older is looked up
     * here: once per note and session, in the database at once and then, after the [gate], on the relays.
     */
    override fun check(noteIds: Collection<String>) {
        val fresh = noteIds.filter { it !in _liked.value && it !in _reposted.value && checked.add(it) }
        if (fresh.isEmpty()) return
        if (checked.size > CHECKED_CAP) checked.clear()
        val s = scope ?: return
        s.launch {
            val filters = fresh.chunked(CHUNK).map { chunk -> ownFilter().events(chunk.map { EventId.parse(it) }) }
            for (f in filters) merge(read(f))
            gate.await()
            for (f in filters) {
                quietly { engine.fetch(f) }
                merge(read(f))
            }
        }
    }

    suspend fun load(fetch: Boolean = true) {
        val filter = ownFilter().limit(limit.toULong())
        val local = read(filter)
        merge(local)
        if (!fetch) return
        gate.await()
        val relays = engine.relayUrls()
        val key = relays.takeIf { it.isNotEmpty() }?.let { relaySetKey(it) }
        // a stamp only counts while the database still holds reactions: a cleared database gets the whole window again
        val since = key?.takeIf { local.stored > 0 }?.let { marks?.get(it) }?.let { (minOf(it, now()) - SKEW_SECONDS).coerceAtLeast(0) }
        val asked = if (since != null) filter.since(Timestamp.fromSecs(since.toULong())) else filter
        val startedAt = now()
        var up = false
        val replied = legReplied(fetchTimeout, { up }) {
            engine.fetch(asked, fetchTimeout).also { up = engine.connectedNormalRelayUrls().isNotEmpty() }
        }
        if (replied && key != null) marks?.put(key, startedAt)
        merge(read(asked))
    }

    /** What one database read found; [stored] counts the events before the NIP-09 filter. */
    private class Found(val reactions: Map<String, String>, val reposted: Set<String>, val stored: Int)

    // read in Kotlin: one asJson per event instead of ~30 FFI calls (sort key, kind, content, every tag)
    private suspend fun read(filter: Filter): Found = withContext(Dispatchers.IO) {
        val raw = engine.query(filter)
        val events = Withdrawn.filterDecoded(engine, raw.map { it to NoteMapper.decoded(it) }).map { it.second }.sortedBy { it.createdAt }
        val reactions = HashMap<String, String>()
        val reposted = HashSet<String>()
        for (ev in events) {
            val target = ev.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: continue
            when (ev.kind) {
                // oldest first, so the newest reaction to a note wins; an empty content counts as a like (NIP-25)
                7 -> ev.content.trim().let { c -> if (c != "-") reactions[target] = c.ifEmpty { LIKE } }
                6, 16 -> reposted += target
            }
        }
        Found(reactions, reposted, raw.size)
    }

    // optimistic marks set meanwhile stay: they belong to events still on their way to the database
    private fun merge(found: Found) {
        _reactions.update { found.reactions + it }
        _liked.update { it + found.reactions.keys }
        _reposted.update { it + found.reposted }
    }

    companion object {
        /** Own reactions read and fetched at the start; older ones come with [check] when their note is on screen. */
        const val LIMIT = 2000
        /** The start fetch asks from this long before the last completed one: clocks, late relays (spec perf2 A5, R6). */
        const val SKEW_SECONDS = 86_400L
        private const val CHUNK = 100
        private const val CHECKED_CAP = 20_000

        /** The stamp key of a relay set: the normal relays, normalised and sorted, hashed (no URL in the file). */
        internal fun relaySetKey(urls: Collection<String>): String {
            val joined = urls.map { it.trim().trimEnd('/') }.distinct().sorted().joinToString("\n")
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(joined.toByteArray())
            return "own-reactions:" + digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
