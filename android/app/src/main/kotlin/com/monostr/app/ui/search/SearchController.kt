package com.monostr.app.ui.search

import com.monostr.nostr.repo.OwnReactions
import com.monostr.nostr.repo.MemoryOwnReactions
import kotlinx.coroutines.flow.map
import com.monostr.app.data.RecentSearchesStore
import com.monostr.app.ui.common.MentionResolver
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.NoteActions
import com.monostr.app.ui.common.Message
import com.monostr.app.ui.common.MuteActions
import com.monostr.app.ui.common.asMessage
import com.monostr.app.ui.common.NoteDeletions
import com.monostr.app.ui.common.NoteVisibility
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.userMessage
import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.feed.withProfiles
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.MuteRepository
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.PublishRepository
import com.monostr.nostr.repo.SearchRepository
import com.monostr.nostr.search.SearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchMode { IDLE, TEXT, HASHTAG }

data class SearchUiState(
    val query: String = "",
    val mode: SearchMode = SearchMode.IDLE,
    val people: List<Profile> = emptyList(),
    val notes: List<NoteUi> = emptyList(),
    val hashtag: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** Relay problem while local hits stay on screen (spec 7). */
    val error: UiText? = null,
    val noSearchRelays: Boolean = false,
    /** A search ran for the current query; distinguishes "nothing found" from "not searched yet". */
    val searched: Boolean = false,
    /** Spec 11.4 §2: the last searches typed on this device, newest first; shown while the field is empty. */
    val recent: List<String> = emptyList(),
    /** The snackbar's message; after a mute it offers to unmute the account (spec 9.4). */
    val message: Message? = null,
)

sealed class SearchNav {
    data class Profile(val pubkey: String) : SearchNav()
    data class Thread(val noteId: String) : SearchNav()
}

/** Spec 3: one field, classified input, local-first people, NIP-50 on the search relays, hashtags on the normal ones. */
class SearchController(
    private val search: SearchRepository,
    private val profiles: ProfileRepository,
    private val publish: PublishRepository,
    private val decorations: TipDecorations,
    private val bookmarks: BookmarkActions,
    private val searchRelays: Flow<List<String>>,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 400,
    /** Outlives [scope]: `viewModelScope` is already cancelled by the time `onCleared` runs `close()` (lifecycle 2.10+). */
    private val detachScope: CoroutineScope = CoroutineScope(Dispatchers.IO + NonCancellable),
    private val mentions: MentionResolver = MentionResolver(profiles, scope),
    private val deletions: NoteDeletions = NoteDeletions(),
    private val visibility: NoteVisibility = NoteVisibility(deletions, MutableStateFlow(emptySet())),
    private val afterDelete: suspend (Note) -> UiText? = { null },
    /** The session's own likes and reposts, shared by every screen; a fresh one in tests. */
    private val own: OwnReactions = MemoryOwnReactions(),
    /** Null (tests that do not mute): [mute] and [unmute] do nothing. */
    mute: MuteRepository? = null,
    /** The scope mute writes run on, so they survive the screen that started them (session: the engine's background scope); null (tests) = [scope]. */
    muteScope: CoroutineScope? = null,
    /** Spec 11.4 §2: where typed searches are remembered; null in tests that do not care. */
    private val recentSearches: RecentSearchesStore? = null,
) {
    val names: StateFlow<Map<String, String>> get() = mentions.names
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private val _nav = MutableSharedFlow<SearchNav>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val nav: SharedFlow<SearchNav> = _nav
    private val actions = NoteActions(publish, scope, deletions = deletions, afterDelete = afterDelete, own = own)
    private val muteActions = mute?.let { MuteActions(it, scope, profiles, background = muteScope) }
    private var debounce: Job? = null
    private var running: Job? = null
    private var attached: List<String> = emptyList()
    /** The first local people of this screen are marked once (spec perf 3.9). */
    private var peopleMarked = false

    fun start() {
        scope.launch { profiles.updates.collect { byKey -> _state.update { s -> s.copy(notes = s.notes.map { it.withProfiles(byKey) }) } } }
        mentions.follow(_state.map { s -> s.notes.map { it.note } })
        recentSearches?.let { r -> scope.launch { r.recent.collect { list -> _state.update { it.copy(recent = list) } } } }
        bookmarks.ensureLoaded()
        scope.launch { bookmarks.changes.collect { _state.update { s -> s.copy(notes = s.notes.map(bookmarks::flags)) } } }
        scope.launch { actions.changes.collect { _state.update { s -> s.copy(notes = s.notes.map(actions::flags)) } } }
        scope.launch { visibility.changes.collect { _state.update { s -> s.copy(notes = visibility.visible(s.notes)) } } }
        // a request started on another screen greys the entry here too, and frees it again when it failed
        scope.launch { deletions.pending.collect { _state.update { s -> s.copy(notes = s.notes.map(actions::flags)) } } }
        decorations.start()
        // the people index is built behind the screen, so the first search does not wait for it (spec perf 3.8)
        search.warmUp()
        scope.launch { decorations.changes.collect { _state.update { s -> s.copy(notes = s.notes.map(decorations::decorate)) } } }
        scope.launch {
            searchRelays.collect { relays ->
                if (attached.isNotEmpty()) search.detach(attached)
                attached = relays
                search.attach(relays)
            }
        }
    }

    /**
     * Detaches the search relays; the ViewModel calls it from onCleared. Runs on [detachScope],
     * not [scope]: `viewModelScope` is already cancelled by the time `onCleared` fires, so a
     * detach launched there would never run.
     */
    fun close() {
        val snapshot = attached
        attached = emptyList()
        detachScope.launch { search.detach(snapshot) }
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        debounce?.cancel()
        val parsed = SearchQuery.parse(q)
        when (parsed) {
            // clearing the field cancels a running search immediately instead of leaving its stale results on screen
            SearchQuery.Empty -> run(parsed)
            is SearchQuery.Text, is SearchQuery.Hashtag -> debounce = scope.launch { delay(debounceMs); run(parsed, remember = false) }
            else -> {}
        }
    }

    fun submit() {
        debounce?.cancel()
        run(SearchQuery.parse(_state.value.query))
    }

    /** Spec 11.4 §2: a tapped recent entry becomes the query and runs at once (and moves to the front). */
    fun searchRecent(query: String) {
        onQueryChange(query)
        submit()
    }

    fun removeRecent(query: String) {
        scope.launch { recentSearches?.remove(query) }
    }

    fun clearRecent() {
        scope.launch { recentSearches?.clear() }
    }

    /** [remember]: spec 11.4 §2 keeps only SENT searches — a typing pause runs the search but does not save partial words. */
    private fun run(q: SearchQuery, remember: Boolean = true) {
        running?.cancel()
        // remembered when sent, on its own: clearing the field right away cancels the search, not the entry (spec 11.4 §2)
        if (remember) {
            val entry = when (q) {
                is SearchQuery.Text -> q.text.takeUnless { RecentSearchesStore.isSecret(it) }
                is SearchQuery.Hashtag -> "#${q.tag}"
                else -> null
            }
            if (entry != null) scope.launch { recentSearches?.add(entry) }
        }
        when (q) {
            SearchQuery.Empty -> _state.update { it.copy(mode = SearchMode.IDLE, people = emptyList(), notes = emptyList(), hashtag = null, loading = false, error = null, searched = false) }
            is SearchQuery.Profile -> { _nav.tryEmit(SearchNav.Profile(q.pubkey)); _state.update { it.copy(query = "") } }
            is SearchQuery.Thread -> { _nav.tryEmit(SearchNav.Thread(q.noteId)); _state.update { it.copy(query = "") } }
            is SearchQuery.Hashtag -> running = scope.launch { hashtag(q.tag) }
            is SearchQuery.Text -> running = scope.launch { text(q.text) }
        }
    }

    /** [SearchRepository] calls that are not wrapped in the repository's own try (e.g. a local-database read) must not crash the screen; a failure here keeps the local people already shown and reports [SearchUiState.error]. */
    private suspend fun text(q: String) {
        val relays = searchRelays.first()
        val local = try { search.profilesLocal(q) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        _state.update { it.copy(mode = SearchMode.TEXT, hashtag = null, people = local, notes = emptyList(), loading = true, error = null, noSearchRelays = relays.isEmpty(), searched = false) }
        if (!peopleMarked) { peopleMarked = true; PerfTrace.mark("search people shown") }
        try {
            coroutineScope {
                // spec perf 3.8: the people show as soon as they are in; the NIP-50 notes take longer and must not hold them back
                val people = async { search.profiles(q, relays).also { p -> _state.update { it.copy(people = p.items) } } }
                val n = search.notes(q, relays)
                val p = people.await()
                val decorated = decorate(n.items)
                val err = (p.error ?: n.error)?.userMessage()
                _state.update { it.copy(notes = visibility.visible(decorated.map(::flagged)), loading = false, error = err, searched = true) }
                decorations.track(n.items)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.userMessage(), searched = true) }
        }
    }

    private suspend fun hashtag(tag: String, until: Long? = null) {
        if (until == null) {
            _state.update { it.copy(mode = SearchMode.HASHTAG, hashtag = tag, people = emptyList(), notes = emptyList(), loading = true, error = null, searched = false) }
            // spec perf 3.8: what the database holds shows at once; the relays' page replaces it when it lands
            val stored = try { search.hashtagLocal(tag) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            if (stored.isNotEmpty()) {
                val decorated = decorate(stored)
                _state.update { s -> s.copy(notes = visibility.visible(decorated.map(::flagged))) }
                decorations.track(stored)
            }
        }
        try {
            val out = search.hashtag(tag, until)
            val decorated = decorate(out.items)
            _state.update { s ->
                val decorated = decorated.map(::flagged)
                val notes = visibility.visible(if (until == null) decorated else (s.notes + decorated).distinctBy { it.note.id })
                s.copy(notes = notes, loading = false, loadingMore = false, error = out.error?.userMessage(), searched = true)
            }
            decorations.track(_state.value.notes.map { it.note })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, loadingMore = false, error = e.userMessage(), searched = true) }
        }
    }

    fun loadMore() {
        val s = _state.value
        val tag = s.hashtag ?: return
        // loading: the first page is still on its way (stored notes may show already); a paged fetch would race it
        if (s.mode != SearchMode.HASHTAG || s.loading || s.loadingMore || s.notes.isEmpty()) return
        _state.update { it.copy(loadingMore = true) }
        running = scope.launch { hashtag(tag, until = s.notes.last().note.createdAt) }
    }

    fun like(note: Note) = actions.like(note, ::onActionResult)
    fun react(note: Note, emoji: String) = actions.react(note, emoji, ::onActionResult)
    fun delete(note: Note) = actions.delete(note)
    fun repost(note: Note) = actions.repost(note, ::onActionResult)
    fun bookmark(note: Note) = bookmarks.toggle(note) { m -> _state.update { s -> s.copy(notes = s.notes.map(bookmarks::flags), message = m?.asMessage() ?: s.message) } }
    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * Spec 9.4: mutes the note's author at once; the publish runs behind it, a failure brings the account back
     * with the usual message. The filtered list no longer holds the notes, so a failure runs the search again.
     */
    fun mute(note: Note) {
        muteActions?.mute(note.author) { m, ok ->
            _state.update { it.copy(message = Message(m, undoMute = note.author.takeIf { ok })) }
            // the callback may run on the session's scope: submit() belongs on this controller's scope
            if (!ok && _state.value.mode != SearchMode.IDLE) scope.launch { submit() }
        }
    }

    /** The snackbar's "Undo" (spec 9.4): the current text or hashtag search runs again, so the account's notes come back. */
    fun unmute(pubkey: String) {
        muteActions?.unmute(pubkey) { m ->
            if (m != null) _state.update { it.copy(message = m.asMessage()) }
            else if (_state.value.mode != SearchMode.IDLE) scope.launch { submit() }
        }
    }

    private fun onActionResult(message: UiText) {
        _state.update { s -> s.copy(notes = s.notes.map(actions::flags), message = message.asMessage()) }
    }

    /** [ui] with the current tips and flags; applied inside `_state.update`, so a change during a slow profile lookup is not undone. */
    private fun flagged(ui: NoteUi): NoteUi = actions.flags(bookmarks.flags(decorations.decorate(ui)))

    private suspend fun decorate(notes: List<Note>): List<NoteUi> {
        val pubkeys = notes.flatMap { listOfNotNull(it.author, it.repostOf?.author) }
        val byKey = profiles.getAll(pubkeys)
        return notes.map { n ->
            NoteUi(n, byKey.getValue(n.author), n.repostOf?.let { byKey.getValue(it.author) } ?: byKey.getValue(n.author))
        }
    }
}
