package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Event
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey

/**
 * The notes the user has liked (kind 7, not "-") and reposted (kind 6), for the filled heart and repost
 * icons on every screen. One instance per session: a like on one screen shows on all of them.
 */
interface OwnReactions {
    val liked: StateFlow<Set<String>>
    val reposted: StateFlow<Set<String>>

    /** Marks [noteId] for [kind] (7 or 6); false when it was marked already, so the caller publishes nothing. */
    fun add(kind: Int, noteId: String): Boolean

    /** Takes a mark back (a publish that failed). */
    fun remove(kind: Int, noteId: String)
}

/** In memory only: tests, and screens built without a session. */
open class MemoryOwnReactions : OwnReactions {
    protected val _liked = MutableStateFlow<Set<String>>(emptySet())
    protected val _reposted = MutableStateFlow<Set<String>>(emptySet())
    override val liked: StateFlow<Set<String>> = _liked.asStateFlow()
    override val reposted: StateFlow<Set<String>> = _reposted.asStateFlow()

    private fun of(kind: Int) = if (kind == 7) _liked else _reposted

    override fun add(kind: Int, noteId: String): Boolean {
        var added = false
        of(kind).update { set -> if (noteId in set) set else { added = true; set + noteId } }
        return added
    }

    override fun remove(kind: Int, noteId: String) = of(kind).update { it - noteId }
}

/**
 * Reads the user's own kind 7 and kind 6 events from the database, after a relay fetch of the latest
 * ones when [load] is told to (a new install, another client). The target is the last `e` tag (NIP-25);
 * a reaction or repost the user withdrew (NIP-09) does not count.
 */
class NostrOwnReactions(
    private val engine: NostrEngine,
    private val limit: Int = LIMIT,
) : MemoryOwnReactions() {
    private val pubkey = requireNotNull(engine.pubkey) { "own reactions need a logged-in user" }

    suspend fun load(fetch: Boolean = true) {
        val filter = Filter().kinds(listOf(Kind(7u), Kind(6u))).author(PublicKey.parse(pubkey)).limit(limit.toULong())
        merge(read(filter))
        if (!fetch) return
        quietly { engine.fetch(filter) }
        merge(read(filter))
    }

    private suspend fun read(filter: Filter): Pair<Set<String>, Set<String>> = withContext(Dispatchers.IO) {
        val events = Withdrawn.filter(engine, engine.query(filter))
        val liked = HashSet<String>()
        val reposted = HashSet<String>()
        for (ev in events) {
            val target = target(ev) ?: continue
            when (ev.kind().asU16().toInt()) {
                7 -> if (ev.content().trim() != "-") liked += target
                6 -> reposted += target
            }
        }
        liked to reposted
    }

    // optimistic marks set meanwhile stay: they belong to events still on their way to the database
    private fun merge(found: Pair<Set<String>, Set<String>>) {
        _liked.update { it + found.first }
        _reposted.update { it + found.second }
    }

    private fun target(ev: Event): String? =
        ev.tags().toVec().map { it.asVec() }.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)

    companion object {
        /** Own reactions read and fetched; older ones show empty until the note's own fetch brings them. */
        const val LIMIT = 2000
    }
}
