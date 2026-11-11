package com.monostr.app.ui.settings

import com.monostr.app.data.GifMode
import com.monostr.app.data.POW_DEFAULT
import com.monostr.app.ui.common.Reactions
import com.monostr.app.translate.TranslateUrl
import com.monostr.app.translate.TranslationMode
import com.monostr.app.translate.TranslationSettingsStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.data.Accent
import com.monostr.app.data.DmSettingsStore
import com.monostr.app.data.HintStore
import com.monostr.app.data.MediaServer
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.data.RelayModeStore
import com.monostr.app.data.RelayStore
import com.monostr.nostr.model.RelayMode
import com.monostr.app.data.SearchRelayStore
import com.monostr.app.data.SecretStore
import com.monostr.app.data.ThemeMode
import com.monostr.app.ui.theme.AccentSeed
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.data.UiSettingsStore
import com.monostr.app.data.isValidRelayUrl
import com.monostr.tips.RelayHosts
import com.monostr.app.session.NostrSession
import com.monostr.app.session.RenewResult
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.NostrProfileRepository
import com.monostr.nostr.RelayList
import com.monostr.nostr.nip55.AmberSigner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject


data class SettingsUiState(val relays: List<String> = emptyList(), val error: UiText? = null)

/** Thrown by a [SettingsController]'s apply step for a list it will not take: the main list when no relay would read or none write. */
class RelayListRefused : Exception()

/** Relay list editing; the session applies changes to the running engine. */
class SettingsController(
    private val relayStore: RelayStore,
    private val apply: suspend (List<String>) -> Unit,
    private val scope: CoroutineScope,
    /** How many relays must stay; 1 for the feed relays, 0 for the search relays (spec 3.4). */
    private val minimum: Int = 1,
    /** False for the search and DM lists: a `.onion` address works only in the main relay list (spec 2026-11-06 Tor 7.4). */
    private val onionAllowed: Boolean = true,
) {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** Shows the refusal of a mode change: no relay would be left to read or none to write. */
    fun saveFailed() { _state.update { it.copy(error = uiText(R.string.settings_relay_save_failed)) } }
    fun refuseModes() { _state.update { it.copy(error = uiText(R.string.settings_relay_keep_read_write)) } }

    fun start() { scope.launch { relayStore.relays.collect { r -> _state.update { it.copy(relays = r) } } } }

    /** Returns true when [url] is a valid relay URL (the input field is then cleared). */
    fun add(url: String): Boolean {
        val u = url.trim().trimEnd('/')
        if (!isValidRelayUrl(u)) {
            _state.update { it.copy(error = uiText(R.string.settings_relay_invalid)) }
            return false
        }
        if (!onionAllowed && RelayHosts.isOnionHost(u)) {
            _state.update { it.copy(error = uiText(R.string.settings_relay_onion_main_only)) }
            return false
        }
        if (u in _state.value.relays) return true
        save(_state.value.relays + u)
        return true
    }

    /**
     * False when the list must keep [url] (the minimum): nothing changes then and the error says why. [onRemoved] runs only
     * once the new list was applied (not when the apply step refused it), e.g. to offer «Undo».
     */
    fun remove(url: String, onRemoved: (() -> Unit)? = null): Boolean {
        if (_state.value.relays.size <= minimum) {
            _state.update { it.copy(error = uiText(R.string.settings_relay_keep_one)) }
            return false
        }
        save(_state.value.relays - url, onSuccess = onRemoved)
        return true
    }

    /**
     * UX P2-9: the snackbar's «Undo» puts a removed relay back at its old [index]; a relay already listed again stays as it
     * is. [apply] replaces the controller's apply step where the list carries more than URLs (the main list: the mode).
     */
    fun restore(url: String, index: Int, apply: suspend (List<String>) -> Unit = this.apply) {
        val current = _state.value.relays
        if (url in current) return
        save(current.toMutableList().also { it.add(index.coerceIn(0, it.size), url) }, apply)
    }

    /** Replaces [old] by [new] at the same position (spec 6: a full inbox list); an invalid [new] only shows the error. */
    fun replace(old: String, new: String) {
        val u = new.trim().trimEnd('/')
        if (!isValidRelayUrl(u)) {
            _state.update { it.copy(error = uiText(R.string.settings_relay_invalid)) }
            return
        }
        if (!onionAllowed && RelayHosts.isOnionHost(u)) {
            _state.update { it.copy(error = uiText(R.string.settings_relay_onion_main_only)) }
            return
        }
        val current = _state.value.relays
        if (old !in current || u in current) return
        save(current.map { if (it == old) u else it })
    }

    /** Puts [relays] in place of the whole list (back to the default relays), through [apply] when that step differs; a list below the minimum only shows the error. */
    fun replaceAll(relays: List<String>, apply: suspend (List<String>) -> Unit = this.apply) {
        val list = relays.map { it.trim().trimEnd('/') }.filter { isValidRelayUrl(it) }.distinct()
        if (list.size < minimum) {
            _state.update { it.copy(error = uiText(R.string.settings_relay_keep_one)) }
            return
        }
        save(list, apply)
    }

    /** Applies [relays]; on failure the previous list stays in state and an error is shown. */
    private fun save(relays: List<String>, apply: suspend (List<String>) -> Unit = this.apply, onSuccess: (() -> Unit)? = null) {
        val previous = _state.value.relays
        scope.launch {
            _state.update { it.copy(error = null) }
            try {
                apply(relays)
                onSuccess?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayListRefused) {
                _state.update { it.copy(relays = previous, error = uiText(R.string.settings_relay_keep_read_write)) }
            } catch (e: com.monostr.app.net.SendRefused) {
                _state.update { it.copy(relays = previous, error = uiText(R.string.send_blocked_tor)) }
            } catch (e: Exception) {
                _state.update { it.copy(relays = previous, error = uiText(R.string.settings_relay_save_failed)) }
            }
        }
    }
}

/** Adapts [DmSettingsStore]'s relay list to [RelayStore] so [SettingsController] can drive it. */
class DmRelayStoreAdapter(private val settings: DmSettingsStore) : RelayStore {
    override val relays: Flow<List<String>> = settings.relays
    override suspend fun set(relays: List<String>) = settings.setRelays(relays)
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val session: NostrSession,
    relayStore: RelayStore,
    private val modeStore: RelayModeStore,
    private val searchRelayStore: SearchRelayStore,
    private val settings: TipSettingsStore,
    secrets: SecretStore,
    private val uiSettings: UiSettingsStore,
    private val dmSettings: DmSettingsStore,
    private val hints: HintStore,
    private val translation: TranslationSettingsStore,
    private val sendGate: com.monostr.app.net.SendGate,
) : ViewModel() {
    val controller = SettingsController(relayStore, { if (!session.applyRelays(it)) throw RelayListRefused() }, viewModelScope)
    private val relays: RelayStore = relayStore
    val search = SettingsController(searchRelayStore, { searchRelayStore.set(it) }, viewModelScope, minimum = 0, onionAllowed = false)
    val dm = SettingsController(
        DmRelayStoreAdapter(dmSettings),
        { list ->
            // spec 11 (Plan 10d): publish first; the list is stored and DmSync restarts only after a relay OK
            applyDmRelays(list, { session.requireReady().publish.dmRelayList(it) }, dmSettings, sendGate) {
                runCatching { session.requireReady().dms.ownRelaysChanged() }
            }
        },
        viewModelScope,
        minimum = 1,
        onionAllowed = false,
    )
    val dmMissingDismissed: StateFlow<Boolean> = hints.dismissed(session.requireReady().pubkey, DmInboxHints.HINT_MISSING)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val dmSecondDismissed: StateFlow<Boolean> = hints.dismissed(session.requireReady().pubkey, DmInboxHints.HINT_SECOND)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    fun dismissDmHint(hint: String) = viewModelScope.launch { runCatching { hints.dismiss(session.requireReady().pubkey, hint) } }
    val dmPreviewName: StateFlow<Boolean> = dmSettings.previewName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val dmPreviewText: StateFlow<Boolean> = dmSettings.previewText.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setDmPreviewName(on: Boolean) = viewModelScope.launch { dmSettings.setPreviewName(on) }
    fun setDmPreviewText(on: Boolean) = viewModelScope.launch { dmSettings.setPreviewText(on) }
    val monero = MoneroSettingsController(settings, secrets, session.requireReady().tips, session.requireReady().watchers, { session.adoptRelayList() }, viewModelScope, session.requireReady().pubkey, hints = hints, sendGate = sendGate)
    val pubkey: String = session.requireReady().pubkey
    val isAmber: Boolean = session.requireReady().signer is AmberSigner
    private val _notice = MutableStateFlow<UiText?>(null)
    val notice: StateFlow<UiText?> = _notice.asStateFlow()
    private val _renewBusy = MutableStateFlow(false)
    val renewBusy: StateFlow<Boolean> = _renewBusy.asStateFlow()
    val relayStates = session.requireReady().engine.relayStates()
    /** How many accounts are muted, for the row on Tor & privacy: the session's in-memory set, no extra read. */
    val mutedCount: StateFlow<Int> = session.requireReady().muted.map { it.size }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The overview's own data: the account card, the relay chip and the Monero chip. */
    val ownProfile: StateFlow<Profile> = session.requireReady().profiles.observe(pubkey)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Profile.empty(pubkey))
    val relayStatus: StateFlow<RelayChip?> = combine(relays.relays, relayStates) { l, s -> relayChip(l, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** Owner 4: «Active» also for an announced or payto address, from the Monero state the visit's syncs load anyway. */
    val moneroChip: StateFlow<MoneroChip> = combine(settings.setup, monero.state.map { it.announced to it.payto }.distinctUntilChanged()) { s, (a, p) -> moneroChip(s, a, p) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoneroChip.NONE)
    /** What search may offer (spec 2026-11-11): a few booleans, each flow distinct, so the overview recomposes only when one flips. */
    val searchAvailability: StateFlow<SettingsAvailability> = combine(
        monero.state.map { Triple(it.setup != null, it.paytoOnly != null, it.hasViewKey) }.distinctUntilChanged(),
        translation.mode.map { it == TranslationMode.CUSTOM }.distinctUntilChanged(),
    ) { (setup, payto, viewKey), custom -> SettingsAvailability(isAmber, setup, payto, viewKey, custom) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsAvailability(isAmber = isAmber))
    /** Only the Monero controller's one-shot message, so a page showing snackbars does not recompose with the whole state. */
    val moneroMessage: StateFlow<UiText?> = monero.state.map { it.message }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 0.24.3: when each relay was last seen connected (read once per screen; the session writes it at most every ten minutes). */
    private val _relaySeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    val relaySeen: StateFlow<Map<String, Long>> = _relaySeen.asStateFlow()

    /** 0.24.3: the user's published NIP-65 list as stored or fetched; null until read or when none was found. */
    private val published = MutableStateFlow<RelayList?>(null)
    private val publishedRead = MutableStateFlow(false)

    /** How the app's relays differ from the published list; null while it is being read or when they agree. */
    val relayListDiff: StateFlow<RelayListDiff?> = combine(relays.relays, published, publishedRead, modeStore.modes) { local, list, read, modes -> if (read) relayListDiff(local, list, modes) else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The main list's stored read and write modes; a relay not in it reads and writes. */
    val relayModes: StateFlow<Map<String, RelayMode>> = modeStore.modes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Sets one relay's mode; the session refuses a change that leaves no reader or no writer, and the row says so. */
    fun setRelayMode(url: String, mode: RelayMode) = viewModelScope.launch {
        val accepted = try {
            session.setRelayMode(url, mode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            controller.saveFailed()
            return@launch
        }
        if (!accepted) controller.refuseModes()
    }

    /** "No list found" only counts when a relay was connected to ask: offline the screen says nothing rather than invite a publish over a list it could not read. */
    private fun readPublished() = viewModelScope.launch {
        val engine = session.requireReady().engine
        val asked = runCatching { engine.connectedNormalRelayUrls().isNotEmpty() }.getOrDefault(false)
        val list = runCatching { engine.ownRelayList(fresh = true, extra = NostrProfileRepository.OWN_LIST_RELAYS) }.getOrNull()
        published.value = list
        publishedRead.value = list != null || asked
    }
    val themeMode: StateFlow<ThemeMode> = uiSettings.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)
    val accent: StateFlow<Accent> = uiSettings.accent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Accent.DEFAULT)
    val customHue: StateFlow<Float> = uiSettings.customHue.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccentSeed.DEFAULT_HUE)
    val blurSensitive: StateFlow<Boolean> = uiSettings.blurSensitive.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val mediaOnTap: StateFlow<Boolean> = uiSettings.mediaOnTap.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val gifMode: StateFlow<GifMode> = uiSettings.gifMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GifMode.AUTO)
    fun setGifMode(mode: GifMode) = viewModelScope.launch { uiSettings.setGifMode(mode) }
    val linkPreviews: StateFlow<Boolean> = uiSettings.linkPreviews.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setLinkPreviews(on: Boolean) = viewModelScope.launch { uiSettings.setLinkPreviews(on) }
    val askLinks: StateFlow<Boolean> = uiSettings.linkAlwaysOpen.map { !it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    fun setAskLinks(on: Boolean) = viewModelScope.launch { uiSettings.setLinkAlwaysOpen(!on) }
    val authorRelays: StateFlow<Boolean> = uiSettings.authorRelays.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { uiSettings.setThemeMode(mode) }
    fun setAccent(accent: Accent) = viewModelScope.launch { uiSettings.setAccent(accent) }
    fun setCustomHue(hue: Float) = viewModelScope.launch { uiSettings.setCustomHue(hue) }
    fun setBlurSensitive(on: Boolean) = viewModelScope.launch { uiSettings.setBlurSensitive(on) }
    fun setMediaOnTap(on: Boolean) = viewModelScope.launch { uiSettings.setMediaOnTap(on) }
    fun setAuthorRelays(on: Boolean) = viewModelScope.launch { uiSettings.setAuthorRelays(on) }
    val clientTag: StateFlow<Boolean> = uiSettings.clientTag.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setClientTag(on: Boolean) = viewModelScope.launch { uiSettings.setClientTag(on) }
    val powBits: StateFlow<Int> = uiSettings.powBits.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), POW_DEFAULT)
    fun setPowBits(bits: Int) = viewModelScope.launch { uiSettings.setPowBits(bits) }
    val powFilterBits: StateFlow<Int> = uiSettings.powFilterBits.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    fun setPowFilterBits(bits: Int) = viewModelScope.launch { uiSettings.setPowFilterBits(bits) }
    val quickReactions: StateFlow<List<String>> = uiSettings.quickReactions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Reactions.QUICK)
    fun setQuickReactions(list: List<String>) = viewModelScope.launch { uiSettings.setQuickReactions(list) }

    val mediaServer: StateFlow<String> = uiSettings.mediaServer.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaServer.DEFAULT)

    /** False when [input] is no acceptable server address; nothing is stored then (spec 5.4). */
    fun setMediaServer(input: String): Boolean {
        val url = MediaServer.normalize(input) ?: return false
        viewModelScope.launch { uiSettings.setMediaServer(url) }
        return true
    }

    fun resetMediaServer() = viewModelScope.launch { uiSettings.setMediaServer(MediaServer.DEFAULT) }

    val translateMode: StateFlow<TranslationMode> = translation.mode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranslationMode.MONOSTR)
    val translateUrl: StateFlow<String> = translation.customUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    fun setTranslateMode(mode: TranslationMode) = viewModelScope.launch { translation.setMode(mode) }

    /** False (nothing stored) for an address [TranslateUrl] refuses; blank clears the address. */
    fun setTranslateUrl(input: String): Boolean {
        if (input.isNotBlank() && TranslateUrl.normalize(input) == null) return false
        viewModelScope.launch { translation.setCustomUrl(input) }
        return true
    }
    fun resetSearchRelays() = viewModelScope.launch { runCatching { searchRelayStore.reset() } }
    private val _relayListBusy = MutableStateFlow(false)
    val relayListBusy: StateFlow<Boolean> = _relayListBusy.asStateFlow()

    /** «Undo» of a removed main relay: the list and the relay's former [mode] in one step, so it keeps reading or writing only. */
    fun restoreRelay(url: String, index: Int, mode: RelayMode?) =
        controller.restore(url, index) { if (!session.restoreRelay(it, url, mode)) throw RelayListRefused() }

    /** Back to the app's default relays, every one reading and writing, e.g. after a published list full of dead relays was adopted. */
    fun resetRelays() = controller.replaceAll(PrefsRelayStore.DEFAULT_RELAYS) { if (!session.resetRelays(it)) throw RelayListRefused() }

    /** Replaces the user's NIP-65 list by the relays of this app (see [publishRelayList]). */
    fun publishRelays() = viewModelScope.launch {
        if (_relayListBusy.value) return@launch
        _relayListBusy.value = true
        try {
            _notice.value = publishRelayList({ session.relayEntriesForPublish() }, { session.requireReady().publish.publishRelayList(it) }, sendGate)
        } finally {
            _relayListBusy.value = false
            readPublished()
        }
    }

    fun renewAmber() = viewModelScope.launch {
        if (_renewBusy.value) return@launch
        _renewBusy.value = true
        try {
            _notice.value = when (session.renewAmberPermissions()) {
                RenewResult.Updated -> uiText(R.string.settings_msg_amber_updated)
                RenewResult.WrongKey -> uiText(R.string.settings_msg_amber_wrong_key)
                RenewResult.Cancelled, RenewResult.NotAmber -> uiText(R.string.settings_msg_amber_cancelled)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a malformed answer (e.g. PublicKey.parse failing on garbage) counts as "nothing changed"
            _notice.value = uiText(R.string.settings_msg_amber_cancelled)
        } finally {
            _renewBusy.value = false
        }
    }
    fun clearNotice() { _notice.value = null }
    private var syncsStarted = false

    /**
     * What every Settings visit starts (spec 2026-11-11 "Side effects"): the relay lists, the Monero state with its
     * relay reads, the published NIP-65 list and when each relay was last seen. Called by the overview after its
     * first frame (and by each page, for a deep link that skips the overview's frame); runs once per settings graph.
     */
    fun startSyncs() {
        if (syncsStarted) return
        syncsStarted = true
        controller.start(); search.start(); dm.start(); monero.start()
        readPublished()
        viewModelScope.launch { _relaySeen.value = runCatching { session.requireReady().relaySeen?.all() }.getOrNull().orEmpty() }
    }
    private val _logoutBusy = MutableStateFlow(false)
    /** True while a logout runs (up to a few seconds for the draft pictures); the button waits. */
    val logoutBusy: StateFlow<Boolean> = _logoutBusy.asStateFlow()
    /** Logs out; the session clears its state even when closing the engine fails, so navigation always happens. */
    fun logout(onDone: () -> Unit) = viewModelScope.launch {
        if (_logoutBusy.value) return@launch
        _logoutBusy.value = true
        try {
            session.logout()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // session.logout() already moved to LoggedOut in its finally block
        }
        onDone()
    }
}
