package com.monostr.nostr.repo

import com.monostr.tips.Kinds
import kotlinx.coroutines.flow.emptyFlow
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.RefreshGate
import com.monostr.nostr.model.AddressableEvent
import com.monostr.nostr.model.Article
import com.monostr.nostr.model.Nip10
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMapper
import com.monostr.tips.event.Event as JsonEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import rust.nostr.sdk.EventId
import com.monostr.nostr.Exit
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp

/** Home feed and per-author timelines. */
interface FeedRepository {
    /** Backlog 60 / owner 2026-11-12: ids of reposted originals that arrived after their list was read (a bare kind 6 showed «not available»). */
    val repostOriginals: Flow<Set<String>> get() = emptyFlow()
    /** [repost] (a kind 6 whose original was missing) mapped again from the database; null while the original is still missing. */
    suspend fun reloaded(repost: Note): Note? = null
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
    /**
     * Spec perf 3.5: one relay refresh of [author] for all profile tabs (kind 1 and kind 6 under their own limits, and the
     * author's deletion requests), in the background; again only after the session TTL. Waiting callers may leave, the fetch goes on.
     */
    suspend fun refreshProfile(author: String, limit: Int = 50) {}
    /** The [section]'s notes before [before], fetched from relays first; paging works like [loadMore]. */
    suspend fun moreProfileNotes(author: String, section: ProfileSection, before: Long, limit: Int = 50): List<Note>
    /**
     * The author's articles (kind 30023 only, never drafts), newest by `published_at` else created_at, the newest version per
     * `d`, deleted ones left out; the database, after one relay fetch of articles and deletion requests when [fetch] is set.
     */
    suspend fun profileArticles(author: String, fetch: Boolean = true): List<Article> = emptyList()
    /** Whether the database holds a visible article among [author]'s newest few; no relay is asked. */
    suspend fun hasArticles(author: String): Boolean = false
    /** One `limit:1` kind 30023 relay probe, then [hasArticles]; a found article answers from memory for the process, none is probed again next time. */
    suspend fun probeArticles(author: String): Boolean = false
}

/** The tabs of a profile, like X: "Posts" the author's own root notes, "Replies" their replies, "Reposts" what they reposted. */
enum class ProfileSection(internal val kinds: List<Int>) {
    POSTS(listOf(1, Kinds.POLL)),
    REPLIES(listOf(1)),
    REPOSTS(listOf(6)),
    /** Long-form articles; read through [FeedRepository.profileArticles], not as notes. */
    ARTICLES(listOf(30023));

    fun keeps(note: Note): Boolean = when (this) {
        POSTS -> note.repostOf == null && isRoot(note)
        REPLIES -> note.isReply
        REPOSTS -> note.repostOf != null
        ARTICLES -> false
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

/** The home feed's kinds: notes, reposts and polls (kind 1068, polls task 4). */
private val FEED_KINDS = listOf(1, 6, Kinds.POLL)

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

/** [quietly] with a result: [fallback] when the call fails. */
internal suspend fun <T> quietlyOr(fallback: T, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    fallback
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
    private val profileTtlSeconds: Long = 60,
    /**
     * Where [deletionsAnswered] is kept across starts (beside the account's database, which holds the requests fetched
     * before); null: this process only. Without it every cold start asked each relay for up to 200 requests per author
     * chunk again (Pixel 2026-11-02: 2365 of the first 3700 events after a start were kind 5).
     */
    private val marks: com.monostr.nostr.SyncMarks? = null,
) : FeedRepository {
    override val repostOriginals: Flow<Set<String>> get() = NoteLoader.found

    override suspend fun reloaded(repost: Note): Note? {
        val ev = runCatching { engine.eventById(repost.id) }.getOrNull() ?: return null
        return NoteLoader.notes(engine, listOf(ev)).firstOrNull()?.takeIf { it.repostOf != null }
    }

    private val pubkey = requireNotNull(engine.pubkey) { "feed needs a logged-in user" }
    /** When the deletion requests of an author set (hashed, see [deletionKey]) were last fetched for the newest page. */
    private val deletionsAnswered = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var fetchedAt: Long? = null
    override val contactsFetchedAt: Long? get() = fetchedAt
    override fun markContactsFetched(at: Long) { fetchedAt = at }

    /** Followed pubkeys from the newest kind 3 of the user (local, then relays). Always includes the user. */
    override suspend fun follows(): List<String> {
        var contacts = storedContacts()
        if (contacts == null) {
            val filter = Filter().kind(Kind(3u)).author(PublicKey.parse(pubkey)).limit(1u)
            quietly { engine.fetch(filter) }
            contacts = storedContacts()
            // on the page's path: a write-only relay is waited for only when without it there is no list at all
            if (engine.hasWriteOnly) {
                if (contacts == null) {
                    quietly { engine.fetchWriteOnly(filter) }
                    contacts = storedContacts()
                } else {
                    engine.backgroundScope.launch { quietly { engine.fetchWriteOnly(filter) } }
                }
            }
            if (contacts != null) markContactsFetched(now())
        }
        return followed(contacts)
    }

    override suspend fun followsLocal(): List<String> = followed(storedContacts())

    private suspend fun storedContacts() = engine.query(Filter().kind(Kind(3u)).author(PublicKey.parse(pubkey)).limit(1u)).firstOrNull()

    /** The last contact list read (its id) and what it gave: every start reads it a few times. */
    @Volatile private var followsMemo: Pair<String, List<String>>? = null

    /**
     * The `p` tags read in Kotlin from the event's JSON: going through rust for each tag cost
     * 0.3–0.6 s for ~300 follows, three times per cold start (Pixel, 2026-10-19).
     */
    private fun followed(contacts: rust.nostr.sdk.Event?): List<String> {
        if (contacts == null) return listOf(pubkey)
        val id = contacts.id().toHex()
        followsMemo?.let { (memoId, list) -> if (memoId == id) return list }
        val keys = NoteMapper.decoded(contacts).tags
            .filter { it.size >= 2 && it[0] == "p" }
            .map { it[1].lowercase() }
            .filter { it.matches(HEX64) }
        // a key that is not on the curve stays in (Filter.fromJson takes it, it matches nobody); checking each
        // key through rust would bring the cost back
        return (keys + pubkey).distinct().also { followsMemo = id to it }
    }

    /**
     * [kinds] by [authors] built from JSON in one call: a [PublicKey.parse] per author cost ~0.5 s for
     * ~300 authors. A key the JSON route refuses (not on the curve) falls back to parsing each and
     * leaving the bad ones out.
     */
    private fun authorsFilter(authors: List<String>, kinds: List<Int>): Filter {
        return try {
            Filter.fromJson(authorsJson(authors, kinds))
        } catch (e: Exception) {
            Filter().kinds(kinds.map { Kind(it.toUShort()) }).authors(authors.mapNotNull { runCatching { PublicKey.parse(it) }.getOrNull() })
        }
    }

    private fun authorsJson(authors: List<String>, kinds: List<Int>): String = buildJsonObject {
        put("kinds", JsonArray(kinds.map { JsonPrimitive(it) }))
        put("authors", JsonArray(authors.map { JsonPrimitive(it) }))
    }.toString()

    private fun feedFilter(authors: List<String>, kinds: List<Int> = FEED_KINDS) = authorsFilter(authors, kinds)

    /**
     * What the live subscription asks for: the notes and their authors' deletion requests (NIP-09, kind 5),
     * so that a withdrawn note leaves the local database. The database is never queried with this
     * filter: the requests would take places in the page.
     */
    private fun liveFilter(authors: List<String>) = authorsFilter(authors, listOf(1, 6, 5, Kinds.POLL))

    /**
     * Fetches a page of notes and, apart from it, the same authors' deletion requests. Two requests and
     * not one filter: under one limit an account that withdrew many notes would fill the page with
     * requests, and paging would stop there for good. More than [AUTHOR_CHUNK] authors go out as
     * several filters, side by side. The user's own part is also asked of the write-only relays, with a filter of its own
     * and beside the page: such a relay never hears another author, and a dead one must not hold the page. What it finds
     * is stored and shows through the live list or the next read. With [awaitOwn] (a read of the user's own notes, where
     * the caller reads the database right after and takes nothing for the end) the own part is waited for.
     */
    private suspend fun fetchPage(authors: List<String>, limit: Int, until: Long? = null, kinds: List<Int> = FEED_KINDS, awaitOwn: Boolean = false) {
        if (authors.isEmpty()) return
        val untilTs = until?.let { Timestamp.fromSecs(it.toULong()) }
        val ownLeg = engine.hasWriteOnly && authors.any { it.equals(pubkey, ignoreCase = true) }
        if (ownLeg && !awaitOwn) engine.backgroundScope.launch { askWriteOnly(limit, untilTs, kinds) }
        coroutineScope {
            if (ownLeg && awaitOwn) launch { askWriteOnly(limit, untilTs, kinds) }
            authors.chunked(AUTHOR_CHUNK).map { chunk ->
                async {
                    var notes = feedFilter(chunk, kinds).limit(limit.toULong())
                    if (untilTs != null) notes = notes.until(untilTs)
                    // side by side: each waits for the slowest relay, one after the other they added up
                    // a relay whose connection failed must not hold the page to the timeout (engine.fetchSettled)
                    if (untilTs == null) {
                        val n = async { quietly { engine.fetchSettled(notes) } }
                        fetchDeletions(chunk, null)
                        n.await()
                    } else {
                        // an older page: only the requests naming its notes, not up to 200 per relay of every author
                        val page = quietlyOr(emptyList()) { engine.fetchSettled(notes) }
                        fetchDeletionsNaming(page.map { it.id() })
                        if (ownLeg) (if (awaitOwn) this@coroutineScope else engine.backgroundScope).launch {
                            fetchOwnDeletionsNaming(page.filter { it.author().toHex().equals(pubkey, ignoreCase = true) }.map { it.id() })
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** The user's notes of a page and their deletion requests from the write-only relays. */
    private suspend fun askWriteOnly(limit: Int, untilTs: Timestamp?, kinds: List<Int>) = coroutineScope {
        if (untilTs == null) launch { quietly { engine.fetchWriteOnly(ownDeletionsFilter()) } }
        var notes = feedFilter(listOf(pubkey), kinds).limit(limit.toULong())
        if (untilTs != null) notes = notes.until(untilTs)
        val found = quietlyOr(emptyList()) { engine.fetchWriteOnly(notes) }
        if (untilTs != null) fetchOwnDeletionsNaming(found.map { it.id() })
    }

    private fun ownDeletionsFilter() = authorsFilter(listOf(pubkey), listOf(5)).limit(DELETION_LIMIT)

    /** The user's deletion requests naming [ids] (the user's own notes) from the write-only relays. */
    private suspend fun fetchOwnDeletionsNaming(ids: List<EventId>) {
        for (chunk in ids.chunked(DELETION_IDS_CHUNK)) {
            quietly { engine.fetchWriteOnly(Filter().kind(Kind(5u)).author(PublicKey.parse(pubkey)).events(chunk)) }
        }
    }

    /** The deletion requests (kind 5) of [authors] (at most [AUTHOR_CHUNK]) under their own limit, before [untilTs] when set. */
    private suspend fun fetchDeletions(authors: List<String>, untilTs: Timestamp?) {
        var requests = authorsFilter(authors, listOf(5)).limit(DELETION_LIMIT)
        if (untilTs != null) requests = requests.until(untilTs)
        // the newest page asks only for what came since the last answer for the same authors: the relays
        // otherwise send the same few hundred requests every time, and taking them in costs seconds on a phone
        val key = if (untilTs == null) deletionKey(authors) else null
        val since = key?.let { deletionsAnswered[it] ?: marks?.get(it)?.also { at -> deletionsAnswered[it] = at } }
        if (since != null) requests = requests.since(Timestamp.fromSecs((since - DELETION_SKEW).coerceAtLeast(0).toULong()))
        val askedAt = now()
        quietly {
            engine.fetchSettled(requests)
            // a fetch that reached no relay returns quietly: remembered, it would hide the requests of the time between
            if (key != null && engine.connectedReadRelayUrls().isNotEmpty()) {
                deletionsAnswered[key] = askedAt
                marks?.put(key, askedAt)
            }
        }
    }

    /** The deletion requests naming [ids] (a page fetched from the relays), in chunks of [DELETION_IDS_CHUNK]. */
    private suspend fun fetchDeletionsNaming(ids: List<EventId>) {
        if (ids.isEmpty()) return
        coroutineScope {
            ids.chunked(DELETION_IDS_CHUNK).map { chunk -> async { quietly { engine.fetchSettled(Filter().kind(Kind(5u)).events(chunk)) } } }.awaitAll()
        }
    }

    /** The key of an author set in [deletionsAnswered] and [marks]: short, a set of 200 joined pubkeys is about 13 KB. */
    private fun deletionKey(authors: List<String>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(authors.sorted().joinToString(",").toByteArray())
        return "deletions:" + digest.take(12).joinToString("") { "%02x".format(it) }
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
        awaitOwn: Boolean = false,
    ): List<Note> {
        if (authors.isEmpty()) return emptyList()
        val fetchLimit = coverLimit(limit, authors, cover)
        var until = before
        repeat(RELAY_ROUNDS) {
            fetchPage(cover, fetchLimit, until = until, kinds = kinds, awaitOwn = awaitOwn)
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
            engine.incoming
                .filter { inc ->
                    val kind = inc.json.kind
                    // any subscription's or fetch's event, not only this subscription's: rust-nostr reports an event once,
                    // under the subscription it came on first, so an overlapping one (notifications, a thread) may carry it
                    (kind == 1 || kind == 6 || kind == 5 || kind == Kinds.POLL) && inc.json.pubkey in wanted
                }
                .conflate()
                .collect { emit(notes(source, limit)) }
        } finally {
            if (subIds.isNotEmpty()) withContext(NonCancellable) { subIds.forEach { id -> quietly { engine.unsubscribe(id) } } }
        }
    }.buffer(Channel.RENDEZVOUS).flowOn(Dispatchers.IO) // rendezvous: each list is handed over before the producer moves on

    override suspend fun notesBy(author: String, limit: Int, fetch: Boolean): List<Note> {
        if (fetch) fetchPage(listOf(author), limit, awaitOwn = true)
        return NoteLoader.notes(engine, engine.query(feedFilter(listOf(author)).limit(limit.toULong()))).sortedByDescending { it.createdAt }
    }

    /** One profile refresh per author at a time, on the engine's background scope; none again within [profileTtlSeconds]. */
    private val profileRefresh = RefreshGate<String>(engine.backgroundScope, profileTtlSeconds, now)

    override suspend fun refreshProfile(author: String, limit: Int) {
        profileRefresh.await(author) { fetchProfilePage(author, limit) }
    }

    /**
     * Kind 1 and kind 6 under their own limits (many notes cannot crowd the reposts out) and the author's kind 5
     * (incremental like [fetchPage]), side by side. The user's own profile asks the write-only relays too, and waits for
     * them: the stored notes are on screen by then, and nothing else reloads this page when they land.
     */
    private suspend fun fetchProfilePage(author: String, limit: Int) {
        val authors = listOf(author)
        coroutineScope {
            val own = if (!engine.hasWriteOnly || !author.equals(pubkey, ignoreCase = true)) emptyList() else listOf(
                async { quietly { engine.fetchWriteOnly(authorsFilter(listOf(pubkey), listOf(1, Kinds.POLL)).limit(limit.toULong())) } },
                async { quietly { engine.fetchWriteOnly(authorsFilter(listOf(pubkey), listOf(6)).limit(limit.toULong())) } },
                async { quietly { engine.fetchWriteOnly(ownDeletionsFilter()) } },
            )
            (listOf(
                async { quietly { engine.fetch(authorsFilter(authors, listOf(1, Kinds.POLL)).limit(limit.toULong())) } },
                async { quietly { engine.fetch(authorsFilter(authors, listOf(6)).limit(limit.toULong())) } },
                async { fetchDeletions(authors, null) },
            ) + own).awaitAll()
        }
    }

    override suspend fun profileNotes(author: String, section: ProfileSection, limit: Int, fetch: Boolean): List<Note> {
        if (fetch) refreshProfile(author, limit)
        return if (section == ProfileSection.POSTS) localPosts(author, limit) else localPage(listOf(author), section::keeps, limit, null, section.kinds)
    }

    /**
     * The "Posts" tab from the database (spec perf 3.5): larger pages of the author's kind 1, each event read once in
     * Kotlin, the root filter on that JSON, and only the kept notes go through [Withdrawn] and [NoteLoader].
     */
    private suspend fun localPosts(author: String, limit: Int): List<Note> = withContext(Dispatchers.IO) {
        val kept = ArrayList<Note>()
        var before: Long? = null
        repeat(POSTS_ROUNDS) {
            val decoded = engine.query(pageFilter(listOf(author), POSTS_SCAN, before, listOf(1, Kinds.POLL))).map { it to NoteMapper.decoded(it) }
            if (decoded.isEmpty()) return@withContext kept.sortedByDescending { it.createdAt }
            val roots = decoded.filter { (_, ev) -> Nip10.parse(ev.tags).replyToId == null }.sortedByDescending { it.second.createdAt }
            val taken = roots.take(limit - kept.size + POSTS_SLACK)
            kept += NoteLoader.notesDecoded(engine, taken).filter(ProfileSection.POSTS::keeps)
            if (kept.size >= limit || (decoded.size < POSTS_SCAN && taken.size == roots.size)) return@withContext kept.sortedByDescending { it.createdAt }.take(limit)
            before = (if (taken.size < roots.size) taken.last().second.createdAt else decoded.minOf { it.second.createdAt }) - 1
        }
        kept.sortedByDescending { it.createdAt }.take(limit)
    }

    override suspend fun moreProfileNotes(author: String, section: ProfileSection, before: Long, limit: Int): List<Note> =
        // the own leg is waited for: an empty page here ends the profile's paging for good
        relayPage(listOf(author), section::keeps, before, limit, section.kinds, awaitOwn = true)

    private val articleProbes = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * Final review I4: on the profile's hot path, so only the author's newest [HAS_ARTICLES_LIMIT] articles are read and checked
     * against the deletion requests naming them; an author whose newest few are all deleted shows no tab until the list is read.
     */
    override suspend fun hasArticles(author: String): Boolean = withContext(Dispatchers.IO) { storedArticles(author, hasArticlesFilter(author)).isNotEmpty() }

    /** The database read of [hasArticles]: the author's newest [HAS_ARTICLES_LIMIT] kind 30023. */
    internal fun hasArticlesFilter(author: String): Filter = articlesFilter(author, HAS_ARTICLES_LIMIT)

    private fun articlesFilter(author: String, limit: Int): Filter = authorsFilter(listOf(author), listOf(Article.KIND)).limit(limit.toULong())

    override suspend fun probeArticles(author: String): Boolean {
        val mine = CompletableDeferred<Boolean>()
        val running = articleProbes.putIfAbsent(author, mine)
        if (running != null) return running.await()
        try {
            quietly { engine.fetchSettled(authorsFilter(listOf(author), listOf(Article.KIND)).limit(1uL)) }
            val found = hasArticles(author)
            // a found article is kept for the process; none is asked again at the next profile open (final review M2)
            if (!found) articleProbes.remove(author, mine)
            mine.complete(found)
        } catch (e: Throwable) {
            articleProbes.remove(author, mine)
            mine.cancel()
            throw e
        }
        return mine.await()
    }

    override suspend fun profileArticles(author: String, fetch: Boolean): List<Article> {
        if (fetch) {
            coroutineScope {
                listOf(
                    async { quietly { engine.fetchSettled(authorsFilter(listOf(author), listOf(Article.KIND)).limit(ARTICLES_LIMIT.toULong())) } },
                    async { fetchDeletions(listOf(author), null) },
                ).awaitAll()
            }
        }
        return storedArticles(author)
    }

    /**
     * The author's stored articles read with [filter] (the newest 100 by default), the newest version per `d`, without the deleted ones: a request by the
     * author naming the coordinate in `a` or the version's id in `e`, not older than the version (the card's rule).
     */
    private suspend fun storedArticles(author: String, filter: Filter = articlesFilter(author, ARTICLES_LIMIT)): List<Article> = withContext(Dispatchers.IO) {
        val events = engine.query(filter).filter { it.author().toHex() == author }
        if (events.isEmpty()) return@withContext emptyList()
        val newest = events.map { e ->
            val tags = e.tags().toVec().map { it.asVec() }
            AddressableEvent(e.id().toHex(), Article.KIND, author, tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: "", e.createdAt().asSecs().toLong(), tags, e.content())
        }.groupBy { it.identifier }.values.map { versions -> versions.maxBy { it.createdAt } }
        val coordinates = newest.map { "${Article.KIND}:$author:${it.identifier}" }
        val deletedAt = HashMap<String, Long>()
        val requests = engine.query(articleDeletions(author, "#a", coordinates)) + engine.query(articleDeletions(author, "#e", newest.map { it.id }))
        for (r in requests) {
            if (r.author().toHex() != author) continue
            val at = r.createdAt().asSecs().toLong()
            for (tag in r.tags().toVec().map { it.asVec() }) {
                if (tag.size >= 2 && (tag[0] == "a" || tag[0] == "e")) deletedAt.merge(tag[1], at, ::maxOf)
            }
        }
        newest
            .filter { ev ->
                val at = maxOf(deletedAt["${Article.KIND}:$author:${ev.identifier}"] ?: Long.MIN_VALUE, deletedAt[ev.id] ?: Long.MIN_VALUE)
                at < ev.createdAt
            }
            .mapNotNull(Article::parse)
            .sortedByDescending { it.publishedAt }
    }

    /** Kind 5 by [author] naming one of [values] in [tag] (`#a` coordinates, `#e` ids). */
    private fun articleDeletions(author: String, tag: String, values: List<String>): Filter = Filter.fromJson(
        buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(5))))
            put("authors", JsonArray(listOf(JsonPrimitive(author))))
            put(tag, JsonArray(values.map { JsonPrimitive(it) }))
        }.toString(),
    )
}

/**
 * Maps events to notes, resolving bare kind 6 reposts (`e` tag only) from the local database first.
 * Verified embedded originals are stored so likes and reposts of them find the event; a repost whose
 * original the database refuses (withdrawn by its author, NIP-09) is dropped.
 */
internal object NoteLoader {
    suspend fun notes(engine: NostrEngine, all: List<rust.nostr.sdk.Event>): List<Note> = notesDecoded(engine, all.map { it to NoteMapper.decoded(it) })

    /** [notes] for events already read in Kotlin: each event crosses the FFI once (spec perf 3.1). */
    suspend fun notesDecoded(engine: NostrEngine, all: List<Pair<rust.nostr.sdk.Event, JsonEvent>>): List<Note> {
        val events = Withdrawn.filterDecoded(engine, all)
        val referenced = HashMap<String, rust.nostr.sdk.Event?>()
        val withdrawn = HashSet<String>()
        for ((_, ev) in events) {
            if (ev.kind != 6) continue
            val embedded = NoteMapper.verifiedEmbedded(ev)
            if (embedded != null) {
                // a write costs about 100 ms on a phone: the copy is saved only the first time, a stored original is just read
                val id = embedded.id().toHex()
                var stored = runCatching { engine.eventById(id) }
                if (stored.isSuccess && stored.getOrNull() == null) {
                    quietly { engine.save(embedded) }
                    stored = runCatching { engine.eventById(id) }
                }
                // the copy in the repost's content must not bring back what its author withdrew; a failing read keeps the repost
                if (stored.isSuccess && stored.getOrNull() == null) withdrawn += ev.id
            } else {
                val id = ev.firstTagValue("e") ?: continue
                if (id !in referenced) referenced[id] = runCatching { engine.eventById(id) }.getOrNull()
            }
        }
        // backlog 60: originals neither embedded validly nor stored are asked of the relays once, in the background; the
        // next read of the list shows them (the card meanwhile says the reposted note is not available)
        askMissing(engine, referenced.filterValues { it == null }.keys)
        return events.filterNot { it.second.id in withdrawn }.mapNotNull { (_, ev) -> NoteMapper.note(ev) { id -> referenced[id] } }
    }

    private val _found = kotlinx.coroutines.flow.MutableSharedFlow<Set<String>>(extraBufferCapacity = 16)
    /** Originals [askMissing] found: the lists that showed «not available» swap in their cards. */
    val found: kotlinx.coroutines.flow.SharedFlow<Set<String>> = _found

    /** Ids [askMissing] asked this process (bounded: cleared when full). */
    private val asked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Ids a first ask did not find: asked once more on a later read, then left (review 2026-11-12). */
    private val missedOnce: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun askMissing(engine: NostrEngine, ids: Collection<String>) {
        if (asked.size > ASKED_CAP) asked.clear()
        if (missedOnce.size > ASKED_CAP) missedOnce.clear()
        val fresh = ids.filter { it.matches(HEX64_ID) && asked.add(it) }
        if (fresh.isEmpty()) return
        engine.backgroundScope.launch {
            fresh.chunked(DELETION_IDS_CHUNK).forEach { chunk ->
                quietly { engine.fetch(Filter().ids(chunk.map { EventId.parse(it) }), REPOST_FETCH_TIMEOUT, Exit.AllIds(chunk.toSet())) }
                val got = chunk.filter { id -> runCatching { engine.eventById(id) }.getOrNull() != null }
                // asked while the relays were still connecting (Tor) finds nothing: the next read of a list asks once more
                chunk.filter { it !in got && missedOnce.add(it) }.forEach { asked.remove(it) }
                if (got.isNotEmpty()) _found.emit(got.toSet())
            }
        }
    }

    private const val ASKED_CAP = 5_000
    private val HEX64_ID = Regex("[0-9a-f]{64}")
    private val REPOST_FETCH_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(6)
}

/** Deletion requests fetched with a page of notes, under their own limit. */
private const val DELETION_LIMIT = 200uL
/** Ids per kind 5 `#e` filter of an older page (relays cap filter ids). */
private const val DELETION_IDS_CHUNK = 100

/** Margin for [NostrFeedRepository]'s incremental deletion fetch: a request signed by a clock a bit behind still comes. */
private const val DELETION_SKEW = 600L

/** Articles per profile read (a person's long-form list is short). */
private const val ARTICLES_LIMIT = 100

/** Articles [NostrFeedRepository.hasArticles] reads: enough for the tab, cheap on the profile's hot path (final review I4). */
internal const val HAS_ARTICLES_LIMIT = 5

/** Events per database read of the "Posts" tab; replies in between are dropped in Kotlin before any further work. */
private const val POSTS_SCAN = 200

/** Database reads per "Posts" page at most. */
private const val POSTS_ROUNDS = 5

/** Roots handed to [NoteLoader] beyond the page size, for the ones [Withdrawn] drops. */
private const val POSTS_SLACK = 10
