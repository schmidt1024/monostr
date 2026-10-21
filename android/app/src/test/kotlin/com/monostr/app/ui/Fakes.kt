package com.monostr.app.ui

import com.monostr.app.data.Accent
import com.monostr.app.data.DmSettingsStore
import com.monostr.app.data.Draft
import com.monostr.app.data.DraftStore
import com.monostr.app.data.MediaServer
import com.monostr.app.data.RecentSearchesStore
import com.monostr.app.data.MoneroSetup
import com.monostr.app.data.PendingTip
import com.monostr.app.data.PendingTipStore
import com.monostr.app.data.PendingTips
import com.monostr.app.data.Presets
import com.monostr.app.data.RelayStore
import com.monostr.app.data.SecretStore
import com.monostr.app.data.ThemeMode
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.data.UiSettingsStore
import com.monostr.app.data.WatcherGateway
import com.monostr.app.media.ImageTarget
import com.monostr.app.media.MediaUploader
import com.monostr.app.media.UploadedMedia
import com.monostr.monero.Address
import com.monostr.monero.toHex
import com.monostr.nostr.PublishResult
import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteAttachment
import com.monostr.nostr.model.NoteCounts
import com.monostr.nostr.model.Profile
import com.monostr.nostr.model.SaveResult
import com.monostr.nostr.repo.FeedTabsRepository
import com.monostr.nostr.repo.ListsRepository
import com.monostr.nostr.repo.AnonymousIntent
import com.monostr.nostr.repo.AuthorRelaysRepository
import com.monostr.nostr.repo.FollowCounts
import com.monostr.nostr.repo.FollowCountsRepository
import com.monostr.nostr.repo.BookmarkEntry
import com.monostr.nostr.repo.BookmarkOutcome
import com.monostr.nostr.repo.BookmarksRepository
import com.monostr.nostr.repo.BookmarksState
import com.monostr.nostr.repo.CountsRepository
import com.monostr.nostr.repo.DmIncoming
import com.monostr.nostr.repo.DmRelaysRepository
import com.monostr.nostr.repo.DmRepository
import com.monostr.nostr.repo.DmSendResult
import com.monostr.nostr.repo.FeedRepository
import com.monostr.nostr.repo.FeedSource
import com.monostr.nostr.repo.ProfileSection
import com.monostr.nostr.repo.FollowRepository
import com.monostr.nostr.repo.FollowState
import com.monostr.nostr.repo.ListOutcome
import com.monostr.nostr.repo.ListState
import com.monostr.nostr.repo.MuteRepository
import com.monostr.nostr.repo.AboutNote
import com.monostr.nostr.repo.NotificationItem
import com.monostr.nostr.repo.NotificationsRepository
import com.monostr.nostr.repo.OwnDmRelays
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.PublishRepository
import com.monostr.nostr.repo.QuoteRepository
import com.monostr.nostr.repo.QuoteResult
import com.monostr.nostr.repo.SearchOutcome
import com.monostr.nostr.repo.SearchRepository
import com.monostr.nostr.repo.ThreadRepository
import com.monostr.nostr.repo.ThreadView
import com.monostr.nostr.repo.TipSummary
import com.monostr.nostr.repo.Tipper
import com.monostr.nostr.repo.TipsRepository
import com.monostr.nostr.repo.Unwrap
import com.monostr.nostr.repo.WrapFetch
import com.monostr.tips.PaymentInfo
import com.monostr.tips.Watcher
import com.monostr.tips.WatcherStatus
import com.monostr.tips.PreparedTip
import com.monostr.tips.watcher.WatcherException
import com.monostr.tips.watcher.WatcherInfo
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import rust.nostr.sdk.Event

fun note(id: Char, author: String = "a".repeat(64), createdAt: Long = 1000, content: String = "note $id", replyTo: String? = null, root: String? = null) =
    Note(id.toString().repeat(64), author, content, createdAt, 1, root ?: replyTo, replyTo, null, emptyList())

fun repostOf(original: Note, by: String, createdAt: Long) =
    Note("f".repeat(63) + createdAt.toString().last(), by, "", createdAt, 6, null, null, original, emptyList())

class FakeConversations(var chains: Map<String, com.monostr.nostr.repo.Chain> = emptyMap()) : com.monostr.nostr.repo.ConversationRepository {
    val calls = ArrayList<List<String>>()
    /** When set, `chains` suspends here after recording the call, to test that nothing waits for it. */
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun chains(notes: List<Note>): Map<String, com.monostr.nostr.repo.Chain> {
        calls += notes.map { it.id }
        gate?.await()
        return chains.filterKeys { k -> notes.any { it.id == k } }
    }
    override suspend fun ancestors(note: Note): List<com.monostr.nostr.repo.Slot> = emptyList()
}

class FakeFeed : FeedRepository {
    var current: List<Note> = emptyList()
    var older: List<Note> = emptyList()
    var byAuthor: Map<String, List<Note>> = emptyMap()
    val liveUpdates = MutableSharedFlow<List<Note>>(replay = 0)
    var refreshCalls = 0
    var loadMoreCalls = 0
    /** When set, `loadMore` suspends here before returning, to test overlapping calls. */
    var loadMoreGate: CompletableDeferred<Unit>? = null
    /** When set, `loadMore` throws it. */
    var loadMoreError: Exception? = null
    /** When set, `refresh` suspends here, to test that local notes do not wait for relays. */
    var refreshGate: CompletableDeferred<Unit>? = null
    /** When set, `refresh` throws it after the gate (relays unreachable). */
    var refreshError: Exception? = null
    var followList: List<String> = listOf("a".repeat(64))
    override suspend fun follows() = followList
    override suspend fun followsLocal() = followList
    /** The source of every notes/refresh/loadMore/live call, in order. */
    val seenSources = ArrayList<FeedSource>()
    /** Like the repository: a list's feed holds its members' notes only. */
    private fun of(source: FeedSource, notes: List<Note>) = if (source is FeedSource.Authors) notes.filter { it.author in source.pubkeys } else notes
    override suspend fun notes(source: FeedSource, limit: Int, until: Long?): List<Note> {
        seenSources += source
        return of(source, current)
    }
    override suspend fun refresh(source: FeedSource, limit: Int) {
        seenSources += source
        refreshCalls++
        refreshGate?.await()
        refreshError?.let { throw it }
    }
    override suspend fun loadMore(source: FeedSource, before: Long, limit: Int): List<Note> {
        seenSources += source
        loadMoreCalls++
        loadMoreGate?.await()
        loadMoreError?.let { throw it }
        return of(source, older)
    }
    /** How often [live] was started (each restart counts). */
    var liveCalls = 0
    /** When set, `live` suspends here before its first list (a slow local read on a cold start). */
    var liveGate: CompletableDeferred<Unit>? = null
    override fun live(source: FeedSource, limit: Int): Flow<List<Note>> = flow {
        liveCalls++
        seenSources += source
        liveGate?.await()
        emit(of(source, current))
        liveUpdates.collect { emit(of(source, it)) }
    }
    /** The `fetch` flag of every notesBy call, in order. */
    val notesByCalls = ArrayList<Boolean>()
    override suspend fun notesBy(author: String, limit: Int, fetch: Boolean): List<Note> {
        notesByCalls += fetch
        return byAuthor[author] ?: emptyList()
    }
    /** Section and `fetch` flag of every profileNotes call, in order (the fetch flag is also added to [notesByCalls]). */
    val profileCalls = ArrayList<Pair<ProfileSection, Boolean>>()
    /** When set, profileNotes suspends here (a slow database read). */
    var profileGate: CompletableDeferred<Unit>? = null
    override suspend fun profileNotes(author: String, section: ProfileSection, limit: Int, fetch: Boolean): List<Note> {
        profileGate?.await()
        notesByCalls += fetch
        profileCalls += section to fetch
        return (byAuthor[author] ?: emptyList()).filter(section::keeps)
    }
    /** Authors of every refreshProfile call, in order. */
    val refreshProfileCalls = ArrayList<String>()
    /** When set, refreshProfile suspends here (the relays still answering). */
    var refreshProfileGate: CompletableDeferred<Unit>? = null
    override suspend fun refreshProfile(author: String, limit: Int) {
        refreshProfileCalls += author
        refreshProfileGate?.await()
    }
    /** What moreProfileNotes returns per author, before the section filter. */
    var olderByAuthor: Map<String, List<Note>> = emptyMap()
    /** Section and `before` of every moreProfileNotes call, in order. */
    val moreCalls = ArrayList<Pair<ProfileSection, Long>>()
    override suspend fun moreProfileNotes(author: String, section: ProfileSection, before: Long, limit: Int): List<Note> {
        moreCalls += section to before
        return (olderByAuthor[author] ?: emptyList()).filter(section::keeps)
    }
}

class FakeProfiles(known: Map<String, Profile> = emptyMap()) : ProfileRepository {
    /** The profiles as the repository knows them now; tests replace entries to simulate an own edit. */
    val current = MutableStateFlow(known)
    val prefetched = ArrayList<Collection<String>>()
    /** The keys of every getAll call, in order. */
    val getAllCalls = ArrayList<List<String>>()
    /** Each getAll waits this long first (virtual time): a slow screen rebuild, for conflation tests. */
    var getAllDelayMs: Long = 0
    private val _updates = MutableSharedFlow<Map<String, Profile>>(extraBufferCapacity = 16)
    override val updates: SharedFlow<Map<String, Profile>> = _updates
    override fun observe(pubkey: String) = current.map { it[pubkey] ?: Profile.empty(pubkey) }
    override suspend fun get(pubkey: String, maxAgeSeconds: Long): Profile = current.value[pubkey] ?: Profile.empty(pubkey)
    override suspend fun getAll(pubkeys: Collection<String>, maxAgeSeconds: Long): Map<String, Profile> {
        getAllCalls += pubkeys.toList()
        if (getAllDelayMs > 0) delay(getAllDelayMs)
        return pubkeys.distinct().associateWith { get(it, maxAgeSeconds) }
    }
    override fun prefetch(pubkeys: Collection<String>) { prefetched += pubkeys }
    override suspend fun local(pubkeys: Collection<String>) = pubkeys.mapNotNull { current.value[it] }
    /** Pubkeys asked through [fetched] (background workers). */
    val fetchedCalls = ArrayList<String>()
    override suspend fun fetched(pubkey: String, timeout: Duration): Profile {
        fetchedCalls += pubkey
        return current.value[pubkey] ?: Profile.empty(pubkey)
    }
    /** A background lookup lands, like the repository's refresh queue: stored, then announced through [updates]. */
    suspend fun land(vararg profiles: Profile) {
        val byKey = profiles.associateBy { it.pubkey }
        current.update { it + byKey }
        _updates.emit(byKey)
    }
    /** Raw kind 0 content per pubkey, as the database would hold it. */
    var raw: Map<String, String> = emptyMap()
    val invalidated = ArrayList<String>()
    override suspend fun rawMetadata(pubkey: String, timeout: Duration) = raw[pubkey]
    /** What a relay fetch right before a save returns; null falls back to [raw] (the database). */
    var fresh: Map<String, String>? = null
    val freshCalls = ArrayList<String>()
    override suspend fun freshMetadata(pubkey: String, timeout: Duration): String? {
        freshCalls += pubkey
        return (fresh ?: raw)[pubkey]
    }
    override suspend fun invalidate(pubkey: String): Profile { invalidated += pubkey; return current.value[pubkey] ?: Profile.empty(pubkey) }
}

class FakeQuotes(var notes: Map<String, Note> = emptyMap()) : QuoteRepository {
    /** (id, hints) of every get, in order. */
    val calls = ArrayList<Pair<String, List<String>>>()
    override fun cached(id: String): QuoteResult? = null
    override suspend fun get(id: String, hints: List<String>): QuoteResult {
        calls += id to hints
        return notes[id]?.let { QuoteResult.Found(it) } ?: QuoteResult.Missing
    }
}

class FakePublish(var relaysOk: Boolean = true, private val fail: Boolean = false) : PublishRepository {
    val calls = ArrayList<String>()
    private var counter = 0
    /** When set, every publish suspends here first, to test optimistic UI before the relay answers. */
    var gate: CompletableDeferred<Unit>? = null
    /** Kinds no relay accepts, while [relaysOk] holds for the others. */
    val refusedKinds = HashSet<Int>()
    private suspend fun result(kind: Int, id: String? = null): PublishResult {
        gate?.await()
        if (fail) throw IllegalStateException("boom")
        counter++
        return PublishResult(id ?: counter.toString().padStart(64, '0'), kind, if (relaysOk && kind !in refusedKinds) listOf("wss://r") else emptyList(), emptyMap())
    }
    /** The attachments and the sensitive flag of the latest post, reply or quote. */
    var lastAttachments: List<NoteAttachment> = emptyList()
    var lastSensitive: Boolean = false
    private fun suffix(attachments: List<NoteAttachment>, sensitive: Boolean): String {
        lastAttachments = attachments
        lastSensitive = sensitive
        return (if (attachments.isEmpty()) "" else "|${attachments.size}img") + (if (sensitive) "|cw" else "")
    }
    override suspend fun post(text: String, attachments: List<NoteAttachment>, sensitive: Boolean) =
        result(1).also { calls += "post:$text" + suffix(attachments, sensitive) }
    override suspend fun reply(text: String, to: Note, attachments: List<NoteAttachment>, sensitive: Boolean) =
        result(1).also { calls += "reply:${to.id.take(4)}:$text" + suffix(attachments, sensitive) }
    override suspend fun quote(text: String, of: Note, attachments: List<NoteAttachment>, sensitive: Boolean) =
        result(1).also { calls += "quote:${of.id.take(4)}:$text" + suffix(attachments, sensitive) }
    /** JSON of every profile publish, in order. */
    val profileJson = ArrayList<String>()
    override suspend fun profile(json: String) = result(0).also { calls += "profile"; profileJson += json }
    override suspend fun react(note: Note, content: String) = result(7).also { calls += if (content == "+") "like:${note.id.take(4)}" else "react:${note.id.take(4)}:$content" }
    override suspend fun repost(note: Note) = result(6).also { calls += "repost:${note.id.take(4)}" }
    override suspend fun withdraw(noteId: String, kind: Int) = result(5).also { calls += (if (kind == 7) "unlike:" else "unrepost:") + noteId.take(4) }
    override suspend fun delete(note: Note) = result(5).also { calls += "delete:${note.id.take(4)}" }
    override suspend fun noteLink(note: Note) = "nostr:nevent1fake${note.id.take(8)}"
    /** When set, a resend throws. */
    var failResend = false
    override suspend fun resend(eventId: String) = result(1, eventId).also { if (failResend) throw IllegalStateException("resend boom") }.also { calls += "resend:${eventId.takeLast(4)}" }
    override suspend fun relayList(relays: List<String>) = result(10002).also { calls += "relays:${relays.size}" }
    override suspend fun publishRelayList(relays: List<String>) = result(10002).also { calls += "relaylist:${relays.size}" }
    override suspend fun dmRelayList(relays: List<String>) = result(10050).also { calls += "dmrelays:${relays.size}" }
    override suspend fun initialRelayList(relays: List<String>) = result(10002).also { calls += "initialrelays:${relays.size}" }
}

/** [flows]: a thread whose views a test pushes (spec perf2 §6); it wins over [views] for its note. */
class FakeThreads(
    private val views: Map<String, ThreadView>,
    private val notes: Map<String, Note> = emptyMap(),
    private val flows: Map<String, Flow<ThreadView>> = emptyMap(),
) : ThreadRepository {
    override fun observe(noteId: String): Flow<ThreadView> = flows[noteId] ?: flow { views[noteId]?.let { emit(it) } }
    override suspend fun note(id: String) = notes[id]
}

class FakeRelayStore(initial: List<String>) : RelayStore {
    val state = MutableStateFlow(initial)
    override val relays: Flow<List<String>> = state
    override suspend fun set(relays: List<String>) { state.value = relays }
}

class FakeTips(
    var infos: Map<String, PaymentInfo> = emptyMap(),
    var relaysOk: Boolean = true,
    var failSend: Boolean = false,
) : TipsRepository {
    val sentIntents = ArrayList<PreparedTip>()
    val sentWatcherRelays = ArrayList<List<String>>()
    val publishedInfos = ArrayList<Triple<Address, Watcher, Boolean>>()
    var disabledCalls = 0
    /** Summaries emitted by observeReceipts; tests push new values to simulate arriving receipts. */
    val receipts = MutableStateFlow<Map<String, TipSummary>>(emptyMap())
    val observed = ArrayList<Map<String, String>>()
    var tippers: Map<String, List<Tipper>> = emptyMap()
    /** When set, `sendIntent` suspends here before publishing, to test a sheet that changes meanwhile. */
    var sendGate: CompletableDeferred<Unit>? = null
    private var counter = 0

    /** When set, `maxAgeSeconds == 0` (a forced relay fetch) answers from here instead of the cached [infos]. */
    var liveInfos: Map<String, PaymentInfo>? = null
    var liveDelayMs = 0L
    /** When set, the forced relay fetch suspends here; [liveCalls] counts those fetches, [liveCancelled] the cancelled ones. */
    var liveGate: CompletableDeferred<Unit>? = null
    var liveCalls = 0
    var liveCancelled = 0
    /** Pubkeys asked through the local-only path, and through the age-limited (possibly fetching) path. */
    val cachedCalls = ArrayList<String>()
    val staleCalls = ArrayList<String>()
    override suspend fun paymentInfo(pubkey: String, maxAgeSeconds: Long): PaymentInfo {
        if (maxAgeSeconds == 0L && liveInfos != null) {
            liveCalls++
            try {
                if (liveDelayMs > 0) delay(liveDelayMs)
                liveGate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                liveCancelled++
                throw e
            }
            return liveInfos!![pubkey] ?: PaymentInfo.Disabled
        }
        if (maxAgeSeconds != 0L) staleCalls += pubkey
        return infos[pubkey] ?: PaymentInfo.Disabled
    }
    override suspend fun cachedPaymentInfo(pubkey: String): PaymentInfo {
        cachedCalls += pubkey
        return infos[pubkey] ?: PaymentInfo.Disabled
    }
    /** When set, publishPaymentInfo throws it (after recording the call). */
    var publishError: Exception? = null
    override suspend fun publishPaymentInfo(address: Address, watcher: Watcher, amountsHidden: Boolean): PublishResult {
        publishedInfos += Triple(address, watcher, amountsHidden)
        publishError?.let { throw it }
        return PublishResult((++counter).toString().padStart(64, '0'), 10037, if (relaysOk) listOf("wss://r") else emptyList(), emptyMap())
    }
    val statuses = HashMap<String, WatcherStatus?>()
    val statusRequests = ArrayList<Pair<String, List<String>>>()
    /** When set, watcherStatus suspends until it completes. */
    var statusGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override suspend fun watcherStatus(watcherPubkey: String, relays: List<String>): WatcherStatus? {
        statusRequests += watcherPubkey to relays
        statusGate?.await()
        return statuses[watcherPubkey]
    }
    override suspend fun disablePaymentInfo(): PublishResult {
        disabledCalls++
        return PublishResult((++counter).toString().padStart(64, '0'), 10037, if (relaysOk) listOf("wss://r") else emptyList(), emptyMap())
    }
    override suspend fun sendIntent(prepared: PreparedTip, watcherRelays: List<String>): PublishResult {
        sendGate?.await()
        if (failSend) throw IllegalStateException("boom")
        sentIntents += prepared; sentWatcherRelays += watcherRelays
        return PublishResult((++counter).toString().padStart(64, '0'), 9738, if (relaysOk) listOf("wss://r") else emptyList(), emptyMap())
    }
    val anonymousIntents = ArrayList<PreparedTip>()
    val anonymousRelays = ArrayList<List<String>>()
    /** (event json, relays) of every resend over the anonymous path. */
    val anonymousResends = ArrayList<Pair<String, List<String>>>()
    override suspend fun sendAnonymousIntent(prepared: PreparedTip, watcherRelays: List<String>): AnonymousIntent {
        sendGate?.await()
        if (failSend) throw IllegalStateException("boom")
        require(watcherRelays.isNotEmpty())
        anonymousIntents += prepared; anonymousRelays += watcherRelays
        val id = (++counter).toString().padStart(64, '0')
        return AnonymousIntent(PublishResult(id, 9738, if (relaysOk) watcherRelays else emptyList(), emptyMap()), "{\"id\":\"$id\"}")
    }
    override suspend fun resendAnonymousIntent(eventJson: String, relays: List<String>): PublishResult {
        if (failSend) throw IllegalStateException("boom")
        anonymousResends += eventJson to relays
        return PublishResult("0".repeat(64), 9738, if (relaysOk) relays else emptyList(), emptyMap())
    }
    /** Notes of every localReceipts call, in order. */
    val localObserved = ArrayList<Map<String, String>>()
    override suspend fun localReceipts(notes: Map<String, String>): Map<String, TipSummary> {
        localObserved += notes
        return receipts.value.filterKeys { it in notes }
    }
    /** observeReceipts collections running right now (each one is a live relay subscription in the real repository). */
    var activeReceiptObservers = 0
    /** When set, the next observeReceipts collection fails with it (once). */
    var receiptsError: Exception? = null
    override fun observeReceipts(notes: Map<String, String>): Flow<Map<String, TipSummary>> {
        observed += notes
        return flow {
            receiptsError?.let { receiptsError = null; throw it }
            activeReceiptObservers++
            try {
                emitAll(receipts.map { all -> all.filterKeys { it in notes } })
            } finally {
                activeReceiptObservers--
            }
        }
    }
    /** Intent ids emitted by observeProfileReceipts; tests push new values to simulate arriving receipts. */
    val profileReceipts = MutableStateFlow<Set<String>>(emptySet())
    val profileObserved = ArrayList<Pair<String, Long>>()
    override fun observeProfileReceipts(recipient: String, since: Long): Flow<Set<String>> { profileObserved += recipient to since; return profileReceipts }
    /** Intent ids emitted by observeAnonymousProfileReceipts; each collection is recorded as (recipient, since, relays). */
    val anonymousProfileReceipts = MutableStateFlow<Set<String>>(emptySet())
    val anonymousProfileObserved = ArrayList<Triple<String, Long, List<String>>>()
    /** When set, the next collection of observeAnonymousProfileReceipts fails with it (once). */
    var anonymousProfileError: Exception? = null
    override fun observeAnonymousProfileReceipts(recipient: String, since: Long, relays: List<String>): Flow<Set<String>> = flow {
        anonymousProfileObserved += Triple(recipient, since, relays)
        anonymousProfileError?.let { anonymousProfileError = null; throw it }
        emitAll(anonymousProfileReceipts)
    }
    override suspend fun tippers(noteId: String, author: String) = tippers[noteId] ?: emptyList()
}

class FakeSearch : SearchRepository {
    var local: List<Profile> = emptyList()
    var remotePeople: List<Profile> = emptyList()
    var remoteNotes: List<Note> = emptyList()
    /** What the database holds per hashtag (hashtagLocal); [hashtagNotes] is the answer after the relays. */
    var hashtagLocalNotes: Map<String, List<Note>> = emptyMap()
    var hashtagNotes: Map<String, List<Note>> = emptyMap()
    var error: Exception? = null
    val attached = ArrayList<List<String>>()
    val detached = ArrayList<List<String>>()
    val calls = ArrayList<String>()
    /** When set, `notes` suspends here before returning — models an in-flight relay round trip for tests that cancel mid-search. */
    var notesGate: CompletableDeferred<Unit>? = null
    /** When set, `profilesLocal` suspends here first (a slow database read). */
    var localGate: CompletableDeferred<Unit>? = null
    /** When set, `hashtag` suspends here after recording the call (the relays are slow). */
    var hashtagGate: CompletableDeferred<Unit>? = null
    override suspend fun profilesLocal(query: String, limit: Int): List<Profile> { localGate?.await(); return local.also { calls += "local:$query" } }
    override suspend fun profiles(query: String, relays: List<String>, limit: Int): SearchOutcome<Profile> {
        calls += "people:$query:${relays.size}"
        return SearchOutcome((local + remotePeople).distinctBy { it.pubkey }, error)
    }
    override suspend fun notes(query: String, relays: List<String>, limit: Int): SearchOutcome<Note> {
        calls += "notes:$query:${relays.size}"
        notesGate?.await()
        return if (relays.isEmpty()) SearchOutcome(emptyList()) else SearchOutcome(remoteNotes, error)
    }
    override suspend fun hashtagLocal(tag: String, until: Long?, limit: Int): List<Note> {
        calls += "tagLocal:$tag:$until"
        return hashtagLocalNotes[tag].orEmpty().filter { until == null || it.createdAt < until }
    }
    override suspend fun hashtag(tag: String, until: Long?, limit: Int): SearchOutcome<Note> {
        calls += "tag:$tag:$until"
        hashtagGate?.await()
        return SearchOutcome(hashtagNotes[tag].orEmpty().filter { until == null || it.createdAt < until }, error)
    }
    override suspend fun attach(relays: List<String>) { attached += relays }
    override suspend fun detach(relays: List<String>) { detached += relays }
}

class FakeNotifications(var items: List<NotificationItem> = emptyList()) : NotificationsRepository {
    var refreshCalls = ArrayList<Long?>()
    /** When set, `refresh` suspends until completed — models a slow relay fetch. */
    var refreshGate: CompletableDeferred<Unit>? = null
    var about: Map<String, AboutNote> = emptyMap()
    /** What the database holds: [localNotes] answers from here, [notes] prefers it over [about] (the relays). */
    var stored: Map<String, AboutNote> = emptyMap()
    override suspend fun localNotes(ids: Set<String>): Map<String, AboutNote> = ids.mapNotNull { id -> stored[id]?.let { id to it } }.toMap()
    val notesCalls = ArrayList<Set<String>>()
    /** When set, `notes` suspends until completed — models a slow relay lookup. */
    var notesGate: CompletableDeferred<Unit>? = null
    /** Number of list() calls (database reads of the list). */
    var listCalls = 0
    /** When set, list() suspends until completed: a slow database read. */
    var listGate: CompletableDeferred<Unit>? = null
    override suspend fun list(limit: Int): List<NotificationItem> {
        listCalls++
        listGate?.await()
        return items.sortedByDescending { it.createdAt }.take(limit)
    }
    /** Null: every id counts as the user's own (tests that do not care). */
    var ownIds: Set<String>? = null
    val ownCalls = ArrayList<Set<String>>()
    override suspend fun own(ids: Set<String>): Set<String> { ownCalls += ids; return ownIds?.let { ids intersect it } ?: ids }
    override suspend fun notes(ids: Set<String>): Map<String, AboutNote> {
        notesCalls += ids
        notesGate?.await()
        return ids.associateWith { stored[it] ?: about[it] ?: AboutNote.Missing }
    }
    override suspend fun refresh(since: Long?, limit: Int) { refreshCalls += since; refreshGate?.await() }
    private val incomingEvents = MutableSharedFlow<Unit>()
    /** Live subscriptions open right now. */
    var subscriptions = 0
    override fun incoming(): Flow<Unit> = flow {
        subscriptions++
        try {
            incomingEvents.collect { emit(Unit) }
        } finally {
            subscriptions--
        }
    }
    /** A live event: the database holds [now] from here on, then the subscription reports one event. */
    suspend fun arrive(now: List<NotificationItem>) {
        items = now
        incomingEvents.emit(Unit)
    }
}

class FakeTipSettings(
    presets: List<Long> = Presets.DEFAULT,
    setup: MoneroSetup? = null,
    seen: Boolean = false,
) : TipSettingsStore {
    val presetsState = MutableStateFlow(presets)
    val setupState = MutableStateFlow(setup)
    val seenState = MutableStateFlow(seen)
    val readAtState = MutableStateFlow(0L)
    var lastNotified = 0L
    override val presets: Flow<List<Long>> = presetsState
    override val setup: Flow<MoneroSetup?> = setupState
    override val onboardingSeen: Flow<Boolean> = seenState
    override val notificationsReadAt: Flow<Long> = readAtState
    val anonymousState = MutableStateFlow(true)
    override val anonymous: Flow<Boolean> = anonymousState
    override suspend fun setAnonymous(value: Boolean) { anonymousState.value = value }
    val amountUnitState = MutableStateFlow(com.monostr.app.data.AmountUnit.XMR)
    override val amountUnit: Flow<com.monostr.app.data.AmountUnit> = amountUnitState
    override suspend fun setAmountUnit(unit: com.monostr.app.data.AmountUnit) { amountUnitState.value = unit }
    override suspend fun setPresets(piconero: List<Long>) { presetsState.value = piconero.sorted() }
    override suspend fun setSetup(setup: MoneroSetup?) { setupState.value = setup }
    override suspend fun setOnboardingSeen() { seenState.value = true }
    override suspend fun setNotificationsReadAt(at: Long) { readAtState.value = at }
    override suspend fun lastTipNotifiedAt() = lastNotified
    override suspend fun setLastTipNotifiedAt(at: Long) { lastNotified = at }
    val kept = mutableMapOf<String, Pair<Long, Long>>()
    override suspend fun keepReadMarks(pubkey: String) { kept[pubkey] = readAtState.value to lastNotified }
    override suspend fun restoreReadMarks(pubkey: String, now: Long) {
        val (read, tip) = kept[pubkey] ?: (now to now)
        readAtState.value = read; lastNotified = tip
    }
    var notifiedIds: Set<String> = emptySet()
    override suspend fun notifiedReceiptIds() = notifiedIds
    override suspend fun setNotifiedReceiptIds(ids: Set<String>) { notifiedIds = ids }
}

class FakePendingTips : PendingTipStore {
    val state = MutableStateFlow<List<PendingTip>>(emptyList())
    override val pending: Flow<List<PendingTip>> = state
    override suspend fun add(tip: PendingTip) { if (state.value.none { it.intentId == tip.intentId }) state.value = state.value + tip }
    /** When set, `remove` runs it after the state changed, before returning: a store whose write suspends (like DataStore's `edit`). */
    var afterRemove: (suspend () -> Unit)? = null
    override suspend fun remove(intentIds: Collection<String>) {
        state.value = state.value.filter { it.intentId !in intentIds }
        afterRemove?.invoke()
    }
    override suspend fun prune(now: Long) { state.value = PendingTips.active(state.value, now) }
    override suspend fun markDelivered(intentId: String) { state.value = state.value.map { if (it.intentId == intentId) it.copy(anonDelivered = true) else it } }
    override suspend fun clear() { state.value = emptyList() }
}

class FakeWatcherGateway(
    var info: WatcherInfo? = WatcherInfo("c".repeat(64), listOf("wss://watcher-relay.example"), "mainnet", 3_000_000, "test"),
    var infoError: Exception? = null,
    var registerError: Exception? = null,
) : WatcherGateway {
    val registered = ArrayList<Triple<String, Address, String>>()
    val unregistered = ArrayList<String>()
    var infoCalls = 0
    val infoUrls = ArrayList<String>()
    /** When set, info suspends until it completes (a never-completed gate = a watcher that never answers). */
    var infoGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var infoCancelled = 0
    override suspend fun info(url: String): WatcherInfo {
        infoCalls++; infoUrls += url
        try {
            infoGate?.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            infoCancelled++; throw e
        }
        infoError?.let { throw it }; return info ?: throw WatcherException.Protocol("no info")
    }
    override suspend fun register(url: String, address: Address, viewSecret: ByteArray): String {
        registerError?.let { throw it }
        registered += Triple(url, address, viewSecret.toHex())
        return info?.pubkey ?: "c".repeat(64)
    }
    override suspend fun unregister(url: String) { unregistered += url }
}

class FakeSecrets : SecretStore {
    val values = HashMap<String, String>()
    override fun put(name: String, value: String) { values[name] = value }
    override fun get(name: String) = values[name]
    override fun remove(name: String) { values.remove(name) }
}

class FakeUiSettings : UiSettingsStore {
    val modeState = MutableStateFlow(ThemeMode.SYSTEM)
    val accentState = MutableStateFlow(Accent.MONO)
    val blurSensitiveState = MutableStateFlow(true)
    val mediaOnTapState = MutableStateFlow(true)
    val authorRelaysState = MutableStateFlow(true)
    override val themeMode: Flow<ThemeMode> = modeState
    override val accent: Flow<Accent> = accentState
    override val blurSensitive: Flow<Boolean> = blurSensitiveState
    override val mediaOnTap: Flow<Boolean> = mediaOnTapState
    override val authorRelays: Flow<Boolean> = authorRelaysState
    val mediaServerState = MutableStateFlow(MediaServer.DEFAULT)
    override val mediaServer: Flow<String> = mediaServerState
    override suspend fun setMediaServer(url: String) { mediaServerState.value = url }
    override suspend fun setThemeMode(mode: ThemeMode) { modeState.value = mode }
    override suspend fun setAccent(accent: Accent) { accentState.value = accent }
    val customHueState = MutableStateFlow(200f)
    override val customHue: Flow<Float> = customHueState
    override suspend fun setCustomHue(hue: Float) { customHueState.value = hue }
    override suspend fun setBlurSensitive(on: Boolean) { blurSensitiveState.value = on }
    override suspend fun setMediaOnTap(on: Boolean) { mediaOnTapState.value = on }
    val linkPreviewsState = MutableStateFlow(false)
    override val linkPreviews: Flow<Boolean> = linkPreviewsState
    override suspend fun setLinkPreviews(on: Boolean) { linkPreviewsState.value = on }
    override suspend fun setAuthorRelays(on: Boolean) { authorRelaysState.value = on }
    val clientTagState = MutableStateFlow(false)
    override val clientTag: Flow<Boolean> = clientTagState
    override suspend fun setClientTag(on: Boolean) { clientTagState.value = on }
    val quickReactionsState = MutableStateFlow(com.monostr.app.ui.common.Reactions.QUICK)
    override val quickReactions: Flow<List<String>> = quickReactionsState
    val powBitsState = MutableStateFlow(0)
    override val powBits: Flow<Int> = powBitsState
    override suspend fun setPowBits(bits: Int) { powBitsState.value = bits }
    override suspend fun setQuickReactions(list: List<String>) { quickReactionsState.value = list.ifEmpty { com.monostr.app.ui.common.Reactions.QUICK } }
}

class FakeAuthorRelays(private val lists: Map<String, List<String>> = emptyMap()) : AuthorRelaysRepository {
    val attached = ArrayList<List<String>>(); val detached = ArrayList<List<String>>(); val fetched = ArrayList<Pair<List<String>, String>>()
    /** When set, `attach` suspends here before recording — models a slow/cancellable outbox setup. */
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun writeRelays(pubkey: String) = lists[pubkey] ?: emptyList()
    override suspend fun attach(relays: List<String>) { gate?.await(); attached += relays }
    override suspend fun detach(relays: List<String>) { detached += relays }
    /** What fetchNotes reports; the default is an id no screen shows, so every outbox tick counts as new. */
    var fetchedIds: Set<String> = setOf("f".repeat(64))
    override suspend fun fetchNotes(relays: List<String>, pubkey: String, limit: Int): Set<String> { fetched += relays to pubkey; return fetchedIds }
}

class FakeCounts : CountsRepository {
    private val _counts = MutableStateFlow<Map<String, NoteCounts>>(emptyMap())
    override val counts: StateFlow<Map<String, NoteCounts>> = _counts
    val requested = ArrayList<List<String>>()
    /** Ids asked with priority (a thread's focused note), in order; [requested] holds the normal ones. */
    val priorityRequested = ArrayList<List<String>>()
    val bumps = ArrayList<Triple<String, Int, Long>>()
    override fun request(noteIds: Collection<String>, priority: Boolean) {
        if (priority) priorityRequested += noteIds.toList() else requested += noteIds.toList()
    }
    override fun bump(noteId: String, kind: Int, delta: Long) { bumps += Triple(noteId, kind, delta) }
    fun set(id: String, c: NoteCounts) { _counts.update { it + (id to c) } }
}

class FakeBookmarks(initial: List<String> = emptyList(), var outcome: BookmarkOutcome = BookmarkOutcome.Ok, writable: Boolean = true) : BookmarksRepository {
    val _state = MutableStateFlow(BookmarksState(ids = initial, writable = writable, loaded = false))
    override val state: StateFlow<BookmarksState> = _state
    val toggled = ArrayList<String>()
    var notes: Map<String, Note> = emptyMap()
    /** The `interactive` flag of every ensureLoaded call, in order. */
    val loadCalls = ArrayList<Boolean>()
    /** When set, toggle suspends on it after the optimistic change, before the outcome (a publish in flight). */
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun ensureLoaded(interactive: Boolean) { loadCalls += interactive; _state.update { it.copy(loaded = true) } }
    override suspend fun toggle(noteId: String): BookmarkOutcome {
        toggled += noteId
        // Mirrors NostrBookmarksRepository.toggle: every outcome but NotLoaded means a load happened first,
        // the ids change optimistically before the publish and revert unless it succeeded.
        if (outcome != BookmarkOutcome.NotLoaded) _state.update { it.copy(loaded = true) }
        val before = _state.value.ids
        _state.update { s -> s.copy(ids = if (noteId in s.ids) s.ids - noteId else listOf(noteId) + s.ids) }
        gate?.await()
        if (outcome != BookmarkOutcome.Ok) _state.update { it.copy(ids = before) }
        return outcome
    }
    /** What the database holds (localEntries); null = the same as [notes]. */
    var localNotes: Map<String, Note>? = null
    /** When set, `entries` suspends here (the relays are asked for the missing notes). */
    var entriesGate: CompletableDeferred<Unit>? = null
    override suspend fun localEntries() = _state.value.ids.map { BookmarkEntry(it, (localNotes ?: notes)[it]) }
    override suspend fun entries(): List<BookmarkEntry> {
        entriesGate?.await()
        return _state.value.ids.map { BookmarkEntry(it, notes[it]) }
    }
    override fun clear() { _state.value = BookmarksState() }
}

/** Mirrors NostrMuteRepository: [muted] changes at once and reverts unless [outcome] is Ok. */
class FakeMute(initial: Set<String> = emptySet(), writable: Boolean = true, var outcome: ListOutcome = ListOutcome.Ok) : MuteRepository {
    override val muted = MutableStateFlow(initial)
    private val list = MutableStateFlow(ListState(ids = initial.toList(), writable = writable, loaded = false))
    override val state: StateFlow<ListState> = list
    /** The `interactive` flag of every ensureLoaded call, in order. */
    val loadCalls = ArrayList<Boolean>()
    /** When set, `ensureLoaded` suspends here before the list counts as loaded (the interactive load running). */
    var loadGate: CompletableDeferred<Unit>? = null
    override suspend fun ensureLoaded(interactive: Boolean) { loadCalls += interactive; loadGate?.await(); list.update { it.copy(loaded = true) } }
    /** When set, a write suspends here between the optimistic flip and the outcome (the publish in flight). */
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun mute(pubkey: String) = set(pubkey, present = true)
    override suspend fun unmute(pubkey: String) = set(pubkey, present = false)
    private suspend fun set(pubkey: String, present: Boolean): ListOutcome {
        val before = muted.value
        muted.value = if (present) before + pubkey else before - pubkey
        gate?.await()
        if (outcome != ListOutcome.Ok) muted.value = before
        list.update { it.copy(ids = muted.value.toList(), loaded = true) }
        return outcome
    }
    override fun clear() { muted.value = emptySet(); list.value = ListState() }
}

class FakeDmRepository : DmRepository {
    val wraps = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    /** The message a wrap unwraps to; null means [Unwrap.Locked] (the signer could not open it). */
    var unwrapAnswers: (Event, Boolean) -> DmIncoming? = { _, _ -> null }
    /** The full outcome; defaults to [unwrapAnswers]. */
    var unwrapOutcomes: (Event, Boolean) -> Unwrap = { w, silent -> unwrapAnswers(w, silent)?.let { Unwrap.Ok(it) } ?: Unwrap.Locked }
    /** Wrap ids of every unwrap call, in order. */
    val unwrapCalls = ArrayList<String>()
    var sendResult: DmSendResult = DmSendResult("r".repeat(64), 1000, sentToPeer = true, sentToSelf = true)
    /** When set, `send` throws it. */
    var sendError: Exception? = null
    /** When set, `send` suspends here first, to observe the provisional SENDING row. */
    var sendGate: CompletableDeferred<Unit>? = null
    var fetched: List<Event> = emptyList()
    /** False: the fetch ran into its timeout or an own relay refused it. */
    var fetchCompleted = true
    /** When set, `fetchWraps` throws it. */
    var fetchError: Exception? = null
    /** When set, fetchWraps suspends here (read at call time), to change the list while a round runs. */
    var fetchGate: CompletableDeferred<Unit>? = null
    /** Own relays the fetch reports as refusing AUTH, with the engine's sequence of that CLOSED. */
    var fetchAuthRefused: Map<String, Long> = emptyMap()
    val sent = ArrayList<Triple<String, String, List<String>>>()
    /** The `ownRelays` of every send, in order. */
    val sentOwnRelays = ArrayList<List<String>>()
    /** (relays, since) of every fetchWraps / subscribeWraps call, in order. */
    val fetchCalls = ArrayList<Pair<List<String>, Long>>()
    val subscribeCalls = ArrayList<Pair<List<String>, Long>>()
    val unsubscribed = ArrayList<String>()
    private var subs = 0
    override suspend fun send(peer: String, text: String, peerRelays: List<String>, ownRelays: List<String>): DmSendResult {
        sent += Triple(peer, text, peerRelays); sentOwnRelays += ownRelays
        sendGate?.await()
        sendError?.let { throw it }
        return sendResult
    }
    override suspend fun unwrap(wrap: Event, silent: Boolean): Unwrap {
        unwrapCalls += wrap.id().toHex()
        return unwrapOutcomes(wrap, silent)
    }
    override suspend fun fetchWraps(relays: List<String>, since: Long): WrapFetch {
        fetchCalls += relays to since
        fetchGate?.await()
        fetchError?.let { throw it }
        return WrapFetch(fetched, fetchCompleted, fetchAuthRefused)
    }
    /** Runs inside subscribeWraps with the id about to be returned (e.g. a relay refusing before the call returns). */
    var onSubscribe: suspend (String) -> Unit = {}
    override suspend fun subscribeWraps(relays: List<String>, since: Long): String {
        subscribeCalls += relays to since
        val id = "sub${++subs}"
        onSubscribe(id)
        return id
    }
    override suspend fun unsubscribe(id: String) { unsubscribed += id }
    override fun incomingWraps(): Flow<Event> = wraps
}

class FakeDmRelays(var own: OwnDmRelays = OwnDmRelays.None, private val peers: Map<String, List<String>> = emptyMap()) : DmRelaysRepository {
    var ownCalls = 0
    override suspend fun ownDmRelays(): OwnDmRelays { ownCalls++; return own }
    override suspend fun dmRelays(pubkey: String) = peers[pubkey] ?: listOf(DmRelaysRepository.FALLBACK)
}

class FakeDmSettings(relays: List<String> = listOf("wss://relay.monostr.com")) : DmSettingsStore {
    val relayState = MutableStateFlow(relays)
    val previewNameState = MutableStateFlow(false)
    val previewTextState = MutableStateFlow(false)
    val listAdoptedState = MutableStateFlow(false)
    override val relays: Flow<List<String>> = relayState
    override val previewName: Flow<Boolean> = previewNameState
    override val previewText: Flow<Boolean> = previewTextState
    override val listAdopted: Flow<Boolean> = listAdoptedState
    override suspend fun setRelays(relays: List<String>) { relayState.value = relays }
    override suspend fun setPreviewName(on: Boolean) { previewNameState.value = on }
    override suspend fun setPreviewText(on: Boolean) { previewTextState.value = on }
    override suspend fun setListAdopted(adopted: Boolean) { listAdoptedState.value = adopted }
}

class FakeFollows(initial: Map<String, FollowState> = emptyMap()) : FollowRepository {
    val states = MutableStateFlow(initial)
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val changes: SharedFlow<Unit> = _changes
    /** When set, follow/unfollow throw it and change nothing. */
    var error: Exception? = null
    /** When set, follow/unfollow suspend here first (a write in flight). */
    var gate: CompletableDeferred<Unit>? = null
    val calls = ArrayList<String>()
    override fun state(pubkey: String): Flow<FollowState> = states.map { it[pubkey] ?: FollowState.Unknown }
    override suspend fun follow(pubkey: String) = write(pubkey, FollowState.Following, "follow")
    override suspend fun unfollow(pubkey: String) = write(pubkey, FollowState.NotFollowing, "unfollow")
    override suspend fun setInitial(pubkeys: List<String>) {
        calls += "initial:${pubkeys.size}"
        error?.let { throw it }
        states.update { it + pubkeys.associateWith { FollowState.Following } }
        _changes.tryEmit(Unit)
    }
    private suspend fun write(pubkey: String, to: FollowState, label: String) {
        calls += "$label:${pubkey.take(4)}"
        gate?.await()
        error?.let { throw it }
        states.update { it + (pubkey to to) }
        _changes.tryEmit(Unit)
    }
}

/** Uploads nothing: answers with a made-up picture per call, or with [failure]; [gate] holds an upload back. */
class FakeMediaUploader : MediaUploader {
    /** "TARGET:source" of every upload that was started. */
    val uploads = ArrayList<String>()
    /** sha256 of every discarded picture. */
    val discarded = ArrayList<String>()
    /** "TARGET:source" of every upload that ran to its end. */
    val completed = ArrayList<String>()
    var gate: CompletableDeferred<Unit>? = null
    var failure: Exception? = null
    /** What the results say about the blob being new on the server. */
    var fresh = true
    /** When set, every upload gets this hash, whatever its source (two sources of one picture). */
    var sameHash: String? = null

    override suspend fun upload(source: String, target: ImageTarget, onProgress: (Float) -> Unit): UploadedMedia {
        uploads += "$target:$source"
        val n = uploads.size
        gate?.await()
        failure?.let { throw it }
        onProgress(1f)
        completed += "$target:$source"
        val hash = sameHash ?: n.toString().padStart(64, '0')
        return UploadedMedia("https://media.test", "https://media.test/$hash.jpg", "image/jpeg", hash, 1000L * n, 400, 300, "LNM}7u}qfQ}q", fresh)
    }

    override fun discard(media: UploadedMedia) {
        discarded += media.sha256
    }
}

/** In-memory [RecentSearchesStore] with the real store's rule: trimmed, case-insensitive, newest first, at most MAX. */
class FakeRecentSearches : RecentSearchesStore {
    val state = MutableStateFlow<List<String>>(emptyList())
    override val recent: Flow<List<String>> = state
    override suspend fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        state.value = (listOf(q) + state.value.filterNot { it.equals(q, ignoreCase = true) }).take(RecentSearchesStore.MAX)
    }
    override suspend fun remove(query: String) { state.value = state.value.filterNot { it.equals(query.trim(), ignoreCase = true) } }
    override suspend fun clear() { state.value = emptyList() }
}

/** In-memory [DraftStore] with the real store's rules; [fail] makes every call throw, [loadGate] holds loads back. */
class FakeDraftStore : DraftStore {
    private val drafts = HashMap<Pair<String, String>, Draft>()
    /** Every draft written, in order (empty ones too). */
    val saves = ArrayList<Draft>()
    var fail = false
    var loadGate: CompletableDeferred<Unit>? = null

    fun put(pubkey: String, target: String, draft: Draft) { drafts[pubkey to target] = draft }
    fun stored(pubkey: String, target: String): Draft? = drafts[pubkey to target]
    private fun failIfAsked() { if (fail) throw java.io.IOException("disk full") }

    override suspend fun load(pubkey: String, target: String): Draft? { loadGate?.await(); failIfAsked(); return drafts[pubkey to target] }
    override suspend fun save(pubkey: String, target: String, draft: Draft) {
        failIfAsked()
        saves += draft
        if (draft.isEmpty) drafts.remove(pubkey to target) else drafts[pubkey to target] = draft
    }
    override suspend fun remove(pubkey: String, target: String) { failIfAsked(); drafts.remove(pubkey to target) }
    override suspend fun expire(pubkey: String, now: Long, maxAgeSeconds: Long): List<Draft> {
        failIfAsked()
        val gone = drafts.filter { (k, d) -> k.first == pubkey && now - d.updatedAt >= maxAgeSeconds }
        gone.keys.forEach { drafts.remove(it) }
        return gone.values.toList()
    }
    override suspend fun all(pubkey: String): List<Draft> { failIfAsked(); return drafts.filterKeys { it.first == pubkey }.values.toList() }
    override suspend fun removeAll(pubkey: String): List<Draft> {
        failIfAsked()
        val gone = drafts.filterKeys { it.first == pubkey }
        gone.keys.forEach { drafts.remove(it) }
        return gone.values.toList()
    }
}

class FakeFollowCounts(private val counts: Map<String, List<FollowCounts>> = emptyMap(), private val fail: Boolean = false) : FollowCountsRepository {
    val asked = ArrayList<String>()
    override fun counts(pubkey: String): Flow<FollowCounts> = flow {
        asked += pubkey
        if (fail) throw IllegalStateException("counts failed")
        counts[pubkey].orEmpty().forEach { emit(it) }
    }
    var invalidated = 0
    override fun invalidateAll() { invalidated++ }
}

fun followSet(d: String, members: List<String>, author: String = "e".repeat(64), title: String = "List $d", private: Boolean = false, locked: Boolean = false) =
    FollowSet(ListAddress(30000, author, d), title, null, members, private, author, 1000, locked)

class FakeLists(initial: List<FollowSet> = emptyList()) : ListsRepository {
    val ownState = MutableStateFlow(initial)
    var foreign: Map<ListAddress, FollowSet> = emptyMap()
    var byAuthor: Map<String, List<FollowSet>> = emptyMap()
    var result: SaveResult = SaveResult.Ok
    /** When set, `setMember` suspends here after recording the call. */
    var gate: CompletableDeferred<Unit>? = null
    /** What `unlock` turns the list into; null = it stays locked. */
    var unlockTo: FollowSet? = null
    val calls = ArrayList<String>()
    /** When set, own() emits nothing until it completes (the own lists are still loading). */
    var ownGate: CompletableDeferred<Unit>? = null
    override fun own(): Flow<List<FollowSet>> = flow {
        ownGate?.await()
        emitAll(ownState)
    }
    /** What the database holds per author (localOf); the relays' answer is [byAuthor]. */
    var localByAuthor: Map<String, List<FollowSet>> = emptyMap()
    /** When set, `of` suspends here (a slow relay lookup). */
    var ofGate: CompletableDeferred<Unit>? = null
    override suspend fun localOf(author: String) = localByAuthor[author] ?: emptyList()
    override suspend fun of(author: String): List<FollowSet> {
        ofGate?.await()
        return byAuthor[author] ?: emptyList()
    }
    /** When set, a lookup that fetches from relays throws (offline). */
    var fetchFails = false
    /** (address, fetch) of every byAddress call, in order. */
    val byAddressCalls = ArrayList<Pair<ListAddress, Boolean>>()
    override suspend fun byAddress(address: ListAddress, fetch: Boolean): FollowSet? {
        byAddressCalls += address to fetch
        if (fetch && fetchFails) throw IllegalStateException("offline")
        return ownState.value.firstOrNull { it.address == address } ?: foreign[address]
    }
    override suspend fun save(set: FollowSet): SaveResult {
        calls += "save:${set.address.d}:${set.private}"
        if (result == SaveResult.Ok) ownState.value = listOf(set) + ownState.value.filterNot { it.address == set.address }
        return result
    }
    override suspend fun edit(address: ListAddress, title: String, description: String?, private: Boolean): SaveResult {
        calls += "edit:${address.d}:$private"
        val current = ownState.value.firstOrNull { it.address == address } ?: return SaveResult.Failed
        if (result == SaveResult.Ok) ownState.value = listOf(current.copy(title = title, description = description, private = private)) + ownState.value.filterNot { it.address == address }
        return result
    }
    override suspend fun setMember(address: ListAddress, pubkey: String, present: Boolean): SaveResult {
        calls += "member:${address.d}:${pubkey.take(4)}:$present"
        gate?.await()
        if (result == SaveResult.Ok) ownState.value = ownState.value.map { s ->
            if (s.address != address) s else s.copy(members = if (present) (s.members + pubkey).distinct() else s.members - pubkey)
        }
        return result
    }
    override suspend fun delete(set: FollowSet): SaveResult {
        calls += "delete:${set.address.d}"
        if (result == SaveResult.Ok) ownState.value = ownState.value.filterNot { it.address == set.address }
        return result
    }
    override suspend fun unlock(address: ListAddress): Boolean {
        calls += "unlock:${address.d}"
        val open = unlockTo ?: return false
        ownState.value = ownState.value.map { if (it.address == address) open else it }
        return true
    }
    override fun newId() = "newid0000001"
}

class FakeFeedTabs(initial: List<ListAddress> = emptyList()) : FeedTabsRepository {
    val state = MutableStateFlow(initial)
    var result: SaveResult = SaveResult.Ok
    val calls = ArrayList<String>()
    override fun tabs(): Flow<List<ListAddress>> = state
    override suspend fun pin(address: ListAddress): SaveResult {
        calls += "pin:${address.d}"
        if (address in state.value) return SaveResult.Ok
        if (state.value.size >= 5) return SaveResult.TooMany
        if (result == SaveResult.Ok) state.value = state.value + address
        return result
    }
    override suspend fun unpin(address: ListAddress): SaveResult {
        calls += "unpin:${address.d}"
        if (result == SaveResult.Ok) state.value = state.value - address
        return result
    }
    override suspend fun move(address: ListAddress, delta: Int): SaveResult {
        calls += "move:${address.d}:$delta"
        if (result != SaveResult.Ok) return result
        val l = state.value.toMutableList()
        val i = l.indexOf(address)
        if (i >= 0) l.add((i + delta).coerceIn(0, l.lastIndex), l.removeAt(i))
        state.value = l
        return SaveResult.Ok
    }
}
