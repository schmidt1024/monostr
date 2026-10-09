package com.monostr.app.ui

import com.monostr.app.data.Accent
import com.monostr.app.data.DmSettingsStore
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
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteAttachment
import com.monostr.nostr.model.NoteCounts
import com.monostr.nostr.model.Profile
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
    var followList: List<String> = listOf("a".repeat(64))
    override suspend fun follows() = followList
    override suspend fun followsLocal() = followList
    override suspend fun notes(limit: Int, until: Long?) = current
    override suspend fun refresh(limit: Int) {
        refreshCalls++
        refreshGate?.await()
    }
    override suspend fun loadMore(before: Long, limit: Int): List<Note> {
        loadMoreCalls++
        loadMoreGate?.await()
        loadMoreError?.let { throw it }
        return older
    }
    /** How often [live] was started (each restart counts). */
    var liveCalls = 0
    override fun live(limit: Int): Flow<List<Note>> = flow { liveCalls++; emit(current); liveUpdates.collect { emit(it) } }
    /** The `fetch` flag of every notesBy call, in order. */
    val notesByCalls = ArrayList<Boolean>()
    override suspend fun notesBy(author: String, limit: Int, fetch: Boolean): List<Note> {
        notesByCalls += fetch
        return byAuthor[author] ?: emptyList()
    }
    /** Section and `fetch` flag of every profileNotes call, in order (the fetch flag is also added to [notesByCalls]). */
    val profileCalls = ArrayList<Pair<ProfileSection, Boolean>>()
    override suspend fun profileNotes(author: String, section: ProfileSection, limit: Int, fetch: Boolean): List<Note> {
        notesByCalls += fetch
        profileCalls += section to fetch
        return (byAuthor[author] ?: emptyList()).filter(section::keeps)
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
    override fun observe(pubkey: String) = current.map { it[pubkey] ?: Profile.empty(pubkey) }
    override suspend fun get(pubkey: String, maxAgeSeconds: Long) = current.value[pubkey] ?: Profile.empty(pubkey)
    override suspend fun prefetch(pubkeys: Collection<String>) { prefetched += pubkeys }
    override suspend fun local(pubkeys: Collection<String>) = pubkeys.mapNotNull { current.value[it] }
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
    private suspend fun result(kind: Int, id: String? = null): PublishResult {
        gate?.await()
        if (fail) throw IllegalStateException("boom")
        counter++
        return PublishResult(id ?: counter.toString().padStart(64, '0'), kind, if (relaysOk) listOf("wss://r") else emptyList(), emptyMap())
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
    override suspend fun like(note: Note) = result(7).also { calls += "like:${note.id.take(4)}" }
    override suspend fun repost(note: Note) = result(6).also { calls += "repost:${note.id.take(4)}" }
    override suspend fun delete(note: Note) = result(5).also { calls += "delete:${note.id.take(4)}" }
    override suspend fun noteLink(note: Note) = "nostr:nevent1fake${note.id.take(8)}"
    /** When set, a resend throws. */
    var failResend = false
    override suspend fun resend(eventId: String) = result(1, eventId).also { if (failResend) throw IllegalStateException("resend boom") }.also { calls += "resend:${eventId.takeLast(4)}" }
    override suspend fun relayList(relays: List<String>) = result(10002).also { calls += "relays:${relays.size}" }
    override suspend fun publishRelayList(relays: List<String>) = result(10002).also { calls += "relaylist:${relays.size}" }
    override suspend fun dmRelayList(relays: List<String>) = result(10050).also { calls += "dmrelays:${relays.size}" }
}

class FakeThreads(private val views: Map<String, ThreadView>, private val notes: Map<String, Note> = emptyMap()) : ThreadRepository {
    override fun observe(noteId: String): Flow<ThreadView> = flow { views[noteId]?.let { emit(it) } }
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
    val publishedInfos = ArrayList<Triple<Address, String, String>>()
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
    /** Pubkeys asked through the local-only path, and through the age-limited (possibly fetching) path. */
    val cachedCalls = ArrayList<String>()
    val staleCalls = ArrayList<String>()
    override suspend fun paymentInfo(pubkey: String, maxAgeSeconds: Long): PaymentInfo {
        if (maxAgeSeconds == 0L && liveInfos != null) {
            if (liveDelayMs > 0) delay(liveDelayMs)
            return liveInfos!![pubkey] ?: PaymentInfo.Disabled
        }
        if (maxAgeSeconds != 0L) staleCalls += pubkey
        return infos[pubkey] ?: PaymentInfo.Disabled
    }
    override suspend fun cachedPaymentInfo(pubkey: String): PaymentInfo {
        cachedCalls += pubkey
        return infos[pubkey] ?: PaymentInfo.Disabled
    }
    override suspend fun publishPaymentInfo(address: Address, watcherUrl: String, watcherPubkey: String): PublishResult {
        publishedInfos += Triple(address, watcherUrl, watcherPubkey)
        return PublishResult((++counter).toString().padStart(64, '0'), 10037, if (relaysOk) listOf("wss://r") else emptyList(), emptyMap())
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
    override fun observeReceipts(notes: Map<String, String>): Flow<Map<String, TipSummary>> { observed += notes; return receipts.map { all -> all.filterKeys { it in notes } } }
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
    var hashtagNotes: Map<String, List<Note>> = emptyMap()
    var error: Exception? = null
    val attached = ArrayList<List<String>>()
    val detached = ArrayList<List<String>>()
    val calls = ArrayList<String>()
    /** When set, `notes` suspends here before returning — models an in-flight relay round trip for tests that cancel mid-search. */
    var notesGate: CompletableDeferred<Unit>? = null
    override suspend fun profilesLocal(query: String, limit: Int) = local.also { calls += "local:$query" }
    override suspend fun profiles(query: String, relays: List<String>, limit: Int): SearchOutcome<Profile> {
        calls += "people:$query:${relays.size}"
        return SearchOutcome((local + remotePeople).distinctBy { it.pubkey }, error)
    }
    override suspend fun notes(query: String, relays: List<String>, limit: Int): SearchOutcome<Note> {
        calls += "notes:$query:${relays.size}"
        notesGate?.await()
        return if (relays.isEmpty()) SearchOutcome(emptyList()) else SearchOutcome(remoteNotes, error)
    }
    override suspend fun hashtag(tag: String, until: Long?, limit: Int): SearchOutcome<Note> {
        calls += "tag:$tag:$until"
        return SearchOutcome(hashtagNotes[tag].orEmpty().filter { until == null || it.createdAt < until }, error)
    }
    override suspend fun attach(relays: List<String>) { attached += relays }
    override suspend fun detach(relays: List<String>) { detached += relays }
}

class FakeNotifications(var items: List<NotificationItem> = emptyList()) : NotificationsRepository {
    var refreshCalls = ArrayList<Long?>()
    /** When set, `refresh` suspends until completed — models a slow relay fetch. */
    var refreshGate: CompletableDeferred<Unit>? = null
    val updates = MutableSharedFlow<List<NotificationItem>>()
    var about: Map<String, AboutNote> = emptyMap()
    val notesCalls = ArrayList<Set<String>>()
    /** When set, `notes` suspends until completed — models a slow relay lookup. */
    var notesGate: CompletableDeferred<Unit>? = null
    override suspend fun list(limit: Int) = items.sortedByDescending { it.createdAt }.take(limit)
    /** Null: every id counts as the user's own (tests that do not care). */
    var ownIds: Set<String>? = null
    val ownCalls = ArrayList<Set<String>>()
    override suspend fun own(ids: Set<String>): Set<String> { ownCalls += ids; return ownIds?.let { ids intersect it } ?: ids }
    override suspend fun notes(ids: Set<String>): Map<String, AboutNote> {
        notesCalls += ids
        notesGate?.await()
        return ids.associateWith { about[it] ?: AboutNote.Missing }
    }
    override suspend fun refresh(since: Long?, limit: Int) { refreshCalls += since; refreshGate?.await() }
    override fun live(limit: Int): Flow<List<NotificationItem>> = flow {
        // mirrors NostrNotificationsRepository.live: an empty local list is not shown before the first refresh
        val local = list(limit)
        if (local.isNotEmpty()) emit(local)
        refresh(null, limit)
        emit(list(limit))
        updates.collect { items = it; emit(list(limit)) }
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
    override suspend fun setPresets(piconero: List<Long>) { presetsState.value = piconero.sorted() }
    override suspend fun setSetup(setup: MoneroSetup?) { setupState.value = setup }
    override suspend fun setOnboardingSeen() { seenState.value = true }
    override suspend fun setNotificationsReadAt(at: Long) { readAtState.value = at }
    override suspend fun lastTipNotifiedAt() = lastNotified
    override suspend fun setLastTipNotifiedAt(at: Long) { lastNotified = at }
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
    override suspend fun info(url: String): WatcherInfo { infoError?.let { throw it }; return info ?: throw WatcherException.Protocol("no info") }
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
    val mediaOnTapState = MutableStateFlow(false)
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
    override suspend fun setAuthorRelays(on: Boolean) { authorRelaysState.value = on }
    val clientTagState = MutableStateFlow(true)
    override val clientTag: Flow<Boolean> = clientTagState
    override suspend fun setClientTag(on: Boolean) { clientTagState.value = on }
    val primalStatsState = MutableStateFlow(true)
    override val primalStats: Flow<Boolean> = primalStatsState
    override suspend fun setPrimalStats(on: Boolean) { primalStatsState.value = on }
}

class FakeAuthorRelays(private val lists: Map<String, List<String>> = emptyMap()) : AuthorRelaysRepository {
    val attached = ArrayList<List<String>>(); val detached = ArrayList<List<String>>(); val fetched = ArrayList<Pair<List<String>, String>>()
    /** When set, `attach` suspends here before recording — models a slow/cancellable outbox setup. */
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun writeRelays(pubkey: String) = lists[pubkey] ?: emptyList()
    override suspend fun attach(relays: List<String>) { gate?.await(); attached += relays }
    override suspend fun detach(relays: List<String>) { detached += relays }
    override suspend fun fetchNotes(relays: List<String>, pubkey: String, limit: Int) { fetched += relays to pubkey }
}

class FakeCounts : CountsRepository {
    private val _counts = MutableStateFlow<Map<String, NoteCounts>>(emptyMap())
    override val counts: StateFlow<Map<String, NoteCounts>> = _counts
    val requested = ArrayList<List<String>>()
    val bumps = ArrayList<Triple<String, Int, Long>>()
    override fun request(noteIds: Collection<String>) { requested += noteIds.toList() }
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
    override suspend fun entries() = _state.value.ids.map { BookmarkEntry(it, notes[it]) }
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
    val previewNameState = MutableStateFlow(true)
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
