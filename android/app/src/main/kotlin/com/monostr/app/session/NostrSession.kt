package com.monostr.app.session

import com.monostr.app.data.FeedSnapshot
import com.monostr.app.data.FileOwnListsCache
import com.monostr.app.data.ListSnapshots
import com.monostr.app.data.SessionOwnListsCache
import com.monostr.app.data.accountDir
import com.monostr.tips.RelayHosts
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
import com.monostr.app.data.PrefsDmSettingsStore
import com.monostr.app.data.dm.DmDatabase
import com.monostr.app.data.dm.DmStore
import com.monostr.app.dm.DmSync
import com.monostr.app.data.KeystoreSecretStore
import com.monostr.app.data.MediaServer
import com.monostr.app.data.PendingTipStore
import com.monostr.app.data.RelayStore
import com.monostr.app.data.SearchRelayStore
import com.monostr.app.data.SecretStore
import com.monostr.app.data.SessionInfo
import com.monostr.app.data.SessionStore
import com.monostr.app.data.SignerType
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.data.UiSettingsStore
import com.monostr.app.data.WatcherClients
import com.monostr.app.data.WatcherGateway
import com.monostr.app.data.isValidRelayUrl
import com.monostr.app.media.BlossomClient
import com.monostr.app.media.BlossomMediaUploader
import com.monostr.app.media.ImagePreparer
import com.monostr.app.media.MediaUploader
import com.monostr.app.media.OwnPictures
import com.monostr.app.ui.common.NoteDeletions
import com.monostr.app.ui.common.NoteVisibility
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.profile.ProfileCache
import com.monostr.app.ui.common.pluralText
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.media.MediaReveals
import com.monostr.app.work.DmCheckWorker
import com.monostr.app.work.DmNotifier
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.RefreshGate
import com.monostr.nostr.Signer
import com.monostr.nostr.StartupGate
import com.monostr.nostr.SyncMarks
import com.monostr.nostr.TimedStartupGate
import com.monostr.nostr.model.Note
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import com.monostr.nostr.model.ProfileJson
import com.monostr.nostr.nip55.AmberSigner
import com.monostr.nostr.nip55.Nip55
import com.monostr.nostr.nip55.SignerBridge
import com.monostr.nostr.repo.AuthorRelaysRepository
import com.monostr.nostr.repo.BookmarksRepository
import com.monostr.nostr.repo.CountLimiter
import com.monostr.nostr.repo.CountsRepository
import com.monostr.nostr.repo.FollowCountsRepository
import com.monostr.nostr.repo.NostrFollowCountsRepository
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
import com.monostr.nostr.repo.NostrQuoteRepository
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
    val counts: CountsRepository,
    val authorRelays: AuthorRelaysRepository,
    val dms: DmSync,
    val dmStore: DmStore,
    val dmRelays: DmRelaysRepository,
    val quotes: QuoteRepository,
    val follows: FollowRepository,
    /** Follower and following numbers for the profile header. */
    val followCounts: FollowCountsRepository,
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
    /** Spec perf2 A5: per-relay-set fetch stamps of this account (timestamps only); deleted at logout (D7). */
    val syncMarks: SyncMarks? = null,
    /** 0.24.3: when each relay was last seen connected ([RELAY_SEEN_FILE]); logout deletes it. */
    val relaySeen: SyncMarks? = null,
    /** Spec perf2 D11: the Messages tab's network follows() lookup; runs on the engine's background scope, so leaving the tab never cancels it. */
    val followsRefresh: RefreshGate<String> = RefreshGate(engine.backgroundScope, 60),
) {
    /** Pubkeys of the muted accounts; every note list filters against them (spec 9.3). */
    val muted: StateFlow<Set<String>> get() = mute.muted

    /** The one filter of every note list: deleted notes and muted accounts. */
    val visibility: NoteVisibility = NoteVisibility(deletions, mute.muted)
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
    fun recentSearches(): com.monostr.app.data.RecentSearchesStore
    fun uiSettings(): UiSettingsStore
    fun drafts(): DraftStore
    fun pendingSetup(): PendingSetupStore
    fun feedTabs(): FeedTabsCache
    fun sessions(): SessionStore
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
    private val sessions: SessionStore,
    private val relayStore: RelayStore,
    private val secrets: SecretStore,
    val bridge: SignerBridge,
    private val http: OkHttpClient,
    private val tipSettings: TipSettingsStore,
    private val pendingTips: PendingTipStore,
    private val dmSettings: DmSettingsStore,
    private val hints: HintStore,
    private val uiSettings: UiSettingsStore,
    private val linkPreviews: LinkPreviews,
    private val drafts: DraftStore,
    private val pendingSetup: PendingSetupStore,
    private val feedTabsCache: FeedTabsCache,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private val mutex = Mutex()

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
     */
    suspend fun restore() = mutex.withLock {
        if (_state.value is SessionState.Active) return@withLock
        try {
            val info = sessions.session.first()
            if (info == null) {
                _state.value = SessionState.LoggedOut()
                return@withLock
            }
            val signer = signerFor(info)
            if (signer == null) {
                clearStaleSession(info.pubkey)
                return@withLock
            }
            val ready = start(signer)
            _state.value = SessionState.Active(ready)
            PerfTrace.mark("session ready")
            markFirstLogin(signer.pubkey)
            resumeSetupLater(ready)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = SessionState.LoggedOut(RESTORE_FAILED)
        }
    }

    /** Closes the engine but keeps the persisted session: the next [restore] starts it again. Used by background work that started the engine itself. */
    suspend fun release() = mutex.withLock {
        val ready = (_state.value as? SessionState.Active)?.ready ?: return@withLock
        ready.dms.stop()
        runCatching { ready.engine.close() }
        runCatching { (ready.dmStore as? DmDatabase)?.close() }
        _state.value = SessionState.Loading
    }

    /**
     * Starts the engine first; the secret and session are persisted only once it runs. A pending setup
     * record of this key is an orphan of an interrupted "Let's go" and is dropped (spec 2026-10-17 §7):
     * a pasted key is an existing account, whose profile and follows must never be overwritten.
     */
    suspend fun loginLocal(signer: LocalSigner) = mutex.withLock {
        bestEffort { pendingSetup.remove(signer.pubkey) }
        loginLocalLocked(signer, setup = null)
    }

    /**
     * Spec 2026-10-17 §4: "Let's go". The session starts like the key login; the pending setup record is
     * written with the secret and session, and published in the background once those are stored. A
     * failed login leaves no record.
     */
    suspend fun createAccount(key: NewKey, setup: PendingSetup) = mutex.withLock {
        try {
            loginLocalLocked(LocalSigner(key.secretHex), setup)
        } catch (e: Exception) {
            // also on cancellation (rethrown below): no record without a session
            withContext(NonCancellable) { bestEffort { pendingSetup.remove(key.pubkey) } }
            throw e
        }
    }

    /** [setup] non-null: a new account, its record is persisted right before the secret (spec 2026-10-17 §7). */
    private suspend fun loginLocalLocked(signer: LocalSigner, setup: PendingSetup?) {
        clearOtherAccount(signer.pubkey)
        restoreReadMarks(signer.pubkey)
        val ready = start(signer)
        persistOrClose(ready) {
            if (setup != null) pendingSetup.save(signer.pubkey, setup)
            secrets.put(KeystoreSecretStore.SECRET_NSEC, signer.secretHex)
            sessions.save(SessionInfo(signer.pubkey, SignerType.LOCAL, null))
        }
        activate(ready)
    }

    /** Like [loginLocal], an orphan pending setup record of [pubkey] is dropped first. */
    suspend fun loginAmber(pubkey: String, packageName: String) = mutex.withLock {
        bestEffort { pendingSetup.remove(pubkey) }
        clearOtherAccount(pubkey)
        restoreReadMarks(pubkey)
        val ready = start(AmberSigner(context, packageName, pubkey, bridge))
        persistOrClose(ready) { sessions.save(SessionInfo(pubkey, SignerType.AMBER, packageName)) }
        activate(ready)
    }

    /** The read marks [pubkey] had at its last logout on this device, or "now" for an account new here. */
    private suspend fun restoreReadMarks(pubkey: String) = bestEffort { tipSettings.restoreReadMarks(pubkey, System.currentTimeMillis() / 1000) }

    /** The persisted session runs: shown, scheduled, and its pending setup (if any) goes out. */
    private suspend fun activate(ready: Ready) {
        _state.value = SessionState.Active(ready)
        PerfTrace.mark("session ready")
        markFirstLogin(ready.pubkey)
        bestEffort { DmCheckWorker.schedule(context) } // logout cancelled it
        resumeSetupLater(ready)
    }

    /**
     * A session kept after a failed restore belongs to another account than the one logging in now: its
     * secret, session and per-account state (Monero, DM settings, drafts, pending setup, DM database) go
     * first, as at logout, so the new account inherits nothing.
     */
    private suspend fun clearOtherAccount(pubkey: String) {
        val old = sessions.session.first() ?: return
        if (old.pubkey == pubkey) return
        bestEffort { DmDatabase.delete(context, old.pubkey) }
        clearStaleSession(old.pubkey)
    }

    /**
     * Spec 2026-10-17 §7: a new account's open parts go out at every session start, off the start path;
     * only for a session already persisted, so no record is published for a key that is not stored.
     */
    private fun resumeSetupLater(ready: Ready) {
        ready.engine.backgroundScope.launch { ready.startupGate.await(); bestEffort { resumeSetup(ready.engine, ready.pubkey, ready.publish, ready.follows) } }
    }

    /** Runs [persist]; if it fails, DM sync, engine and DM database of the fresh session are closed before rethrowing. */
    private suspend fun persistOrClose(ready: Ready, persist: suspend () -> Unit) {
        try {
            persist()
        } catch (e: Exception) {
            SessionCleanup.closeAll({ ready.dms.stop() }, { ready.engine.close() }, { (ready.dmStore as? DmDatabase)?.close() })
            throw e
        }
    }

    /**
     * Runs to the end even when the caller is cancelled (e.g. the settings screen is left during the
     * draft cleanup): a cancelled logout would leave the secret gone but the session still Active.
     */
    suspend fun logout() = mutex.withLock { withContext(NonCancellable) { logoutLocked() } }

    private suspend fun logoutLocked() {
        val ready = (_state.value as? SessionState.Active)?.ready
        try {
            // spec 2026-10-16 §8: the drafts leave the device with the account; their pictures go while signer and engine still work
            if (ready != null) bestEffort { withTimeoutOrNull(DRAFT_CLEANUP_MS) { removeDrafts(drafts, ready.pubkey, ready.media) } }
            ready?.dms?.stop()
            ready?.engine?.close()
        } finally {
            MediaReveals.clear()
            // the next account must not see which links this one opened (spec link previews 3.4)
            bestEffort { linkPreviews.clear() }
            // a run in flight is cancelled too; the next login schedules the check again
            bestEffort { DmCheckWorker.cancel(context) }
            bestEffort { DmNotifier(context).cancelAll() }
            // a notification tapped while logged out must not open a chat for the next account
            MainActivity.pendingDm.value = null
            MainActivity.pendingMessages.value = false
            if (ready != null) {
                // The account's DMs leave the device with it (spec §4), best effort.
                bestEffort { (ready.dmStore as? DmDatabase)?.close() }
                bestEffort { DmDatabase.delete(context, ready.pubkey) }
                // the feed's last page leaves the device with the account too
                bestEffort { ready.feedSnapshot?.delete() }
                // spec perf2 D7: nothing about this session stays behind; a re-login does one full fetch
                bestEffort { ready.syncMarks?.delete() }
                bestEffort { ready.relaySeen?.delete() }
                // the engine's scope (reconcile, list jobs) ended above; a collector of the old session on a screen's
                // scope may still load or persist the own lists: closed before the summary goes, it cannot bring it back
                bestEffort { ready.engine.backgroundScope.cancel() } // a no-op unless a step above threw before close
                bestEffort { ready.ownLists?.close() }
                // the pinned lists' pages leave the device with the account too (spec perf 3.7)
                bestEffort { ready.listSnapshots?.deleteAll() }
            }
            clearStaleSession(ready?.pubkey)
        }
    }

    /** The app is in the foreground again: reconnect at once when every relay connection was lost meanwhile. Best effort, a no-op without a session. */
    suspend fun reconnectIfLost() {
        val ready = (_state.value as? SessionState.Active)?.ready ?: return
        bestEffort { ready.engine.reconnectIfLost() }
    }

    /** Applies a new relay list to the running engine: removes dropped relays, adds new ones, reconnects. */
    suspend fun applyRelays(relays: List<String>) = mutex.withLock {
        relayStore.set(relays)
        val ready = (_state.value as? SessionState.Active)?.ready ?: return@withLock
        val current = ready.engine.relayUrls()
        ready.engine.removeRelays(current - relays.toSet())
        ready.engine.addRelays(relays - current.toSet())
        ready.engine.connect()
        // An inbox relay that just left the normal set left the pool with it (the DM run held it as a
        // normal relay, not a temporary one); one that joined was promoted. Take the inbox list again.
        ready.dms.restart()
    }

    /**
     * Drops the persisted session and secret and moves to [SessionState.LoggedOut]. The Monero
     * state (view key, setup, pending tips, notification watermarks) belongs to the account and
     * is cleared too, best effort: one failing step does not stop the others. So are the drafts of
     * [pubkey] (spec 2026-10-16 §8; without a signer their pictures stay on the server) and its
     * pending setup record (spec 2026-10-17 §7) and its pinned tabs (spec 2026-10-18 §3.3), the own lists' summary
     * and the list snapshots (spec perf 3.7).
     */
    private suspend fun clearStaleSession(pubkey: String?) {
        secrets.remove(KeystoreSecretStore.SECRET_NSEC)
        sessions.clear()
        if (pubkey != null) bestEffort { drafts.removeAll(pubkey) }
        if (pubkey != null) bestEffort { pendingSetup.remove(pubkey) }
        if (pubkey != null) bestEffort { feedTabsCache.remove(pubkey) }
        // spec perf 3.7: the own lists' summary (decrypted private members) and the list snapshots, also without a running session
        if (pubkey != null) bestEffort { ownListsCache.remove(pubkey) }
        if (pubkey != null) bestEffort { ListSnapshots(listSnapshotsDir(pubkey)).deleteAll() }
        if (pubkey != null) bestEffort { withContext(Dispatchers.IO) { File(accountDir(context.filesDir, pubkey), SyncMarks.FILE_NAME).delete() } }
        if (pubkey != null) bestEffort { withContext(Dispatchers.IO) { File(accountDir(context.filesDir, pubkey), RELAY_SEEN_FILE).delete() } }
        if (pubkey != null) bestEffort { withContext(Dispatchers.IO) { accountDir(context.noBackupFilesDir, pubkey).delete() } } // only when empty
        bestEffort { secrets.remove(KeystoreSecretStore.SECRET_VIEW_KEY) }
        bestEffort { tipSettings.setSetup(null) }
        bestEffort { pendingTips.clear() }
        bestEffort { tipSettings.setAnonymous(true) }
        // the read marks stay with the account: logging in again must not make every notification unread
        if (pubkey != null) bestEffort { tipSettings.keepReadMarks(pubkey) }
        bestEffort { tipSettings.setNotificationsReadAt(0) }
        bestEffort { tipSettings.setLastTipNotifiedAt(0) }
        // The next account must not publish this one's inbox relays as its own kind 10050, and
        // looks its own list up again.
        bestEffort { dmSettings.setRelays(PrefsDmSettingsStore.DEFAULT_RELAYS) }
        bestEffort { dmSettings.setListAdopted(false) }
        _state.value = SessionState.LoggedOut()
    }

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
        NewKeys.ofSession(sessions.session.first()) { secrets.get(KeystoreSecretStore.SECRET_NSEC) }
    }

    fun requireReady(): Ready = (_state.value as? SessionState.Active)?.ready ?: error("not logged in")

    private fun signerFor(info: SessionInfo): Signer? = when (info.signerType) {
        SignerType.LOCAL -> secrets.get(KeystoreSecretStore.SECRET_NSEC)?.let { LocalSigner.parseOrNull(it) }
        SignerType.AMBER -> info.amberPackage?.let { AmberSigner(context, it, info.pubkey, bridge) }
    }

    private suspend fun start(signer: Signer): Ready {
        val dir = accountDir(context.filesDir, signer.pubkey).apply { mkdirs() }
        val engine = NostrEngine.create(dir.absolutePath, signer, relayStore.relays.first())
        try {
            engine.connect()
        } catch (e: Exception) {
            engine.close()
            throw e
        }
        // spec perf2 A2: on the engine's scope, so logout (engine close) ends the timer and every waiter
        val startupGate = TimedStartupGate(engine.backgroundScope)
        val dmStore = DmDatabase(context, signer.pubkey)
        // spec 7.3: whatever fails from here on, the DM database and the engine are closed again
        try {
            val tips = NostrTipsRepository(engine, gate = startupGate)
            val authorRelays = NostrAuthorRelaysRepository(engine)
            // a collector of this session's own lists may outlive it (a screen's scope): logout closes this view first
            val ownLists = SessionOwnListsCache(ownListsCache)
            val lists = NostrListsRepository(engine, signer, authorRelays, defaultRelays = PrefsRelayStore.DEFAULT_RELAYS, cache = ownLists, reconcileScope = engine.backgroundScope, gate = startupGate)
            val feedTabs = NostrFeedTabsRepository(engine, signer, feedTabsCache, defaultRelays = PrefsRelayStore.DEFAULT_RELAYS, reconcileScope = engine.backgroundScope, gate = startupGate)
            // NIP-89 "via Monostr": read from the store at publish time (not a stateIn with a seed), so a
            // note published before the first emission cannot carry the tag against the user's choice
            val publish = NostrPublishRepository(engine, indexRelays = NostrAuthorRelaysRepository.INDEXERS, clientTag = { uiSettings.clientTag.first() }, powBits = { uiSettings.powBits.first() })
            val dmRelays = NostrDmRelaysRepository(engine, authorRelays)
            val dms = DmSync(
                NostrDmRepository(engine), dmRelays, dmStore, dmSettings, publish,
                engine::attachTemporary, engine::detachTemporary, engine.closed, engine.backgroundScope,
                loadWrap = engine::eventById, authOk = engine.authenticated, authOkSeq = engine::authOkSeq,
                authRejected = engine.authRejected, authRejection = engine::authRejection, gate = startupGate,
            )
            // Live DMs and the unread badge from session start on; silent only, so safe for Amber. The
            // own kind 10050 is settled later, where the user opens the Messages tab or a chat.
            dms.start()
            val mute = NostrMuteRepository(engine, signer, watch = engine.backgroundScope, gate = startupGate)
            // Spec 9.3: the lists filter from the start; silent, so Amber is never asked here (the settings screen asks)
            engine.backgroundScope.launch { bestEffort { mute.ensureLoaded(interactive = false) } }
            // muted accounts are left out at the query, so a flooding account cannot fill the feed's pages
            val feed = NostrFeedRepository(engine, excluded = { mute.muted.value })
            val profiles = NostrProfileRepository(engine, indexRelays = NostrProfileRepository.INDEX_RELAYS)
            val notifications = NostrNotificationsRepository(engine, tips)
            // one pipeline for the badge, the screen and the tip check; started by the first of them (spec perf 3.6)
            val notificationsStore = NotificationsStore(notifications, profiles, tipSettings, engine.backgroundScope, muted = mute.muted, me = signer.pubkey, startupGate = startupGate)
            val follows = NostrFollowRepository(engine, feed)
            // the database at once, the relays' latest behind the startup gate and only since the last completed fetch (spec perf2 A5)
            val syncMarks = SyncMarks(File(dir, SyncMarks.FILE_NAME))
            val relaySeen = SyncMarks(File(dir, RELAY_SEEN_FILE))
            engine.backgroundScope.launch { trackRelaysSeen({ engine.relayStates().first() }, relaySeen) }
            val ownReactions = NostrOwnReactions(engine, scope = engine.backgroundScope, gate = startupGate, marks = syncMarks)
            engine.backgroundScope.launch { bestEffort { ownReactions.load() } }
            // followers = the largest NIP-45 COUNT of the relays (0.22.4: no proprietary index any more)
            // spec perf2 A6: one COUNT cap per relay for the note and the follower counts
            val countLimiter = CountLimiter()
            val followCounts = NostrFollowCountsRepository(engine, limiter = countLimiter)
            // a follow or unfollow changes the own Following number and the target's Followers: counted afresh
            engine.backgroundScope.launch { follows.changes.collect { followCounts.invalidateAll() } }
            val blossom = BlossomClient(http, signer)
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
            val conversations = NostrConversationRepository(engine)
            // spec 2026-10-16 §8: a discarded draft or composer picture stays while a published note or the profile shows it
            val media = BlossomMediaUploader(ImagePreparer(context.contentResolver), blossom, uiSettings, engine.backgroundScope, inUse = pictures::published)
            // spec 2026-10-16 §8.2: drafts untouched for 30 days go with their pictures; silent, best effort, off the start path
            engine.backgroundScope.launch { startupGate.await(); bestEffort { expireDrafts(drafts, signer.pubkey, System.currentTimeMillis() / 1000, media) } }
            return Ready(
                startupGate = startupGate,
                syncMarks = syncMarks,
                relaySeen = relaySeen,
                feedSnapshot = FeedSnapshot(File(dir, "feed-snapshot.json")),
                // they name the members of possibly private lists: noBackupFilesDir, like the own lists' summary
                listSnapshots = ListSnapshots(listSnapshotsDir(signer.pubkey)),
                ownLists = ownLists,
                pubkey = signer.pubkey, signer = signer, engine = engine,
                feed = feed, threads = NostrThreadRepository(engine, conversations), conversations = conversations,
                profiles = profiles, publish = publish,
                tips = tips, notifications = notifications, notificationsStore = notificationsStore, search = NostrSearchRepository(engine, profileUpdates = profiles.updates, scope = engine.backgroundScope),
                bookmarks = NostrBookmarksRepository(engine, signer, watch = engine.backgroundScope, gate = startupGate),
                watchers = WatcherClients(http, signer),
                counts = NostrCountsRepository(engine, engine.backgroundScope, sessionRelays = { engine.relayUrls() }, limiter = countLimiter),
                authorRelays = authorRelays,
                dms = dms, dmStore = dmStore, dmRelays = dmRelays,
                quotes = NostrQuoteRepository(engine),
                follows = follows,
                followCounts = followCounts,
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
        })
        if (publisher.run()) return
        delay(SETUP_RETRY_MS)
        publisher.run()
    }

    /**
     * Makes sure receipts can reach the app (spec 3.3): adds [extra] relays (the watcher's) to
     * the user's relay list and publishes a kind 10002 when the user has none yet. Best effort.
     */
    suspend fun ensureRelays(extra: List<String>) {
        val current = relayStore.relays.first()
        // the watcher names these relays, not the user: only public wss ones join the user's list
        val wanted = (current + extra.map { it.trim().trimEnd('/') }.filter { isValidRelayUrl(it) && RelayHosts.isPublicWss(it) }).distinct()
        if (wanted != current) applyRelays(wanted)
        val ready = (_state.value as? SessionState.Active)?.ready ?: return
        if (ready.engine.ownRelayList() == null) runCatching { ready.publish.relayList(wanted) }
    }

    /** Replaces the relay list with the user's NIP-65 list; false when the user has none. */
    suspend fun adoptRelayList(): Boolean {
        val ready = (_state.value as? SessionState.Active)?.ready ?: return false
        val list = ready.engine.ownRelayList() ?: return false
        val relays = (list.read + list.write).distinct().filter { isValidRelayUrl(it) }
        if (relays.isEmpty()) return false
        applyRelays(relays)
        return true
    }

    /** Re-runs get_public_key with the full permission set so Amber grants everything in one dialog (spec 6). Nothing in the session changes. */
    suspend fun renewAmberPermissions(): RenewResult {
        val ready = (_state.value as? SessionState.Active)?.ready ?: return RenewResult.NotAmber
        if (ready.signer !is AmberSigner) return RenewResult.NotAmber
        val (ok, response) = bridge.request(Nip55.getPublicKey())
        return renewOutcome(ready.pubkey, ok, response)
    }
}
