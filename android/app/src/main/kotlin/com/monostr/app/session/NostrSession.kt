package com.monostr.app.session

import com.monostr.app.net.SendGate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import com.monostr.app.ui.common.PowFilter
import com.monostr.app.ui.discover.DiscoverDecision
import com.monostr.nostr.repo.DiscoverRepository
import com.monostr.nostr.repo.NostrDiscoverRepository
import com.monostr.app.data.FeedSnapshot
import com.monostr.app.data.FileOwnListsCache
import com.monostr.app.data.ListSnapshots
import com.monostr.app.data.SessionOwnListsCache
import com.monostr.app.data.accountDir
import com.monostr.app.data.linkpreview.LinkPreviews
import com.monostr.nostr.repo.NostrOwnReactions
import com.monostr.nostr.repo.OwnReactions
import android.content.Context
import com.monostr.app.MainActivity
import com.monostr.app.R
import com.monostr.app.data.DmSettingsStore
import com.monostr.app.data.DraftStore
import com.monostr.app.data.PendingSetupStore
import com.monostr.app.data.expireDrafts
import com.monostr.app.data.removeDrafts
import com.monostr.app.data.HintStore
import com.monostr.app.data.dm.DmDatabase
import com.monostr.app.data.dm.DmStore
import com.monostr.app.dm.DmSync
import com.monostr.app.data.AccountMigration
import com.monostr.app.data.AccountPrefs
import com.monostr.app.data.SecretNames
import com.monostr.app.data.MediaServer
import com.monostr.app.data.RelayModeStore
import com.monostr.app.data.RelayStore
import com.monostr.app.data.SearchRelayStore
import com.monostr.app.data.SecretStore
import com.monostr.app.data.SessionInfo
import com.monostr.app.data.SessionStore
import com.monostr.app.data.AccountsStore
import com.monostr.app.net.dropAllConnections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import com.monostr.app.data.SignerType
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.data.UiSettingsStore
import com.monostr.app.data.WatcherClients
import com.monostr.app.data.WatcherGateway
import com.monostr.app.media.BlossomClient
import com.monostr.app.media.BlossomMediaUploader
import com.monostr.app.media.ImagePreparer
import com.monostr.app.media.TransformerVideoPreparer
import com.monostr.app.media.MediaUploader
import com.monostr.app.media.OwnPictures
import com.monostr.app.net.SwitchedSession
import com.monostr.app.net.TorController
import com.monostr.app.net.torConnected
import com.monostr.app.net.publicAddressesOnly
import com.monostr.app.ui.common.NoteDeletions
import com.monostr.app.ui.common.NoteVisibility
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.profile.ProfileCache
import com.monostr.app.ui.common.pluralText
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.media.MediaReveals
import com.monostr.app.ui.tips.WatcherReceipts
import com.monostr.app.work.BackgroundCheckWorker
import com.monostr.nostr.DetachedSender
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.RefreshGate
import com.monostr.nostr.Signer
import com.monostr.nostr.StartupGate
import com.monostr.nostr.SyncMarks
import com.monostr.nostr.TimedStartupGate
import com.monostr.nostr.model.Note
import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import com.monostr.nostr.net.sessionSocksPort
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import com.monostr.nostr.model.ProfileJson
import com.monostr.nostr.nip55.AmberSigner
import com.monostr.nostr.nip55.Nip55
import com.monostr.nostr.nip55.SignerBridge
import com.monostr.nostr.model.RelayEntry
import com.monostr.nostr.model.RelayMode
import com.monostr.nostr.repo.AuthorRelaysRepository
import com.monostr.nostr.repo.BookmarksRepository
import com.monostr.nostr.repo.CountLimiter
import com.monostr.nostr.repo.CountsRepository
import com.monostr.nostr.repo.FollowCountsRepository
import com.monostr.nostr.repo.NostrFollowCountsRepository
import com.monostr.nostr.repo.FollowListsRepository
import com.monostr.nostr.repo.NostrFollowListsRepository
import com.monostr.nostr.repo.DmRelaysRepository
import com.monostr.nostr.repo.FeedRepository
import com.monostr.nostr.repo.FollowRepository
import com.monostr.nostr.repo.FeedTabsCache
import com.monostr.nostr.repo.FeedTabsRepository
import com.monostr.nostr.repo.NostrFeedTabsRepository
import com.monostr.nostr.repo.ListsRepository
import com.monostr.nostr.repo.NostrListsRepository
import com.monostr.app.data.PrefsRelayStore
import com.monostr.nostr.repo.MuteRepository
import com.monostr.nostr.repo.NostrFeedRepository
import com.monostr.nostr.repo.NostrFollowRepository
import com.monostr.nostr.repo.NostrNotificationsRepository
import com.monostr.nostr.repo.NostrProfileRepository
import com.monostr.nostr.repo.NostrAddressableRepository
import com.monostr.nostr.repo.NostrQuoteRepository
import com.monostr.nostr.repo.EventLocator
import com.monostr.nostr.repo.ForeignRelays
import com.monostr.nostr.repo.NostrEventLocator
import com.monostr.nostr.repo.NostrAuthorRelaysRepository
import com.monostr.nostr.repo.NostrBookmarksRepository
import com.monostr.nostr.repo.NostrMuteRepository
import com.monostr.nostr.repo.NostrCountsRepository
import com.monostr.nostr.repo.NostrDmRelaysRepository
import com.monostr.nostr.repo.NostrDmRepository
import com.monostr.nostr.repo.NostrPublishRepository
import com.monostr.nostr.repo.ConversationRepository
import com.monostr.nostr.repo.NostrConversationRepository
import com.monostr.nostr.repo.NostrThreadRepository
import com.monostr.nostr.repo.NostrSearchRepository
import com.monostr.nostr.repo.NostrTipsRepository
import com.monostr.nostr.repo.NotificationsRepository
import com.monostr.app.ui.notifications.NotificationsStore
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.PublishRepository
import com.monostr.nostr.repo.AddressableRepository
import com.monostr.nostr.repo.QuoteRepository
import com.monostr.nostr.repo.SearchRepository
import com.monostr.nostr.repo.ThreadRepository
import com.monostr.nostr.repo.storedOrFetched
import com.monostr.nostr.repo.TipsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.io.File
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/** Everything screens need once a user is logged in. */
class Ready(
    val pubkey: String,
    /** Whether anything may leave now; the same for every session. */
    val sendGate: SendGate,
    val signer: Signer,
    val engine: NostrEngine,
    val feed: FeedRepository,
    val threads: ThreadRepository,
    val conversations: ConversationRepository,
    val profiles: ProfileRepository,
    val publish: PublishRepository,
    val tips: TipsRepository,
    val notifications: NotificationsRepository,
    /** Spec perf 3.6: the notifications of this session, shared by the badge, the screen and the tip check. */
    val notificationsStore: NotificationsStore,
    val search: SearchRepository,
    val bookmarks: BookmarksRepository,
    val watchers: WatcherGateway,
    /** Backlog 97: which watcher relays to ask for the tips of others' notes, and when; shared by every screen. */
    val watcherReceipts: WatcherReceipts,
    val counts: CountsRepository,
    val authorRelays: AuthorRelaysRepository,
    val dms: DmSync,
    val dmStore: DmStore,
    val dmRelays: DmRelaysRepository,
    val quotes: QuoteRepository,
    /** Spec 2026-11-12 polls: votes, own vote, new poll. */
    val polls: com.monostr.nostr.repo.PollRepository,
    /** Spec 2026-10-29 naddr: the event behind a `nostr:naddr1…` card. */
    val addresses: AddressableRepository,
    /** Spec 2026-10-29 article reader: the replies under an article. */
    val articleReplies: com.monostr.nostr.repo.ArticleRepliesRepository,
    val follows: FollowRepository,
    /** Follower and following numbers for the profile header. */
    val followCounts: FollowCountsRepository,
    /** Spec 2026-10-30: the accounts behind the profile's Following and Followers numbers. */
    val followLists: FollowListsRepository,
    /** Plan 10e: prepares and uploads pictures for notes and the profile. */
    val media: MediaUploader,
    /** Plan 11-A: own notes deleted in this session. */
    val deletions: NoteDeletions,
    /** Plan 11-A: removes pictures from the media server once nothing of the user shows them any more. */
    val pictures: OwnPictures,
    /** Runs after an accepted deletion request: the note's pictures leave the server (spec 4.3); a text reports failures. */
    val afterNoteDelete: suspend (Note) -> UiText?,
    /** Plan 11-B: the NIP-51 mute list (spec 9). */
    val mute: MuteRepository,
    /** v0.13.9: the user's own likes and reposts for the filled icons on every screen. */
    val ownReactions: OwnReactions,
    /** Spec 2026-10-18: the NIP-51 follow sets (own and foreign). */
    val lists: ListsRepository,
    /** Spec 2026-10-18 §3.3: the lists pinned as home feed tabs. */
    val feedTabs: FeedTabsRepository,
    /** Spec 2026-10-27 §3: the curated lists of the monostr account (the app's default relays asked beside the user's). */
    val discover: DiscoverRepository = NostrDiscoverRepository(engine, PrefsRelayStore.DEFAULT_RELAYS),
    /** Spec 2026-10-27 §4: whether the home feed shows the discover block, decided once per session. */
    val discoverDecision: DiscoverDecision = DiscoverDecision(),
    /** The home feed's last first page, shown at once on the next start (2026-10-19); deleted on logout. */
    val feedSnapshot: FeedSnapshot? = null,
    /** Spec perf 3.5: the last 20 profiles as shown, for an instant reopen; this session only. */
    val profileCache: ProfileCache = ProfileCache(),
    /** Spec perf 3.7: the last first page of each pinned list; deleted on unpin and logout. */
    val listSnapshots: ListSnapshots? = null,
    /** Spec perf 3.7: this session's access to the own lists' summary; closed at logout before the file goes. */
    val ownLists: SessionOwnListsCache? = null,
    /** Spec perf2 A2: this session's background network work waits here until the home feed's first page is in (or 3 s). */
    val startupGate: StartupGate = StartupGate.OPEN,
    /**
     * Starts the work only a visible app needs (own reactions, relay-seen tracking, draft expiry); once per session, any
     * later call does nothing. The UI calls it when it shows the session, so a background check (which never shows any)
     * leaves it out: Pixel 2026-11-02, a worker run spent up to 5.5 s CPU reading the own reactions alone.
     */
    val startForegroundWork: () -> Unit = {},
    /** Spec perf2 A5: per-relay-set fetch stamps of this account (timestamps only); deleted at logout (D7). */
    val syncMarks: SyncMarks? = null,
    /** 0.24.3: when each relay was last seen connected ([RELAY_SEEN_FILE]); logout deletes it. */
    val relaySeen: SyncMarks? = null,
    /** Spec perf2 D11: the Messages tab's network follows() lookup; runs on the engine's background scope, so leaving the tab never cancels it. */
    val followsRefresh: RefreshGate<String> = RefreshGate(engine.backgroundScope, 60),
    /** Spec 2026-10-23 §4: stage 3 for missing notes; null in tests that build a [Ready] by hand. */
    val locator: EventLocator? = null,
    /** Spec 2026-10-23 §3: the app-wide budget of foreign relays; logout and release give them back. */
    val foreign: ForeignRelays? = null,
    /** GitHub #4: the proof of work minimum for notes of people the user does not follow. */
    val powFilter: StateFlow<PowFilter> = MutableStateFlow(PowFilter.OFF),
) {
    /** Pubkeys of the muted accounts; every note list filters against them (spec 9.3). */
    val muted: StateFlow<Set<String>> get() = mute.muted

    /** The one filter of every note list: deleted notes and muted accounts. */
    val visibility: NoteVisibility = NoteVisibility(deletions, mute.muted, powFilter, mute.mutedThreads, pubkey)
}

/**
 * Exposes [NostrSession] to instrumentation tests. A Hilt `@EntryPoint` is only woven into the
 * component of the app actually installed on the device when it lives in a source set the app's
 * own build aggregates (this one); one declared inside an androidTest class is aggregated only
 * into a component generated for the test APK, which the running `MonostrApp` never uses, so
 * `EntryPointAccessors.fromApplication` casts against it and throws `ClassCastException`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NostrSessionEntryPoint {
    fun session(): NostrSession
    fun searchRelays(): SearchRelayStore
    fun relayStore(): com.monostr.app.data.RelayStore
    fun recentSearches(): com.monostr.app.data.RecentSearchesStore
    fun uiSettings(): UiSettingsStore
    fun drafts(): DraftStore
    fun pendingSetup(): PendingSetupStore
    fun feedTabs(): FeedTabsCache
    fun sessions(): SessionStore
    fun accountPrefs(): AccountPrefs
    fun translationSettings(): com.monostr.app.translate.TranslationSettingsStore
    fun readerTheme(): com.monostr.app.ui.article.ReaderThemeStore
    fun torSwitchControl(): com.monostr.app.net.TorSwitch
    fun torSwitchStore(): com.monostr.app.net.TorSwitchStore
    fun torController(): dagger.Lazy<TorController>
    fun networkGate(): NetworkGate
    fun http(): OkHttpClient
}

/** The switcher's name and picture of [profile] (display name before name, blanks dropped); null while nothing of it is known. */
internal fun switcherDisplay(profile: Profile): Pair<String?, String?>? {
    val name = profile.displayName?.takeIf { it.isNotBlank() } ?: profile.name?.takeIf { it.isNotBlank() }
    val picture = profile.picture?.takeIf { it.isNotBlank() }
    return if (name == null && picture == null) null else name to picture
}

/** Spec 6: outcome of re-requesting Amber's permissions. */
sealed class RenewResult {
    data object Updated : RenewResult()
    data object Cancelled : RenewResult()
    data object WrongKey : RenewResult()
    data object NotAmber : RenewResult()
}

sealed class SessionState {
    data object Loading : SessionState()
    /** [message] explains a failed restore; the kept session is retried on the next start. */
    data class LoggedOut(val message: UiText? = null) : SessionState()
    data class Active(val ready: Ready) : SessionState()
}

/**
 * Owns the engine for the logged-in user. Restores a saved session on start,
 * builds a fresh engine on login, tears it down on logout.
 */
@Singleton
class NostrSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessions: AccountsStore,
    private val relayStore: RelayStore,
    relayModeStore: RelayModeStore,
    private val secrets: SecretStore,
    val bridge: SignerBridge,
    private val http: OkHttpClient,
    private val tipSettings: TipSettingsStore,
    private val dmSettings: DmSettingsStore,
    private val searchRelayStore: SearchRelayStore,
    private val hints: HintStore,
    private val uiSettings: UiSettingsStore,
    private val linkPreviews: LinkPreviews,
    private val drafts: DraftStore,
    private val pendingSetup: PendingSetupStore,
    private val feedTabsCache: FeedTabsCache,
    private val networkGate: NetworkGate,
    private val tor: dagger.Lazy<TorController>,
    private val sendGate: SendGate,
    /** Every settings store reads through it: the active account's file, or the global one while logged out. */
    private val accountPrefs: AccountPrefs,
    private val migration: AccountMigration,
    /** A switch waits for no send: it is refused while one of the active account runs. */
    busy: AccountBusy,
) : SwitchedSession {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private val mutex = Mutex()
    private val torRoutes = TorRouteHistory()
    private val lifecycle = AccountLifecycle(sessions, accountPrefs, migration, secrets, busy, LifecycleHost())

    /** Every account on this device, in the order added; the active one is [SessionStore.session]. */
    val accounts: Flow<List<SessionInfo>> = sessions.accounts

    suspend fun switchHintSeen(): Boolean = sessions.switchHintSeen()

    suspend fun markSwitchHintSeen() = sessions.markSwitchHintSeen()

    /** The new active pubkey after a switch away from a running account (switch, add, removal of the active one); MainActivity recreates. */
    val switched: SharedFlow<String> = lifecycle.switched
    private val sessionRelays = SessionRelays(relayStore, relayModeStore, mutex) {
        (_state.value as? SessionState.Active)?.ready?.let { ReadyRelays(it) }
    }

    /**
     * Spec perf 3.7: the own lists' summary per account (private members decrypted). Below `noBackupFilesDir`
     * (never backed up nor moved to a new device, unlike `filesDir`); removed at logout.
     */
    private val ownListsCache = FileOwnListsCache(context.noBackupFilesDir)

    companion object {
        val RESTORE_FAILED: UiText = uiText(R.string.session_restore_failed)
        /** Logout waits at most this long for the drafts' pictures to leave the server. */
        private const val DRAFT_CLEANUP_MS = 5_000L
        /** Spec 2026-10-17 §7: a pending setup that did not go out completely is tried once more after this long. */
        private const val SETUP_RETRY_MS = 30_000L
        /** As `GatedHttp`: only a gate whose setting cannot be read waits this long. */
        private const val UNKNOWN_WAIT_MS = 10_000L

        fun renewOutcome(sessionPubkey: String, ok: Boolean, response: Nip55.Response?): RenewResult = when (val o = Nip55.parsePubkeyResponse(ok, response)) {
            is Nip55.Outcome.Pubkey -> if (o.pubkey == sessionPubkey) RenewResult.Updated else RenewResult.WrongKey
            else -> RenewResult.Cancelled
        }
    }

    /**
     * Restores the persisted session, if any. Safe to call repeatedly. Never throws.
     * An unusable secret (Keystore key lost or invalidated, entry missing) clears the session
     * and secret. A failure to read the session or start the engine keeps both, so the next
     * start can retry, and shows [RESTORE_FAILED] on the login screen.
     *
     * @param onStarted told when this call is the one that built the session, also if it is cancelled right after. Not told when the
     * session was there, or came up by someone else while this call waited for its turn, as during a Tor switch.
     */
    suspend fun restore(onStarted: (Ready) -> Unit = {}) = mutex.withLock { restoreLocked(onStarted) }

    private suspend fun restoreLocked(onStarted: (Ready) -> Unit = {}) {
        if (_state.value is SessionState.Active) return
        try {
            // the layout of older versions moves first; the account's settings go live before anything reads relays or settings,
            // also when the migration failed (a passing Keystore error): its settings may already sit in the account's file
            val migrationFailure = try { migration.run(); null } catch (e: CancellationException) { throw e } catch (e: Exception) { e }
            val info = sessions.session.first()
            accountPrefs.activate(info?.pubkey)
            if (migrationFailure != null) throw migrationFailure
            if (info == null) {
                _state.value = SessionState.LoggedOut()
                return
            }
            PerfTrace.mark("restore: session read")
            val signer = signerFor(info)
            PerfTrace.mark("restore: signer ${info.signerType}")
            if (signer == null) {
                // its key is gone for good: only that secret goes, the account and its data stay until signed in again or
                // removed by the user; nothing else starts (also not from a background check)
                lifecycle.keyUnusable(info)
                return
            }
            val ready = start(signer)
            _state.value = SessionState.Active(ready)
            onStarted(ready)
            PerfTrace.mark("session ready")
            markFirstLogin(signer.pubkey)
            resumeSetupLater(ready)
            // the switcher's name and picture of this account, also on a normal start (final review M3: the first after the update)
            keepDisplayFresh(ready)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = SessionState.LoggedOut(RESTORE_FAILED)
        }
    }

    /** Closes the engine but keeps the persisted session: the next [restore] starts it again. Used by background work that started the engine itself. */
    suspend fun release() = mutex.withLock { closeLocked() }

    /** False without a running session. */
    private suspend fun closeLocked(): Boolean {
        val ready = (_state.value as? SessionState.Active)?.ready ?: return false
        runCatching { ready.dms.stop() }
        runCatching { ready.foreign?.close() }
        runCatching { ready.engine.close() }
        // the engine's close ends it; also when that threw: no late write of this session reaches the next account's settings
        runCatching { ready.engine.backgroundScope.cancel() }
        // a collector of this session's own lists on a screen's scope must not load or persist them any more (decrypted private
        // members of an account that may be removed next); a rebuilt session opens its own view
        runCatching { ready.ownLists?.close() }
        runCatching { (ready.dmStore as? DmDatabase)?.close() }
        _state.value = SessionState.Loading
        return true
    }

    /** A running session comes back after a Tor switch; a logged-out one stays as it is. Only touched while the switch holds [mutex]. */
    private var rebuildAfterSwitch = false
    private val switchLock = SwitchLock(mutex)

    private fun holdRunningEngine() { (_state.value as? SessionState.Active)?.ready?.engine?.holdConnections() }

    /**
     * [mutex] is held from here to the end of [rebuild], so no restore (the UI asks for one as soon as the state is Loading), login or
     * logout falls between the steps of a switch, and every [start] reads the mode it is built with. The first hold does not wait for
     * the lock, the second is for an engine whose login finished while waiting. No step of a switch waits for Tor, so this stays short.
     */
    override suspend fun stop() {
        holdRunningEngine()
        switchLock.take()
        rebuildAfterSwitch = false
        holdRunningEngine()
    }

    override suspend fun close() { rebuildAfterSwitch = closeLocked() }

    override suspend fun rebuild() = switchLock.finish {
        // a step before the close failed: this engine is held for good, it has to go in any case
        if (_state.value is SessionState.Active) rebuildAfterSwitch = closeLocked()
        if (rebuildAfterSwitch) restoreLocked()
    }

    /**
     * Starts the engine first; the secret and session are persisted only once it runs. A pending setup
     * record of this key is an orphan of an interrupted "Let's go" and is dropped (spec 2026-10-17 §7):
     * a pasted key is an existing account, whose profile and follows must never be overwritten.
     */
    suspend fun loginLocal(signer: LocalSigner) = mutex.withLock { loginLocalLocked(signer, setup = null) }

    /**
     * Spec 2026-10-17 §4: "Let's go". The session starts like the key login; the pending setup record is
     * written with the secret and session, and published in the background once those are stored. A
     * failed login leaves no record.
     */
    suspend fun createAccount(key: NewKey, setup: PendingSetup) = mutex.withLock {
        loginLocalLocked(LocalSigner(key.secretHex), setup)
        createdHere += LocalSigner(key.secretHex).pubkey
    }

    /** Accounts created in this process: nothing of theirs can be on a relay yet (the Monero intro asks nobody). */
    private val createdHere: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun wasCreatedHere(pubkey: String): Boolean = pubkey in createdHere

    /** [setup] non-null: a new account, its record is persisted right before the secret (spec 2026-10-17 §7). */
    private suspend fun loginLocalLocked(signer: LocalSigner, setup: PendingSetup?) {
        val restored = setup == null && takeKeptSettings(signer)
        val result = try { lifecycle.login(
            SessionInfo(signer.pubkey, SignerType.LOCAL, null),
            start = {
                if (setup == null) bestEffort { pendingSetup.remove(signer.pubkey) }
                restoreReadMarks(signer.pubkey)
                start(signer)
            },
            persist = {
                if (setup != null) pendingSetup.save(signer.pubkey, setup)
                secrets.put(SecretNames.nsec(signer.pubkey), signer.secretHex)
            },
            // also on cancellation: no record without a session
            onFailure = { if (setup != null) bestEffort { pendingSetup.remove(signer.pubkey) } },
        ) } catch (e: Throwable) {
            settleKeptSettings(signer.pubkey, restored, done = false)
            throw e
        }
        settleKeptSettings(signer.pubkey, restored, done = result == SwitchResult.Done)
        if (result != SwitchResult.Done) throw SwitchRefusedException(result)
    }

    /** Like [loginLocal], an orphan pending setup record of [pubkey] is dropped first. */
    suspend fun loginAmber(pubkey: String, packageName: String) = mutex.withLock {
        // the app that just answered the login is the one asked to open the kept settings (one more request to it)
        val restored = keptSettingsFor(pubkey) && run {
            withContext(Dispatchers.IO) { com.monostr.nostr.nip55.SignerTrust.pin(context, packageName) }
            takeKeptSettings(AmberSigner(context, packageName, pubkey, bridge))
        }
        val result = try { lifecycle.login(
            SessionInfo(pubkey, SignerType.AMBER, packageName),
            start = {
                bestEffort { pendingSetup.remove(pubkey) }
                restoreReadMarks(pubkey)
                // the app that just answered the login is the account's signer from here on
                withContext(Dispatchers.IO) { com.monostr.nostr.nip55.SignerTrust.pin(context, packageName) }
                start(AmberSigner(context, packageName, pubkey, bridge))
            },
            persist = {},
        ) } catch (e: Throwable) {
            settleKeptSettings(pubkey, restored, done = false)
            throw e
        }
        settleKeptSettings(pubkey, restored, done = result == SwitchResult.Done)
        if (result != SwitchResult.Done) throw SwitchRefusedException(result)
    }

    /**
     * Spec «Switch flow»: closes the running account's session, then [pubkey]'s settings go live and its session starts; the
     * previous account comes back when that fails. Runs to the end once begun. Refused while a send of the active account runs.
     */
    suspend fun switchTo(pubkey: String): SwitchResult = mutex.withLock { withContext(NonCancellable) { lifecycle.switchTo(pubkey) } }

    /**
     * Spec «Removing an account» (replaces logout): nothing of [pubkey] stays on the device; the active account is replaced by the
     * next one, the last one leads to the login screen. Runs to the end even when the caller is cancelled (e.g. the settings screen
     * is left during the draft cleanup).
     */
    suspend fun removeAccount(pubkey: String, keepSettings: Boolean = false) = mutex.withLock {
        withContext(NonCancellable) {
            if (keepSettings) {
                // backlog 121: sealed while the key (or Amber) is still there; when that fails nothing is removed
                val signer = try {
                    sessions.accounts.first().firstOrNull { it.pubkey == pubkey }?.let { signerFor(it) }
                } catch (e: Exception) {
                    throw com.monostr.app.data.KeepSettingsFailedException(e)
                } ?: throw com.monostr.app.data.KeepSettingsFailedException(IllegalStateException("no signer"))
                keptSettings.keep(signer)
            } else {
                keptSettings.drop(pubkey)
            }
            lifecycle.remove(pubkey)
        }
    }

    private val keptSettings = com.monostr.app.data.KeptSettings(File(context.noBackupFilesDir, com.monostr.app.data.KeptSettings.DIR), secrets, accountPrefs::storeFor)

    /**
     * Backlog 121: an account new on this device whose settings were kept at its removal gets them back before its session
     * starts (relays, look, Monero setup and view key). False without a package or when the signer does not open it.
     */
    private suspend fun takeKeptSettings(signer: Signer): Boolean {
        val pubkey = signer.pubkey
        if (!keptSettings.exists(pubkey)) return false
        if (sessions.accounts.first().any { it.pubkey == pubkey } || !migration.isEmpty(pubkey)) return false
        return keptSettings.restore(signer)
    }

    private fun keptSettingsFor(pubkey: String) = keptSettings.exists(pubkey)

    /** After the login: the package was taken and goes; a login that did not go through takes back what [takeKeptSettings] wrote. */
    private suspend fun settleKeptSettings(pubkey: String, restored: Boolean, done: Boolean) {
        if (!restored) return
        if (done) { keptSettings.drop(pubkey); return }
        withContext(NonCancellable) {
            bestEffort { secrets.remove(SecretNames.viewKey(pubkey)) }
            // a refused login (a send was running) left the file; a failed one removed it already
            if (sessions.accounts.first().none { it.pubkey == pubkey }) bestEffort { accountPrefs.delete(pubkey) }
        }
    }

    /** The read marks [pubkey] had at its last logout on this device, or "now" for an account new here. */
    private suspend fun restoreReadMarks(pubkey: String) = bestEffort { tipSettings.restoreReadMarks(pubkey, System.currentTimeMillis() / 1000) }

    /** The persisted session runs: shown, scheduled, and its pending setup (if any) goes out. */
    private suspend fun activate(ready: Ready) {
        _state.value = SessionState.Active(ready)
        PerfTrace.mark("session ready")
        markFirstLogin(ready.pubkey)
        // a switch or removal cancelled it; a session runs, so the gate is known
        bestEffort { BackgroundCheckWorker.schedule(context, torOn = networkGate.mode.value is NetworkMode.Tor) }
        resumeSetupLater(ready)
        keepDisplayFresh(ready)
    }

    /**
     * Spec «Switcher UI»: the switcher shows every account's name and picture from the registry, never from an inactive account's
     * store; the active one's are refreshed there from its own profile while it runs, off the start path. Ends with the session.
     */
    private fun keepDisplayFresh(ready: Ready) {
        ready.engine.backgroundScope.launch {
            ready.startupGate.await()
            bestEffort { ready.profiles.get(ready.pubkey) }
            ready.profiles.observe(ready.pubkey).map(::switcherDisplay).filterNotNull().distinctUntilChanged().collect { (name, picture) ->
                bestEffort {
                    val stored = sessions.accounts.first().firstOrNull { it.pubkey == ready.pubkey }
                    if (stored != null && (stored.name != name || stored.picture != picture)) sessions.updateDisplay(ready.pubkey, name, picture)
                }
            }
        }
    }

    /**
     * Spec 2026-10-17 §7: a new account's open parts go out at every session start, off the start path;
     * only for a session already persisted, so no record is published for a key that is not stored.
     */
    private fun resumeSetupLater(ready: Ready) {
        ready.engine.backgroundScope.launch { ready.startupGate.await(); bestEffort { resumeSetup(ready.engine, ready.pubkey, ready.publish, ready.follows) } }
    }

    /** The account steps' view of this session ([AccountLifecycle]); every call runs under [mutex]. */
    private inner class LifecycleHost : AccountLifecycle.Host<Ready> {
        override val running: Ready? get() = (_state.value as? SessionState.Active)?.ready
        override fun pubkeyOf(session: Ready) = session.pubkey

        override suspend fun stopPendingWork() {
            // spec «Work in flight»: a pending Amber request ends as if the user backed out
            bridge.cancelPending()
            // a run in flight is cancelled too; the next session's activate schedules the check again
            bestEffort { BackgroundCheckWorker.cancel(context) }
        }

        override suspend fun close() { closeLocked() }

        // closing a pooled TLS connection writes its close_notify: network I/O, never on the main thread the screens call from
        // (Pixel 2026-11-25: NetworkOnMainThreadException, every add and switch rolled back)
        override suspend fun evictConnections() = withContext(Dispatchers.IO) { http.dropAllConnections() }

        // only ever raises it: the start of the next session sets the value it needs. Direct builds nothing of Tor
        override fun accountChanging(next: String?) {
            if (networkGate.mode.value != NetworkMode.Direct && torRoutes.needsFreshRoute(next, viaTor = true)) tor.get().setRoutePending(true)
        }

        override suspend fun start(info: SessionInfo): Ready? = signerFor(info)?.let { start(it) }

        override suspend fun discard(session: Ready) = SessionCleanup.closeAll(
            { session.dms.stop() }, { session.foreign?.close() }, { session.engine.close() }, { (session.dmStore as? DmDatabase)?.close() },
        )

        override suspend fun activate(session: Ready) = this@NostrSession.activate(session)

        override suspend fun restore() = restoreLocked()

        override fun scheduleBackgroundCheck() {
            runCatching { BackgroundCheckWorker.schedule(context, torOn = networkGate.mode.value is NetworkMode.Tor) }
        }

        override suspend fun failed() { _state.value = SessionState.LoggedOut(RESTORE_FAILED) }

        // spec 2026-10-16 §8: the drafts' pictures go while signer and engine still work; the drafts themselves go with the settings file
        override suspend fun beforeRemoveRunning(session: Ready) {
            withTimeoutOrNull(DRAFT_CLEANUP_MS) { removeDrafts(drafts, session.pubkey, session.media) }
        }

        override suspend fun wipe(pubkey: String) {
            // the account's DMs (spec §4), closed with its session
            bestEffort { DmDatabase.delete(context, pubkey) }
            // spec perf 3.7: the own lists' summary (decrypted private members); a write still pending from a screen is dropped
            bestEffort { ownListsCache.remove(pubkey) }
            // the engine's database, sync marks, relay-seen, feed snapshot; the private folder with the list snapshots
            bestEffort { withContext(Dispatchers.IO) { accountDir(context.filesDir, pubkey).deleteRecursively() } }
            bestEffort { withContext(Dispatchers.IO) { accountDir(context.noBackupFilesDir, pubkey).deleteRecursively() } }            // the caches all accounts share say what this one looked at: link previews, pictures, video poster frames. They
            // went only with the last account (bug bounty 2026-11-26); the accounts that stay load theirs again
            bestEffort { linkPreviews.clear() }
            bestEffort {
                withContext(Dispatchers.IO) {
                    coil3.SingletonImageLoader.get(context).let { it.memoryCache?.clear(); it.diskCache?.clear() }
                    java.io.File(context.cacheDir, "video_frames").deleteRecursively()
                }
            }
        }

        override fun dropNotifications(pubkey: String) {
            // only this account's notifications; the others stay and open their own account
            runCatching { com.monostr.app.work.AccountNotifications.cancelAccount(context, pubkey) }
            // a tap on one of them still pending must not open a chat for another account
            MainActivity.clearPending(pubkey)
        }

        override suspend fun loggedOut() {
            MediaReveals.clear()
            // the next account must not see which links this one opened (spec link previews 3.4)
            bestEffort { linkPreviews.clear() }
            _state.value = SessionState.LoggedOut()
        }
    }

    /** The app is in the foreground again: reconnect at once when every relay connection was lost meanwhile. Best effort, a no-op without a session. */
    suspend fun reconnectIfLost() {
        val ready = (_state.value as? SessionState.Active)?.ready ?: return
        bestEffort { ready.engine.reconnectIfLost() }
    }

    /**
     * Spec 2026-10-23 §7: the default network changed while the app is in the foreground. A switch reconnects every
     * relay (their sockets may be dead but still "connected"), a return only the lost ones; either way the "not found"
     * marks go, so open screens look for their missing notes once more. Lingering foreign relays are dropped first: their
     * sockets may be dead but still read connected, and the retry must connect afresh (final review I1). Best effort, a
     * no-op without a session.
     */
    suspend fun onNetworkChange(change: NetworkChange) {
        val ready = (_state.value as? SessionState.Active)?.ready ?: return
        ready.foreign?.let { bestEffort { it.dropLingering() } }
        bestEffort {
            when (change) {
                NetworkChange.SWITCHED -> ready.engine.reconnectAll()
                NetworkChange.RETURNED -> ready.engine.reconnectIfLost()
            }
        }
        ready.locator?.forget()
    }

    /** Applies a new relay list to the running engine; false when it would leave no relay to read from or none to write to. See [SessionRelays.apply]. */
    suspend fun applyRelays(relays: List<String>): Boolean = sessionRelays.apply(relays)

    /** Puts a removed relay back with its former mode in one step. See [SessionRelays.restore]. */
    suspend fun restoreRelay(relays: List<String>, url: String, mode: RelayMode?): Boolean = sessionRelays.restore(relays, url, mode)

    /** False when [RelayModes][com.monostr.nostr.model.RelayModes] refuses: at least one relay reads and one writes. See [SessionRelays.setRelayMode]. */
    suspend fun setRelayMode(url: String, mode: RelayMode): Boolean = sessionRelays.setRelayMode(url, mode)

    /** What "publish the relay list" sends. See [SessionRelays.entriesForPublish]. */
    suspend fun relayEntriesForPublish(): List<RelayEntry> = sessionRelays.entriesForPublish()

    /** Back to [relays] with every relay reading and writing, as one step. See [SessionRelays.resetTo]. */
    suspend fun resetRelays(relays: List<String>): Boolean = sessionRelays.resetTo(relays)

    /** The pinned lists' snapshots of [pubkey]: below `noBackupFilesDir` (spec perf 3.7, ruling Task 4). */
    private fun listSnapshotsDir(pubkey: String) = File(accountDir(context.noBackupFilesDir, pubkey), ListSnapshots.DIR)

    /** Spec 11: the first successful login of this account on this device (existing accounts: the first start with 10d). */
    private suspend fun markFirstLogin(pubkey: String) = bestEffort { hints.markFirstLogin(pubkey, System.currentTimeMillis() / 1000) }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // leftover state is overwritten by the next setup; logout must still complete
        }
    }

    /**
     * Spec 2026-10-17 §6: the key of a key login for Settings → "Show key"; null for Amber or when the stored
     * key is missing or unreadable. A parse error is dropped unseen: its message may echo the key.
     */
    suspend fun ownKey(): NewKey? = withContext(Dispatchers.IO) {
        val info = sessions.session.first()
        NewKeys.ofSession(info) { info?.let { secrets.get(SecretNames.nsec(it.pubkey)) } }
    }

    /** The account whose session runs, if any. */
    fun activePubkey(): String? = (_state.value as? SessionState.Active)?.ready?.pubkey

    fun requireReady(): Ready = (_state.value as? SessionState.Active)?.ready ?: error("not logged in")

    /**
     * True when [info] cannot start until signed in again (its key unusable for good, see [AccountLifecycle.keyUnusable]): a key
     * account without a readable key, an Amber account without its package. A passing Keystore error does not count.
     */
    suspend fun needsSignIn(info: SessionInfo): Boolean = withContext(Dispatchers.IO) {
        when (info.signerType) {
            SignerType.LOCAL -> runCatching { secrets.getOrThrow(SecretNames.nsec(info.pubkey)) }
                .fold(onSuccess = { it?.let { k -> LocalSigner.parseOrNull(k) } == null }, onFailure = { false })
            SignerType.AMBER -> info.amberPackage == null
        }
    }

    private fun signerFor(info: SessionInfo): Signer? = when (info.signerType) {
        // getOrThrow: a passing Keystore error ends in RESTORE_FAILED (session and key kept), not in the removal of the account
        SignerType.LOCAL -> secrets.getOrThrow(SecretNames.nsec(info.pubkey))?.let { LocalSigner.parseOrNull(it) }
        SignerType.AMBER -> info.amberPackage?.let { AmberSigner(context, it, info.pubkey, bridge) }
    }

    private suspend fun start(signer: Signer): Ready {
        val dir = accountDir(context.filesDir, signer.pubkey).apply { mkdirs() }
        val modes = sessionRelays.startupModes()
        // spec Tor 7.2: no engine before the route is known. Normally one file stat; a setting that cannot be read fails this start, it never goes direct
        val port = networkGate.sessionSocksPort(UNKNOWN_WAIT_MS)
        // with Tor the session runs on what is stored, and the engine waits: the port is ours only while Tor is connected (followTor below)
        val engine = NostrEngine.create(withContext(Dispatchers.IO) { com.monostr.app.data.EngineDir.of(dir) }.absolutePath, signer, relayStore.relays.first(), socksPort = port, held = port != null)
        // spec «Tor isolation per account»: after another account's session went through tor, this one connects only once tor
        // acknowledged a NEWNYM (switch, add, re-login, a removal's successor); restore, a first login and a Tor switch go at once
        val needsFresh = torRoutes.needsFreshRoute(signer.pubkey, viaTor = port != null)
        // before the session shows: its pictures and sends wait for the acknowledgement too (gated HTTP, SendGate, DetachedSender)
        if (port != null) tor.get().setRoutePending(needsFresh)
        // a connection pooled before the switch rides a circuit of the account before: it goes before the gate opens
        val freshRoute: (suspend () -> Unit)? = if (needsFresh) ({ tor.get().awaitNewIdentity(onAck = http::dropAllConnections) }) else null
        engine.setRelayModes(modes)
        PerfTrace.mark("restore: engine created")
        try {
            engine.connect()
        } catch (e: Exception) {
            engine.close()
            throw e
        }
        PerfTrace.mark("restore: connect returned")
        // spec perf2 A2: on the engine's scope, so logout (engine close) ends the timer and every waiter
        val startupGate = TimedStartupGate(engine.backgroundScope, route = engine.routeOpen.takeIf { port != null })
        val dmStore = DmDatabase(context, signer.pubkey)
        // spec 7.3: whatever fails from here on, the DM database and the engine are closed again
        try {
            // spec 2026-10-23 §3/§4.4: one budget of foreign relays for the whole session, the indexers included
            val foreign = ForeignRelays(engine)
            val tips = NostrTipsRepository(engine, DetachedSender(engine.ffiDispatcher, networkGate, torReady = torConnected(tor)), gate = startupGate, testNetworks = com.monostr.app.BuildConfig.DEBUG, foreign = foreign)
            // spec 2026-11-19: NIP-42 AUTH only for the user's own relays (normal set, own DM inbox, configured search relays;
            // a default search relay never counts, even when the editor stored it with the user's list)
            AuthAllowlist.wire(engine.authPolicy, dmRelays = dmSettings.relays, searchRelays = searchRelayStore.configured, borrowed = foreign::holds)
            val authorRelays = NostrAuthorRelaysRepository(engine, foreign = foreign)
            // the switch is read at use time (no seeded stateIn), so a lookup never runs against the user's choice
            val locator = NostrEventLocator(engine, authorRelays, foreign, enabled = { uiSettings.authorRelays.first() })
            // spec 2026-10-23 §4.3: turning the switch either way clears the marks and retries the open screens (another setting
            // written re-emits the same value: not a change); turned off, no idle foreign relay stays connected (§3, final
            // review I3; held ones go when their round ends)
            engine.backgroundScope.launch {
                uiSettings.authorRelays.distinctUntilChanged().drop(1).collect { on ->
                    if (!on) bestEffort { foreign.dropLingering() }
                    locator.forget()
                }
            }
            // a collector of this session's own lists may outlive it (a screen's scope): logout closes this view first
            val ownLists = SessionOwnListsCache(ownListsCache)
            // spec 2026-11-19: a foreign list's outbox lookup follows the «author relays» switch (read at use time) and the foreign budget
            val lists = NostrListsRepository(
                engine, signer, authorRelays, defaultRelays = PrefsRelayStore.DEFAULT_RELAYS, cache = ownLists, reconcileScope = engine.backgroundScope, gate = startupGate,
                foreign = foreign, authorRelaysEnabled = { uiSettings.authorRelays.first() },
            )
            val feedTabs = NostrFeedTabsRepository(engine, signer, feedTabsCache, defaultRelays = PrefsRelayStore.DEFAULT_RELAYS, reconcileScope = engine.backgroundScope, gate = startupGate)
            // NIP-89 "via Monostr": read from the store at publish time (not a stateIn with a seed), so a
            // note published before the first emission cannot carry the tag against the user's choice
            val publish = NostrPublishRepository(engine, indexRelays = NostrAuthorRelaysRepository.INDEXERS, clientTag = { uiSettings.clientTag.first() }, powBits = { uiSettings.powBits.first() })
            val dmRelays = NostrDmRelaysRepository(engine, authorRelays)
            val dmDetached = DetachedSender(engine.ffiDispatcher, networkGate, torReady = torConnected(tor))
            val dms = DmSync(
                // backlog 99: in Tor mode the recipient's wrap goes over a detached connection (no signer, isolated port)
                NostrDmRepository(
                    engine,
                    // only Tor counts: Unknown and Direct keep the session path (sendGate blocks Unknown anyway)
                    torMode = { networkGate.mode.value is NetworkMode.Tor },
                    torPeerLeg = { wrap, relays -> dmDetached.send(wrap, relays, DetachedSender.DM_CONNECT, torOnly = true, graceMs = DetachedSender.DM_GRACE_MS) },
                ),
                dmRelays, dmStore, dmSettings, publish,
                engine::attachTemporary, engine::detachTemporary, engine.closed, engine.backgroundScope,
                loadWrap = engine::eventById, authOk = engine.authenticated, authOkSeq = engine::authOkSeq,
                authRejected = engine.authRejected, authRejection = engine::authRejection, gate = startupGate, sendGate = sendGate,
            )
            // Live DMs and the unread badge from session start on; silent only, so safe for Amber. The
            // own kind 10050 is settled later, where the user opens the Messages tab or a chat.
            dms.start()
            val mute = NostrMuteRepository(engine, signer, watch = engine.backgroundScope, gate = startupGate)
            // Spec 9.3: the lists filter from the start; silent, so Amber is never asked here (the settings screen asks)
            engine.backgroundScope.launch { bestEffort { mute.ensureLoaded(interactive = false) } }
            // muted accounts are left out at the query, so a flooding account cannot fill the feed's pages
            // the database at once, the relays' latest behind the startup gate and only since the last completed fetch (spec perf2 A5)
            val syncMarks = SyncMarks(File(dir, SyncMarks.FILE_NAME))
            val feed = NostrFeedRepository(engine, excluded = { mute.muted.value }, marks = syncMarks)
            val profiles = NostrProfileRepository(engine, indexRelays = NostrProfileRepository.INDEX_RELAYS)
            val notifications = NostrNotificationsRepository(engine, tips)
            // issue 5: a list kept only elsewhere still counts as current when an index relay delivers it
            val follows = NostrFollowRepository(engine, feed, indexRelays = NostrProfileRepository.OWN_LIST_RELAYS)
            // off (the default) reads no follow list; while the follows are unknown nothing is hidden, or their notes would blink away
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            val powFilter = uiSettings.powFilterBits.distinctUntilChanged().flatMapLatest { bits ->
                if (bits <= 0) flowOf(PowFilter.OFF) else follows.followed().map { f -> if (f == null) PowFilter.OFF else PowFilter(bits, f + signer.pubkey) }
            }.stateIn(engine.backgroundScope, SharingStarted.Eagerly, PowFilter.OFF)
            // one pipeline for the badge, the screen and the tip check; started by the first of them (spec perf 3.6)
            val notificationsStore = NotificationsStore(notifications, profiles, tipSettings, engine.backgroundScope, muted = mute.muted, pow = powFilter, mutedThreads = mute.mutedThreads, me = signer.pubkey, startupGate = startupGate)
            val relaySeen = SyncMarks(File(dir, RELAY_SEEN_FILE))
            val ownReactions = NostrOwnReactions(engine, scope = engine.backgroundScope, gate = startupGate, marks = syncMarks)
            // followers = the largest NIP-45 COUNT of the relays (0.22.4: no proprietary index any more)
            // spec perf2 A6: one COUNT cap per relay for the note and the follower counts
            val countLimiter = CountLimiter()
            val followCounts = NostrFollowCountsRepository(engine, limiter = countLimiter, viewer = signer.pubkey)
            // a follow or unfollow changes the own Following number and the target's Followers: counted afresh
            engine.backgroundScope.launch { follows.changes.collect { followCounts.invalidateAll() } }
            // the user's own list and the lists of whom they follow stay stored; other followers' lists are passing
            val followLists = NostrFollowListsRepository(engine, kept = { feed.followsLocal().toHashSet() + signer.pubkey })
            val blossom = BlossomClient(http, signer, sendGate)
            val mediaServer = uiSettings.mediaServer.stateIn(engine.backgroundScope, SharingStarted.Eagerly, MediaServer.DEFAULT)
            val pictures = OwnPictures(
                mediaServer,
                ownNotes = { feed.notesBy(signer.pubkey, limit = 2000, fetch = false).map { it.repostOf ?: it }.filter { it.author == signer.pubkey } },
                // null (nothing is deleted) when the kind 0 could not be fetched or is no JSON object; an account
                // without any kind 0 therefore keeps its pictures on the server (accepted)
                profileFields = {
                    profiles.rawMetadata(signer.pubkey)
                        ?.takeIf { runCatching { Json.parseToJsonElement(it) is JsonObject }.getOrDefault(false) }
                        ?.let { ProfileJson.fields(it) }
                },
                delete = { server, sha256 -> blossom.delete(server, sha256, silent = false) },
                // spec 2026-10-16 §8: a stored draft still shows its pictures
                draftPictures = { drafts.all(signer.pubkey).flatMap { d -> d.attachments.map { it.sha256 } } },
                storedProfile = { profiles.local(listOf(signer.pubkey)).flatMap { listOfNotNull(it.picture, it.banner) } },
            )
            val conversations = NostrConversationRepository(engine, locator = locator)
            // spec 2026-10-16 §8: a discarded draft or composer picture stays while a published note or the profile shows it
            val media = BlossomMediaUploader(ImagePreparer(context.contentResolver), blossom, uiSettings, engine.backgroundScope, inUse = pictures::published, videos = TransformerVideoPreparer(context))
            val foregroundStarted = java.util.concurrent.atomic.AtomicBoolean(false)
            val startForegroundWork = {
                if (foregroundStarted.compareAndSet(false, true)) {
                    engine.backgroundScope.launch { trackRelaysSeen({ engine.relayStates().first() }, relaySeen) }
                    engine.backgroundScope.launch { bestEffort { ownReactions.load() } }
                    // spec 2026-10-16 §8.2: drafts untouched for 30 days go with their pictures; silent, best effort, off the start path
                    engine.backgroundScope.launch { startupGate.await(); bestEffort { expireDrafts(drafts, signer.pubkey, System.currentTimeMillis() / 1000, media) } }
                }
            }
            // on the engine's scope: a logout during the wait ends it, and nothing starts afterwards
            if (port != null) engine.backgroundScope.launch {
                followTor(tor.get().state, hold = engine::holdConnections, freshRoute = freshRoute) {
                    torRoutes.opened(signer.pubkey)
                    if (engine.openConnections()) {
                        // what the DM run and the marks tried meanwhile found nobody there
                        dms.restart()
                        locator.forget()
                        // an empty answer of a held fetch must not read as "no payment info" for the next hour
                        tips.forgetFetched()
                    }
                }
            }
            return Ready(
                sendGate = sendGate,
                startForegroundWork = startForegroundWork,
                startupGate = startupGate,
                syncMarks = syncMarks,
                relaySeen = relaySeen,
                feedSnapshot = FeedSnapshot(File(dir, "feed-snapshot.json")),
                // they name the members of possibly private lists: noBackupFilesDir, like the own lists' summary
                listSnapshots = ListSnapshots(listSnapshotsDir(signer.pubkey)),
                ownLists = ownLists,
                pubkey = signer.pubkey, signer = signer, engine = engine,
                feed = feed, threads = NostrThreadRepository(engine, conversations, locator = locator), conversations = conversations,
                profiles = profiles, publish = publish,
                tips = tips, notifications = notifications, notificationsStore = notificationsStore, search = NostrSearchRepository(engine, profileUpdates = profiles.updates, scope = engine.backgroundScope),
                bookmarks = NostrBookmarksRepository(engine, signer, watch = engine.backgroundScope, gate = startupGate),
                watchers = WatcherClients(http, signer),
                // a stranger's watcher URL, asked without the user doing anything: only public addresses, also after the lookup
                watcherReceipts = WatcherReceipts(WatcherClients.infoOnly(http.publicAddressesOnly())),
                counts = NostrCountsRepository(engine, engine.backgroundScope, sessionRelays = { engine.readRelayUrls() }, limiter = countLimiter),
                authorRelays = authorRelays,
                dms = dms, dmStore = dmStore, dmRelays = dmRelays,
                quotes = NostrQuoteRepository(engine, locator = locator, foreign = foreign, hintsAllowed = { uiSettings.authorRelays.first() }),
                polls = com.monostr.nostr.repo.NostrPollRepository(engine, powBits = { uiSettings.powBits.first() }, clientTag = { uiSettings.clientTag.first() }, foreign = foreign, hintsAllowed = { uiSettings.authorRelays.first() }),
                addresses = NostrAddressableRepository(engine, foreign = foreign, locator = locator, hintsAllowed = { uiSettings.authorRelays.first() }),
                articleReplies = com.monostr.nostr.repo.NostrArticleRepliesRepository(engine, foreign = foreign, hintsAllowed = { uiSettings.authorRelays.first() }),
                follows = follows,
                followCounts = followCounts,
                followLists = followLists,
                deletions = NoteDeletions(engine.backgroundScope),
                media = media,
                pictures = pictures,
                afterNoteDelete = { n ->
                    pictures.remove(pictures.candidates(n), exceptNoteId = n.id)
                        .takeIf { it > 0 }?.let { pluralText(R.plurals.note_delete_pictures_failed, it) }
                },
                mute = mute,
                ownReactions = ownReactions,
                lists = lists,
                feedTabs = feedTabs,
                locator = locator,
                foreign = foreign,
                powFilter = powFilter,
            )
        } catch (e: Exception) {
            SessionCleanup.closeAll({ dmStore.close() }, { engine.close() })
            throw e
        }
    }

    /**
     * Spec 2026-10-17 §7: publishes the open parts of [pubkey]'s pending setup, and once more after
     * [SETUP_RETRY_MS] when some part failed. A part counts as done when an event of its kind by the
     * account is stored locally or found by one fetch from the session's relays (§11).
     */
    private suspend fun resumeSetup(engine: NostrEngine, pubkey: String, publish: PublishRepository, follows: FollowRepository) {
        if (pendingSetup.load(pubkey) == null) return
        val author = PublicKey.parse(pubkey)
        val publisher = PendingSetupPublisher(pendingSetup, pubkey, publish, follows, dmSettings, exists = { kind ->
            // spec wave 2 B4 call site 10: existence, so the first relay that has one decides
            storedOrFetched(engine, Filter().kind(Kind(kind.toUShort())).author(author).limit(1u), Duration.ofSeconds(5))
        }, sendGate = sendGate)
        if (publisher.run()) return
        delay(SETUP_RETRY_MS)
        publisher.run()
    }

    /**
     * Makes sure receipts can reach the app (spec 3.3): adds [extra] relays (the watcher's) to the
     * app's relay list. Nothing is published: the watcher always sends receipts to its own relays,
     * and a kind 10002 made up here once hid a user's notes that lived only on relays we did not
     * know (bug 54). Publishing a list stays the user's step in Settings. See [SessionRelays.ensure].
     */
    suspend fun ensureRelays(extra: List<String>) = sessionRelays.ensure(extra)

    /** Replaces the relay list and its modes with the user's NIP-65 list. See [SessionRelays.adopt]. */
    suspend fun adoptRelayList(): AdoptResult = sessionRelays.adopt()

    /** Re-runs get_public_key with the full permission set so Amber grants everything in one dialog (spec 6). Nothing in the session changes. */
    suspend fun renewAmberPermissions(): RenewResult {
        val ready = (_state.value as? SessionState.Active)?.ready ?: return RenewResult.NotAmber
        if (ready.signer !is AmberSigner) return RenewResult.NotAmber
        val (ok, response) = bridge.request(Nip55.getPublicKey())
        return renewOutcome(ready.pubkey, ok, response)
    }
}

/** The running session as [SessionRelays] needs it. */
private class ReadyRelays(private val ready: Ready) : RunningRelays {
    override suspend fun relayUrls() = ready.engine.relayUrls()
    override suspend fun removeRelays(urls: List<String>) = ready.engine.removeRelays(urls)
    override suspend fun addRelays(urls: List<String>) = ready.engine.addRelays(urls)
    override suspend fun forgetJoiningOutside(keep: List<String>) = ready.engine.forgetJoiningOutside(keep)
    override suspend fun applyRelayModes(modes: Map<String, RelayMode>) = ready.engine.applyRelayModes(modes)
    override suspend fun connect() = ready.engine.connect()
    override suspend fun restartDms() = ready.dms.restart()
    override suspend fun ownRelayList(fresh: Boolean) =
        if (fresh) ready.engine.ownRelayList(fresh = true, extra = NostrProfileRepository.OWN_LIST_RELAYS) else ready.engine.ownRelayList()
}
