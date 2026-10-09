package com.monostr.app.ui.bookmarks

import kotlinx.coroutines.Dispatchers
import com.monostr.app.ui.common.MentionResolver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.data.dm.unreadTotalExcept
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.DeletionMessages
import com.monostr.app.ui.common.LoadingScreen
import com.monostr.app.ui.common.MessageSnackbar
import com.monostr.app.ui.common.NoteActions
import com.monostr.app.ui.common.BottomTab
import com.monostr.app.ui.common.MonostrBottomBar
import com.monostr.app.ui.common.NoteCard
import com.monostr.app.ui.common.NoteDeletions
import com.monostr.app.ui.common.Message
import com.monostr.app.ui.common.MuteActions
import com.monostr.app.ui.common.asMessage
import com.monostr.app.ui.common.NoteVisibility
import com.monostr.app.ui.common.noteMenu
import com.monostr.app.ui.common.UiText
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
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.BookmarksRepository
import com.monostr.nostr.repo.CountsRepository
import com.monostr.nostr.repo.MuteRepository
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.PublishRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookmarkUi(val id: String, val note: NoteUi?)
data class BookmarksUiState(val entries: List<BookmarkUi> = emptyList(), val loading: Boolean = true, val message: Message? = null,
)

/**
 * Spec 4.3: the bookmark list. Rows follow the repository state, except that a row being removed
 * stays until the publish outcome (the repository drops the id optimistically), so a removed
 * bookmark vanishes only after the publish succeeded.
 */
class BookmarksController(
    private val bookmarks: BookmarksRepository,
    private val actions: BookmarkActions,
    private val profiles: ProfileRepository,
    private val publish: PublishRepository,
    private val decorations: TipDecorations,
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
    private val _state = MutableStateFlow(BookmarksUiState())
    val state: StateFlow<BookmarksUiState> = _state.asStateFlow()
    private val noteActions = NoteActions(publish, scope, counts, deletions, afterDelete)
    private val muteActions = mute?.let { MuteActions(it, scope, profiles, background = muteScope) }
    /** Ids whose removal is in flight: their rows stay until the outcome. */
    private val pendingRemoval = MutableStateFlow<Set<String>>(emptySet())

    fun start() {
        mentions.follow(_state.map { s -> s.entries.mapNotNull { it.note?.note } })
        // spec 4.2, 9.3: a deleted own note or a muted account's note shows like any note that cannot be found; the list itself is not touched
        scope.launch { visibility.changes.collect { _state.update { s -> s.copy(entries = s.entries.map(::filtered)) } } }
        decorations.start()
        actions.ensureLoaded(interactive = true)
        scope.launch { decorations.changes.collect { _state.update { s -> s.copy(entries = s.entries.map { e -> e.copy(note = e.note?.let(decorations::decorate)) }) } } }
        scope.launch { noteActions.changes.collect { _state.update { s -> s.copy(entries = s.entries.map { e -> e.copy(note = e.note?.let(noteActions::flags)) }) } } }
        // a request started on another screen greys the entry here too, and frees it again when it failed
        scope.launch { deletions.pending.collect { _state.update { s -> s.copy(entries = s.entries.map { e -> e.copy(note = e.note?.let(noteActions::flags)) }) } } }
        scope.launch { counts.counts.collect { _state.update { s -> s.copy(entries = s.entries.map { e -> e.copy(note = e.note?.let(::withCounts)) }) } } }
        // Rows are rebuilt only when the id list changes (entries() may hit the relays); a pending
        // removal just re-applies the kept rows, re-flagged so the icon shows the pending state.
        scope.launch { bookmarks.state.map { it.ids }.distinctUntilChanged().collect { ids -> rebuild(ids) } }
        scope.launch {
            pendingRemoval.collect { pending -> _state.update { it.copy(entries = keepPending(it.entries, fresh, pending, bookmarks.state.value.ids)) } }
        }
    }

    /** The rows for the repository's current id list, as last resolved. */
    private var fresh: List<BookmarkUi> = emptyList()

    /** Resolves the rows for [ids] from the repository. */
    private suspend fun rebuild(ids: List<String>) {
        try {
            val entries = bookmarks.entries()
            profiles.prefetch(entries.mapNotNull { it.note?.author })
            fresh = entries.map { e ->
                BookmarkUi(e.id, e.note?.let { n -> withCounts(noteActions.flags(actions.flags(decorations.decorate(NoteUi(n, profiles.get(n.author), n.repostOf?.let { profiles.get(it.author) } ?: profiles.get(n.author)))))) })
            }.map(::filtered)
            _state.update { it.copy(entries = keepPending(it.entries, fresh, pendingRemoval.value, ids), loading = false) }
            decorations.track(entries.mapNotNull { it.note })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, message = uiText(R.string.bookmarks_error_load).asMessage()) }
        }
    }

    fun remove(note: Note) {
        pendingRemoval.update { it + note.id }
        actions.toggle(note) { m ->
            if (m != null) _state.update { it.copy(message = m.asMessage()) }
            // Success or not, the list now follows the repository again: the id is gone after a
            // successful publish and back (reverted) after a failed one.
            pendingRemoval.update { it - note.id }
        }
    }
    fun like(note: Note) = noteActions.like(note, ::onActionResult)
    fun delete(note: Note) = noteActions.delete(note)
    fun repost(note: Note) = noteActions.repost(note, ::onActionResult)
    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * Spec 9.4: mutes the note's author at once; the publish runs behind it, a failure brings the account back
     * with the usual message. The filtered rows no longer hold the notes, so a failure resolves them again.
     */
    fun mute(note: Note) {
        muteActions?.mute(note.author) { m, ok ->
            _state.update { it.copy(message = Message(m, undoMute = note.author.takeIf { ok })) }
            if (!ok) scope.launch { rebuild(bookmarks.state.value.ids) }
        }
    }

    /** The snackbar's "Undo" (spec 9.4): the rows are resolved again, so the account's notes come back. */
    fun unmute(pubkey: String) {
        muteActions?.unmute(pubkey) { m -> if (m == null) scope.launch { rebuild(bookmarks.state.value.ids) } else _state.update { it.copy(message = m.asMessage()) } }
    }

    /** [row] without its note when [visibility] hides that note; the bookmark itself stays. */
    private fun filtered(row: BookmarkUi): BookmarkUi =
        if (row.note != null && visibility.visible(listOf(row.note)).isEmpty()) row.copy(note = null) else row

    /** Visible bookmark ids from the list; counts are requested for their notes' targets (spec 4.2). */
    fun visible(noteIds: List<String>) {
        val wanted = noteIds.toSet()
        val targets = _state.value.entries.filter { it.id in wanted }.mapNotNull { it.note?.target?.id }
        if (targets.isNotEmpty()) counts.request(targets)
    }

    /** [ui] with its current count, so a list rebuild never drops a count the collector already had (spec 4.2). */
    private fun withCounts(ui: NoteUi): NoteUi = ui.copy(counts = counts.counts.value[ui.target.id])

    /** [fresh] plus the rows of [shown] whose removal is still pending, at their previous position. */
    private fun keepPending(shown: List<BookmarkUi>, fresh: List<BookmarkUi>, pending: Set<String>, ids: List<String>): List<BookmarkUi> {
        if (pending.isEmpty()) return fresh
        val out = fresh.toMutableList()
        shown.forEachIndexed { index, row ->
            // the kept row is re-flagged: the repository already dropped the id, so its icon shows "not bookmarked"
            if (row.id in pending && row.id !in ids) out.add(index.coerceAtMost(out.size), row.copy(note = row.note?.let(actions::flags)))
        }
        return out
    }

    private fun onActionResult(message: UiText) {
        _state.update { s -> s.copy(entries = s.entries.map { e -> e.copy(note = e.note?.let(noteActions::flags)) }, message = message.asMessage()) }
    }
}

@HiltViewModel
class BookmarksViewModel @Inject constructor(session: NostrSession, factory: TipSheetFactory) : ViewModel() {
    private val ready = session.requireReady()
    val decorations = factory.decorations(viewModelScope)
    val controller = BookmarksController(ready.bookmarks, BookmarkActions(ready.bookmarks, viewModelScope, background = ready.engine.backgroundScope), ready.profiles, ready.publish, decorations, ready.counts, viewModelScope, mentions = MentionResolver(ready.profiles, viewModelScope, Dispatchers.Default), deletions = ready.deletions, visibility = ready.visibility, afterDelete = ready.afterNoteDelete, mute = ready.mute, muteScope = ready.engine.backgroundScope)
    val deletions = ready.deletions
    val menu = ready.noteMenu(delete = controller::delete, mute = controller::mute)
    val tipSheet = factory.tipSheet(viewModelScope)
    val dmUnread: StateFlow<Int> = ready.dmStore.unreadTotalExcept(ready.muted).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    init { controller.start() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    onFeed: () -> Unit,
    onSearch: () -> Unit,
    onMessages: () -> Unit,
    onNotifications: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onReply: (String) -> Unit,
    onHashtag: (String) -> Unit,
    onOpenMedia: (String, Int) -> Unit = { _, _ -> },
    onOpenVideo: (String) -> Unit = {},
    onQuote: (String) -> Unit = {},
    vm: BookmarksViewModel = hiltViewModel(),
) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val names by vm.controller.names.collectAsStateWithLifecycle()
    val dmUnread by vm.dmUnread.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tippersFor by remember { mutableStateOf<Note?>(null) }
    MessageSnackbar(state.message, snackbar, onUnmute = vm.controller::unmute, onShown = vm.controller::clearMessage)
    DeletionMessages(vm.deletions, snackbar)
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.bookmarks_title)) }) },
        // spec 11.1: a tab like search and notifications, not a sub-screen with a back arrow
        bottomBar = { MonostrBottomBar(BottomTab.BOOKMARKS, unread = 0, dmUnread = dmUnread, onFeed = onFeed, onSearch = onSearch, onBookmarks = {}, onMessages = onMessages, onNotifications = onNotifications) },
        snackbarHost = { SnackbarHost(snackbar, Modifier.muteUndoTag(state.message)) },
    ) { padding ->
        when {
            state.loading -> LoadingScreen()
            state.entries.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.bookmarks_empty), textAlign = TextAlign.Center) }
            else -> {
                val listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.padding(padding).fillMaxSize().testTag("bookmarks-list")) {
                    items(state.entries, key = { it.id }) { e ->
                        val n = e.note
                        if (n == null) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.bookmarks_missing, Profile.shortPubkey(e.id)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { vm.controller.remove(Note(e.id, "", "", 0, 1, null, null, null, emptyList())) }, modifier = Modifier.testTag("bookmark-remove-${e.id.take(8)}")) { Icon(Icons.Filled.Bookmark, contentDescription = stringResource(R.string.note_bookmark)) }
                            }
                        } else {
                            NoteCard(
                                note = n, onOpen = { onOpenThread(n.target.id) }, onOpenProfile = onOpenProfile, onReply = { onReply(n.target.id) },
                                onLike = { vm.controller.like(n.target) }, onRepost = { vm.controller.repost(n.target) }, onQuote = { onQuote(n.target.id) }, onTip = { vm.tipSheet.open(n.target) },
                                onShowTippers = { tippersFor = n.target }, onBookmark = { vm.controller.remove(n.target) }, onHashtag = onHashtag, onOpenNote = onOpenThread,
                                onOpenMedia = onOpenMedia, onOpenVideo = onOpenVideo, names = names,
                                menu = vm.menu,
                            )
                        }
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
