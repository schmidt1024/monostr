package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp

/** Home feed and per-author timelines. */
interface FeedRepository {
    suspend fun follows(): List<String>
    /** Like [follows] but from the database only: never a relay fetch, empty when no kind 3 is stored (no network while typing). */
    suspend fun followsLocal(): List<String>
    /**
     * Epoch seconds of the last relay fetch that returned the user's kind 3 in this session; null
     * before one (spec 2: a local list counts as current for five minutes after it).
     */
    val contactsFetchedAt: Long? get() = null
    fun markContactsFetched(at: Long) {}
    /** The home feed ([FeedSource.Following]) or a list's feed ([FeedSource.Authors]): notes, reposts and replies, newest first (spec 2026-10-16 §2.1, 2026-10-18 §4.1). */
    suspend fun notes(source: FeedSource = FeedSource.Following, limit: Int = 50, until: Long? = null): List<Note>
    suspend fun refresh(source: FeedSource = FeedSource.Following, limit: Int = 50)
    suspend fun loadMore(source: FeedSource = FeedSource.Following, before: Long, limit: Int = 50): List<Note>
    fun live(source: FeedSource = FeedSource.Following, limit: Int = 50): Flow<List<Note>>
    /** Kind 1 and 6 by one author, newest first; fetches from relays when [fetch] is set. */
    suspend fun notesBy(author: String, limit: Int = 50, fetch: Boolean = true): List<Note>
    /** The newest [limit] notes of one profile tab ([section]), newest first; fetches the author's latest page from relays when [fetch] is set. */
    suspend fun profileNotes(author: String, section: ProfileSection, limit: Int = 50, fetch: Boolean = true): List<Note>
    /** The [section]'s notes before [before], fetched from relays first; paging works like [loadMore]. */
    suspend fun moreProfileNotes(author: String, section: ProfileSection, before: Long, limit: Int = 50): List<Note>
}

/** The tabs of a profile, like X: "Posts" the author's own root notes, "Replies" their replies, "Reposts" what they reposted. */
enum class ProfileSection(internal val kinds: List<Int>) {
    POSTS(listOf(1)),
    REPLIES(listOf(1)),
    REPOSTS(listOf(6));

    fun keeps(note: Note): Boolean = when (this) {
        POSTS -> note.repostOf == null && isRoot(note)
        REPLIES -> note.isReply
        REPOSTS -> note.repostOf != null
    }
}

/** Whose notes a feed shows (spec 2026-10-18 §4.1). */
sealed interface FeedSource {
    /** The home feed: the follows and the user. */
    data object Following : FeedSource

    /** A list's feed: its members (malformed entries are skipped). */
    data class Authors(val pubkeys: List<String>) : FeedSource
}

/** Relays cap the authors of a filter: larger author sets go out as several filters (spec 2026-10-18 §4.1). */
internal const val AUTHOR_CHUNK = 200

/** The largest page a list feed asks for when its request also names the follows (relays cap limits near 500). */
internal const val COVER_LIMIT = 500

private val HEX64 = Regex("[0-9a-f]{64}")

/** The home feed's kinds: notes and reposts. */
private val FEED_KINDS = listOf(1, 6)

/** A root note: neither a reply nor a repost of one (the profile's "Posts" section). */
internal fun isRoot(note: Note): Boolean = !(note.isReply || note.repostOf?.isReply == true)

/** Runs a best-effort relay call: failures are ignored, cancellation still propagates. */
/** Local pages read past replies per [FeedRepository.notes] call. */
private const val LOCAL_ROUNDS = 10

/** Relay pages fetched past replies per [FeedRepository.loadMore] call. */
private const val RELAY_ROUNDS = 3

internal suspend fun quietly(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // local-first: a relay problem must not break the caller
    }
}

/**
 * Home feed: kind 1 and 6 from the accounts the user follows (kind 3),
 * newest first, read from the local database. [refresh] pulls the latest
 * page from relays; [live] keeps a subscription open and re-emits.
 */
class NostrFeedRepository(
    private val engine: NostrEngine,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * Authors whose own notes are left out of the home feed (the muted accounts), at the query so they
     * cannot fill a page; reposts of their notes by others are still filtered in the UI. [notesBy] and the profile reads ignore it.
     */
    private val excluded: () -> Set<String> = { emptySet() },
) : FeedRepository {
    private val pubkey = requireNotNull(engine.pubkey) { "feed needs a logged-in user" }
    @Volatile private var fetchedAt: Long? = null
    override val contactsFetchedAt: Long? get() = fetchedAt
    override fun markContactsFetched(at: Long) { fetchedAt = at }

    /** Followed pubkeys from the newest kind 3 of the user (local, then relays). Always includes the user. */
    override suspend fun follows(): List<String> {
        var contacts = storedContacts()
        if (contacts == null) {
            quietly { engine.fetch(Filter().kind(Kind(3u)).author(PublicKey.parse(pubkey)).limit(1u)) }
            contacts = storedContacts()
            if (contacts != null) markContactsFetched(now())
        }
        return followed(contacts)
    }

    override suspend fun followsLocal(): List<String> = followed(storedContacts())

    private suspend fun storedContacts() = engine.query(Filter().kind(Kind(3u)).author(PublicKey.parse(pubkey)).limit(1u)).firstOrNull()

    private fun followed(contacts: rust.nostr.sdk.Event?): List<String> =
        ((contacts?.tags()?.publicKeys()?.map { it.toHex() } ?: emptyList()) + pubkey).distinct()

    private fun feedFilter(authors: List<String>, kinds: List<Int> = FEED_KINDS) =
        Filter().kinds(kinds.map { Kind(it.toUShort()) }).authors(authors.map { PublicKey.parse(it) })

    /**
     * What the live subscription asks for: the notes and their authors' deletion requests (NIP-09, kind 5),
     * so that a withdrawn note leaves the local database. The database is never queried with this
     * filter: the requests would take places in the page.
     */
    private fun liveFilter(authors: List<String>) =
        Filter().kinds(listOf(Kind(1u), Kind(6u), Kind(5u))).authors(authors.map { PublicKey.parse(it) })

    /**
     * Fetches a page of notes and, apart from it, the same authors' deletion requests. Two requests and
     * not one filter: under one limit an account that withdrew many notes would fill the page with
     * requests, and paging would stop there for good. More than [AUTHOR_CHUNK] authors go out as
     * several filters, side by side.
     */
    private suspend fun fetchPage(authors: List<String>, limit: Int, until: Long? = null, kinds: List<Int> = FEED_KINDS) {
        if (authors.isEmpty()) return
        val untilTs = until?.let { Timestamp.fromSecs(it.toULong()) }
        coroutineScope {
            authors.chunked(AUTHOR_CHUNK).map { chunk ->
                async {
                    var notes = feedFilter(chunk, kinds).limit(limit.toULong())
                    var requests = Filter().kind(Kind(5u)).authors(chunk.map { PublicKey.parse(it) }).limit(DELETION_LIMIT)
                    if (untilTs != null) {
                        notes = notes.until(untilTs)
                        requests = requests.until(untilTs)
                    }
                    quietly { engine.fetch(notes) }
                    quietly { engine.fetch(requests) }
                }
            }.awaitAll()
        }
    }

    /** The authors of [source] without [excluded]. */
    private suspend fun authorsOf(source: FeedSource): List<String> = when (source) {
        FeedSource.Following -> follows()
        is FeedSource.Authors -> source.pubkeys.filter { it.matches(HEX64) }.distinct()
    } - excluded()

    /**
     * The authors a relay request for [source] names. A list's members alone would tell the relays who is on
     * the list (a private list too), so a list asks for the home feed's authors plus the members it does not
     * follow; members that are all followed give the same author set as the home feed (spec 2026-10-18 §4.2).
     */
    private suspend fun relayAuthorsOf(source: FeedSource, members: List<String>): List<String> = when (source) {
        FeedSource.Following -> members
        is FeedSource.Authors -> if (members.isEmpty()) members else ((follows() - excluded()) + members).distinct()
    }

    /** A page size for [cover] that still holds about [limit] notes of [members]. */
    private fun coverLimit(limit: Int, members: List<String>, cover: List<String>): Int =
        if (cover.size <= members.size) limit else minOf(COVER_LIMIT, limit * ((cover.size + members.size - 1) / members.size))

    /** Latest [limit] notes of [source] before [until] from the local database, replies included. Main-safe: the mapping runs on [Dispatchers.IO]. */
    override suspend fun notes(source: FeedSource, limit: Int, until: Long?): List<Note> = localPage(authorsOf(source), { true }, limit, until)

    /** The local read behind [notes] and [profileNotes]: pages of [authors]' notes, [keep] applied, until [limit] are together. */
    private suspend fun localPage(authors: List<String>, keep: (Note) -> Boolean, limit: Int, until: Long?, kinds: List<Int> = FEED_KINDS): List<Note> {
        // no authors must not become a filter without authors, which matches everybody
        if (authors.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val kept = ArrayList<Note>()
            var before = until
            repeat(LOCAL_ROUNDS) {
                val raw = engine.query(pageFilter(authors, limit, before, kinds))
                kept += NoteLoader.notes(engine, raw).filter(keep)
                if (kept.size >= limit || raw.size < limit) return@withContext kept.take(limit).sortedByDescending { it.createdAt }
                before = raw.minOf { it.createdAt().asSecs().toLong() } - 1
            }
            kept.sortedByDescending { it.createdAt }
        }
    }

    private fun pageFilter(authors: List<String>, limit: Int, until: Long?, kinds: List<Int> = FEED_KINDS): Filter {
        var filter = feedFilter(authors, kinds).limit(limit.toULong())
        if (until != null) filter = filter.until(Timestamp.fromSecs(until.toULong()))
        return filter
    }

    /** Pulls the newest page of [source] from relays into the database. */
    override suspend fun refresh(source: FeedSource, limit: Int) {
        val members = authorsOf(source)
        val cover = relayAuthorsOf(source, members)
        fetchPage(cover, coverLimit(limit, members, cover))
    }

    /** Older page: fetches from relays, then returns the local notes before [before]. */
    override suspend fun loadMore(source: FeedSource, before: Long, limit: Int): List<Note> {
        val members = authorsOf(source)
        return relayPage(members, { true }, before, limit, cover = relayAuthorsOf(source, members))
    }

    /** The relay paging behind [loadMore] and [moreProfileNotes]: asks relays for [cover], returns [authors]' notes. */
    private suspend fun relayPage(
        authors: List<String>,
        keep: (Note) -> Boolean,
        before: Long,
        limit: Int,
        kinds: List<Int> = FEED_KINDS,
        cover: List<String> = authors,
    ): List<Note> {
        if (authors.isEmpty()) return emptyList()
        val fetchLimit = coverLimit(limit, authors, cover)
        var until = before
        repeat(RELAY_ROUNDS) {
            fetchPage(cover, fetchLimit, until = until, kinds = kinds)
            val page = localPage(authors, keep, limit, until, kinds)
            if (page.isNotEmpty()) return page
            val raw = withContext(Dispatchers.IO) { engine.query(pageFilter(cover, fetchLimit, until, kinds)) }
            if (raw.isEmpty()) return page
            until = raw.minOf { it.createdAt().asSecs().toLong() } - 1
        }
        return emptyList()
    }

    /**
     * Live feed of [source]: subscribes for new notes (one subscription per [AUTHOR_CHUNK] authors asked for) and emits
     * the refreshed list on arrival. A burst of events is conflated into one re-query, and the work runs on
     * [Dispatchers.IO] so collectors on the main thread are never starved. No authors: one empty list, no subscription.
     */
    override fun live(source: FeedSource, limit: Int): Flow<List<Note>> = flow {
        // read once per start: a mute during the session takes effect when the caller restarts the flow
        val authors = authorsOf(source)
        emit(notes(source, limit))
        if (authors.isEmpty()) return@flow
        val wanted = authors.toHashSet()
        // best-effort: without relays (or on a relay error) the local list stays and no live updates arrive
        // the subscription names the cover, not the members (see relayAuthorsOf); only members' events re-query
        val subIds = relayAuthorsOf(source, authors).chunked(AUTHOR_CHUNK).mapNotNull { chunk ->
            try {
                engine.subscribe(liveFilter(chunk).since(Timestamp.fromSecs(now().toULong())))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        try {
            engine.events
                .filter { ev ->
                    val kind = ev.kind().asU16().toInt()
                    (kind == 1 || kind == 6 || kind == 5) && ev.author().toHex() in wanted
                }
                .conflate()
                .collect { emit(notes(source, limit)) }
        } finally {
            if (subIds.isNotEmpty()) withContext(NonCancellable) { subIds.forEach { id -> quietly { engine.unsubscribe(id) } } }
        }
    }.buffer(Channel.RENDEZVOUS).flowOn(Dispatchers.IO) // rendezvous: each list is handed over before the producer moves on

    override suspend fun notesBy(author: String, limit: Int, fetch: Boolean): List<Note> {
        if (fetch) fetchPage(listOf(author), limit)
        return NoteLoader.notes(engine, engine.query(feedFilter(listOf(author)).limit(limit.toULong()))).sortedByDescending { it.createdAt }
    }

    override suspend fun profileNotes(author: String, section: ProfileSection, limit: Int, fetch: Boolean): List<Note> {
        if (fetch) fetchPage(listOf(author), limit, kinds = section.kinds)
        return localPage(listOf(author), section::keeps, limit, null, section.kinds)
    }

    override suspend fun moreProfileNotes(author: String, section: ProfileSection, before: Long, limit: Int): List<Note> =
        relayPage(listOf(author), section::keeps, before, limit, section.kinds)
}

/**
 * Maps events to notes, resolving bare kind 6 reposts (`e` tag only) from the local database first.
 * Verified embedded originals are stored so likes and reposts of them find the event; a repost whose
 * original the database refuses (withdrawn by its author, NIP-09) is dropped.
 */
internal object NoteLoader {
    suspend fun notes(engine: NostrEngine, all: List<rust.nostr.sdk.Event>): List<Note> {
        val events = Withdrawn.filter(engine, all)
        val referenced = HashMap<String, rust.nostr.sdk.Event?>()
        val withdrawn = HashSet<String>()
        for (ev in events) {
            if (ev.kind().asU16().toInt() != 6) continue
            val embedded = NoteMapper.verifiedEmbedded(ev)
            if (embedded != null) {
                quietly { engine.save(embedded) }
                // the copy in the repost's content must not bring back what its author withdrew; a failing read keeps the repost
                val stored = runCatching { engine.eventById(embedded.id().toHex()) }
                if (stored.isSuccess && stored.getOrNull() == null) withdrawn += ev.id().toHex()
            } else {
                val id = ev.tags().toVec().map { it.asVec() }.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: continue
                if (id !in referenced) referenced[id] = runCatching { engine.eventById(id) }.getOrNull()
            }
        }
        return events.filterNot { it.id().toHex() in withdrawn }.mapNotNull { NoteMapper.note(it) { id -> referenced[id] } }
    }
}

/** Deletion requests fetched with a page of notes, under their own limit. */
private const val DELETION_LIMIT = 200uL
