package com.monostr.app.session

import android.content.Context
import com.monostr.app.MainActivity
import com.monostr.app.R
import com.monostr.app.data.DmSettingsStore
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
import com.monostr.app.data.PrimalStats
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
import com.monostr.app.ui.common.pluralText
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.media.MediaReveals
import com.monostr.app.work.DmCheckWorker
import com.monostr.app.work.DmNotifier
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.Signer
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.ProfileJson
import com.monostr.nostr.nip55.AmberSigner
import com.monostr.nostr.nip55.Nip55
import com.monostr.nostr.nip55.SignerBridge
import com.monostr.nostr.repo.AuthorRelaysRepository
import com.monostr.nostr.repo.BookmarksRepository
import com.monostr.nostr.repo.CountsRepository
import com.monostr.nostr.repo.FollowCountsRepository
import com.monostr.nostr.repo.NostrFollowCountsRepository
import com.monostr.nostr.repo.DmRelaysRepository
import com.monostr.nostr.repo.FeedRepository
import com.monostr.nostr.repo.FollowRepository
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
import com.monostr.nostr.repo.NostrThreadRepository
import com.monostr.nostr.repo.NostrSearchRepository
import com.monostr.nostr.repo.NostrTipsRepository
import com.monostr.nostr.repo.NotificationsRepository
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.PublishRepository
import com.monostr.nostr.repo.QuoteRepository
import com.monostr.nostr.repo.SearchRepository
import com.monostr.nostr.repo.ThreadRepository
import com.monostr.nostr.repo.TipsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Everything screens need once a user is logged in. */
class Ready(
    val pubkey: String,
    val signer: Signer,
    val engine: NostrEngine,
    val feed: FeedRepository,
    val threads: ThreadRepository,
    val profiles: ProfileRepository,
    val publish: PublishRepository,
    val tips: TipsRepository,
    val notifications: NotificationsRepository,
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
    fun uiSettings(): UiSettingsStore
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
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private val mutex = Mutex()

    companion object {
        val RESTORE_FAILED: UiText = uiText(R.string.session_restore_failed)

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
                clearStaleSession()
                return@withLock
            }
            _state.value = SessionState.Active(start(signer))
            markFirstLogin(signer.pubkey)
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

    /** Starts the engine first; the secret and session are persisted only once it runs. */
    suspend fun loginLocal(signer: LocalSigner) = mutex.withLock {
        val ready = start(signer)
        persistOrClose(ready) {
            secrets.put(KeystoreSecretStore.SECRET_NSEC, signer.secretHex)
            sessions.save(SessionInfo(signer.pubkey, SignerType.LOCAL, null))
        }
        _state.value = SessionState.Active(ready)
        markFirstLogin(ready.pubkey)
        bestEffort { DmCheckWorker.schedule(context) } // logout cancelled it
    }

    suspend fun loginAmber(pubkey: String, packageName: String) = mutex.withLock {
        val ready = start(AmberSigner(context, packageName, pubkey, bridge))
        persistOrClose(ready) { sessions.save(SessionInfo(pubkey, SignerType.AMBER, packageName)) }
        _state.value = SessionState.Active(ready)
        markFirstLogin(ready.pubkey)
        bestEffort { DmCheckWorker.schedule(context) } // logout cancelled it
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

    suspend fun logout() = mutex.withLock {
        val ready = (_state.value as? SessionState.Active)?.ready
        try {
            ready?.dms?.stop()
            ready?.engine?.close()
        } finally {
            MediaReveals.clear()
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
            }
            clearStaleSession()
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
     * is cleared too, best effort: one failing step does not stop the others.
     */
    private suspend fun clearStaleSession() {
        secrets.remove(KeystoreSecretStore.SECRET_NSEC)
        sessions.clear()
        bestEffort { secrets.remove(KeystoreSecretStore.SECRET_VIEW_KEY) }
        bestEffort { tipSettings.setSetup(null) }
        bestEffort { pendingTips.clear() }
        bestEffort { tipSettings.setAnonymous(true) }
        bestEffort { tipSettings.setNotificationsReadAt(0) }
        bestEffort { tipSettings.setLastTipNotifiedAt(0) }
        // The next account must not publish this one's inbox relays as its own kind 10050, and
        // looks its own list up again.
        bestEffort { dmSettings.setRelays(PrefsDmSettingsStore.DEFAULT_RELAYS) }
        bestEffort { dmSettings.setListAdopted(false) }
        _state.value = SessionState.LoggedOut()
    }

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

    fun requireReady(): Ready = (_state.value as? SessionState.Active)?.ready ?: error("not logged in")

    private fun signerFor(info: SessionInfo): Signer? = when (info.signerType) {
        SignerType.LOCAL -> secrets.get(KeystoreSecretStore.SECRET_NSEC)?.let { LocalSigner.parseOrNull(it) }
        SignerType.AMBER -> info.amberPackage?.let { AmberSigner(context, it, info.pubkey, bridge) }
    }

    private suspend fun start(signer: Signer): Ready {
        val dir = File(context.filesDir, "nostr-${signer.pubkey.take(16)}").apply { mkdirs() }
        val engine = NostrEngine.create(dir.absolutePath, signer, relayStore.relays.first())
        try {
            engine.connect()
        } catch (e: Exception) {
            engine.close()
            throw e
        }
        val dmStore = DmDatabase(context, signer.pubkey)
        // spec 7.3: whatever fails from here on, the DM database and the engine are closed again
        try {
            val tips = NostrTipsRepository(engine)
            val authorRelays = NostrAuthorRelaysRepository(engine)
            // NIP-89 "via Monostr": read from the store at publish time (not a stateIn with a seed), so a
            // note published before the first emission cannot carry the tag against the user's choice
            val publish = NostrPublishRepository(engine, indexRelays = NostrAuthorRelaysRepository.INDEXERS, clientTag = { uiSettings.clientTag.first() })
            val dmRelays = NostrDmRelaysRepository(engine, authorRelays)
            val dms = DmSync(
                NostrDmRepository(engine), dmRelays, dmStore, dmSettings, publish,
                engine::attachTemporary, engine::detachTemporary, engine.closed, engine.backgroundScope,
                loadWrap = engine::eventById, authOk = engine.authenticated, authOkSeq = engine::authOkSeq,
                authRejected = engine.authRejected, authRejection = engine::authRejection,
            )
            // Live DMs and the unread badge from session start on; silent only, so safe for Amber. The
            // own kind 10050 is settled later, where the user opens the Messages tab or a chat.
            dms.start()
            val mute = NostrMuteRepository(engine, signer, watch = engine.backgroundScope)
            // Spec 9.3: the lists filter from the start; silent, so Amber is never asked here (the settings screen asks)
            engine.backgroundScope.launch { bestEffort { mute.ensureLoaded(interactive = false) } }
            // muted accounts are left out at the query, so a flooding account cannot fill the feed's pages
            val feed = NostrFeedRepository(engine, excluded = { mute.muted.value })
            val profiles = NostrProfileRepository(engine, indexRelays = NostrProfileRepository.INDEX_RELAYS)
            val follows = NostrFollowRepository(engine, feed)
            // Primal's cache has every contact list ever indexed: the number other clients show; the relays' COUNTs stay as a floor
            // read per lookup (privacy setting: never a seeded default); a switch counts every profile afresh
            val followCounts = NostrFollowCountsRepository(engine, indexers = listOf(PrimalStats(http, enabled = { uiSettings.primalStats.first() })))
            engine.backgroundScope.launch { uiSettings.primalStats.drop(1).collect { followCounts.invalidateAll() } }
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
            )
            return Ready(
                pubkey = signer.pubkey, signer = signer, engine = engine,
                feed = feed, threads = NostrThreadRepository(engine),
                profiles = profiles, publish = publish,
                tips = tips, notifications = NostrNotificationsRepository(engine, tips), search = NostrSearchRepository(engine),
                bookmarks = NostrBookmarksRepository(engine, signer, watch = engine.backgroundScope),
                watchers = WatcherClients(http, signer),
                counts = NostrCountsRepository(engine, engine.backgroundScope),
                authorRelays = authorRelays,
                dms = dms, dmStore = dmStore, dmRelays = dmRelays,
                quotes = NostrQuoteRepository(engine),
                follows = follows,
                followCounts = followCounts,
                deletions = NoteDeletions(engine.backgroundScope),
                media = BlossomMediaUploader(ImagePreparer(context.contentResolver), blossom, uiSettings, engine.backgroundScope),
                pictures = pictures,
                afterNoteDelete = { n ->
                    pictures.remove(pictures.candidates(n), exceptNoteId = n.id)
                        .takeIf { it > 0 }?.let { pluralText(R.plurals.note_delete_pictures_failed, it) }
                },
                mute = mute,
            )
        } catch (e: Exception) {
            SessionCleanup.closeAll({ dmStore.close() }, { engine.close() })
            throw e
        }
    }

    /**
     * Makes sure receipts can reach the app (spec 3.3): adds [extra] relays (the watcher's) to
     * the user's relay list and publishes a kind 10002 when the user has none yet. Best effort.
     */
    suspend fun ensureRelays(extra: List<String>) {
        val current = relayStore.relays.first()
        val wanted = (current + extra.map { it.trim().trimEnd('/') }.filter { isValidRelayUrl(it) }).distinct()
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
