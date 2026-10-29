package com.monostr.app.ui.feed

import com.monostr.app.ui.discover.DiscoverController
import com.monostr.app.ui.discover.DiscoverVisibility
import kotlinx.coroutines.delay
import com.monostr.app.data.FeedSnapshot
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
import com.monostr.app.ui.tips.AmountsPrompt
import com.monostr.app.ui.tips.AmountsPromptState
import com.monostr.app.ui.tips.AmountsQuestion
import com.monostr.app.ui.tips.AmountsVisibility
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.app.ui.tips.TipSheetFactory
import com.monostr.nostr.PerfTrace
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
import kotlinx.coroutines.flow.flow
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
    /** Spec 2026-10-23 §5.1: ancestor ids still searched on foreign relays; shown as the pending row, not as "unavailable". */
    val pending: Set<String> = emptySet(),
    /** Ancestors only a mute hides: shown as "Note from a muted account", one tap shows the note there. */
    val mutedAncestors: Map<String, NoteUi> = emptyMap(),
    /** Spec 2026-10-29 article reader (final review M11): chain slots that are the article a comment sits under, by coordinate; shown as its card. */
    val articles: Map<String, com.monostr.nostr.model.AddressRef> = emptyMap(),
    /** Like X's "new posts": live notes above the newest shown, held back while the user reads; [FeedController.showNew] or a refresh shows them. */
    val newAbove: List<NoteUi> = emptyList(),
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
    /** The last first page of this feed (home or a pinned list), shown at once on the next start; null (tests, the list screen) = none. */
    private val snapshot: FeedSnapshot? = null,
    /** Spec perf2 A2: called once when the home feed's first relay page is in, failed or was cancelled; never for a list (session: opens the startup gate). */
    private val onFirstFresh: () -> Unit = {},
    /** Spec 2026-10-23 §5.1: pull-to-refresh forgets the "searched, not found" marks before the chains are tried again (session: the locator's forget without a retry signal, so the refresh walks them once). */
    private val forgetMisses: () -> Unit = {},
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
    /** Ids that came from [snapshot] only: the first fresh page drops those it does not hold. */
    private var snapshotIds: Set<String> = emptySet()
    private var snapshotJob: Job? = null
    /** True once a page came from the database or the relays (also an empty one): the snapshot is over then. */
    private val freshSeen = MutableStateFlow(false)
    /** The home feed's first shown notes are marked once (spec perf 3.9). */
    private var firstNotesMarked = false
    /** Count targets the home feed reported visible; the first count among them is marked once (spec perf2 §7.1). */
    private val countsWanted = HashSet<String>()
    private var countsMarked = false

    fun start() {
        // first, so nothing is missed: a profile the background lookup finds replaces the shown one (spec perf 3.2);
        // only the profiles change, flags set meanwhile (a like, a bookmark, a pending tip) stay, the kept ancestors too
        scope.launch {
            profiles.updates.collect { byKey ->
                allAncestors.replaceAll { _, ui -> ui.withProfiles(byKey) }
                _state.update { s -> s.mapAll { ui -> ui.withProfiles(byKey) } }
            }
        }
        if (snapshot != null) scope.launch { showSnapshot(snapshot) }
        // spec 2026-10-23 §5.1: a changed relay set or a new search chance walks the open chains again
        scope.launch { conversations.retries.collect { retryUnavailable() } }
        mentions.follow(_state.map { s -> s.notes.map { it.note } + s.ancestors.values.map { it.note } })
        bookmarks.ensureLoaded()
        scope.launch { bookmarks.changes.collect { _state.update { s -> s.mapAll(bookmarks::flags) } } }
        scope.launch { actions.changes.collect { _state.update { s -> s.mapAll(actions::flags) } } }
        scope.launch { visibility.changes.collect { _state.update { s -> s.copy(notes = visibility.visible(s.notes), ancestors = visibleAncestors(), mutedAncestors = mutedAncestors()) } } }
        // a request started on another screen greys the entry here too, and frees it again when it failed
        scope.launch { deletions.pending.collect { _state.update { s -> s.mapAll(actions::flags) } } }
        decorations.start()
        scope.launch { decorations.changes.collect { _state.update { s -> s.mapAll(decorations::decorate) } } }
        scope.launch {
            counts.counts.collect { m ->
                _state.update { s -> s.mapAll(::withCounts) }
                markFirstCounts(m)
            }
        }
        if (liveOn) startLive()
        // the repository leaves muted accounts out at the query (read once per start). A newly muted account is only
        // hidden (visibility); an unmute, also one reverted after a failed publish, restarts the list like a follow
        // change does, so the account's notes are asked for again. Restarting on every mute threw away the first
        // page on a cold start, when the mute list arrives from the relays seconds after the feed (2026-10-19).
        scope.launch { merge(followChanges, unmutes()).collect { if (liveOn) { startLive(); refresh() } } }
        // a newly muted account would keep its places in the live window until a restart: the live list alone
        // is read again, once the first fresh page is in (the snapshot does not count), so the cold start's
        // first database read is never thrown away
        scope.launch {
            newMutes().collect {
                freshSeen.first { it }
                if (liveOn) startLive()
            }
        }
        scope.launch {
            try {
                feed.refresh(source)
                val decorated = decorate(feed.notes(source))
                setFresh(decorated)
                if (source == FeedSource.Following) PerfTrace.mark("feed fresh page")
                chainsFor(decorated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // relays are best-effort; the live collector still shows the local notes
                _state.update { it.copy(loading = false) }
            } finally {
                // the snapshot and the local page never count: only the first relay page lets the background work go
                if (source == FeedSource.Following) onFirstFresh()
                // from now on a live note above the newest shown waits behind the pill (not at start: no pill flashing up)
                holdLive = true
            }
        }
    }

    /** Fires when an account joined the muted set. */
    private fun newMutes(): Flow<Unit> = flow {
        var last = visibility.muted.value
        visibility.muted.drop(1).collect { now ->
            if ((now - last).isNotEmpty()) emit(Unit)
            last = now
        }
    }

    /** Fires when an account left the muted set; adding accounts does not fire. */
    private fun unmutes(): Flow<Unit> = flow {
        var last = visibility.muted.value
        visibility.muted.drop(1).collect { now ->
            if ((last - now).isNotEmpty()) emit(Unit)
            last = now
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
                    setFresh(decorated, live = true)
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
        // what waits behind the pill shows at once, the relays bring the rest
        showNew()
        scope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                feed.refresh(source)
                val decorated = decorate(feed.notes(source))
                setFresh(decorated, refreshing = false)
                chainsFor(decorated)
                forgetMisses()
                retryUnavailable()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = uiText(R.string.feed_error_load).asMessage(), refreshing = false, loading = false) }
            }
        }
    }

    /** Whether a live note above the newest shown waits behind the pill: from the end of the first relay page on. */
    private var holdLive = false

    /** The pill (X's "new posts"): the waiting notes go on top of the list. */
    fun showNew() {
        val waiting = _state.value.newAbove
        if (waiting.isEmpty()) return
        _state.update { it.copy(newAbove = emptyList()) }
        setNotes { s -> (waiting + s.notes).distinctBy { it.note.id }.sortedByDescending { it.note.createdAt } }
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

    /** Entries a retry asked for while they were in [resolving]: their job walks them once more at its end. */
    private val retryAgain = HashSet<String>()

    private fun chainsFor(page: List<NoteUi>) = resolveChains(page.filter { it.note.id !in _state.value.chains })

    /** Spec 2026-10-16 §2.2 Timeouts, §6: a refresh tries the chains again that had unavailable notes (muted ones are loaded, so they stay). */
    private fun retryUnavailable() {
        val s = _state.value
        val open = s.notes.filter { n -> s.chains[n.note.id]?.ancestors?.any { it !in allAncestors && it !in s.articles } == true }
        // entries a job is still resolving are walked again when it ends (a retry must not get lost in the stage-3 window)
        retryAgain += open.map { it.note.id }.filter { it in resolving }
        resolveChains(open)
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
            // Pending ids this job put into the state: a failed or cancelled search is finished too, so none stays
            val added = HashSet<String>()
            try {
                var notes = todo.map { it.note }
                var resolved = emptySet<String>()
                // spec 2026-10-23 §10 (final review M1): an id that missed on the own relays in this job is not asked there again
                val asked = HashSet<String>()
                // bounded: each round needs a new Pending hop, and the locator marks what it did not find
                repeat(PENDING_ROUNDS) {
                    val found = conversations.chains(notes, asked)
                    val pendings = storeChains(found, resolved)
                    if (pendings.isEmpty()) return@launch
                    added += pendings.map { it.id }
                    // spec 2026-10-23 §5.1: the chains are shown already; stage 3 runs behind them and only the affected ones are walked again
                    conversations.resolve(pendings)
                    resolved = pendings.mapTo(HashSet()) { it.id }
                    notes = notes.filter { n -> found[n.id]?.ancestors?.any { it is Slot.Pending && it.id in resolved } == true }
                }
                // out of rounds: one last walk, what is still pending after it is shown as unavailable
                val last = storeChains(conversations.chains(notes, asked), resolved)
                if (last.isNotEmpty()) storeChains(emptyMap(), last.mapTo(HashSet()) { it.id })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // best-effort: the replies stay single; a later refresh tries again
            } finally {
                resolving -= ids.toSet()
                if (added.isNotEmpty()) _state.update { s -> if (s.pending.isEmpty()) s else s.copy(pending = s.pending - added) }
                // a retry signal or refresh that came in while this job held the entries walks them once more
                val again = ids.filter { retryAgain.remove(it) }
                if (again.isNotEmpty()) resolveChains(_state.value.notes.filter { it.note.id in again })
            }
        }
    }

    /** Stores [found]; [resolved] ids leave `pending` in the same update. Returns the Pending slots of [found] (no update when there is nothing to store). */
    private suspend fun storeChains(found: Map<String, Chain>, resolved: Set<String> = emptySet()): List<Slot.Pending> {
        if (found.isEmpty() && resolved.isEmpty()) return emptyList()
        val pendings = found.values.flatMap { c -> c.ancestors.filterIsInstance<Slot.Pending>() }.distinctBy { it.id }
        val shown = found.values.flatMap { c -> c.ancestors.filterIsInstance<Slot.Shown>().map { it.note } }.distinctBy { it.id }
        decorate(shown).forEach { allAncestors[it.note.id] = it }
        _state.update { s ->
            s.copy(
                chains = s.chains + found.mapValues { (_, c) -> ChainUi(c.ancestors.map { it.id }, c.cut) },
                ancestors = visibleAncestors(),
                mutedAncestors = mutedAncestors(),
                pending = if (resolved.isEmpty() && pendings.isEmpty()) s.pending else s.pending - resolved + pendings.map { it.id },
                articles = s.articles + found.values.flatMap { c -> c.ancestors.filterIsInstance<Slot.Article>() }.associate { it.id to it.ref },
            )
        }
        trackShown()
        return pendings
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
    /** The stored ancestors a mute hides, with the current flags. */
    private fun mutedAncestors(): Map<String, NoteUi> =
        visibility.mutedOnly(allAncestors.values.map(::flagged)).associateBy { it.note.id }

    private fun FeedUiState.mapAll(f: (NoteUi) -> NoteUi) =
        copy(notes = notes.map(f), ancestors = ancestors.mapValues { (_, n) -> f(n) }, mutedAncestors = mutedAncestors.mapValues { (_, n) -> f(n) })

    /**
     * Sets the note list and keeps the receipt subscription in step with what is shown. The flags are applied inside the
     * update: a list decorated before a slow profile lookup would otherwise bring back flags a change collector had
     * already replaced (a pending tip, a like, a bookmark), and no later change would correct them.
     */
    private suspend fun showSnapshot(snapshot: FeedSnapshot) {
        // a list feed shows its members only: a snapshot written before one left the list must not bring their notes back
        val members = (source as? FeedSource.Authors)?.pubkeys?.toSet()
        val items = snapshot.read().let { all -> if (members == null) all else all.filter { it.note.author in members } }
        // the live list was faster (also with an empty page): nothing to show from the snapshot
        if (items.isEmpty() || freshSeen.value) return
        snapshotIds = items.map { it.note.id }.toSet()
        setNotes(loading = false) { items.map { NoteUi(it.note, it.author, it.repostedAuthor) } }
    }

    /** A page read from the database or the relays: notes only the snapshot had go, and the new first page is kept for the next start. */
    private fun setFresh(decorated: List<NoteUi>, refreshing: Boolean? = null, live: Boolean = false) {
        val stale = snapshotIds
        snapshotIds = emptySet()
        freshSeen.value = true
        val shown = _state.value
        // X's "new posts": a live list brings notes above the newest shown while the user reads; they wait (no jump under the
        // finger) until the pill or a refresh; a refresh, the first page and an update of what is shown take everything in
        val held = if (live && holdLive && !shown.refreshing && shown.notes.isNotEmpty()) {
            val newest = shown.notes.first().note.createdAt
            val ids = shown.notes.mapTo(HashSet()) { it.note.id }
            decorated.filter { it.note.id !in ids && it.note.createdAt > newest }
        } else emptyList()
        val heldIds = held.mapTo(HashSet()) { it.note.id }
        val taken = if (held.isEmpty()) decorated else decorated.filterNot { it.note.id in heldIds }
        _state.update { s -> s.copy(newAbove = if (held.isEmpty() && !live) emptyList() else visibility.visible((held + s.newAbove).distinctBy { it.note.id }.map(::flagged))) }
        setNotes(loading = false, refreshing = refreshing) { s -> merge(taken, if (stale.isEmpty()) s.notes else s.notes.filterNot { it.note.id in stale }) }
        val snap = snapshot ?: return
        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            delay(SNAPSHOT_DELAY_MS)
            snap.write(_state.value.notes.take(FeedSnapshot.MAX_ITEMS).map { FeedSnapshot.Item(it.note, it.author, it.repostedAuthor) })
        }
    }

    private fun setNotes(loading: Boolean? = null, refreshing: Boolean? = null, f: (FeedUiState) -> List<NoteUi>) {
        _state.update { s -> s.copy(notes = visibility.visible(f(s).map(::flagged)), loading = loading ?: s.loading, refreshing = refreshing ?: s.refreshing) }
        if (!firstNotesMarked && source == FeedSource.Following && _state.value.notes.isNotEmpty()) {
            firstNotesMarked = true
            PerfTrace.mark("feed first notes")
        }
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
        if (source == FeedSource.Following && !countsMarked) {
            countsWanted += targets
            markFirstCounts(counts.counts.value)
        }
    }

    private fun markFirstCounts(m: Map<String, NoteCounts>) {
        if (countsMarked || countsWanted.none { m[it] != null }) return
        countsMarked = true
        countsWanted.clear()
        PerfTrace.mark("feed first counts")
    }

    private fun onActionResult(message: UiText) {
        _state.update { s -> s.mapAll(actions::flags).copy(message = message.asMessage()) }
    }

    /** [ui] with its current count, so a list rebuild (live/refresh/loadMore) never drops a count the collector already had (spec 4.2). */
    private fun withCounts(ui: NoteUi): NoteUi = ui.copy(counts = counts.counts.value[ui.target.id])

    /**
     * Notes with the profiles stored now; the flags follow in [setNotes] / [visibleAncestors]. Missing or stale
     * profiles are queued in the repository and arrive through [ProfileRepository.updates] (collected in [start]):
     * a page waiting for the relays' profiles took seconds on a cold start (Pixel, 2026-10-19).
     */
    private suspend fun decorate(notes: List<Note>): List<NoteUi> {
        val pubkeys = notes.flatMap { listOfNotNull(it.author, it.repostOf?.author) }.distinct()
        val stored = profiles.local(pubkeys).associateBy { it.pubkey }
        profiles.prefetch(pubkeys)
        return notes.map { n ->
            val author = stored[n.author] ?: Profile.empty(n.author)
            NoteUi(n, author, n.repostOf?.let { stored[it.author] ?: Profile.empty(it.author) } ?: author)
        }
    }

    companion object {
        /** A burst of pages (live, refresh) writes the snapshot once. */
        const val SNAPSHOT_DELAY_MS = 2_000L
        /** Spec 2026-10-23 §5.1: at most this many foreign-search hops per chain job. */
        const val PENDING_ROUNDS = 3
    }
}

/** The note a like/repost/reply/tip refers to: the original for reposts. */
val NoteUi.target: Note get() = note.repostOf ?: note

/** [this] with the authors [byKey] holds (a landed profile, spec perf 3.2); the same instance when neither changes. */
fun NoteUi.withProfiles(byKey: Map<String, Profile>): NoteUi {
    val author = byKey[note.author] ?: author
    val reposted = note.repostOf?.let { byKey[it.author] ?: repostedAuthor } ?: author
    return if (author == this.author && reposted == repostedAuthor) this else copy(author = author, repostedAuthor = reposted)
}

/** A list's feed (spec 2026-10-18 §4.3): the home feed's controller over [source], on its own [scope]; a pinned tab passes its [snapshot] (spec perf 3.7). */
fun Ready.listFeed(factory: TipSheetFactory, source: FeedSource.Authors, scope: CoroutineScope, snapshot: FeedSnapshot? = null): FeedController = FeedController(
    feed, profiles, publish, factory.decorations(scope),
    BookmarkActions(bookmarks, scope, background = engine.backgroundScope), counts, scope,
    conversations = conversations, mentions = MentionResolver(profiles, scope, Dispatchers.Default),
    deletions = deletions, visibility = visibility, afterDelete = afterNoteDelete, own = ownReactions,
    mute = mute, muteScope = engine.backgroundScope, source = source, snapshot = snapshot,
    forgetMisses = { locator?.forget(notify = false) },
)

@HiltViewModel
class FeedViewModel @Inject constructor(session: NostrSession, private val factory: TipSheetFactory, settings: TipSettingsStore, private val hints: HintStore) : ViewModel() {
    private val ready = session.requireReady()
    val decorations = factory.decorations(viewModelScope)
    val bookmarks = BookmarkActions(ready.bookmarks, viewModelScope, background = ready.engine.backgroundScope)
    val controller = FeedController(ready.feed, ready.profiles, ready.publish, decorations, bookmarks, ready.counts, viewModelScope, conversations = ready.conversations, mentions = MentionResolver(ready.profiles, viewModelScope, Dispatchers.Default), followChanges = ready.follows.changes, deletions = ready.deletions, visibility = ready.visibility, afterDelete = ready.afterNoteDelete, own = ready.ownReactions, mute = ready.mute, muteScope = ready.engine.backgroundScope, snapshot = ready.feedSnapshot, forgetMisses = { ready.locator?.forget(notify = false) }, onFirstFresh = { ready.startupGate.open("feed fresh page") })
    val deletions = ready.deletions
    val menu = ready.noteMenu(delete = controller::delete, mute = controller::mute)

    /** Spec 2026-10-18 §4.3: Following plus one feed per pinned list. */
    val tabs = HomeTabsController(ready.feedTabs, ready.lists, viewModelScope, ready.pubkey, controller, ready.listSnapshots, ready.startupGate) { source, scope, snapshot ->
        ready.listFeed(factory, source, scope, snapshot)
    }

    /** The note menu of [feed]: delete and mute go through the tab's own controller. */
    fun menuFor(feed: FeedController): NoteMenu = if (feed === controller) menu else ready.noteMenu(delete = feed::delete, mute = feed::mute)
    val tipSheet = factory.tipSheet(viewModelScope)
    val relayStates = ready.engine.relayStates()

    /** Badge count from the live notification list and the read marker (spec 5.7). */
    val unread: StateFlow<Int> = ready.notificationsStore.unread
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

    /** Spec 2026-10-27 §4/§5: the curated lists above the home feed for accounts with a thin feed. */
    val discover = DiscoverController(ready.discover, ready.follows, ready.profiles, ready.mute, ready.pubkey, viewModelScope, writeScope = ready.engine.backgroundScope)
    val discoverShown: StateFlow<Boolean> = ready.discoverDecision.shown.map { it == true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // decided once per session, after the feed's first page (spec perf2 A2); a contact list still unknown is asked
        // again a few times (the feed's fetch may still run) before it counts as none (a brand-new account)
        viewModelScope.launch {
            ready.startupGate.await()
            val followed = DiscoverVisibility.awaitFollowed { ready.follows.followed().first() }
            hints.dismissed(ready.pubkey, DiscoverVisibility.HINT).collect { dismissed -> ready.discoverDecision.offer(DiscoverVisibility.decide(followed, dismissed)) }
        }
        viewModelScope.launch {
            discoverShown.first { it }
            discover.start()
        }
    }

    fun dismissDiscover() {
        ready.discoverDecision.dismiss()
        viewModelScope.launch { runCatching { hints.dismiss(ready.pubkey, DiscoverVisibility.HINT) } }
    }

    private val amountsQuestion = AmountsQuestion(
        AmountsPrompt(settings, ready.tips, hints, AmountsVisibility(ready.tips, ready.watchers, settings), ready.pubkey), viewModelScope,
    )

    /** The one-time "hide tip amounts?" question, decided once per start after the feed loaded; never while a tip sheet is open. */
    val amountsPrompt: StateFlow<AmountsPromptState> = combine(amountsQuestion.state, tipSheet.state) { q, sheet ->
        if (sheet.target == null) q else q.copy(shown = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AmountsPromptState())

    /** Back or a tap outside: no answer, nothing stored; asked again next start. */
    fun deferAmountsPrompt() = amountsQuestion.defer()

    fun answerAmountsPrompt(hide: Boolean) = amountsQuestion.answer(hide)

    init {
        ready.notificationsStore.start()
        tabs.start()
        viewModelScope.launch {
            controller.start()
            controller.state.first { !it.loading }
            amountsQuestion.ask()
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
