package com.monostr.app.ui.thread

import com.monostr.nostr.repo.OwnReactions
import com.monostr.nostr.repo.MemoryOwnReactions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import com.monostr.app.ui.common.MentionResolver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.monostr.app.ui.common.ShowRepliesRow
import com.monostr.app.ui.common.UnavailableRow
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
import com.monostr.app.ui.feed.withProfiles
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.app.ui.tips.TipSheetFactory
import com.monostr.app.ui.tips.TipSheetHost
import com.monostr.app.ui.tips.TippersDialog
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Strands
import com.monostr.nostr.repo.Slot
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThreadUiState(val items: List<NoteUi> = emptyList(), val focusedId: String? = null, val loading: Boolean = true, val notFound: Boolean = false, val message: Message? = null,
    /** The focused note was deleted (spec 4.2): the screen closes. */
    val closed: Boolean = false,
    /** The page's layout, top to bottom (spec 2026-10-16 §4.2); each row's note is looked up in [items]. */
    val rows: List<ThreadRow> = emptyList(),
)

/** One row of the thread page (spec 2026-10-16 §4.2), top to bottom. [id] keys the LazyColumn. */
sealed interface ThreadRow {
    val id: String

    /** A note above the focused one; its NoteUi comes from items, else it shows as unavailable. */
    data class Ancestor(override val id: String, val lineAbove: Boolean) : ThreadRow
    data class Focused(override val id: String, val lineAbove: Boolean) : ThreadRow

    /**
     * A direct reply; hidden, it stays as an unavailable row only while its child is shown (so the strand keeps its head), else it leaves with its More row.
     * [orphan]: its parent is not the focused note (focused = root, parent unknown), so it keeps "Replying to @name" (spec 2026-10-16 §4.1).
     */
    data class Reply(override val id: String, val lineBelow: Boolean, val keepWhenHidden: Boolean, val orphan: Boolean) : ThreadRow

    /** The one reply shown under a strand's head; always joined to it by a line. */
    data class Child(override val id: String) : ThreadRow

    /** "Show replies": opens [open], the strand's head; id = "more-$open". */
    data class More(override val id: String, val open: String) : ThreadRow
}

/**
 * The rows the screen shows, given the ids of the notes in items (spec 2026-10-16 §4.2): a hidden ancestor stays as
 * unavailable; a hidden child leaves; a hidden reply stays as unavailable only when it has a shown child, else it
 * leaves together with its child row and its "Show replies".
 */
internal fun visibleRows(rows: List<ThreadRow>, shown: Set<String>): List<ThreadRow> {
    val dropped = HashSet<String>()
    rows.forEachIndexed { i, row ->
        when (row) {
            is ThreadRow.Child -> if (row.id !in shown) dropped += row.id
            is ThreadRow.Reply -> if (row.id !in shown) {
                val child = rows.getOrNull(i + 1) as? ThreadRow.Child
                if (!row.keepWhenHidden || child == null || child.id !in shown) dropped += row.id
            }
            else -> Unit
        }
    }
    return rows.filterNot { it.id in dropped || (it is ThreadRow.More && it.open in dropped) }
}

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
    /** The session's own likes and reposts, shared by every screen; a fresh one in tests. */
    private val own: OwnReactions = MemoryOwnReactions(),
    /** Null (tests that do not mute): [mute] and [unmute] do nothing. */
    mute: MuteRepository? = null,
    /** The scope mute writes run on, so they survive the screen that started them (session: the engine's background scope); null (tests) = [scope]. */
    muteScope: CoroutineScope? = null,
) {
    val names: StateFlow<Map<String, String>> get() = mentions.names
    /** The thread's first shown content is marked once (spec perf 3.9). */
    private var contentMarked = false
    private var focusedAsked = false
    /** Spec perf2 §7.1: each wave 2 thread mark once per screen. */
    private val marked = HashSet<String>()
    private fun markOnce(name: String) { if (marked.add(name)) PerfTrace.mark(name) }
    private fun markFocusedCounts() {
        val s = _state.value
        if (s.items.any { it.note.id == s.focusedId && it.counts != null }) markOnce("thread focused counts")
    }
    private val _state = MutableStateFlow(ThreadUiState())
    val state: StateFlow<ThreadUiState> = _state.asStateFlow()
    private val actions = NoteActions(publish, scope, counts, deletions, afterDelete, own)
    private val muteActions = mute?.let { MuteActions(it, scope, profiles, background = muteScope) }

    fun start() {
        // a profile the background lookup finds replaces the shown one (spec perf 3.2); flags and counts stay
        scope.launch {
            profiles.updates.collect { byKey ->
                all = all.map { it.withProfiles(byKey) }
                _state.update { s -> s.copy(items = s.items.map { it.withProfiles(byKey) }) }
            }
        }
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
        scope.launch {
            counts.counts.collect {
                _state.update { s -> s.copy(items = s.items.map(::withCounts)) }
                markFocusedCounts()
            }
        }
        scope.launch {
            try {
                var seen = false
                // spec perf2 C6: a rebuild never queues behind stale views; the newest one wins
                threads.observe(noteId).conflate().collect { view ->
                    seen = true
                    if (view.gone) {
                        all = emptyList() // a later refilter must not bring the withdrawn thread back
                        _state.update { it.copy(items = emptyList(), loading = false, notFound = true) }
                        return@collect
                    }
                    // spec perf2 A6: the focused note's counts take the priority lane at once, not at the first visible()
                    if (!focusedAsked) {
                        focusedAsked = true
                        counts.request(listOf((view.focused.repostOf ?: view.focused).id), priority = true)
                    }
                    // spec 2026-10-16 §4: the ancestor chain, the focused note, then each direct reply with at most one child
                    val strands = Strands.build(view.focused, view.replies)
                    // spec 2026-10-16 §4.1: the root stays on top even when the walk did not reach it (or failed)
                    val ancestors = view.root?.takeIf { r -> r.id != view.focused.id && view.ancestors.none { it.id == r.id } }
                        ?.let { listOf(Slot.Shown(it)) + view.ancestors } ?: view.ancestors
                    val shownAncestors = ancestors.filterIsInstance<Slot.Shown>().map { it.note }
                    val withFocused = (shownAncestors + view.focused + strands.flatMap { listOfNotNull(it.reply, it.child) }).distinctBy { it.id }
                    val rows = buildList {
                        ancestors.forEachIndexed { i, a -> add(ThreadRow.Ancestor(a.id, lineAbove = i > 0)) }
                        add(ThreadRow.Focused(view.focused.id, lineAbove = ancestors.isNotEmpty()))
                        for (s in strands) {
                            add(ThreadRow.Reply(s.reply.id, lineBelow = s.child != null, keepWhenHidden = s.child != null, orphan = s.reply.replyToId != view.focused.id))
                            s.child?.let { add(ThreadRow.Child(it.id)) }
                            if (s.hidden > 0) add(ThreadRow.More("more-${s.reply.id}", open = s.reply.id))
                        }
                    }
                    val byKey = profiles.getAll(withFocused.map { it.author })
                    all = withFocused.map { n -> NoteUi(n, byKey.getValue(n.author), byKey.getValue(n.author)) }
                    val items = visibility.visible(all.map(::fresh), keep = view.focused.id)
                    _state.update { it.copy(items = items, rows = rows, focusedId = view.focused.id, loading = false) }
                    if (!contentMarked && items.isNotEmpty()) { contentMarked = true; PerfTrace.mark("thread first content") }
                    if (view.ancestors.isNotEmpty()) markOnce("thread ancestors shown")
                    if (view.repliesFetched) markOnce("thread relay replies shown")
                    markFocusedCounts()
                    // spec perf2 C6: only new notes start receipts, one fetch per new slice (MAX_LIVE_GROUPS x MAX_NOTES)
                    decorations.add(withFocused)
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
    fun react(note: Note, emoji: String) = actions.react(note, emoji, ::onActionResult)
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
        if (targets.isNotEmpty()) { counts.request(targets); actions.check(targets) }
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
    val controller = ThreadController(checkNotNull(handle["id"]), ready.threads, ready.profiles, ready.publish, decorations, bookmarks, ready.counts, viewModelScope, mentions = MentionResolver(ready.profiles, viewModelScope, Dispatchers.Default), deletions = ready.deletions, visibility = ready.visibility, afterDelete = ready.afterNoteDelete, own = ready.ownReactions, mute = ready.mute, muteScope = ready.engine.backgroundScope)
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
                val byId = state.items.associateBy { it.note.id }
                val rows = remember(state.rows, byId.keys) { visibleRows(state.rows, byId.keys) }
                // spec 11.5 §4: the thread opens with the focused note in view, like X's detail view — once;
                // coming back from a profile or a rotation keeps the position the user scrolled to.
                // The ancestors arrive after the first view (spec 2026-10-16 §4): wait for them unless the user scrolls first
                var scrolled by rememberSaveable(state.focusedId) { mutableStateOf(false) }
                val focusedIndex = rows.indexOfFirst { it is ThreadRow.Focused }
                LaunchedEffect(state.focusedId, focusedIndex) {
                    if (scrolled || focusedIndex <= 0) return@LaunchedEffect
                    listState.scrollToItem(focusedIndex)
                    scrolled = true
                }
                LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) scrolled = true }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("thread-list")) {
                    itemsIndexed(rows, key = { _, row -> row.id }) { i, row ->
                        val next = rows.getOrNull(i + 1)
                        when (row) {
                            is ThreadRow.More -> ShowRepliesRow(onClick = { onOpenThread(row.open) })
                            else -> {
                                val lineAbove = when (row) {
                                    is ThreadRow.Ancestor -> row.lineAbove
                                    is ThreadRow.Focused -> row.lineAbove
                                    else -> row is ThreadRow.Child
                                }
                                // the chain runs on into the focused note; a strand's head joins its child only when that child is shown
                                val lineBelow = row is ThreadRow.Ancestor || (row is ThreadRow.Reply && row.lineBelow && next is ThreadRow.Child)
                                val n = byId[row.id]
                                if (n == null) UnavailableRow(lineAbove, lineBelow) else NoteCard(
                                    note = n,
                                    replyContext = row is ThreadRow.Reply && row.orphan,
                                    onOpen = { if (n.note.id != state.focusedId) onOpenThread(n.note.id) },
                                    onOpenProfile = onOpenProfile,
                                    onReply = { onReply(n.note.id) },
                                    onLike = { vm.controller.like(n.target) },
                                    onReact = { e -> vm.controller.react(n.target, e) },
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
                                    lineAbove = lineAbove,
                                    lineBelow = lineBelow,
                                )
                            }
                        }
                        // one divider below the focused note and after each strand, none inside a strand or the ancestor chain
                        if (row is ThreadRow.Focused || next is ThreadRow.Reply || next == null) HorizontalDivider()
                    }
                }
                VisibleNotes(listState, vm.controller::visible)
            }
        }
    }
    TipSheetHost(vm.tipSheet)
    tippersFor?.let { TippersDialog(it, load = vm.decorations::tippers, onDismiss = { tippersFor = null }) }
}
