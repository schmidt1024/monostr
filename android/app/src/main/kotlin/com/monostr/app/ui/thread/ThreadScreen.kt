package com.monostr.app.ui.thread

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import com.monostr.app.ui.common.MentionResolver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.DeletionMessages
import com.monostr.app.ui.common.LoadingScreen
import com.monostr.app.ui.common.MessageSnackbar
import com.monostr.app.ui.common.NoteActions
import com.monostr.app.ui.common.NoteCard
import com.monostr.app.ui.common.NoteDeletions
import com.monostr.app.ui.common.Message
import com.monostr.app.ui.common.MuteActions
import com.monostr.app.ui.common.asMessage
import com.monostr.app.ui.common.NoteVisibility
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.noteMenu
import com.monostr.app.ui.common.VisibleNotes
import com.monostr.app.ui.common.muteUndoTag
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.feed.target
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.app.ui.tips.TipSheetFactory
import com.monostr.app.ui.tips.TipSheetHost
import com.monostr.app.ui.tips.TippersDialog
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.CountsRepository
import com.monostr.nostr.repo.MuteRepository
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.PublishRepository
import com.monostr.nostr.repo.ThreadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThreadUiState(val items: List<NoteUi> = emptyList(), val focusedId: String? = null, val loading: Boolean = true, val notFound: Boolean = false, val message: Message? = null,
    /** The focused note was deleted (spec 4.2): the screen closes. */
    val closed: Boolean = false,
)

class ThreadController(
    private val noteId: String,
    private val threads: ThreadRepository,
    private val profiles: ProfileRepository,
    private val publish: PublishRepository,
    private val decorations: TipDecorations,
    private val bookmarks: BookmarkActions,
    private val counts: CountsRepository,
    private val scope: CoroutineScope,
    private val mentions: MentionResolver = MentionResolver(profiles, scope),
    private val deletions: NoteDeletions = NoteDeletions(),
    private val visibility: NoteVisibility = NoteVisibility(deletions, MutableStateFlow(emptySet())),
    private val afterDelete: suspend (Note) -> UiText? = { null },
    /** Null (tests that do not mute): [mute] and [unmute] do nothing. */
    mute: MuteRepository? = null,
    /** The scope mute writes run on, so they survive the screen that started them (session: the engine's background scope); null (tests) = [scope]. */
    muteScope: CoroutineScope? = null,
) {
    val names: StateFlow<Map<String, String>> get() = mentions.names
    private val _state = MutableStateFlow(ThreadUiState())
    val state: StateFlow<ThreadUiState> = _state.asStateFlow()
    private val actions = NoteActions(publish, scope, counts, deletions, afterDelete)
    private val muteActions = mute?.let { MuteActions(it, scope, profiles, background = muteScope) }

    fun start() {
        mentions.follow(_state.map { s -> s.items.map { it.note } })
        bookmarks.ensureLoaded()
        scope.launch { bookmarks.changes.collect { _state.update { s -> s.copy(items = s.items.map(bookmarks::flags)) } } }
        scope.launch { actions.changes.collect { _state.update { s -> s.copy(items = s.items.map(actions::flags)) } } }
        // a request started on another screen greys the entry here too, and frees it again when it failed
        scope.launch { deletions.pending.collect { _state.update { s -> s.copy(items = s.items.map(actions::flags)) } } }
        scope.launch {
            deletions.deleted.collect { gone ->
                // spec 4.2: the focused note is gone, so the screen closes; another one just leaves the list below
                if (noteId in gone || _state.value.focusedId in gone) _state.update { it.copy(closed = true) }
            }
        }
        // a deleted note or a muted account leaves the list; replies to it and the focused note stay (spec 4.2, 9.3)
        scope.launch { visibility.changes.collect { refilter() } }
        decorations.start()
        scope.launch { decorations.changes.collect { _state.update { s -> s.copy(items = s.items.map(decorations::decorate)) } } }
        scope.launch { counts.counts.collect { _state.update { s -> s.copy(items = s.items.map(::withCounts)) } } }
        scope.launch {
            try {
                var seen = false
                threads.observe(noteId).collect { view ->
                    seen = true
                    if (view.gone) {
                        all = emptyList() // a later refilter must not bring the withdrawn thread back
                        _state.update { it.copy(items = emptyList(), loading = false, notFound = true) }
                        return@collect
                    }
                    val ordered = listOfNotNull(view.root) + view.replies.filter { it.id != view.root?.id }
                    val withFocused = if (ordered.none { it.id == view.focused.id }) ordered + view.focused else ordered
                    profiles.prefetch(withFocused.map { it.author })
                    all = withFocused.map { n -> NoteUi(n, profiles.get(n.author), profiles.get(n.author)) }
                    val items = visibility.visible(all.map(::fresh), keep = view.focused.id)
                    _state.update { it.copy(items = items, focusedId = view.focused.id, loading = false) }
                    decorations.track(withFocused)
                }
                if (!seen) _state.update { it.copy(loading = false, notFound = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, notFound = true) }
            }
        }
    }

    fun like(note: Note) = actions.like(note, ::onActionResult)
    fun delete(note: Note) = actions.delete(note)
    fun repost(note: Note) = actions.repost(note, ::onActionResult)
    fun bookmark(note: Note) = bookmarks.toggle(note) { m -> _state.update { s -> s.copy(items = s.items.map(bookmarks::flags), message = m?.asMessage() ?: s.message) } }
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Spec 9.4: mutes the note's author at once; the publish runs behind it, a failure brings the account back with the usual message. */
    fun mute(note: Note) {
        muteActions?.mute(note.author) { m, ok -> _state.update { it.copy(message = Message(m, undoMute = note.author.takeIf { ok })) } }
    }

    /** The snackbar's "Undo" (spec 9.4): the account's replies return at once, from the unfiltered thread. */
    fun unmute(pubkey: String) {
        // the callback may run on the session's scope: refilter() reads [all], which this controller's scope writes
        muteActions?.unmute(pubkey) { m -> if (m == null) scope.launch { refilter() } else _state.update { it.copy(message = m.asMessage()) } }
    }

    /**
     * The last thread as [ThreadRepository.observe] delivered it, before [visibility]: a note that was
     * filtered out (a muted account, unmuted again) comes back from here without a new emission.
     */
    private var all: List<NoteUi> = emptyList()

    /** Applies [visibility] to [all] again; shown items keep their current flags and counts. */
    private fun refilter() = _state.update { s ->
        val shown = s.items.associateBy { it.note.id }
        s.copy(items = visibility.visible(all.map { shown[it.note.id] ?: fresh(it) }, keep = s.focusedId ?: noteId))
    }

    /** [ui] with the current bookmark, action, tip and count state. */
    private fun fresh(ui: NoteUi): NoteUi = withCounts(actions.flags(bookmarks.flags(decorations.decorate(ui))))

    /** Visible note ids from the list; counts are requested for their targets (spec 4.2). */
    fun visible(noteIds: List<String>) {
        val wanted = noteIds.toSet()
        val targets = _state.value.items.filter { it.note.id in wanted }.map { it.target.id }
        if (targets.isNotEmpty()) counts.request(targets)
    }

    private fun onActionResult(message: UiText) {
        _state.update { s -> s.copy(items = s.items.map(actions::flags), message = message.asMessage()) }
    }

    /** [ui] with its current count, so a list rebuild never drops a count the collector already had (spec 4.2). */
    private fun withCounts(ui: NoteUi): NoteUi = ui.copy(counts = counts.counts.value[ui.target.id])
}

@HiltViewModel
class ThreadViewModel @Inject constructor(session: NostrSession, handle: SavedStateHandle, factory: TipSheetFactory) : ViewModel() {
    private val ready = session.requireReady()
    val decorations = factory.decorations(viewModelScope)
    val bookmarks = BookmarkActions(ready.bookmarks, viewModelScope, background = ready.engine.backgroundScope)
    val controller = ThreadController(checkNotNull(handle["id"]), ready.threads, ready.profiles, ready.publish, decorations, bookmarks, ready.counts, viewModelScope, mentions = MentionResolver(ready.profiles, viewModelScope, Dispatchers.Default), deletions = ready.deletions, visibility = ready.visibility, afterDelete = ready.afterNoteDelete, mute = ready.mute, muteScope = ready.engine.backgroundScope)
    val deletions = ready.deletions
    val menu = ready.noteMenu(delete = controller::delete, mute = controller::mute)
    val tipSheet = factory.tipSheet(viewModelScope)
    init { controller.start() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onReply: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onHashtag: (String) -> Unit,
    onOpenMedia: (String, Int) -> Unit = { _, _ -> },
    onOpenVideo: (String) -> Unit = {},
    onQuote: (String) -> Unit = {},
    vm: ThreadViewModel = hiltViewModel(),
) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val names by vm.controller.names.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tippersFor by remember { mutableStateOf<Note?>(null) }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    MessageSnackbar(state.message, snackbar, onUnmute = vm.controller::unmute, onShown = vm.controller::clearMessage)
    DeletionMessages(vm.deletions, snackbar)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.thread_title)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } })
    }, snackbarHost = { SnackbarHost(snackbar, Modifier.muteUndoTag(state.message)) }) { padding ->
        when {
            state.loading -> LoadingScreen()
            state.notFound -> Text(stringResource(R.string.thread_not_found), Modifier.padding(padding).padding(16.dp))
            else -> Box(Modifier.padding(padding).fillMaxSize()) {
                val listState = rememberLazyListState()
                // spec 11.5 §4: the thread opens with the focused note in view, like X's detail view — once;
                // coming back from a profile or a rotation keeps the position the user scrolled to
                var scrolled by rememberSaveable(state.focusedId) { mutableStateOf(false) }
                LaunchedEffect(state.focusedId, state.items.isNotEmpty()) {
                    if (scrolled || state.items.isEmpty()) return@LaunchedEffect
                    val index = state.items.indexOfFirst { it.note.id == state.focusedId }
                    if (index > 0) listState.scrollToItem(index)
                    scrolled = true
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("thread-list")) {
                    items(state.items, key = { it.note.id }) { n ->
                        NoteCard(
                            note = n,
                            replyContext = false,
                            onOpen = { if (n.note.id != state.focusedId) onOpenThread(n.note.id) },
                            onOpenProfile = onOpenProfile,
                            onReply = { onReply(n.note.id) },
                            onLike = { vm.controller.like(n.target) },
                            onRepost = { vm.controller.repost(n.target) },
                            onQuote = { onQuote(n.target.id) },
                            onTip = { vm.tipSheet.open(n.target) },
                            onShowTippers = { tippersFor = n.target },
                            onHashtag = onHashtag,
                            onOpenNote = onOpenThread,
                            onBookmark = { vm.controller.bookmark(n.target) },
                            onOpenMedia = onOpenMedia,
                            onOpenVideo = onOpenVideo,
                            names = names,
                            menu = vm.menu,
                            collapsed = n.note.id != state.focusedId,
                            detail = n.note.id == state.focusedId,
                        )
                        HorizontalDivider()
                    }
                }
                VisibleNotes(listState, vm.controller::visible)
            }
        }
    }
    TipSheetHost(vm.tipSheet)
    tippersFor?.let { TippersDialog(it, load = vm.decorations::tippers, onDismiss = { tippersFor = null }) }
}
