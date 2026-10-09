package com.monostr.app.ui.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import com.monostr.app.ui.common.MentionResolver
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.data.UiSettingsStore
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.Avatar
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.DeletionMessages
import com.monostr.app.ui.common.MessageSnackbar
import com.monostr.app.ui.common.NoteActions
import com.monostr.app.ui.common.NoteDeletions
import com.monostr.app.ui.common.Message
import com.monostr.app.ui.common.MuteActions
import com.monostr.app.ui.common.asMessage
import com.monostr.app.ui.common.noteMenu
import com.monostr.app.ui.common.offersMute
import com.monostr.app.ui.common.userMessage
import com.monostr.nostr.repo.FollowRepository
import com.monostr.nostr.repo.FollowState
import com.monostr.app.ui.common.NoteCard
import com.monostr.app.ui.common.NoteText
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
import com.monostr.nostr.repo.AuthorRelaysRepository
import com.monostr.nostr.repo.CountsRepository
import com.monostr.nostr.repo.FeedRepository
import com.monostr.nostr.repo.FollowCounts
import com.monostr.nostr.repo.FollowCountsRepository
import com.monostr.nostr.repo.MuteRepository
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.ProfileSection
import com.monostr.nostr.repo.PublishRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.monostr.app.ui.theme.MoneroGlyph
import com.monostr.app.ui.tips.TipTarget
import javax.inject.Inject

data class ProfileUiState(
    val profile: Profile,
    /** The "Posts" tab's notes. */
    val notes: List<NoteUi> = emptyList(),
    val loading: Boolean = true,
    val isSelf: Boolean = false,
    val message: Message? = null,
    val extraRelays: List<String> = emptyList(),
    /** Spec 2: whether the user follows this profile; Unknown until a contact list is known. */
    val follow: FollowState = FollowState.Unknown,
    /** A follow or unfollow is being written: the button is disabled. */
    val followBusy: Boolean = false,
    /** The "unfollow @name?" dialog is open. */
    val confirmUnfollow: Boolean = false,
    /** Whether this profile's account is muted; the hint row with "Unmute" shows then (spec 9.3). */
    val isMuted: Boolean = false,
    /** The menu offers "Mute" (same rule as on notes: not the own profile, not a read-only list). */
    val canMute: Boolean = false,
    /** Following and follower numbers; null until looked up or when the lookup failed (the line is left out). */
    val followCounts: FollowCounts? = null,
    /** The open tab. */
    val tab: ProfileSection = ProfileSection.POSTS,
    /** The "Replies" tab's notes; null until the tab was opened once. */
    val replies: List<NoteUi>? = null,
    /** An older page is being fetched for the open tab. */
    val loadingMore: Boolean = false,
    /** Tabs whose paging found nothing older: the list end no longer asks. */
    val ended: Set<ProfileSection> = emptySet(),
) {
    /** The open tab's notes. */
    val shown: List<NoteUi> get() = if (tab == ProfileSection.POSTS) notes else replies.orEmpty()

    /** The open tab has not loaded yet. */
    val shownLoading: Boolean get() = if (tab == ProfileSection.POSTS) loading else replies == null

    /** Applies [f] to the lists of both tabs. */
    fun mapNotes(f: (List<NoteUi>) -> List<NoteUi>): ProfileUiState = copy(notes = f(notes), replies = replies?.let(f))

    fun list(section: ProfileSection): List<NoteUi>? = if (section == ProfileSection.POSTS) notes else replies

    fun withList(section: ProfileSection, list: List<NoteUi>): ProfileUiState =
        if (section == ProfileSection.POSTS) copy(notes = list, loading = false) else copy(replies = list)
}

class ProfileController(
    private val pubkey: String,
    private val selfPubkey: String,
    private val feed: FeedRepository,
    private val profiles: ProfileRepository,
    private val publish: PublishRepository,
    private val decorations: TipDecorations,
    private val bookmarks: BookmarkActions,
    private val counts: CountsRepository,
    private val authorRelays: AuthorRelaysRepository,
    private val authorRelaysEnabled: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val refreshMs: Long = 30_000,
    private val mentions: MentionResolver = MentionResolver(profiles, scope),
    /** Spec 2; null keeps the button hidden (tests that do not care). */
    private val follows: FollowRepository? = null,
    // only deletions filter here: a muted account's profile keeps showing its notes (spec 9.3)
    private val deletions: NoteDeletions = NoteDeletions(),
    private val afterDelete: suspend (Note) -> UiText? = { null },
    /** Null (tests that do not mute): [mute] and [unmute] do nothing. */
    private val mute: MuteRepository? = null,
    /** The scope mute writes run on, so they survive the screen that started them (session: the engine's background scope); null (tests) = [scope]. */
    muteScope: CoroutineScope? = null,
    /** Null (tests that do not care): no counts line. */
    private val followCounts: FollowCountsRepository? = null,
) {
    val names: StateFlow<Map<String, String>> get() = mentions.names
    private val _state = MutableStateFlow(ProfileUiState(Profile.empty(pubkey), isSelf = pubkey == selfPubkey))
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    private val actions = NoteActions(publish, scope, counts, deletions, afterDelete)
    private val muteActions = mute?.let { MuteActions(it, scope, profiles, background = muteScope) }

    /** The author's write relays currently attached (spec 5); empty when the setting is off or none were found. */
    private var attached: List<String> = emptyList()
    private var loader: Job? = null
    /** Whether the screen is visible (started); the outbox refresh loop only fetches while it is (spec 5.2). */
    private val visible = MutableStateFlow(true)

    /** Called from the screen's lifecycle: false on stop, true on start. */
    fun setVisible(visible: Boolean) { this.visible.value = visible }

    fun start() {
        mentions.follow(_state.map { s -> (s.notes + s.replies.orEmpty()).map { it.note } })
        scope.launch { deletions.deleted.collect { _state.update { s -> s.mapNotes(deletions::visible) } } }
        // a request started on another screen greys the entry here too, and frees it again when it failed
        scope.launch { deletions.pending.collect { _state.update { s -> s.mapNotes { l -> l.map(actions::flags) } } } }
        mute?.let { m -> scope.launch { m.state.collect { l -> _state.update { it.copy(canMute = offersMute(pubkey, selfPubkey, l)) } } } }
        mute?.let { m -> scope.launch { m.muted.collect { set -> _state.update { it.copy(isMuted = pubkey in set) } } } }
        if (!_state.value.isSelf && follows != null) {
            scope.launch { follows.state(pubkey).collect { f -> _state.update { it.copy(follow = f) } } }
        }
        // spec 5: an own edit (or a later fetch) shows at once, without reopening the screen
        scope.launch {
            profiles.observe(pubkey).collect { p ->
                if (p == Profile.empty(pubkey)) return@collect
                _state.update { s ->
                    s.copy(profile = p).mapNotes { l -> l.map { n -> if (n.note.author == pubkey) n.copy(author = p, repostedAuthor = if (n.note.isRepost) n.repostedAuthor else p) else n } }
                }
            }
        }
        // the numbers come in as the relays answer (following at once, followers growing): apart from the notes, a failure costs only the line
        followCounts?.let { repo ->
            scope.launch {
                try {
                    repo.counts(pubkey).collect { counts -> _state.update { it.copy(followCounts = counts) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                }
            }
        }
        bookmarks.ensureLoaded()
        scope.launch { bookmarks.changes.collect { _state.update { s -> s.mapNotes { l -> l.map(bookmarks::flags) } } } }
        scope.launch { actions.changes.collect { _state.update { s -> s.mapNotes { l -> l.map(actions::flags) } } } }
        decorations.start()
        scope.launch { decorations.changes.collect { _state.update { s -> s.mapNotes { l -> l.map(decorations::decorate) } } } }
        scope.launch { counts.counts.collect { _state.update { s -> s.mapNotes { l -> l.map(::withCounts) } } } }
        // This one coroutine owns the whole outbox lifecycle: it attaches the author's write relays
        // and, in its `finally`, detaches them — on a normal return, on close()'s cancel(), and on the
        // ViewModel's viewModelScope being torn down (onCleared runs after that cancellation already
        // happened, so a plain launch in close() would never run; cancelling this job's finally does).
        loader = scope.launch {
            try {
                val profile = profiles.get(pubkey)
                _state.update { it.copy(profile = profile) }
                reload(profile, fetch = true) // normal relays first: no outbox wait before the first notes
                val extra = if (authorRelaysEnabled()) runCatching { authorRelays.writeRelays(pubkey) }.getOrElse { e -> if (e is CancellationException) throw e; emptyList() } else emptyList()
                if (extra.isEmpty()) return@launch
                try {
                    authorRelays.attach(extra)
                    attached = extra
                    _state.update { it.copy(extraRelays = extra) }
                    authorRelays.fetchNotes(extra, pubkey)
                    reloadQuietly()
                    while (true) {
                        delay(refreshMs)
                        visible.first { it }
                        authorRelays.fetchNotes(extra, pubkey)
                        reloadQuietly()
                    }
                } finally {
                    // Unconditional: attach() itself loops per relay URL with suspension points, so a
                    // cancellation landing mid-attach can leave some of [extra] actually attached while
                    // `attached` is still empty. Detaching is refcounted in the engine, so a URL this
                    // screen never attached would decrement another holder's reference; that cannot
                    // happen here because attach() only fails between URLs by cancellation, fetchNotes()
                    // pairs its own attach/detach per call, and this loader does exactly one attach and
                    // one detach of [extra]. Detaching the full list is therefore balanced and leak-free.
                    withContext(NonCancellable) { runCatching { authorRelays.detach(extra) } }
                    attached = emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = uiText(R.string.profile_error_load).asMessage()) }
            }
        }
    }

    /** Loads and decorates the author's "Posts"; called on start (with a normal-relay fetch) and after every outbox refresh (without, so the refresh only pulls in what the extra relays already delivered). */
    private suspend fun reload(profile: Profile, fetch: Boolean) = reload(ProfileSection.POSTS, profile, fetch)

    /**
     * Loads the newest page of [section]. Notes older than that page, loaded by [loadMore] before, stay below it:
     * the refresh loop must not take the user's scrolled-in pages away.
     */
    private suspend fun reload(section: ProfileSection, profile: Profile, fetch: Boolean) {
        val fresh = decorate(feed.profileNotes(pubkey, section, fetch = fetch), profile)
        _state.update { s ->
            val oldest = fresh.minOfOrNull { it.note.createdAt }
            val ids = fresh.mapTo(HashSet()) { it.note.id }
            val kept = s.list(section).orEmpty().filter { it.note.id !in ids && (oldest == null || it.note.createdAt < oldest) }
            s.withList(section, deletions.visible(fresh + kept))
        }
        track()
    }

    private suspend fun decorate(notes: List<Note>, profile: Profile): List<NoteUi> {
        profiles.prefetch(notes.mapNotNull { it.repostOf?.author })
        return notes.map { n -> withCounts(actions.flags(bookmarks.flags(decorations.decorate(NoteUi(n, profile, n.repostOf?.let { r -> profiles.get(r.author) } ?: profile))))) }
    }

    /** Tips are watched for the notes of both tabs. */
    private fun track() {
        val s = _state.value
        decorations.track((s.notes + s.replies.orEmpty()).map { it.note })
    }

    /** A refresh-loop [reload] of every tab loaded so far; a per-iteration failure must not stop the loop or replace an already-loaded profile with an error message. */
    private suspend fun reloadQuietly() {
        // the CURRENT profile, not the one captured at start: the observer above may have replaced it after an edit
        runCatching { reload(_state.value.profile, fetch = false) }.onFailure { if (it is CancellationException) throw it }
        if (_state.value.replies == null) return
        runCatching { reload(ProfileSection.REPLIES, _state.value.profile, fetch = false) }.onFailure { if (it is CancellationException) throw it }
    }

    /** Opens a tab; "Replies" loads (with a relay fetch) the first time it opens. */
    fun selectTab(section: ProfileSection) {
        val before = _state.value
        _state.update { it.copy(tab = section) }
        if (section != ProfileSection.REPLIES || before.replies != null || repliesLoading) return
        repliesLoading = true
        scope.launch {
            try {
                reload(ProfileSection.REPLIES, _state.value.profile, fetch = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(replies = emptyList(), message = uiText(R.string.profile_error_load).asMessage()) }
            } finally {
                repliesLoading = false
            }
        }
    }

    private var repliesLoading = false

    /** Fetches the open tab's page before its oldest note; ignored while a call runs, before the tab loaded, and once its paging ended. */
    fun loadMore() {
        val s = _state.value
        val section = s.tab
        val list = s.list(section)
        if (s.loadingMore || list.isNullOrEmpty() || section in s.ended) return
        val oldest = list.last().note.createdAt
        _state.update { it.copy(loadingMore = true) }
        scope.launch {
            try {
                val older = decorate(feed.moreProfileNotes(pubkey, section, oldest), _state.value.profile)
                _state.update { st ->
                    val current = st.list(section).orEmpty()
                    val added = older.filter { o -> current.none { it.note.id == o.note.id } }
                    if (added.isEmpty()) st.copy(ended = st.ended + section)
                    else st.withList(section, deletions.visible(current + added))
                }
                track()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = uiText(R.string.feed_error_more).asMessage()) }
            } finally {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    /** Stops the refresh loop and releases the author's relays; safe to call twice. The ViewModel calls
     * it from onCleared, where viewModelScope is already cancelled — cancellation alone also runs the
     * `finally` above, so this is mostly for closing the screen without tearing down the whole scope. */
    fun close() {
        loader?.cancel()
        loader = null
    }

    fun like(note: Note) = actions.like(note, ::onActionResult)
    fun delete(note: Note) = actions.delete(note)
    fun repost(note: Note) = actions.repost(note, ::onActionResult)
    fun bookmark(note: Note) = bookmarks.toggle(note) { m -> _state.update { s -> s.mapNotes { l -> l.map(bookmarks::flags) }.copy(message = m?.asMessage() ?: s.message) } }
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Spec 9.4: mutes the note's author at once; the publish runs behind it, a failure brings the account back with the usual message. */
    fun mute(note: Note) {
        muteActions?.mute(note.author) { m, ok -> _state.update { it.copy(message = Message(m, undoMute = note.author.takeIf { ok })) } }
    }

    /** The profile menu's "Mute": this account, with the same snackbar and "Undo" as from a note. */
    fun mute() {
        muteActions?.mute(pubkey) { m, ok -> _state.update { it.copy(message = Message(m, undoMute = pubkey.takeIf { ok })) } }
    }

    /** The profile menu's and the hint row's "Unmute". */
    fun unmute() = unmute(pubkey)

    /** The snackbar's "Undo"; nothing to reload, the profile never hid the notes (spec 9.3). */
    fun unmute(pubkey: String) {
        muteActions?.unmute(pubkey) { m -> _state.update { s -> s.copy(message = m?.asMessage() ?: s.message) } }
    }

    fun follow() = writeFollow { it.follow(pubkey) }
    fun askUnfollow() = _state.update { it.copy(confirmUnfollow = true) }
    fun dismissUnfollow() = _state.update { it.copy(confirmUnfollow = false) }
    fun unfollow() {
        _state.update { it.copy(confirmUnfollow = false) }
        writeFollow { it.unfollow(pubkey) }
    }

    /** One write at a time; the state flow of the repository shows the result, a failure becomes the snackbar. */
    private fun writeFollow(action: suspend (FollowRepository) -> Unit) {
        val repo = follows ?: return
        if (_state.value.isSelf || _state.value.followBusy) return
        _state.update { it.copy(followBusy = true) }
        scope.launch {
            try {
                action(repo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.userMessage().asMessage()) }
            } finally {
                _state.update { it.copy(followBusy = false) }
            }
        }
    }

    /** Visible note ids from the list; counts are requested for their targets (spec 4.2). */
    fun visible(noteIds: List<String>) {
        val wanted = noteIds.toSet()
        val targets = _state.value.shown.filter { it.note.id in wanted }.map { it.target.id }
        if (targets.isNotEmpty()) counts.request(targets)
    }

    private fun onActionResult(message: UiText) {
        _state.update { s -> s.mapNotes { l -> l.map(actions::flags) }.copy(message = message.asMessage()) }
    }

    /** [ui] with its current count, so a list rebuild never drops a count the collector already had (spec 4.2). */
    private fun withCounts(ui: NoteUi): NoteUi = ui.copy(counts = counts.counts.value[ui.target.id])
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    session: NostrSession,
    handle: SavedStateHandle,
    factory: TipSheetFactory,
    private val uiSettings: UiSettingsStore,
) : ViewModel() {
    private val ready = session.requireReady()
    private val pubkey: String = checkNotNull(handle["pubkey"])
    val decorations = factory.decorations(viewModelScope)
    val bookmarks = BookmarkActions(ready.bookmarks, viewModelScope, background = ready.engine.backgroundScope)
    val controller = ProfileController(
        pubkey, ready.pubkey, ready.feed, ready.profiles, ready.publish, decorations, bookmarks, ready.counts,
        ready.authorRelays, { uiSettings.authorRelays.first() }, viewModelScope,
        mentions = MentionResolver(ready.profiles, viewModelScope, Dispatchers.Default),
        follows = ready.follows,
        deletions = ready.deletions, afterDelete = ready.afterNoteDelete,
        mute = ready.mute, muteScope = ready.engine.backgroundScope,
        followCounts = ready.followCounts,
    )
    val deletions = ready.deletions
    val menu = ready.noteMenu(delete = controller::delete, mute = controller::mute)
    val tipSheet = factory.tipSheet(viewModelScope)
    /** The user's own pending profile tips to this person; idle on the own profile (no tip button there). */
    val tipWatch = factory.profileTipWatch(pubkey, viewModelScope)
    init {
        controller.start()
        if (pubkey != ready.pubkey) tipWatch.start()
    }
    override fun onCleared() { controller.close() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onOpenThread: (String) -> Unit,
    onReply: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onHashtag: (String) -> Unit,
    onOpenMedia: (String, Int) -> Unit = { _, _ -> },
    onOpenVideo: (String) -> Unit = {},
    onMessage: (String) -> Unit = {},
    onEditProfile: () -> Unit = {},
    onQuote: (String) -> Unit = {},
    vm: ProfileViewModel = hiltViewModel(),
) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val names by vm.controller.names.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tippersFor by remember { mutableStateOf<Note?>(null) }
    MessageSnackbar(state.message, snackbar, onUnmute = vm.controller::unmute, onShown = vm.controller::clearMessage)
    DeletionMessages(vm.deletions, snackbar)
    val pendingTip by vm.tipWatch.pendingTip.collectAsStateWithLifecycle()
    val arrivedText = stringResource(R.string.tip_arrived)
    LaunchedEffect(Unit) { vm.tipWatch.arrived.collect { snackbar.showSnackbar(arrivedText) } }
    LifecycleStartEffect(Unit) {
        vm.controller.setVisible(true)
        vm.tipWatch.setVisible(true)
        onStopOrDispose {
            vm.controller.setVisible(false)
            vm.tipWatch.setVisible(false)
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (state.isSelf) stringResource(R.string.profile_title_self) else state.profile.shownName) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } }, actions = {
            if (!state.isSelf && (state.isMuted || state.canMute)) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }, modifier = Modifier.testTag("profile-menu")) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.note_menu))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(
                            text = { Text(if (state.isMuted) stringResource(R.string.unmute_account) else stringResource(R.string.mute_account, state.profile.shownName)) },
                            onClick = { open = false; if (state.isMuted) vm.controller.unmute() else vm.controller.mute() },
                            modifier = Modifier.testTag("profile-menu-mute"),
                        )
                    }
                }
            }
        })
    }, snackbarHost = { SnackbarHost(snackbar, Modifier.muteUndoTag(state.message)) }) { padding ->
        val listState = rememberLazyListState()
        LazyColumn(state = listState, modifier = Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    state.profile.banner?.takeIf { it.isNotBlank() }?.let { url ->
                        AsyncImage(
                            model = url, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant).testTag("profile-banner"),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(state.profile.picture)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(state.profile.shownName, style = MaterialTheme.typography.titleMedium)
                            state.profile.nip05?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            Text(Profile.shortPubkey(state.profile.pubkey), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // the bio links like a note: URLs, nostr: mentions and hashtags open
                    state.profile.about?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        NoteText(it, onOpen = {}, onHashtag = onHashtag, onOpenProfile = onOpenProfile, onOpenNote = onOpenThread, names = names, modifier = Modifier.testTag("profile-about"))
                    }
                    state.followCounts?.let { Spacer(Modifier.height(8.dp)); FollowCountsLine(it) }
                    Spacer(Modifier.height(8.dp))
                    if (state.isSelf) {
                        OutlinedButton(onClick = onEditProfile, modifier = Modifier.testTag("profile-edit")) { Text(stringResource(R.string.profile_edit)) }
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            FollowButton(state.follow, busy = state.followBusy, onFollow = vm.controller::follow, onUnfollow = vm.controller::askUnfollow)
                            ProfileTipButton(pending = pendingTip, onClick = { vm.tipSheet.open(TipTarget.Profile(state.profile.pubkey)) })
                            OutlinedButton(onClick = { onMessage(state.profile.pubkey) }, modifier = Modifier.testTag("profile-message")) { Text(stringResource(R.string.profile_message)) }
                        }
                    }
                    if (state.isMuted && !state.isSelf) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.mute_profile_hint), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("profile-muted-hint"))
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = vm.controller::unmute, modifier = Modifier.testTag("profile-unmute")) { Text(stringResource(R.string.unmute_account)) }
                        }
                    }
                    if (state.extraRelays.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.profile_extra_relays, state.extraRelays.joinToString(", ") { it.removePrefix("wss://") }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("profile-extra-relays"))
                    }
                }
            }
            stickyHeader(key = "profile-tabs") {
                PrimaryTabRow(selectedTabIndex = state.tab.ordinal, modifier = Modifier.testTag("profile-tabs")) {
                    ProfileSection.entries.forEach { section ->
                        Tab(
                            selected = state.tab == section,
                            onClick = { vm.controller.selectTab(section) },
                            // M3 colours an unselected tab like the selected one unless told otherwise; X greys it
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = { Text(stringResource(if (section == ProfileSection.POSTS) R.string.profile_tab_posts else R.string.profile_tab_replies)) },
                            modifier = Modifier.testTag("profile-tab-${section.name.lowercase()}"),
                        )
                    }
                }
            }
            items(state.shown, key = { it.note.id }) { n ->
                NoteCard(
                    note = n,
                    onOpen = { onOpenThread(n.target.id) },
                    onOpenProfile = onOpenProfile,
                    onReply = { onReply(n.target.id) },
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
                )
                HorizontalDivider()
            }
            when {
                state.shownLoading -> item(key = "profile-loading") { ListSpinner() }
                state.shown.isEmpty() -> item(key = "profile-empty") {
                    Text(
                        stringResource(if (state.tab == ProfileSection.POSTS) R.string.profile_empty_posts else R.string.profile_empty_replies),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("profile-empty"),
                    )
                }
                state.tab !in state.ended -> item(key = "profile-more-${state.tab}") {
                    LaunchedEffect(state.tab, state.shown.size) { vm.controller.loadMore() }
                    if (state.loadingMore) ListSpinner()
                }
            }
        }
        VisibleNotes(listState, vm.controller::visible)
    }
    TipSheetHost(vm.tipSheet)
    if (state.confirmUnfollow) {
        AlertDialog(
            onDismissRequest = vm.controller::dismissUnfollow,
            title = { Text(stringResource(R.string.profile_unfollow_title, state.profile.shownName)) },
            confirmButton = {
                TextButton(onClick = vm.controller::unfollow, modifier = Modifier.testTag("profile-unfollow-confirm")) { Text(stringResource(R.string.profile_unfollow_confirm)) }
            },
            dismissButton = { TextButton(onClick = vm.controller::dismissUnfollow) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    tippersFor?.let { TippersDialog(it, load = vm.decorations::tippers, onDismiss = { tippersFor = null }) }
}

@Composable
private fun ListSpinner() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
}

/** Spec 2: filled "Follow", outlined "Following" (tap asks to unfollow), disabled "Follow" while no list is known or a write runs. */
@Composable
private fun FollowButton(follow: FollowState, busy: Boolean, onFollow: () -> Unit, onUnfollow: () -> Unit) {
    val tagged = Modifier.testTag("profile-follow")
    when (follow) {
        FollowState.Following -> OutlinedButton(onClick = onUnfollow, enabled = !busy, modifier = tagged) { Text(stringResource(R.string.profile_following)) }
        FollowState.NotFollowing -> Button(onClick = onFollow, enabled = !busy, modifier = tagged) { Text(stringResource(R.string.profile_follow)) }
        FollowState.Unknown -> Button(onClick = {}, enabled = false, modifier = tagged) { Text(stringResource(R.string.profile_follow)) }
    }
}

/** Tips the person, not a note (protocol 0.2). The glyph turns to the primary colour while an own tip waits for its receipt; another tip stays possible. No sum and no list here: who earned what is nobody's headline. */
@Composable
private fun ProfileTipButton(pending: Boolean, onClick: () -> Unit) {
    val pendingLabel = stringResource(R.string.note_tip_pending)
    OutlinedIconButton(
        onClick = onClick,
        modifier = Modifier.testTag("profile-tip").semantics { if (pending) stateDescription = pendingLabel },
    ) {
        Icon(
            MoneroGlyph, contentDescription = stringResource(R.string.note_tip),
            tint = if (pending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
