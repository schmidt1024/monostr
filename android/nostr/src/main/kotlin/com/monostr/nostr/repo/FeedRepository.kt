package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    suspend fun notes(limit: Int = 50, until: Long? = null): List<Note>
    suspend fun refresh(limit: Int = 50)
    suspend fun loadMore(before: Long, limit: Int = 50): List<Note>
    fun live(limit: Int = 50): Flow<List<Note>>
    /** Kind 1 and 6 by one author, newest first; fetches from relays when [fetch] is set. */
    suspend fun notesBy(author: String, limit: Int = 50, fetch: Boolean = true): List<Note>
    /** The newest [limit] notes of one profile tab ([section]), newest first; fetches the author's latest page from relays when [fetch] is set. */
    suspend fun profileNotes(author: String, section: ProfileSection, limit: Int = 50, fetch: Boolean = true): List<Note>
    /** The [section]'s notes before [before], fetched from relays first; paging works like [loadMore]. */
    suspend fun moreProfileNotes(author: String, section: ProfileSection, before: Long, limit: Int = 50): List<Note>
}

/** The tabs of a profile: "Posts" shows what the home feed would show of the author, "Replies" their replies. */
enum class ProfileSection {
    POSTS,
    REPLIES;

    fun keeps(note: Note): Boolean = when (this) {
        POSTS -> isRoot(note)
        REPLIES -> note.isReply
    }
}

/** Spec "feed without replies": a reply, or a repost of one, belongs to its thread and not to the feed. */
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

    private fun feedFilter(authors: List<String>) =
        Filter().kinds(listOf(Kind(1u), Kind(6u))).authors(authors.map { PublicKey.parse(it) })

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
     * requests, and paging would stop there for good.
     */
    private suspend fun fetchPage(authors: List<String>, limit: Int, until: Long? = null) {
        val untilTs = until?.let { Timestamp.fromSecs(it.toULong()) }
        var notes = feedFilter(authors).limit(limit.toULong())
        var requests = Filter().kind(Kind(5u)).authors(authors.map { PublicKey.parse(it) }).limit(DELETION_LIMIT)
        if (untilTs != null) {
            notes = notes.until(untilTs)
            requests = requests.until(untilTs)
        }
        quietly { engine.fetch(notes) }
        quietly { engine.fetch(requests) }
    }

    /** The home feed's authors: the follows without [excluded]. */
    private suspend fun feedAuthors(): List<String> = follows() - excluded()

    /**
     * Latest [limit] root notes before [until] from the local database (spec "feed without replies": a reply, or a
     * repost of one, belongs to its thread and not to the feed). Relays cannot leave replies out, so a page may be
     * all replies: the read goes on below it, up to [LOCAL_ROUNDS] pages, until [limit] notes are together or the
     * database has nothing older. Main-safe: the mapping runs on [Dispatchers.IO].
     */
    override suspend fun notes(limit: Int, until: Long?): List<Note> = localPage(feedAuthors(), ::isRoot, limit, until)

    /** The local read behind [notes] and [profileNotes]: pages of [authors]' notes, [keep] applied, until [limit] are together. */
    private suspend fun localPage(authors: List<String>, keep: (Note) -> Boolean, limit: Int, until: Long?): List<Note> = withContext(Dispatchers.IO) {
        val kept = ArrayList<Note>()
        var before = until
        repeat(LOCAL_ROUNDS) {
            val raw = engine.query(pageFilter(authors, limit, before))
            kept += NoteLoader.notes(engine, raw).filter(keep)
            if (kept.size >= limit || raw.size < limit) return@withContext kept.take(limit).sortedByDescending { it.createdAt }
            before = raw.minOf { it.createdAt().asSecs().toLong() } - 1
        }
        kept.sortedByDescending { it.createdAt }
    }

    private fun pageFilter(authors: List<String>, limit: Int, until: Long?): Filter {
        var filter = feedFilter(authors).limit(limit.toULong())
        if (until != null) filter = filter.until(Timestamp.fromSecs(until.toULong()))
        return filter
    }

    /** Pulls the newest page from relays into the database. */
    override suspend fun refresh(limit: Int) {
        fetchPage(feedAuthors(), limit)
    }

    /**
     * Older page: fetches from relays, then returns local root notes before [before]. A relay page made of replies
     * alone yields nothing; then the next page below the oldest event fetched is asked for, up to [RELAY_ROUNDS]
     * times, so the list does not stick to the same page.
     */
    override suspend fun loadMore(before: Long, limit: Int): List<Note> = relayPage(feedAuthors(), ::isRoot, before, limit)

    /** The relay paging behind [loadMore] and [moreProfileNotes]. */
    private suspend fun relayPage(authors: List<String>, keep: (Note) -> Boolean, before: Long, limit: Int): List<Note> {
        var until = before
        repeat(RELAY_ROUNDS) {
            fetchPage(authors, limit, until = until)
            val page = localPage(authors, keep, limit, until)
            if (page.isNotEmpty()) return page
            val raw = withContext(Dispatchers.IO) { engine.query(pageFilter(authors, limit, until)) }
            if (raw.isEmpty()) return page
            until = raw.minOf { it.createdAt().asSecs().toLong() } - 1
        }
        return emptyList()
    }

    /**
     * Live feed: subscribes for new notes and emits the refreshed list on arrival. A burst of
     * events (e.g. a relay refresh running in parallel) is conflated into one re-query, and the
     * work runs on [Dispatchers.IO] so collectors on the main thread are never starved.
     */
    override fun live(limit: Int): Flow<List<Note>> = flow {
        // read once per start: a mute during the session takes effect when the caller restarts the flow
        val authors = feedAuthors()
        emit(notes(limit))
        // best-effort: without relays (or on a relay error) the local list stays and no live updates arrive
        val subId = try {
            engine.subscribe(liveFilter(authors).since(Timestamp.fromSecs(now().toULong())))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        try {
            engine.events
                .filter { ev ->
                    val kind = ev.kind().asU16().toInt()
                    (kind == 1 || kind == 6 || kind == 5) && ev.author().toHex() in authors
                }
                .conflate()
                .collect { emit(notes(limit)) }
        } finally {
            if (subId != null) withContext(NonCancellable) { quietly { engine.unsubscribe(subId) } }
        }
    }.buffer(Channel.RENDEZVOUS).flowOn(Dispatchers.IO) // rendezvous: each list is handed over before the producer moves on

    override suspend fun notesBy(author: String, limit: Int, fetch: Boolean): List<Note> {
        if (fetch) fetchPage(listOf(author), limit)
        return NoteLoader.notes(engine, engine.query(feedFilter(listOf(author)).limit(limit.toULong()))).sortedByDescending { it.createdAt }
    }

    override suspend fun profileNotes(author: String, section: ProfileSection, limit: Int, fetch: Boolean): List<Note> {
        if (fetch) fetchPage(listOf(author), limit)
        return localPage(listOf(author), section::keeps, limit, null)
    }

    override suspend fun moreProfileNotes(author: String, section: ProfileSection, before: Long, limit: Int): List<Note> =
        relayPage(listOf(author), section::keeps, before, limit)
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
