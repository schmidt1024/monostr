package com.monostr.nostr.repo

import com.monostr.nostr.Exit
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.AddressRef
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import com.monostr.nostr.model.parentCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Event
import com.monostr.tips.event.Event as JsonEvent

sealed interface Slot {
    val id: String
    data class Shown(val note: Note) : Slot { override val id: String get() = note.id }
    data class Unavailable(override val id: String) : Slot
    /** Spec 2026-10-29 article reader: the long-form article a comment sits under, shown as a card; [id] is its coordinate. */
    data class Article(val ref: AddressRef) : Slot { override val id: String get() = ref.coordinate }
    /** Spec 2026-10-23 §4.2: missing after the database and the own relays, but the [EventLocator] may still find it at [wanted]. */
    data class Pending(override val id: String, val wanted: Wanted) : Slot
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
    /**
     * Spec 2026-10-23 §10 (final review M1): [chains] for the feed's walks of one resolve job; ids in [asked] are not asked
     * from the own relays again, every id it does ask is added. The default (fakes) ignores it.
     */
    suspend fun chains(notes: List<Note>, asked: MutableSet<String>): Map<String, Chain> = chains(notes)
    /** Every note above [note] up to the root, root first; at most [MAX_HOPS]. A walk that stops below the root gets the root (by `rootId`) in front. */
    suspend fun ancestors(note: Note): List<Slot>
    /**
     * Spec 2026-10-23 §10: [ancestors] for the re-walk after a stage 3 round; ids in [asked] are not asked from the own
     * relays again (they missed there in this walk), every id it does ask is added. The default (fakes) ignores it.
     */
    suspend fun ancestors(note: Note, asked: MutableSet<String>): List<Slot> = ancestors(note)
    /** Spec perf2 C2.4: [ancestors] through the database only, never a relay. The default (fakes) knows nothing. */
    suspend fun localAncestors(note: Note): LocalWalk = LocalWalk(null, emptySet())
    /** Spec perf2 C3 job W: asks the relays for those of [ids] the database does not hold, one ids request per 100; true when it asked. */
    suspend fun fetchMissing(ids: Set<String>): Boolean = false
    /** Spec 2026-10-23 §4.2: looks for [pending] on foreign relays (one locator round); returns the ids now in the database. */
    suspend fun resolve(pending: Collection<Slot.Pending>): Set<String> = emptySet()
    /** Emits when the locator forgot its marks: pending gaps are worth another [resolve]. */
    val retries: Flow<Unit> get() = emptyFlow()
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
    /** Stage 3 for feed chains (spec 2026-10-23 §4.2); without one every missing hop is unavailable. */
    private val locator: EventLocator? = null,
    /** Where the decoding and mapping of a read runs (tests count its dispatches). */
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ConversationRepository {

    override suspend fun chains(notes: List<Note>): Map<String, Chain> = walkChains(notes, null)

    override suspend fun chains(notes: List<Note>, asked: MutableSet<String>): Map<String, Chain> = walkChains(notes, asked)

    private suspend fun walkChains(notes: List<Note>, asked: MutableSet<String>?): Map<String, Chain> {
        // the entry id → the reply whose chain it shows (a repost shows the reposted note's chain)
        val replies = notes.mapNotNull { n -> (n.repostOf ?: n).takeIf { it.isReply || it.articleParent != null }?.let { n.id to it } }
        if (replies.isEmpty()) return emptyMap()
        val known = HashMap<String, Note?>()
        // only with a locator: the ids the database holds (a withdrawn note is held, so never pending)
        val stored = locator?.let { HashSet<String>() }
        // per entry: the ids walked, direct parent first, upwards
        val paths = replies.associate { (key, _) -> key to ArrayList<String>() }
        val reply = replies.toMap()
        // spec 2026-10-29 article reader (final review M11): like [walk], a comment under an article has the article above it,
        // shown as a card; its id (the article's event) is never looked up as a note
        val articles = HashMap<String, AddressRef>()
        for ((key, r) in replies) r.articleParent?.let { articles[key] = it }
        var frontier: Map<String, String> = replies.filter { (key, _) -> key !in articles }.associate { (key, r) -> key to r.replyToId!! }
        repeat(FEED_ROUNDS) {
            if (frontier.isEmpty()) return@repeat
            load(frontier.values.toSet(), known, stored = stored, asked = asked) // one batch for the whole page per round
            frontier = buildMap {
                for ((key, id) in frontier) {
                    val path = paths.getValue(key)
                    if (id in path || id == reply.getValue(key).id) continue // cycle: stop
                    path += id
                    val found = known[id] ?: continue
                    val article = found.articleParent
                    if (article != null) articles[key] = article else found.replyToId?.let { put(key, it) } // go on above a found reply
                }
            }
        }
        val walks = replies.filter { (key, _) -> key !in articles }
        load(walks.mapNotNull { (key, r) -> r.rootId?.takeIf { needsRoot(paths.getValue(key), it, known) } }.toSet(), known, stored = stored, asked = asked)
        val pending = if (locator == null) emptyMap() else pending(locator, replies, paths, known, stored!!, articles.keys)
        return replies.associate { (key, r) ->
            val article = articles[key]
            key to if (article != null) shape(listOf<Slot>(Slot.Article(article)) + paths.getValue(key).asReversed().map { slot(it, known, pending) }) else assemble(paths.getValue(key), r.rootId, known, pending)
        }
    }

    /**
     * Spec 2026-10-23 §4.2: where to look for each hop the walk did not find (not in the database, not withdrawn) and the
     * locator may still search: a path hop from the hints of the note below it, the root from the reply's root hint
     * (its p candidates when the hint names no author). Hints of several entries for one id are merged.
     */
    private suspend fun pending(
        locator: EventLocator,
        replies: List<Pair<String, Note>>,
        paths: Map<String, List<String>>,
        known: Map<String, Note?>,
        stored: Set<String>,
        /** Entries whose walk ended at an article: no root is wanted for them. */
        underArticle: Set<String> = emptySet(),
    ): Map<String, Wanted> {
        val wants = Wants(known, stored)
        for ((key, r) in replies) {
            val path = paths.getValue(key)
            wants.path(r, path)
            if (key in underArticle) continue
            val rootId = r.rootId ?: continue
            if (needsRoot(path, rootId, known)) wants.root(rootId, r)
        }
        return wants.searchable(locator)
    }

    /** The [Wanted] of the gaps of one or more walks, merged per id; an id the database holds or that is malformed is never wanted. */
    private class Wants(private val known: Map<String, Note?>, private val stored: Set<String>) {
        private val wanted = HashMap<String, Wanted>()

        /** Each hop of [path] (direct parent of [reply] first, upwards) from the hints of the note below it. */
        fun path(reply: Note, path: List<String>) = path.forEachIndexed { i, id ->
            val below = if (i == 0) reply else known[path[i - 1]] ?: return@forEachIndexed
            want(id, below.replyHint?.relay, below.parentCandidates())
        }

        /** The root from [reply]'s root hint, its p candidates when the hint names no author. */
        fun root(rootId: String, reply: Note) =
            want(rootId, reply.rootHint?.relay, listOfNotNull(reply.rootHint?.author).ifEmpty { reply.parentCandidates() })

        private fun want(id: String, relay: String?, authors: List<String>) {
            val lower = id.lowercase()
            if (known[id] != null || lower in stored || !HEX_ID.matches(lower)) return
            val w = Wanted(lower, listOfNotNull(relay), authors.distinct())
            wanted[id] = wanted[id]?.let { it.copy(relays = (it.relays + w.relays).distinct(), authors = (it.authors + w.authors).distinct()) } ?: w
        }

        /** One batched check for the missing ids (the switch read once), none on the found path. */
        suspend fun searchable(locator: EventLocator): Map<String, Wanted> {
            if (wanted.isEmpty()) return emptyMap()
            val ok = locator.searchable(wanted.keys.map { it.lowercase() }).toHashSet()
            return wanted.filter { (id, _) -> id.lowercase() in ok }
        }
    }

    override suspend fun resolve(pending: Collection<Slot.Pending>): Set<String> {
        val locator = locator ?: return emptySet()
        if (pending.isEmpty()) return emptySet()
        return locator.find(pending.distinctBy { it.id }.map { it.wanted })
    }

    override val retries: Flow<Unit> get() = locator?.retries ?: emptyFlow()

    private fun reachedRoot(path: List<String>, known: Map<String, Note?>) = path.isNotEmpty() && known[path.last()]?.let { it.replyToId == null } == true

    /** An empty [path] (a crafted note naming itself as parent) has nothing above it, the root neither. */
    private fun needsRoot(path: List<String>, rootId: String, known: Map<String, Note?>) = path.isNotEmpty() && !reachedRoot(path, known) && rootId !in path

    /** Root first. A walk that stopped below the root gets the root in front: with a gap row when notes in between are unknown. */
    private fun assemble(path: List<String>, rootId: String?, known: Map<String, Note?>, pending: Map<String, Wanted>): Chain {
        val up = path.asReversed().map { slot(it, known, pending) } // walk's top … direct parent
        if (path.isEmpty() || reachedRoot(path, known) || rootId == null || rootId in path) return shape(up)
        val top = known[path.last()]
        val gap = top != null && top.replyToId != rootId // rounds used up below the root
        val root = slot(rootId, known, pending)
        return if (gap) Chain(listOf(root, up.last()), cut = true) else shape(listOf(root) + up)
    }

    /** X rule (a pending slot counts like an unavailable one): up to [MAX_SHOWN] notes complete, more → root, gap, direct parent. */
    private fun shape(full: List<Slot>): Chain =
        if (full.size <= MAX_SHOWN) Chain(full, cut = false) else Chain(listOf(full.first(), full.last()), cut = true)

    override suspend fun ancestors(note: Note): List<Slot> = walk(note, HashMap(), network = true)

    override suspend fun ancestors(note: Note, asked: MutableSet<String>): List<Slot> = walk(note, HashMap(), network = true, asked = asked)

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
     * the walk stops at the first hop it does not hold; [stored] collects the ids the database holds. With [network]
     * and a locator, a gap the locator may still search is [Slot.Pending] (spec 2026-10-23 §5.2), same hints as [chains];
     * [asked]: see [ConversationRepository.ancestors].
     */
    private suspend fun walk(
        note: Note,
        known: HashMap<String, Note?>,
        network: Boolean,
        stored: MutableSet<String>? = null,
        asked: MutableSet<String>? = null,
    ): List<Slot> {
        val held = stored ?: if (network && locator != null) HashSet() else null
        val path = ArrayList<String>()
        // spec 2026-10-29 article reader: a comment directly under an article has the article above it, not a note to load
        var article: AddressRef? = note.articleParent
        var id = if (article != null) null else note.replyToId
        while (id != null && id !in path && path.size < MAX_HOPS) {
            load(setOf(id), known, network, held, asked)
            path += id
            val next = known[id]
            article = next?.articleParent
            id = if (article != null) null else next?.replyToId
        }
        val rootId = note.rootId?.takeIf { article == null && path.isNotEmpty() && it != note.id && !reachedRoot(path, known) && it !in path }
        if (rootId != null) load(setOf(rootId), known, network, held, asked)
        val pending = if (!network || locator == null || held == null) emptyMap() else Wants(known, held).run {
            path(note, path)
            if (rootId != null) root(rootId, note)
            searchable(locator)
        }
        val up = path.asReversed().map { slot(it, known, pending) }
        val tail = if (rootId == null) up else listOf(slot(rootId, known, pending)) + up
        return article?.let { listOf<Slot>(Slot.Article(it)) + tail } ?: tail
    }

    private fun slot(id: String, known: Map<String, Note?>, pending: Map<String, Wanted> = emptyMap()): Slot =
        known[id]?.let { Slot.Shown(it) } ?: pending[id]?.let { Slot.Pending(id, it) } ?: Slot.Unavailable(id)

    /**
     * Spec perf2 C5: one database read for [ids] (decoded once, one Withdrawn check for the set); with [network] the
     * ones the database does not hold come from the relays, one ids request per [BATCH], and are read again.
     * Withdrawn notes count as missing; a malformed id is missing without any query. Ids in [asked] are not fetched
     * again; the ones fetched are added to it.
     */
    private suspend fun load(
        ids: Set<String>,
        known: MutableMap<String, Note?>,
        network: Boolean = true,
        stored: MutableSet<String>? = null,
        asked: MutableSet<String>? = null,
    ) {
        val wanted = ids.filter { it !in known }
        if (wanted.isEmpty()) return
        val valid = wanted.map { it.lowercase() }.filter { HEX_ID.matches(it) }.distinct()
        var local = readLocal(valid)
        val missing = (valid - local.stored).filter { asked == null || it !in asked }
        if (network && missing.isNotEmpty()) {
            fetchIds(missing)
            asked?.addAll(missing)
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
