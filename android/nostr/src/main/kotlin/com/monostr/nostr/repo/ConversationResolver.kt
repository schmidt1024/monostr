package com.monostr.nostr.repo

import com.monostr.nostr.Exit
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Event
import com.monostr.tips.event.Event as JsonEvent

sealed interface Slot {
    val id: String
    data class Shown(val note: Note) : Slot { override val id: String get() = note.id }
    data class Unavailable(override val id: String) : Slot
}

/** The notes above a reply, root first … direct parent last; with [cut] the notes between ancestors[0] (the root) and ancestors[1] are left out (spec 2026-10-16 §2.2). */
data class Chain(val ancestors: List<Slot>, val cut: Boolean)

/**
 * Spec perf2 C2.4: the walk above a note through the database alone. [ancestors]: what
 * [ConversationRepository.ancestors] gives when every slot is stored and shown, else null (a thread never shows a
 * pending hop as unavailable). [missing]: the ids of that walk the database does not hold (the first missing hop,
 * the root on top), lower case; a stored note (also a withdrawn one) is never in it.
 */
data class LocalWalk(val ancestors: List<Slot>?, val missing: Set<String>)

interface ConversationRepository {
    /** Chains of the replies in [notes] (for a repost: of the reposted note), keyed by the id of the entry in [notes]. Entries that are no replies are absent. */
    suspend fun chains(notes: List<Note>): Map<String, Chain>
    /** Every note above [note] up to the root, root first; at most [MAX_HOPS]. A walk that stops below the root gets the root (by `rootId`) in front. */
    suspend fun ancestors(note: Note): List<Slot>
    /** Spec perf2 C2.4: [ancestors] through the database only, never a relay. The default (fakes) knows nothing. */
    suspend fun localAncestors(note: Note): LocalWalk = LocalWalk(null, emptySet())
    /** Spec perf2 C3 job W: asks the relays for those of [ids] the database does not hold, one ids request per 100; true when it asked. */
    suspend fun fetchMissing(ids: Set<String>): Boolean = false
}

/** Walk rounds for a feed chain: parent, grandparent, great-grandparent. */
const val FEED_ROUNDS = 3
/** Hard stop for the thread page's walk. */
const val MAX_HOPS = 50
/** X: at most this many notes above a reply in the feed. */
private const val MAX_SHOWN = 3
private const val BATCH = 100

/** A NIP-01 event id. Ids from foreign tags are checked against it (after lower-casing) before any filter is built. */
internal val HEX_ID = Regex("[0-9a-f]{64}")

class NostrConversationRepository(
    private val engine: NostrEngine,
    /** Where the decoding and mapping of a read runs (tests count its dispatches). */
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ConversationRepository {

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

    override suspend fun ancestors(note: Note): List<Slot> = walk(note, HashMap(), network = true)

    override suspend fun localAncestors(note: Note): LocalWalk {
        val stored = HashSet<String>()
        val slots = walk(note, HashMap(), network = false, stored = stored)
        val unavailable = slots.filterIsInstance<Slot.Unavailable>().map { it.id.lowercase() }
        if (unavailable.isEmpty()) return LocalWalk(slots, emptySet())
        val notStored = unavailable.filter { it !in stored }.toSet()
        // stored but withdrawn: no relay round changes it, so the walk is complete
        if (notStored.isEmpty()) return LocalWalk(slots, emptySet())
        return LocalWalk(null, notStored)
    }

    override suspend fun fetchMissing(ids: Set<String>): Boolean {
        val valid = ids.map { it.lowercase() }.filter { HEX_ID.matches(it) }.distinct()
        val missing = valid - readLocal(valid).stored
        if (missing.isEmpty()) return false
        fetchIds(missing)
        return true
    }

    /**
     * Root first ... direct parent last (spec 2026-10-16 4.1 / 2.2 step 4: a walk that stopped below the root, at a
     * missing hop or the hop limit, still gets the root on top). With [network] false only the database is read, so
     * the walk stops at the first hop it does not hold; [stored] collects the ids the database holds.
     */
    private suspend fun walk(note: Note, known: HashMap<String, Note?>, network: Boolean, stored: MutableSet<String>? = null): List<Slot> {
        val path = ArrayList<String>()
        var id = note.replyToId
        while (id != null && id !in path && path.size < MAX_HOPS) {
            load(setOf(id), known, network, stored)
            path += id
            id = known[id]?.replyToId
        }
        val up = path.asReversed().map { slot(it, known) }
        val rootId = note.rootId?.takeIf { path.isNotEmpty() && it != note.id && !reachedRoot(path, known) && it !in path } ?: return up
        load(setOf(rootId), known, network, stored)
        return listOf(slot(rootId, known)) + up
    }

    private fun slot(id: String, known: Map<String, Note?>): Slot = known[id]?.let { Slot.Shown(it) } ?: Slot.Unavailable(id)

    /**
     * Spec perf2 C5: one database read for [ids] (decoded once, one Withdrawn check for the set); with [network] the
     * ones the database does not hold come from the relays, one ids request per [BATCH], and are read again.
     * Withdrawn notes count as missing; a malformed id is missing without any query.
     */
    private suspend fun load(ids: Set<String>, known: MutableMap<String, Note?>, network: Boolean = true, stored: MutableSet<String>? = null) {
        val wanted = ids.filter { it !in known }
        if (wanted.isEmpty()) return
        val valid = wanted.map { it.lowercase() }.filter { HEX_ID.matches(it) }.distinct()
        var local = readLocal(valid)
        val missing = valid - local.stored
        if (network && missing.isNotEmpty()) {
            fetchIds(missing)
            local += readLocal(missing)
        }
        stored?.addAll(local.stored)
        for (id in wanted) known[id] = local.notes[id.lowercase()]
    }

    /** What one read found: the shown notes by id, and every id the database holds (also withdrawn ones). */
    private class Local(val notes: Map<String, Note>, val stored: Set<String>) {
        operator fun plus(other: Local) = Local(notes + other.notes, stored + other.stored)
    }

    private suspend fun readLocal(ids: List<String>): Local {
        if (ids.isEmpty()) return Local(emptyMap(), emptySet())
        val rows = ArrayList<Pair<Event, JsonEvent>>()
        for (chunk in ids.chunked(BATCH)) {
            try {
                rows += engine.queryBoth(idsFilter(chunk))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a failing read leaves its ids missing, as a failing eventById did
            }
        }
        // off the caller (the feed asks from Main): the media parsing of the mapper is no work for it
        return withContext(workDispatcher) {
            val shown = Withdrawn.filterDecoded(engine, rows)
            Local(
                shown.mapNotNull { (_, ev) -> NoteMapper.note(ev)?.let { ev.id to it } }.toMap(),
                rows.mapTo(HashSet()) { it.second.id },
            )
        }
    }

    /** One ids request per [BATCH]; spec wave 2 B4: it ends at the first relays that have every id of the chunk. */
    private suspend fun fetchIds(ids: List<String>) {
        for (chunk in ids.chunked(BATCH)) quietly { engine.fetch(idsFilter(chunk), exit = Exit.AllIds(chunk.toSet())) }
    }
}
