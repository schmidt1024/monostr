package com.monostr.app.ui.profile

import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.ui.platform.LocalContext
import com.monostr.app.ui.common.copyToClipboard
import com.monostr.nostr.Npub
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.repo.OwnReactions
import com.monostr.nostr.repo.MemoryOwnReactions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import com.monostr.app.ui.common.MentionResolver
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.saveable.rememberSaveable
import com.monostr.app.ui.lists.AddToListController
import com.monostr.app.ui.lists.AddToListSheet
import com.monostr.app.ui.lists.ListRow
import com.monostr.app.ui.lists.ProfileListsController
import com.monostr.nostr.model.ListAddress
import androidx.compose.material3.Tab
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.monostr.app.ui.common.HideOnScrollHeader
import com.monostr.app.ui.common.rememberHideOnScroll
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
import com.monostr.app.ui.feed.withProfiles
import com.monostr.app.ui.common.attempt
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
    /** The "Reposts" tab's notes; null until the tab was opened once. */
    val reposts: List<NoteUi>? = null,
    /** An older page is being fetched for the open tab. */
    val loadingMore: Boolean = false,
    /** Tabs whose paging found nothing older: the list end no longer asks. */
    val ended: Set<ProfileSection> = emptySet(),
    /** The relays are asked for this profile's notes (spec perf 3.5); the stored ones show meanwhile. */
    val refreshing: Boolean = false,
) {
    /** The open tab's notes. */
    val shown: List<NoteUi> get() = list(tab).orEmpty()

    /** The open tab has not loaded yet, or it is still empty while the relays are asked. */
    val shownLoading: Boolean get() = (if (tab == ProfileSection.POSTS) loading else list(tab) == null) || (refreshing && shown.isEmpty())

    /** Applies [f] to the lists of all tabs. */
    fun mapNotes(f: (List<NoteUi>) -> List<NoteUi>): ProfileUiState = copy(notes = f(notes), replies = replies?.let(f), reposts = reposts?.let(f))

    fun list(section: ProfileSection): List<NoteUi>? = when (section) {
        ProfileSection.POSTS -> notes
        ProfileSection.REPLIES -> replies
        ProfileSection.REPOSTS -> reposts
    }

    fun withList(section: ProfileSection, list: List<NoteUi>): ProfileUiState = when (section) {
        ProfileSection.POSTS -> copy(notes = list, loading = false)
        ProfileSection.REPLIES -> copy(replies = list)
        ProfileSection.REPOSTS -> copy(reposts = list)
    }

    /** The notes of every tab loaded so far. */
    val allNotes: List<NoteUi> get() = notes + replies.orEmpty() + reposts.orEmpty()
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
    /** The session's own likes and reposts, shared by every screen; a fresh one in tests. */
    private val own: OwnReactions = MemoryOwnReactions(),
    /** Null (tests that do not mute): [mute] and [unmute] do nothing. */
    private val mute: MuteRepository? = null,
    /** The scope mute writes run on, so they survive the screen that started them (session: the engine's background scope); null (tests) = [scope]. */
    muteScope: CoroutineScope? = null,
    /** Null (tests that do not care): no counts line. */
    private val followCounts: FollowCountsRepository? = null,
    /** Spec perf 3.5: the session's last profiles; null (tests that do not care) keeps nothing. */
    private val cache: ProfileCache? = null,
) {
    val names: StateFlow<Map<String, String>> get() = mentions.names
    private val _state = MutableStateFlow(ProfileUiState(Profile.empty(pubkey), isSelf = pubkey == selfPubkey))
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    private val actions = NoteActions(publish, scope, counts, deletions, afterDelete, own)
    private val muteActions = mute?.let { MuteActions(it, scope, profiles, background = muteScope) }

    /** The author's write relays currently attached (spec 5); empty when the setting is off or none were found. */
    private var attached: List<String> = emptyList()
    private var loader: Job? = null
    private var notesJob: Job? = null
    /** Whether the screen is visible (started); the outbox refresh loop only fetches while it is (spec 5.2). */
    private val visible = MutableStateFlow(true)

    /** Called from the screen's lifecycle: false on stop, true on start. */
    fun setVisible(visible: Boolean) { this.visible.value = visible }

    /** The perf marks of this screen, once each (spec perf 3.9). */
    private val marked: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    private fun markOnce(name: String) { if (marked.add(name)) PerfTrace.mark(name) }

    fun start() {
        // a reopened profile shows what it showed last at once; everything below refreshes it (spec perf 3.5)
        cache?.get(pubkey)?.let { e ->
            _state.update { s ->
                s.copy(
                    profile = e.profile,
                    notes = deletions.visible(e.posts.map(::flagged)),
                    loading = false,
                    replies = e.replies?.let { l -> deletions.visible(l.map(::flagged)) },
                    reposts = e.reposts?.let { l -> deletions.visible(l.map(::flagged)) },
                    followCounts = e.counts,
                )
            }
            markOnce("profile header")
            markOnce("profile first notes")
        }
        // first, so nothing is missed: a profile the background lookup finds lands on the notes (spec perf 3.2)
        scope.launch { profiles.updates.collect { byKey -> _state.update { s -> s.mapNotes { l -> l.map { it.withProfiles(byKey) } } } } }
        mentions.follow(_state.map { s -> s.allNotes.map { it.note } })
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
                markOnce("profile header")
            }
        }
        // the header from the database at once; a missing or stale one is looked up behind it and arrives above (spec perf 3.2)
        scope.launch { attempt { profiles.get(pubkey) } }
        // the numbers come in as the relays answer (following at once, followers growing): apart from the notes, a failure costs only the line
        followCounts?.let { repo ->
            scope.launch {
                try {
                    repo.counts(pubkey).collect { counts ->
                        _state.update { it.copy(followCounts = counts) }
                        markOnce("profile counts")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the counts line is an extra: a failed lookup leaves it out (followCounts stays null or keeps the last numbers)
                }
            }
        }
        bookmarks.ensureLoaded()
        scope.launch { bookmarks.changes.collect { _state.update { s -> s.mapNotes { l -> l.map(bookmarks::flags) } } } }
        scope.launch { actions.changes.collect { _state.update { s -> s.mapNotes { l -> l.map(actions::flags) } } } }
        decorations.start()
        scope.launch { decorations.changes.collect { _state.update { s -> s.mapNotes { l -> l.map(decorations::decorate) } } } }
        scope.launch { counts.counts.collect { _state.update { s -> s.mapNotes { l -> l.map(::withCounts) } } } }
        // spec perf 3.5: independent jobs; the notes never wait for the author's relays, nor the other way round
        notesJob = scope.launch { loadNotes() }
        // This one coroutine owns the whole outbox lifecycle: it attaches the author's write relays
        // and, in its `finally`, detaches them — on a normal return, on close()'s cancel(), and on the
        // ViewModel's viewModelScope being torn down (onCleared runs after that cancellation already
        // happened, so a plain launch in close() would never run; cancelling this job's finally does).
        loader = scope.launch {
            try {
                outbox()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the author's relays are an extra: the notes from the user's own relays stay
            }
        }
    }

    /** Spec perf 3.5: the stored "Posts" first, then one relay refresh for every tab, then the database again. */
    private suspend fun loadNotes() {
        try {
            reload(ProfileSection.POSTS)
            markOnce("profile first notes")
            _state.update { it.copy(refreshing = true) }
            try {
                feed.refreshProfile(pubkey)
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
            reloadLoaded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, message = uiText(R.string.profile_error_load).asMessage()) }
        }
    }

    /** Attaches the author's write relays (spec 5), fetches from them and refreshes every [refreshMs] while visible. */
    private suspend fun outbox() {
        val extra = try {
            if (authorRelaysEnabled()) authorRelays.writeRelays(pubkey) else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (extra.isEmpty()) return
        try {
            authorRelays.attach(extra)
            attached = extra
            _state.update { it.copy(extraRelays = extra) }
            refreshFrom(extra)
            while (true) {
                delay(refreshMs)
                visible.first { it }
                refreshFrom(extra)
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
    }

    /**
     * Ids earlier outbox fetches returned. A fetch reports every note it saw (replies and reposts of unopened tabs,
     * deleted ones, ones older than the loaded page), so "not shown" alone would reload on every tick.
     */
    private val outboxSeen = HashSet<String>()

    /** One outbox fetch; the tabs are read again only when it brought an id neither shown nor seen by an earlier fetch (spec perf 3.5). */
    private suspend fun refreshFrom(relays: List<String>) {
        val ids = authorRelays.fetchNotes(relays, pubkey)
        val shown = _state.value.allNotes.mapTo(HashSet()) { it.note.id }
        val fresh = ids.any { it !in outboxSeen && it !in shown }
        outboxSeen += ids
        if (fresh) reloadLoaded()
    }

    /**
     * Reads the newest page of [section] from the database. Notes older than that page, loaded by [loadMore] before,
     * stay below it: a refresh must not take the user's scrolled-in pages away.
     */
    private suspend fun reload(section: ProfileSection) {
        val fresh = decorate(feed.profileNotes(pubkey, section, fetch = false), _state.value.profile)
        _state.update { s ->
            val fresh = fresh.map(::flagged)
            val oldest = fresh.minOfOrNull { it.note.createdAt }
            val ids = fresh.mapTo(HashSet()) { it.note.id }
            val kept = s.list(section).orEmpty().filter { it.note.id !in ids && (oldest == null || it.note.createdAt < oldest) }
            s.withList(section, deletions.visible(fresh + kept))
        }
        track()
    }

    /** [reload] of every tab opened so far; one tab's failure must not stop the others or replace a loaded profile with an error. */
    private suspend fun reloadLoaded() {
        for (section in ProfileSection.entries) {
            if (section != ProfileSection.POSTS && _state.value.list(section) == null) continue
            runCatching { reload(section) }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /** [notes] with their authors' profiles (one read; landed ones come through [ProfileRepository.updates]); the flags follow in [flagged]. */
    private suspend fun decorate(notes: List<Note>, profile: Profile): List<NoteUi> {
        val byKey = profiles.getAll(notes.mapNotNull { it.repostOf?.author })
        return notes.map { n -> NoteUi(n, profile, n.repostOf?.let { r -> byKey.getValue(r.author) } ?: profile) }
    }

    /** [ui] with the current tips, flags and counts; applied inside `_state.update`, so a change during a slow lookup is not undone. */
    private fun flagged(ui: NoteUi): NoteUi = withCounts(actions.flags(bookmarks.flags(decorations.decorate(ui))))

    /** Tips are watched for the notes of all tabs; added ones only, the running subscriptions stay (spec perf 3.5). */
    private fun track() {
        decorations.add(_state.value.allNotes.map { it.note })
    }

    /** Opens a tab; "Replies" and "Reposts" are read from the database the first time they open (the refresh in [loadNotes] covers them). */
    fun selectTab(section: ProfileSection) {
        val before = _state.value
        _state.update { it.copy(tab = section) }
        if (section == ProfileSection.POSTS || before.list(section) != null || !tabsLoading.add(section)) return
        scope.launch {
            try {
                reload(section)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.withList(section, emptyList()).copy(message = uiText(R.string.profile_error_load).asMessage()) }
            } finally {
                tabsLoading.remove(section)
            }
        }
    }

    private val tabsLoading = HashSet<ProfileSection>()

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
                    val added = older.filter { o -> current.none { it.note.id == o.note.id } }.map(::flagged)
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
        notesJob?.cancel()
        notesJob = null
        val s = _state.value
        if (!s.loading) {
            cache?.put(pubkey, ProfileCache.Entry(s.profile, s.notes.take(ProfileCache.PAGE), s.replies?.take(ProfileCache.PAGE), s.reposts?.take(ProfileCache.PAGE), s.followCounts))
        }
    }

    fun like(note: Note) = actions.like(note, ::onActionResult)
    fun react(note: Note, emoji: String) = actions.react(note, emoji, ::onActionResult)
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
        if (targets.isNotEmpty()) { counts.request(targets); actions.check(targets) }
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
        deletions = ready.deletions, afterDelete = ready.afterNoteDelete, own = ready.ownReactions,
        mute = ready.mute, muteScope = ready.engine.backgroundScope,
        followCounts = ready.followCounts,
        cache = ready.profileCache,
    )
    val deletions = ready.deletions
    val menu = ready.noteMenu(delete = controller::delete, mute = controller::mute)
    val tipSheet = factory.tipSheet(viewModelScope)
    /** The user's own pending profile tips to this person; idle on the own profile (no tip button there). */
    val tipWatch = factory.profileTipWatch(pubkey, viewModelScope)
    /** The Lists tab (spec 2026-10-18 §2.2); pin writes run on the background scope so leaving does not cancel them. */
    val lists = ProfileListsController(pubkey, ready.pubkey, ready.lists, ready.feedTabs, viewModelScope, writeScope = ready.engine.backgroundScope, cache = ready.profileCache)
    /** The "Add to list" sheet; started when it opens, so other profiles do not load the own lists for nothing. */
    val addToList = AddToListController(pubkey, ready.lists, viewModelScope, writeScope = ready.engine.backgroundScope)
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
    onOpenList: (ListAddress) -> Unit = {},
    onCreateList: () -> Unit = {},
    onNewListWith: (String) -> Unit = {},
    /** Backlog 29 (like X): the profile picture or banner, large. */
    onOpenPicture: (String) -> Unit = {},
    vm: ProfileViewModel = hiltViewModel(),
) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val names by vm.controller.names.collectAsStateWithLifecycle()
    val listsState by vm.lists.state.collectAsStateWithLifecycle()
    var listsTab by rememberSaveable { mutableStateOf(false) }
    // restored as true after process death: the new view model must read the lists again (open() is idempotent)
    LaunchedEffect(listsTab) { if (listsTab) vm.lists.open() }
    val addState by vm.addToList.state.collectAsStateWithLifecycle()
    var addToListOpen by rememberSaveable { mutableStateOf(false) }
    // restored as true after a configuration change or process death: the sheet must load its rows again (start() is idempotent)
    LaunchedEffect(addToListOpen) { if (addToListOpen) vm.addToList.start() }
    val snackbar = remember { SnackbarHostState() }
    var tippersFor by remember { mutableStateOf<Note?>(null) }
    MessageSnackbar(state.message, snackbar, onUnmute = vm.controller::unmute, onShown = vm.controller::clearMessage)
    MessageSnackbar(listsState.message, snackbar, onUnmute = {}, onShown = vm.lists::clearMessage)
    MessageSnackbar(addState.message, snackbar, onUnmute = {}, onShown = vm.addToList::clearMessage)
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
    val hide = rememberHideOnScroll()
    Scaffold(modifier = Modifier.nestedScroll(hide.connection), topBar = { HideOnScrollHeader(hide) {
        TopAppBar(windowInsets = WindowInsets(0), title = { Text(if (state.isSelf) stringResource(R.string.profile_title_self) else state.profile.shownName) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } }, actions = {
            if (!state.isSelf) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }, modifier = Modifier.testTag("profile-menu")) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.note_menu))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        // spec 2026-10-18 §2.5
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_menu_lists)) },
                            onClick = { open = false; addToListOpen = true },
                            modifier = Modifier.testTag("profile-menu-lists"),
                        )
                        if (state.isMuted || state.canMute) {
                            DropdownMenuItem(
                                text = { Text(if (state.isMuted) stringResource(R.string.unmute_account) else stringResource(R.string.mute_account, state.profile.shownName)) },
                                onClick = { open = false; if (state.isMuted) vm.controller.unmute() else vm.controller.mute() },
                                modifier = Modifier.testTag("profile-menu-mute"),
                            )
                        }
                    }
                }
            }
        })
    } }, snackbarHost = { SnackbarHost(snackbar, Modifier.muteUndoTag(state.message)) }) { padding ->
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        LazyColumn(state = listState, modifier = Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    state.profile.banner?.takeIf { it.isNotBlank() }?.let { url ->
                        AsyncImage(
                            model = url, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(onClickLabel = stringResource(R.string.profile_show_banner)) { onOpenPicture(url) }
                                .testTag("profile-banner"),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val picture = state.profile.picture?.takeIf { it.isNotBlank() }
                        Avatar(
                            picture,
                            modifier = if (picture == null) Modifier else Modifier.clip(CircleShape)
                                .clickable(onClickLabel = stringResource(R.string.profile_show_picture)) { onOpenPicture(picture) }
                                .testTag("profile-picture"),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(state.profile.shownName, style = MaterialTheme.typography.titleMedium)
                            state.profile.nip05?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            NpubLine(state.profile.pubkey)
                        }
                    }
                    // the bio links like a note: URLs, nostr: mentions and hashtags open
                    state.profile.about?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        NoteText(it, onOpen = {}, onHashtag = onHashtag, onOpenProfile = onOpenProfile, onOpenNote = onOpenThread, names = names, modifier = Modifier.testTag("profile-about"))
                    }
                    // kind 0 website: a link that reads without the scheme, like links in notes
                    state.profile.website?.trim()?.takeIf { ProfileEditController.websiteOk(it) && it.isNotEmpty() }?.let { site ->
                        Spacer(Modifier.height(4.dp))
                        NoteText(ProfileEditController.website(site), onOpen = {}, onHashtag = {}, onOpenProfile = {}, onOpenNote = {}, modifier = Modifier.testTag("profile-website"))
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
                PrimaryTabRow(selectedTabIndex = if (listsTab) ProfileSection.entries.size else state.tab.ordinal, modifier = Modifier.testTag("profile-tabs")) {
                    // X: tabs stuck at the top stay there and the new tab starts right below them;
                    // otherwise the swap of the list's items would send the list back to the profile's top
                    fun keepTabsAtTop() {
                        val tabs = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "profile-tabs" }
                        if (tabs != null && tabs.offset <= 0) scope.launch { listState.scrollToItem(tabs.index) }
                    }
                    ProfileSection.entries.forEach { section ->
                        Tab(
                            selected = !listsTab && state.tab == section,
                            onClick = {
                                listsTab = false
                                vm.controller.selectTab(section)
                                keepTabsAtTop()
                            },
                            // M3 colours an unselected tab like the selected one unless told otherwise; X greys it
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = { Text(stringResource(section.label)) },
                            modifier = Modifier.testTag("profile-tab-${section.name.lowercase()}"),
                        )
                    }
                    // spec 2026-10-18 §2.2
                    Tab(
                        selected = listsTab,
                        onClick = {
                            listsTab = true
                            keepTabsAtTop()
                        },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = { Text(stringResource(R.string.profile_tab_lists)) },
                        modifier = Modifier.testTag("profile-tab-lists"),
                    )
                }
            }
            if (!listsTab) {
                items(state.shown, key = { it.note.id }) { n ->
                    NoteCard(
                        note = n,
                        onOpen = { onOpenThread(n.target.id) },
                        onOpenProfile = onOpenProfile,
                        onReply = { onReply(n.target.id) },
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
                    )
                    HorizontalDivider()
                }
                when {
                    state.shownLoading -> item(key = "profile-loading") { ListSpinner() }
                    state.shown.isEmpty() -> item(key = "profile-empty") {
                        Text(
                            stringResource(state.tab.empty),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("profile-empty"),
                        )
                    }
                    state.tab !in state.ended -> item(key = "profile-more-${state.tab}") {
                        LaunchedEffect(state.tab, state.shown.size) { vm.controller.loadMore() }
                        if (state.loadingMore) ListSpinner()
                    }
                }
                // X: a short tab leaves room below, so the tabs can stay stuck at the top when it opens there
                if (state.shown.size < SHORT_TAB) item(key = "profile-tab-room") { Spacer(Modifier.fillParentMaxHeight()) }
            } else {
                if (vm.lists.isSelf) item(key = "list-create") {
                    OutlinedButton(onClick = onCreateList, modifier = Modifier.padding(16.dp).testTag("list-create")) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.list_create))
                    }
                }
                val shownLists = listsState.lists
                when {
                    shownLists == null -> item(key = "lists-loading") { ListSpinner() }
                    shownLists.isEmpty() -> item(key = "lists-empty") {
                        Text(
                            stringResource(if (vm.lists.isSelf) R.string.lists_empty_own else R.string.lists_empty_other),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("lists-empty"),
                        )
                    }
                    else -> items(shownLists, key = { "list-" + it.address }) { set ->
                        ListRow(set, pinned = set.address in listsState.pinned, onOpen = { onOpenList(set.address) }, onPin = { vm.lists.togglePin(set) })
                        HorizontalDivider()
                    }
                }
                if ((shownLists?.size ?: 0) < SHORT_TAB) item(key = "profile-tab-room") { Spacer(Modifier.fillParentMaxHeight()) }
            }
        }
        VisibleNotes(listState, vm.controller::visible)
    }
    TipSheetHost(vm.tipSheet)
    if (addToListOpen) {
        AddToListSheet(
            vm.addToList,
            onNewList = { addToListOpen = false; onNewListWith(state.profile.pubkey) },
            onDismiss = { addToListOpen = false },
        )
    }
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

/** The shortened npub with a button that copies the whole one (launch feedback 2026-10-09: "copy your npub from your profile"). */
@Composable
private fun NpubLine(pubkey: String) {
    val context = LocalContext.current
    val copied = stringResource(R.string.note_copied)
    val npub = remember(pubkey) { runCatching { Npub.encode(pubkey) }.getOrNull() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(npub?.let { it.take(9) + "…" + it.takeLast(4) } ?: Profile.shortPubkey(pubkey), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("profile-npub"))
        if (npub != null) {
            IconButton(onClick = { copyToClipboard(context, npub, copied) }, modifier = Modifier.size(32.dp).testTag("profile-copy-npub")) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.profile_copy_npub), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
    }
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

private val ProfileSection.label: Int get() = when (this) {
    ProfileSection.POSTS -> R.string.profile_tab_posts
    ProfileSection.REPLIES -> R.string.profile_tab_replies
    ProfileSection.REPOSTS -> R.string.profile_tab_reposts
}

private val ProfileSection.empty: Int get() = when (this) {
    ProfileSection.POSTS -> R.string.profile_empty_posts
    ProfileSection.REPLIES -> R.string.profile_empty_replies
    ProfileSection.REPOSTS -> R.string.profile_empty_reposts
}

/** Below this many notes a tab may be shorter than the screen and gets room below (see the tab row). */
private const val SHORT_TAB = 10
