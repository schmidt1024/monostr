package com.monostr.app.ui.feed

import com.monostr.app.data.dm.unreadTotalExcept
import kotlinx.coroutines.Dispatchers
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
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.app.ui.tips.TipSheetFactory
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteCounts
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.CountsRepository
import com.monostr.nostr.repo.FeedRepository
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
    val reposted: Boolean = false,
    /** Valid receipts of the target note (spec 5.6); null when there are none. */
    val tips: TipSummary? = null,
    /** An own tip intent without receipt yet (spec 5.5 step 5). */
    val pendingTip: Boolean = false,
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

data class FeedUiState(
    val notes: List<NoteUi> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    /** The snackbar's message; after a mute it offers to unmute the account (spec 9.4). */
    val message: Message? = null,
)

/** Feed logic shared by the Hilt ViewModel and the JVM tests. */
class FeedController(
    private val feed: FeedRepository,
    private val profiles: ProfileRepository,
    private val publish: PublishRepository,
    private val decorations: TipDecorations,
    private val bookmarks: BookmarkActions,
    private val counts: CountsRepository,
    private val scope: CoroutineScope,
    private val mentions: MentionResolver = MentionResolver(profiles, scope),
    /** Spec 2: emits after an own follow/unfollow; the author list of [FeedRepository.live] is read once per start (a mute change restarts it too). */
    private val followChanges: Flow<Unit> = emptyFlow(),
    private val deletions: NoteDeletions = NoteDeletions(),
    private val visibility: NoteVisibility = NoteVisibility(deletions, MutableStateFlow(emptySet())),
    private val afterDelete: suspend (Note) -> UiText? = { null },
    /** Null (tests that do not mute): [mute] and [unmute] do nothing. */
    mute: MuteRepository? = null,
    /** The scope mute writes run on, so they survive the screen that started them (session: the engine's background scope); null (tests) = [scope]. */
    muteScope: CoroutineScope? = null,
) {
    val names: StateFlow<Map<String, String>> get() = mentions.names
    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()
    private val actions = NoteActions(publish, scope, counts, deletions, afterDelete)
    private val muteActions = mute?.let { MuteActions(it, scope, profiles, background = muteScope) }

    /**
     * Local notes first: the live collector emits the database window immediately, while the
     * relay refresh runs separately. `loading` ends on whichever finishes first.
     */
    fun start() {
        mentions.follow(_state.map { s -> s.notes.map { it.note } })
        bookmarks.ensureLoaded()
        scope.launch { bookmarks.changes.collect { _state.update { s -> s.copy(notes = s.notes.map(bookmarks::flags)) } } }
        scope.launch { actions.changes.collect { _state.update { s -> s.copy(notes = s.notes.map(actions::flags)) } } }
        scope.launch { visibility.changes.collect { _state.update { s -> s.copy(notes = visibility.visible(s.notes)) } } }
        // a request started on another screen greys the entry here too, and frees it again when it failed
        scope.launch { deletions.pending.collect { _state.update { s -> s.copy(notes = s.notes.map(actions::flags)) } } }
        decorations.start()
        scope.launch { decorations.changes.collect { setNotes { s -> s.notes.map(decorations::decorate) } } }
        scope.launch { counts.counts.collect { _state.update { s -> s.copy(notes = s.notes.map(::withCounts)) } } }
        startLive()
        // the repository leaves muted accounts out at the query (read once per start), so a mute or an unmute,
        // also one reverted after a failed publish, restarts the list like a follow change does
        scope.launch { merge(followChanges, visibility.muted.drop(1).map { }).collect { startLive(); refresh() } }
        scope.launch {
            try {
                feed.refresh()
                val decorated = decorate(feed.notes())
                setNotes(loading = false) { s -> merge(decorated, s.notes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // relays are best-effort; the live collector still shows the local notes
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private var liveJob: Job? = null

    private fun startLive() {
        liveJob?.cancel()
        liveJob = scope.launch {
            try {
                feed.live().collect { notes ->
                    val decorated = decorate(notes)
                    setNotes(loading = false) { s -> merge(decorated, s.notes) }
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
                feed.refresh()
                val decorated = decorate(feed.notes())
                setNotes(loading = false, refreshing = false) { s -> merge(decorated, s.notes) }
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
                val older = feed.loadMore(oldest)
                val decorated = decorate(older)
                setNotes { s -> (s.notes + decorated).distinctBy { it.note.id } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = uiText(R.string.feed_error_more).asMessage()) }
            } finally {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    /** Sets the note list and keeps the receipt subscription in step with what is shown. */
    private fun setNotes(loading: Boolean? = null, refreshing: Boolean? = null, f: (FeedUiState) -> List<NoteUi>) {
        _state.update { s -> s.copy(notes = visibility.visible(f(s)), loading = loading ?: s.loading, refreshing = refreshing ?: s.refreshing) }
        decorations.track(_state.value.notes.map { it.note })
    }

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
    fun delete(note: Note) = actions.delete(note)
    fun repost(note: Note) = actions.repost(note, ::onActionResult)
    fun bookmark(note: NoteUi) = bookmarks.toggle(note.target) { m -> _state.update { s -> s.copy(notes = s.notes.map(bookmarks::flags), message = m?.asMessage() ?: s.message) } }
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
        val targets = _state.value.notes.filter { it.note.id in wanted }.map { it.target.id }
        if (targets.isNotEmpty()) counts.request(targets)
    }

    private fun onActionResult(message: UiText) {
        _state.update { s -> s.copy(notes = s.notes.map(actions::flags), message = message.asMessage()) }
    }

    /** [ui] with its current count, so a list rebuild (live/refresh/loadMore) never drops a count the collector already had (spec 4.2). */
    private fun withCounts(ui: NoteUi): NoteUi = ui.copy(counts = counts.counts.value[ui.target.id])

    private suspend fun decorate(notes: List<Note>): List<NoteUi> {
        val pubkeys = notes.flatMap { listOfNotNull(it.author, it.repostOf?.author) }
        profiles.prefetch(pubkeys)
        return notes.map { n ->
            val ui = NoteUi(n, profiles.get(n.author), n.repostOf?.let { profiles.get(it.author) } ?: profiles.get(n.author))
            withCounts(actions.flags(bookmarks.flags(decorations.decorate(ui))))
        }
    }
}

/** The note a like/repost/reply/tip refers to: the original for reposts. */
val NoteUi.target: Note get() = note.repostOf ?: note

@HiltViewModel
class FeedViewModel @Inject constructor(session: NostrSession, factory: TipSheetFactory, settings: TipSettingsStore, private val hints: HintStore) : ViewModel() {
    private val ready = session.requireReady()
    val decorations = factory.decorations(viewModelScope)
    val bookmarks = BookmarkActions(ready.bookmarks, viewModelScope, background = ready.engine.backgroundScope)
    val controller = FeedController(ready.feed, ready.profiles, ready.publish, decorations, bookmarks, ready.counts, viewModelScope, mentions = MentionResolver(ready.profiles, viewModelScope, Dispatchers.Default), followChanges = ready.follows.changes, deletions = ready.deletions, visibility = ready.visibility, afterDelete = ready.afterNoteDelete, mute = ready.mute, muteScope = ready.engine.backgroundScope)
    val deletions = ready.deletions
    val menu = ready.noteMenu(delete = controller::delete, mute = controller::mute)
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

    init {
        controller.start()
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
