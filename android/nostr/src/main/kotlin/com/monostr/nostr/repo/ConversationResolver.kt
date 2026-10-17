package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter

sealed interface Slot {
    val id: String
    data class Shown(val note: Note) : Slot { override val id: String get() = note.id }
    data class Unavailable(override val id: String) : Slot
}

/** The notes above a reply, root first … direct parent last; with [cut] the notes between ancestors[0] (the root) and ancestors[1] are left out (spec 2026-10-16 §2.2). */
data class Chain(val ancestors: List<Slot>, val cut: Boolean)

interface ConversationRepository {
    /** Chains of the replies in [notes] (for a repost: of the reposted note), keyed by the id of the entry in [notes]. Entries that are no replies are absent. */
    suspend fun chains(notes: List<Note>): Map<String, Chain>
    /** Every note above [note] up to the root, root first; at most [MAX_HOPS]. A walk that stops below the root gets the root (by `rootId`) in front. */
    suspend fun ancestors(note: Note): List<Slot>
}

/** Walk rounds for a feed chain: parent, grandparent, great-grandparent. */
const val FEED_ROUNDS = 3
/** Hard stop for the thread page's walk. */
const val MAX_HOPS = 50
/** X: at most this many notes above a reply in the feed. */
private const val MAX_SHOWN = 3
private const val BATCH = 100

class NostrConversationRepository(private val engine: NostrEngine) : ConversationRepository {

    override suspend fun chains(notes: List<Note>): Map<String, Chain> {
        // the entry id → the reply whose chain it shows (a repost shows the reposted note's chain)
        val replies = notes.mapNotNull { n -> (n.repostOf ?: n).takeIf { it.isReply }?.let { n.id to it } }
        if (replies.isEmpty()) return emptyMap()
        val known = HashMap<String, Note?>()
        // per entry: the ids walked, direct parent first, upwards
        val paths = replies.associate { (key, _) -> key to ArrayList<String>() }
        var frontier: Map<String, String> = replies.associate { (key, r) -> key to r.replyToId!! }
        repeat(FEED_ROUNDS) {
            if (frontier.isEmpty()) return@repeat
            load(frontier.values.toSet(), known) // one batch for the whole page per round
            frontier = buildMap {
                for ((key, id) in frontier) {
                    val path = paths.getValue(key)
                    if (id in path) continue // cycle: stop
                    path += id
                    known[id]?.replyToId?.let { put(key, it) } // go on above a found reply
                }
            }
        }
        load(replies.mapNotNull { (key, r) -> r.rootId?.takeIf { needsRoot(paths.getValue(key), it, known) } }.toSet(), known)
        return replies.associate { (key, r) -> key to assemble(paths.getValue(key), r.rootId, known) }
    }

    private fun reachedRoot(path: List<String>, known: Map<String, Note?>) = known[path.last()]?.let { it.replyToId == null } == true

    private fun needsRoot(path: List<String>, rootId: String, known: Map<String, Note?>) = !reachedRoot(path, known) && rootId !in path

    /** Root first. A walk that stopped below the root gets the root in front: with a gap row when notes in between are unknown. */
    private fun assemble(path: List<String>, rootId: String?, known: Map<String, Note?>): Chain {
        val up = path.asReversed().map { slot(it, known) } // walk's top … direct parent
        if (reachedRoot(path, known) || rootId == null || rootId in path) return shape(up)
        val top = known[path.last()]
        val gap = top != null && top.replyToId != rootId // rounds used up below the root
        return if (gap) Chain(listOf(slot(rootId, known), up.last()), cut = true) else shape(listOf(slot(rootId, known)) + up)
    }

    /** X rule: up to [MAX_SHOWN] notes complete, more → root, gap, direct parent. */
    private fun shape(full: List<Slot>): Chain =
        if (full.size <= MAX_SHOWN) Chain(full, cut = false) else Chain(listOf(full.first(), full.last()), cut = true)

    override suspend fun ancestors(note: Note): List<Slot> {
        val known = HashMap<String, Note?>()
        val path = ArrayList<String>()
        var id = note.replyToId
        while (id != null && id !in path && path.size < MAX_HOPS) {
            load(setOf(id), known)
            path += id
            id = known[id]?.replyToId
        }
        val up = path.asReversed().map { slot(it, known) }
        // spec 2026-10-16 §4.1 / §2.2 step 4: a walk that stopped below the root (missing hop, hop limit) still gets the root on top
        val rootId = note.rootId?.takeIf { path.isNotEmpty() && it != note.id && !reachedRoot(path, known) && it !in path } ?: return up
        load(setOf(rootId), known)
        return listOf(slot(rootId, known)) + up
    }

    private fun slot(id: String, known: Map<String, Note?>): Slot = known[id]?.let { Slot.Shown(it) } ?: Slot.Unavailable(id)

    /** Local first; the rest in batches of [BATCH] ids from the relays; withdrawn notes count as missing. */
    private suspend fun load(ids: Set<String>, known: MutableMap<String, Note?>) {
        val wanted = ids.filter { it !in known }
        if (wanted.isEmpty()) return
        val missing = wanted.filter { runCatching { engine.eventById(it) }.getOrNull() == null }
        for (chunk in missing.chunked(BATCH)) quietly { engine.fetch(Filter().ids(chunk.map { EventId.parse(it) })) }
        withContext(Dispatchers.IO) {
            for (id in wanted) {
                val ev = runCatching { engine.eventById(id) }.getOrNull()
                known[id] = ev?.takeUnless { Withdrawn.isWithdrawn(engine, it) }?.let { NoteMapper.note(it) }
            }
        }
    }
}
