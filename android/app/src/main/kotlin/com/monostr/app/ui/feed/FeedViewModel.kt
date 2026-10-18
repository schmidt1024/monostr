package com.monostr.app.ui.feed

import kotlinx.coroutines.flow.first
import com.monostr.nostr.repo.OwnReactions
import com.monostr.nostr.repo.MemoryOwnReactions
import com.monostr.app.data.dm.unreadTotalExcept
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import com.monostr.app.ui.common.MentionResolver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.data.HintStore
import com.monostr.app.data.SupportHint
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.session.NostrSession
import com.monostr.app.session.Ready
import com.monostr.app.ui.common.NoteMenu
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.NoteActions
import com.monostr.app.ui.common.NoteDeletions
import com.monostr.app.ui.common.Message
import com.monostr.app.ui.common.MuteActions
import com.monostr.app.ui.common.asMessage
import com.monostr.app.ui.common.NoteVisibility
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.noteMenu
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.notifications.Unread
import com.monostr.app.ui.tips.AmountsPrompt
import com.monostr.app.ui.tips.AmountsVisibility
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.app.ui.tips.TipSheetFactory
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteCounts
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.Chain
import com.monostr.nostr.repo.ConversationRepository
import com.monostr.nostr.repo.CountsRepository
import com.monostr.nostr.repo.Slot
import com.monostr.nostr.repo.FeedRepository
import com.monostr.nostr.repo.FeedSource
import com.monostr.nostr.repo.MuteRepository
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.PublishRepository
import com.monostr.nostr.repo.TipSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NoteUi(
    val note: Note,
    val author: Profile,
    val repostedAuthor: Profile,
    val liked: Boolean = false,
    /** The user's own reaction when [liked]: "+" (the heart) or an emoji (NIP-25). */
    val reaction: String? = null,
    val reposted: Boolean = false,
    /** Valid receipts of the target note (spec 5.6); null when there are none. */
    val tips: TipSummary? = null,
    /** An own tip intent without receipt yet (spec 5.5 step 5). */
    val pendingTip: Boolean = false,
    /** A receipt names the user as sender: the tip icon and its count take the accent colour, like an own like. */
    val tipped: Boolean = false,
    /** False for the user's own notes: tipping oneself only moves money in a circle. */
    val canTip: Boolean = true,
    /** Whether [note]'s target is in the private bookmark list (spec 4.3). */
    val bookmarked: Boolean = false,
    /** Whether the bookmark icon is enabled: the list is writable, or not yet loaded (spec 4.3). */
    val bookmarkEnabled: Boolean = true,
    /** NIP-45 counts of the target (spec 4); null until requested. */
    val counts: NoteCounts? = null,
    /** The shown note (the original of a repost) is by the logged-in user: the menu offers "request deletion". */
    val isOwn: Boolean = false,
    /** A deletion request for the shown note is on its way: the menu entry is disabled. */
    val deleting: Boolean = false,
)

/** The block above a reply in the feed (spec 2026-10-16 §2.3): ancestor ids root first; [cut]: a "Show replies" row after the first. Key = the entry's `NoteUi.note.id`. */
data class ChainUi(val ancestors: List<String>, val cut: Boolean)

/** No conversations: for tests and callers that do not care. */
object NoConversations : ConversationRepository {
    override suspend fun chains(notes: List<Note>): Map<String, Chain> = emptyMap()
    override suspend fun ancestors(note: Note): List<Slot> = emptyList()
}

data class FeedUiState(
    val notes: List<NoteUi> = emptyList(),
    val chains: Map<String, ChainUi> = emptyMap(),
    /** Decorated ancestor notes by id; an id of a chain that is missing here is shown as "unavailable" (not found, withdrawn, muted, deleted). */
    val ancestors: Map<String, NoteUi> = emptyMap(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    /** The snackbar's message; after a mute it offers to unmute the account (spec 9.4). */
    val message: Message? = null,
) {
    /**
     * What the feed shows: [notes] without the ones that already stand as an ancestor in a conversation
     * block, so a thread appears once, at its newest reply, like on X. A repost stays: it tells who reposted.
     */
    val entries: List<NoteUi> by lazy {
        // only blocks that are shown: a reply hidden later (mute, delete) gives its first post back
        val inBlocks = notes.flatMapTo(HashSet()) { chains[it.note.id]?.ancestors.orEmpty() }
        if (inBlocks.isEmpty()) notes else notes.filterNot { it.note.repostOf == null && it.note.id in inBlocks }
    }
}

/** Feed logic shared by the Hilt ViewModel and the JVM tests. */
class FeedController(
    private val feed: FeedRepository,
    private val profiles: ProfileRepository,
    private val publish: PublishRepository,
    private val decorations: TipDecorations,
    private val bookmarks: BookmarkActions,
    private val counts: CountsRepository,
    private val scope: CoroutineScope,
    private val conversations: ConversationRepository = NoConversations,
    private val mentions: MentionResolver = MentionResolver(profiles, scope),
    /** Spec 2: emits after an own follow/unfollow; the author list of [FeedRepository.live] is read once per start (a mute change restarts it too). */
    private val followChanges: Flow<Unit> = emptyFlow(),
    private val deletions: NoteDeletions = NoteDeletions(),
    private val visibility: NoteVisibility = NoteVisibility(deletions, MutableStateFlow(emptySet())),
    private val afterDelete: suspend (Note) -> UiText? = { null },
    /** The session's own likes and reposts, shared by every screen; a fresh one in tests. */
    private val own: OwnReactions = MemoryOwnReactions(),
    /** Null (tests that do not mute): [mute] and [unmute] do nothing. */
    mute: MuteRepository? = null,
    /** The scope mute writes run on, so they survive the screen that started them (session: the engine's background scope); null (tests) = [scope]. */
    muteScope: CoroutineScope? = null,
    /** Whose notes this feed shows: the home feed, or a list's members (spec 2026-10-18 §4.3). */
    private val source: FeedSource = FeedSource.Following,
) {
    val names: StateFlow<Map<String, String>> get() = mentions.names
    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()
    private val actions = NoteActions(publish, scope, counts, deletions, afterDelete, own)
    private val muteActions = mute?.let { MuteActions(it, scope, profiles, background = muteScope) }

    /**
     * Local notes first: the live collector emits the database window immediately, while the
     * relay refresh runs separately. `loading` ends on whichever finishes first.
     */
    fun start() {
        mentions.follow(_state.map { s -> s.notes.map { it.note } + s.ancestors.values.map { it.note } })
        bookmarks.ensureLoaded()
        scope.launch { bookmarks.changes.collect { _state.update { s -> s.mapAll(bookmarks::flags) } } }
        scope.launch { actions.changes.collect { _state.update { s -> s.mapAll(actions::flags) } } }
        scope.launch { visibility.changes.collect { _state.update { s -> s.copy(notes = visibility.visible(s.notes), ancestors = visibleAncestors()) } } }
        // a request started on another screen greys the entry here too, and frees it again when it failed
        scope.launch { deletions.pending.collect { _state.update { s -> s.mapAll(actions::flags) } } }
        decorations.start()
        scope.launch { decorations.changes.collect { _state.update { s -> s.mapAll(decorations::decorate) } } }
        scope.launch { counts.counts.collect { _state.update { s -> s.mapAll(::withCounts) } } }
        if (liveOn) startLive()
        // the repository leaves muted accounts out at the query (read once per start), so a mute or an unmute,
        // also one reverted after a failed publish, restarts the list like a follow change does
        scope.launch { merge(followChanges, visibility.muted.drop(1).map { }).collect { if (liveOn) { startLive(); refresh() } } }
        scope.launch {
            try {
                feed.refresh(source)
                val decorated = decorate(feed.notes(source))
                setNotes(loading = false) { s -> merge(decorated, s.notes) }
                chainsFor(decorated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // relays are best-effort; the live collector still shows the local notes
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private var liveJob: Job? = null

    /** Whether the live subscription runs; only the visible tab of the home feed is live (spec 2026-10-18 §4.3). */
    private var liveOn = true

    /** Off: the live subscription ends, the notes stay. On again: live restarts and the feed refreshes (spec §4.3). */
    fun setLive(on: Boolean) {
        if (on == liveOn) return
        liveOn = on
        if (on) {
            startLive()
            refresh()
        } else {
            liveJob?.cancel()
            liveJob = null
        }
    }

    private fun startLive() {
        liveJob?.cancel()
        liveJob = scope.launch {
            try {
                feed.live(source).collect { notes ->
                    val decorated = decorate(notes)
                    setNotes(loading = false) { s -> merge(decorated, s.notes) }
                    chainsFor(decorated)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = uiText(R.string.feed_error_load).asMessage(), loading = false) }
            }
        }
    }

    fun refresh() {
        scope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                feed.refresh(source)
                val decorated = decorate(feed.notes(source))
                setNotes(loading = false, refreshing = false) { s -> merge(decorated, s.notes) }
                chainsFor(decorated)
                retryUnavailable()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = uiText(R.string.feed_error_load).asMessage(), refreshing = false, loading = false) }
            }
        }
    }

    /** Fetches the page before the oldest note; ignored while a previous call is still in flight or there is nothing to page from. */
    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || current.notes.isEmpty()) return
        val oldest = current.notes.last().note.createdAt
        _state.update { it.copy(loadingMore = true) }
        scope.launch {
            try {
                val older = feed.loadMore(source, before = oldest)
                val decorated = decorate(older)
                setNotes { s -> (s.notes + decorated).distinctBy { it.note.id } }
                chainsFor(decorated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = uiText(R.string.feed_error_more).asMessage()) }
            } finally {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    /** Every ancestor note as loaded, before [visibility]: a muted account unmuted again comes back from here. */
    private val allAncestors = HashMap<String, NoteUi>()

    /** Entry ids whose chain a running job is resolving: a second job does not walk them again. */
    private val resolving = HashSet<String>()

    private fun chainsFor(page: List<NoteUi>) = resolveChains(page.filter { it.note.id !in _state.value.chains })

    /** Spec 2026-10-16 §2.2 Timeouts, §6: a refresh tries the chains again that had unavailable notes (muted ones are loaded, so they stay). */
    private fun retryUnavailable() {
        val s = _state.value
        resolveChains(s.notes.filter { n -> s.chains[n.note.id]?.ancestors?.any { it !in allAncestors } == true })
    }

    /**
     * Resolves the chains of [page] in its own job, so the list, live updates and paging never wait for it, and stores
     * them with their decorated ancestors (spec 2026-10-16 §2.3). Entries already in flight are left out.
     */
    private fun resolveChains(page: List<NoteUi>) {
        val todo = page.filter { (it.note.repostOf ?: it.note).isReply && it.note.id !in resolving }
        if (todo.isEmpty()) return
        val ids = todo.map { it.note.id }
        resolving += ids
        scope.launch {
            try {
                storeChains(conversations.chains(todo.map { it.note }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // best-effort: the replies stay single; a later refresh tries again
            } finally {
                resolving -= ids.toSet()
            }
        }
    }

    private suspend fun storeChains(found: Map<String, Chain>) {
        if (found.isEmpty()) return
        val shown = found.values.flatMap { c -> c.ancestors.filterIsInstance<Slot.Shown>().map { it.note } }.distinctBy { it.id }
        decorate(shown).forEach { allAncestors[it.note.id] = it }
        _state.update { s ->
            s.copy(
                chains = s.chains + found.mapValues { (_, c) -> ChainUi(c.ancestors.map { it.id }, c.cut) },
                ancestors = visibleAncestors(),
            )
        }
        trackShown()
    }

    /** Keeps the receipt subscription on everything shown: the entries and their ancestors (track replaces the set). */
    private fun trackShown() {
        val s = _state.value
        decorations.track((s.notes.map { it.note } + s.ancestors.values.map { it.note }).distinctBy { it.id })
    }

    /** The stored ancestors with the current flags, counts and tips, minus what [visibility] hides. */
    private fun visibleAncestors(): Map<String, NoteUi> =
        visibility.visible(allAncestors.values.map(::flagged)).associateBy { it.note.id }

    /** Applies [f] to every shown note: the entries and the ancestors (flags, counts, tips). */
    private fun FeedUiState.mapAll(f: (NoteUi) -> NoteUi) = copy(notes = notes.map(f), ancestors = ancestors.mapValues { (_, n) -> f(n) })

    /**
     * Sets the note list and keeps the receipt subscription in step with what is shown. The flags are applied inside the
     * update: a list decorated before a slow profile lookup would otherwise bring back flags a change collector had
     * already replaced (a pending tip, a like, a bookmark), and no later change would correct them.
     */
    private fun setNotes(loading: Boolean? = null, refreshing: Boolean? = null, f: (FeedUiState) -> List<NoteUi>) {
        _state.update { s -> s.copy(notes = visibility.visible(f(s).map(::flagged)), loading = loading ?: s.loading, refreshing = refreshing ?: s.refreshing) }
        trackShown()
    }

    /** [ui] with the current tips, flags and counts. */
    private fun flagged(ui: NoteUi): NoteUi = withCounts(actions.flags(bookmarks.flags(decorations.decorate(ui))))

    /**
     * Combines a fresh [live] snapshot with [previous] state: the live list is the truth for
     * everything it covers, but notes older than its oldest entry (loaded via [loadMore]) are
     * kept rather than dropped.
     */
    private fun merge(live: List<NoteUi>, previous: List<NoteUi>): List<NoteUi> {
        if (live.isEmpty()) return previous
        val oldest = live.minOf { it.note.createdAt }
        return (live + previous.filter { it.note.createdAt < oldest }).distinctBy { it.note.id }
    }

    fun like(note: NoteUi) = like(note.target)
    fun repost(note: NoteUi) = repost(note.target)
    fun like(note: Note) = actions.like(note, ::onActionResult)
    fun react(note: Note, emoji: String) = actions.react(note, emoji, ::onActionResult)
    fun delete(note: Note) = actions.delete(note)
    fun repost(note: Note) = actions.repost(note, ::onActionResult)
    fun bookmark(note: NoteUi) = bookmarks.toggle(note.target) { m -> _state.update { s -> s.mapAll(bookmarks::flags).copy(message = m?.asMessage() ?: s.message) } }
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Spec 9.4: mutes the note's author at once; the publish runs behind it, a failure brings the account back with the usual message. */
    fun mute(note: Note) {
        muteActions?.mute(note.author) { m, ok -> _state.update { it.copy(message = Message(m, undoMute = note.author.takeIf { ok })) } }
    }

    /** The snackbar's "Undo" (spec 9.4): the mute change restarts the list (see [start]), which brings the account's notes back. */
    fun unmute(pubkey: String) {
        muteActions?.unmute(pubkey) { m -> if (m != null) _state.update { it.copy(message = m.asMessage()) } }
    }

    /** Visible note ids from the list; counts are requested for their targets (spec 4.2). */
    fun visible(noteIds: List<String>) {
        val wanted = noteIds.toSet()
        // the feed reports entries only; the notes above each reply get their counts through it (spec 2026-10-16 §3)
        val withAncestors = wanted + wanted.flatMap { _state.value.chains[it]?.ancestors ?: emptyList() }
        val targets = (_state.value.notes + _state.value.ancestors.values).filter { it.note.id in withAncestors }.map { it.target.id }.distinct()
        if (targets.isNotEmpty()) { counts.request(targets); actions.check(targets) }
    }

    private fun onActionResult(message: UiText) {
        _state.update { s -> s.mapAll(actions::flags).copy(message = message.asMessage()) }
    }

    /** [ui] with its current count, so a list rebuild (live/refresh/loadMore) never drops a count the collector already had (spec 4.2). */
    private fun withCounts(ui: NoteUi): NoteUi = ui.copy(counts = counts.counts.value[ui.target.id])

    /** [notes] with their authors' profiles; the flags follow in [setNotes] / [visibleAncestors], read at the moment of the update. */
    private suspend fun decorate(notes: List<Note>): List<NoteUi> {
        val pubkeys = notes.flatMap { listOfNotNull(it.author, it.repostOf?.author) }
        profiles.prefetch(pubkeys)
        return notes.map { n ->
            NoteUi(n, profiles.get(n.author), n.repostOf?.let { profiles.get(it.author) } ?: profiles.get(n.author))
        }
    }
}

/** The note a like/repost/reply/tip refers to: the original for reposts. */
val NoteUi.target: Note get() = note.repostOf ?: note

/** A list's feed (spec 2026-10-18 §4.3): the home feed's controller over [source], on its own [scope]. */
fun Ready.listFeed(factory: TipSheetFactory, source: FeedSource.Authors, scope: CoroutineScope): FeedController = FeedController(
    feed, profiles, publish, factory.decorations(scope),
    BookmarkActions(bookmarks, scope, background = engine.backgroundScope), counts, scope,
    conversations = conversations, mentions = MentionResolver(profiles, scope, Dispatchers.Default),
    deletions = deletions, visibility = visibility, afterDelete = afterNoteDelete, own = ownReactions,
    mute = mute, muteScope = engine.backgroundScope, source = source,
)

@HiltViewModel
class FeedViewModel @Inject constructor(session: NostrSession, private val factory: TipSheetFactory, settings: TipSettingsStore, private val hints: HintStore) : ViewModel() {
    private val ready = session.requireReady()
    val decorations = factory.decorations(viewModelScope)
    val bookmarks = BookmarkActions(ready.bookmarks, viewModelScope, background = ready.engine.backgroundScope)
    val controller = FeedController(ready.feed, ready.profiles, ready.publish, decorations, bookmarks, ready.counts, viewModelScope, conversations = ready.conversations, mentions = MentionResolver(ready.profiles, viewModelScope, Dispatchers.Default), followChanges = ready.follows.changes, deletions = ready.deletions, visibility = ready.visibility, afterDelete = ready.afterNoteDelete, own = ready.ownReactions, mute = ready.mute, muteScope = ready.engine.backgroundScope)
    val deletions = ready.deletions
    val menu = ready.noteMenu(delete = controller::delete, mute = controller::mute)

    /** Spec 2026-10-18 §4.3: Following plus one feed per pinned list. */
    val tabs = HomeTabsController(ready.feedTabs, ready.lists, viewModelScope, ready.pubkey, controller) { source, scope -> ready.listFeed(factory, source, scope) }

    /** The note menu of [feed]: delete and mute go through the tab's own controller. */
    fun menuFor(feed: FeedController): NoteMenu = if (feed === controller) menu else ready.noteMenu(delete = feed::delete, mute = feed::mute)
    val tipSheet = factory.tipSheet(viewModelScope)
    val relayStates = ready.engine.relayStates()

    /** Badge count from the live notification list and the read marker (spec 5.7). */
    val unread: StateFlow<Int> = Unread.live(ready.notifications, settings.notificationsReadAt, ready.muted)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val dmUnread: StateFlow<Int> = ready.dmStore.unreadTotalExcept(ready.muted).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val selfPubkey: String = ready.pubkey
    /** Spec 5: the own picture for the top-bar avatar; follows an edit through the profile cache. */
    val self: StateFlow<Profile> = ready.profiles.observe(ready.pubkey).stateIn(viewModelScope, SharingStarted.Eagerly, Profile.empty(ready.pubkey))

    /** Spec 4: the one-time support card above the notes. */
    val supportHint: StateFlow<Boolean> =
        SupportHint.observe(hints, ready.pubkey) { System.currentTimeMillis() / 1000 }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun dismissSupportHint() {
        viewModelScope.launch { runCatching { hints.setSupportHintDone(ready.pubkey) } }
    }

    private val amountsPromptLogic = AmountsPrompt(settings, ready.tips, hints, AmountsVisibility(ready.tips, ready.watchers, settings), ready.pubkey)
    private val _amountsPrompt = MutableStateFlow(false)

    /** The one-time "hide tip amounts?" question, decided once per start after the feed loaded; never while a tip sheet is open. */
    val amountsPrompt: StateFlow<Boolean> = combine(_amountsPrompt, tipSheet.state) { ask, sheet -> ask && sheet.target == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Back or a tap outside: no answer, nothing stored; asked again next start. */
    fun deferAmountsPrompt() {
        _amountsPrompt.value = false
    }

    fun answerAmountsPrompt(hide: Boolean) {
        _amountsPrompt.value = false
        viewModelScope.launch { runCatching { amountsPromptLogic.answer(hide) } }
    }

    init {
        tabs.start()
        viewModelScope.launch {
            controller.start()
            controller.state.first { !it.loading }
            _amountsPrompt.value = amountsPromptLogic.shouldAsk()
        }
        viewModelScope.launch {
            try {
                ready.profiles.get(ready.pubkey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // no own kind 0 yet: the fallback icon stays
            }
        }
    }
}
