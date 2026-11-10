package com.monostr.app.ui.settings

import com.monostr.app.ui.common.GuardedDropdownMenu
import com.monostr.app.ui.common.GuardedAlertDialog
import com.monostr.app.ui.common.TorSettingsRow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import com.monostr.app.data.GifMode
import com.monostr.app.data.POW_CHOICES
import com.monostr.app.data.POW_DEFAULT
import androidx.compose.ui.platform.LocalUriHandler
import com.monostr.app.data.AmountUnit
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.monostr.app.ui.common.Reactions
import androidx.compose.ui.platform.LocalContext
import com.monostr.app.ui.common.copyToClipboard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import com.monostr.app.translate.TranslateUrl
import com.monostr.app.translate.TranslationMode
import com.monostr.app.translate.TranslationSettingsStore
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.data.Accent
import com.monostr.app.data.DmSettingsStore
import com.monostr.app.data.HintStore
import com.monostr.app.data.MediaServer
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.data.Presets
import com.monostr.app.data.RelayModeStore
import com.monostr.app.data.RelayStore
import com.monostr.nostr.model.RelayMode
import com.monostr.app.data.RelaySuggestion
import com.monostr.app.data.RelaySuggestions
import com.monostr.app.data.SearchRelayStore
import com.monostr.app.data.SecretStore
import com.monostr.app.data.ThemeMode
import com.monostr.app.ui.theme.AccentSeed
import com.monostr.app.ui.theme.Spacing
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.data.UiSettingsStore
import com.monostr.app.data.isValidRelayUrl
import com.monostr.tips.RelayHosts
import com.monostr.app.session.NostrSession
import com.monostr.app.session.RenewResult
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.statusColor
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.common.label
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.repo.NostrProfileRepository
import com.monostr.nostr.RelayList
import com.monostr.nostr.model.Profile
import com.monostr.nostr.model.RelayInfo
import com.monostr.nostr.model.RelayState
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
import kotlinx.coroutines.flow.first
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

    fun remove(url: String) {
        if (_state.value.relays.size <= minimum) {
            _state.update { it.copy(error = uiText(R.string.settings_relay_keep_one)) }
            return
        }
        save(_state.value.relays - url)
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
    private fun save(relays: List<String>, apply: suspend (List<String>) -> Unit = this.apply) {
        val previous = _state.value.relays
        scope.launch {
            _state.update { it.copy(error = null) }
            try {
                apply(relays)
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
    settings: TipSettingsStore,
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
    init {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(section: String? = null, onBack: () -> Unit, onMoneroSetup: () -> Unit, onMoneroUpgrade: () -> Unit = {}, onSupport: () -> Unit = {}, onAbout: () -> Unit = {}, onMuted: () -> Unit = {}, onShowKey: () -> Unit = {}, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val monero by vm.monero.state.collectAsStateWithLifecycle()
    val live by vm.relayStates.collectAsStateWithLifecycle(initialValue = emptyList<RelayInfo>())
    val snackbar = remember { SnackbarHostState() }
    val moneroMessage = monero.message?.asString()
    LaunchedEffect(moneroMessage) { moneroMessage?.let { snackbar.showSnackbar(it); vm.monero.clearMessage() } }
    // back from the setup screen: an address-only setup, a change or a removal there leaves the stored setup alone
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.monero.resumed() }
    val notice by vm.notice.collectAsStateWithLifecycle()
    val noticeText = notice?.asString()
    LaunchedEffect(noticeText) { noticeText?.let { snackbar.showSnackbar(it); vm.clearNotice() } }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val scroll = rememberScrollState()
        // opened at a section (the translate consent's «Settings»): scroll there once it is placed
        var sectionTop by remember { mutableStateOf<Int?>(null) }
        var scrolledToSection by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(sectionTop) {
            val top = sectionTop ?: return@LaunchedEffect
            if (!scrolledToSection) { scrolledToSection = true; scroll.scrollTo(top) }
        }
        Column(Modifier.padding(padding).padding(Spacing.l).fillMaxSize().verticalScroll(scroll)) {
            Text(stringResource(R.string.settings_logged_in_as), style = MaterialTheme.typography.labelLarge)
            Text(Profile.shortPubkey(vm.pubkey), style = MaterialTheme.typography.bodyMedium)
            if (vm.isAmber) {
                val renewBusy by vm.renewBusy.collectAsStateWithLifecycle()
                OutlinedButton(onClick = vm::renewAmber, enabled = !renewBusy, modifier = Modifier.fillMaxWidth().testTag("settings-amber-renew"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.settings_amber_renew)) }
            }
            if (!vm.isAmber) ShowKeyButton(onShowKey)
            Spacer(Modifier.height(Spacing.l))
            val mode by vm.themeMode.collectAsStateWithLifecycle()
            val accent by vm.accent.collectAsStateWithLifecycle()
            val customHue by vm.customHue.collectAsStateWithLifecycle()
            AppearanceSection(mode, accent, customHue, vm::setThemeMode, vm::setAccent, vm::setCustomHue)
            Spacer(Modifier.height(Spacing.xl))
            val quick by vm.quickReactions.collectAsStateWithLifecycle()
            QuickReactionsSection(quick, onChange = vm::setQuickReactions)
            Spacer(Modifier.height(Spacing.xl))
            Text(stringResource(R.string.settings_media), style = MaterialTheme.typography.titleMedium)
            val blur by vm.blurSensitive.collectAsStateWithLifecycle()
            val onTap by vm.mediaOnTap.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_media_blur), stringResource(R.string.settings_media_blur_hint), blur, vm::setBlurSensitive, "settings-media-blur")
            SettingSwitch(stringResource(R.string.settings_media_on_tap), stringResource(R.string.settings_media_on_tap_hint), onTap, vm::setMediaOnTap, "settings-media-on-tap")
            val gifMode by vm.gifMode.collectAsStateWithLifecycle()
            Spacer(Modifier.height(Spacing.s))
            Text(stringResource(R.string.settings_gif), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_gif_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("settings-gif")) {
                GifMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = gifMode == mode, onClick = { vm.setGifMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, GifMode.entries.size, baseShape = MaterialTheme.shapes.medium), modifier = Modifier.testTag("settings-gif-$mode"),
                    ) {
                        Text(stringResource(when (mode) { GifMode.AUTO -> R.string.settings_gif_auto; GifMode.ON_TAP -> R.string.settings_gif_tap; GifMode.OFF -> R.string.settings_gif_off }))
                    }
                }
            }
            Spacer(Modifier.height(Spacing.s))
            val previews by vm.linkPreviews.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_link_previews), stringResource(R.string.settings_link_previews_hint), previews, vm::setLinkPreviews, "settings-link-previews")
            val mediaServer by vm.mediaServer.collectAsStateWithLifecycle()
            Spacer(Modifier.height(Spacing.s))
            MediaServerSetting(mediaServer, onSave = vm::setMediaServer, onReset = { vm.resetMediaServer() })
            Spacer(Modifier.height(Spacing.xl))
            // Privacy: switches about what the app tells other parties.
            Text(
                stringResource(R.string.settings_privacy), style = MaterialTheme.typography.titleMedium,
                modifier = if (section == SettingsSection.PRIVACY) Modifier.onPlaced { sectionTop = it.positionInParent().y.toInt() } else Modifier,
            )
            val askLinks by vm.askLinks.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_ask_links), stringResource(R.string.settings_ask_links_hint), askLinks, vm::setAskLinks, "settings-ask-links")
            TorSettingsRow()
            Spacer(Modifier.height(Spacing.xl))
            val translateMode by vm.translateMode.collectAsStateWithLifecycle()
            val translateUrl by vm.translateUrl.collectAsStateWithLifecycle()
            TranslationSection(
                translateMode, translateUrl, onMode = { vm.setTranslateMode(it) }, onSaveUrl = vm::setTranslateUrl,
                modifier = if (section == SettingsSection.TRANSLATION) Modifier.onPlaced { sectionTop = it.positionInParent().y.toInt() } else Modifier,
            )
            Spacer(Modifier.height(Spacing.xl))
            val relaySeen by vm.relaySeen.collectAsStateWithLifecycle()
            val relayModes by vm.relayModes.collectAsStateWithLifecycle()
            RelayListEditor(
                title = stringResource(R.string.settings_relays), relays = state.relays, live = live, error = state.error,
                onAdd = vm.controller::add, onRemove = vm.controller::remove, tagPrefix = "settings-relay", seen = relaySeen,
                modes = relayModes, onMode = vm::setRelayMode,
            )
            SuggestedRelays(suggestions = RelaySuggestions.PAID_MONERO.filter { it.url !in state.relays }, onAdd = { vm.controller.add(it) })
            val relayListBusy by vm.relayListBusy.collectAsStateWithLifecycle()
            val relayDiff by vm.relayListDiff.collectAsStateWithLifecycle()
            relayDiff?.let { RelayListDiffHint(it) }
            RelayListActions(
                isDefault = isDefaultRelayList(state.relays, modes = relayModes), busy = relayListBusy || monero.busy,
                onPublish = { vm.publishRelays() }, onAdopt = vm.monero::adoptRelayList, onReset = { vm.resetRelays() },
            )
            val authorRelays by vm.authorRelays.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_author_relays), stringResource(R.string.settings_author_relays_hint), authorRelays, vm::setAuthorRelays, "settings-author-relays")
            val clientTag by vm.clientTag.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_client_tag), stringResource(R.string.settings_client_tag_hint), clientTag, vm::setClientTag, "settings-client-tag")
            val pow by vm.powBits.collectAsStateWithLifecycle()
            Spacer(Modifier.height(Spacing.s))
            Text(stringResource(R.string.settings_pow), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_pow_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("settings-pow")) {
                POW_CHOICES.forEachIndexed { i, bits ->
                    SegmentedButton(
                        selected = pow == bits, onClick = { vm.setPowBits(bits) },
                        shape = SegmentedButtonDefaults.itemShape(i, POW_CHOICES.size, baseShape = MaterialTheme.shapes.medium), modifier = Modifier.testTag("settings-pow-$bits"),
                    ) { Text(if (bits == 0) stringResource(R.string.settings_pow_off) else stringResource(R.string.settings_pow_bits, bits)) }
                }
            }
            val powFilter by vm.powFilterBits.collectAsStateWithLifecycle()
            Spacer(Modifier.height(Spacing.l))
            Text(stringResource(R.string.settings_pow_filter), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_pow_filter_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("settings-pow-filter")) {
                POW_CHOICES.forEachIndexed { i, bits ->
                    SegmentedButton(
                        selected = powFilter == bits, onClick = { vm.setPowFilterBits(bits) },
                        shape = SegmentedButtonDefaults.itemShape(i, POW_CHOICES.size, baseShape = MaterialTheme.shapes.medium), modifier = Modifier.testTag("settings-pow-filter-$bits"),
                    ) { Text(if (bits == 0) stringResource(R.string.settings_pow_off) else stringResource(R.string.settings_pow_bits, bits)) }
                }
            }
            Spacer(Modifier.height(Spacing.xl))
            val dmState by vm.dm.state.collectAsStateWithLifecycle()
            val dmPreviewName by vm.dmPreviewName.collectAsStateWithLifecycle()
            val dmPreviewText by vm.dmPreviewText.collectAsStateWithLifecycle()
            val dmMissingDismissed by vm.dmMissingDismissed.collectAsStateWithLifecycle()
            val dmSecondDismissed by vm.dmSecondDismissed.collectAsStateWithLifecycle()
            MessagesSection(
                dmState = dmState, onAdd = vm.dm::add, onRemove = vm.dm::remove,
                previewName = dmPreviewName, onPreviewName = vm::setDmPreviewName,
                previewText = dmPreviewText, onPreviewText = vm::setDmPreviewText,
                showMissing = !dmMissingDismissed && DmInboxHints.missing(dmState.relays),
                showSecond = !dmSecondDismissed && DmInboxHints.needsSecond(dmState.relays),
                onReplace = vm.dm::replace, onDismissHint = vm::dismissDmHint,
            )
            Spacer(Modifier.height(Spacing.xl))
            val searchState by vm.search.state.collectAsStateWithLifecycle()
            RelayListEditor(
                title = stringResource(R.string.settings_search_relays), relays = searchState.relays, live = null, error = searchState.error,
                onAdd = vm.search::add, onRemove = vm.search::remove, tagPrefix = "settings-search-relay",
                hint = stringResource(R.string.settings_search_relays_hint),
                footer = { TextButton(onClick = vm::resetSearchRelays, modifier = Modifier.testTag("settings-search-relays-reset")) { Text(stringResource(R.string.settings_search_relays_reset)) } },
            )
            Spacer(Modifier.height(Spacing.xl))
            Text(stringResource(R.string.settings_monero), style = MaterialTheme.typography.titleMedium)
            val setup = monero.setup
            val payto = monero.paytoOnly
            if (setup == null && payto != null) {
                val copied = stringResource(R.string.note_copied)
                val context = LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_monero_payto, payto.take(8), payto.takeLast(6)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false).testTag("settings-monero-payto"))
                    TextButton(onClick = { copyToClipboard(context, payto, copied) }, modifier = Modifier.testTag("settings-monero-copy-payto")) { Text(stringResource(R.string.action_copy_address)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedButton(onClick = onMoneroSetup, modifier = Modifier.testTag("settings-monero-payto-change"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.settings_monero_payto_change)) }
                    OutlinedButton(onClick = vm.monero::removePayto, enabled = !monero.busy, modifier = Modifier.testTag("settings-monero-payto-remove"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.settings_monero_payto_remove)) }
                }
                Card(Modifier.fillMaxWidth().testTag("settings-monero-upgrade"), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(R.string.setup_upgrade_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.setup_upgrade_text), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onMoneroUpgrade, modifier = Modifier.fillMaxWidth().testTag("settings-monero-upgrade-action"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.setup_upgrade_action)) }
                    }
                }
            } else if (setup == null) {
                val announced = monero.announced
                if (announced != null) Text(stringResource(R.string.settings_monero_announced, announced.take(8), announced.takeLast(6)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("settings-monero-announced"))
                else Text(stringResource(R.string.settings_monero_not_set_up), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onMoneroSetup, modifier = Modifier.fillMaxWidth().testTag("settings-monero-setup"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.settings_monero_set_up)) }
            } else {
                // launch feedback: people expected tips at a Monero address in their bio; say plainly where they go
                val copied = stringResource(R.string.note_copied)
                val context = LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_monero_address, setup.address.take(8), setup.address.takeLast(6), setup.network), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false).testTag("settings-monero-address"))
                    TextButton(onClick = { copyToClipboard(context, setup.address, copied) }, modifier = Modifier.testTag("settings-monero-copy-address")) { Text(stringResource(R.string.action_copy_address)) }
                }
                Text(stringResource(R.string.settings_monero_wallet_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("settings-monero-wallet-hint"))
                if (setup.isPersonal) {
                    val name = com.monostr.nostr.Npub.short(setup.watcherPubkey)
                    val line = when (val l = monero.personalLine) {
                        PersonalWatcherLine.Unknown -> stringResource(R.string.settings_monero_personal, name)
                        PersonalWatcherLine.NotFound -> stringResource(R.string.settings_monero_personal_unknown, name)
                        PersonalWatcherLine.Online -> stringResource(R.string.settings_monero_personal_online, name)
                        PersonalWatcherLine.Mismatch -> stringResource(R.string.settings_monero_personal_mismatch, name)
                        is PersonalWatcherLine.Offline -> stringResource(R.string.settings_monero_personal_offline, name, com.monostr.app.ui.monero.localTime(l.since))
                    }
                    Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("settings-monero-watcher"))
                } else Text(stringResource(R.string.settings_monero_watcher, setup.watcherUrl), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("settings-monero-watcher"))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedButton(onClick = onMoneroSetup, modifier = Modifier.testTag("settings-monero-setup"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.settings_monero_switch_watcher)) }
                    OutlinedButton(onClick = vm.monero::disable, enabled = !monero.busy, modifier = Modifier.testTag("settings-monero-disable"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.settings_monero_disable)) }
                }
            }
            if (setup != null) {
                SettingSwitch(
                    stringResource(R.string.settings_monero_amounts_public),
                    stringResource(
                        when {
                            monero.amountsSupported == false -> R.string.settings_monero_amounts_unsupported
                            monero.amountsSupported == null && !setup.amountsHidden -> R.string.settings_monero_amounts_checking
                            else -> R.string.settings_monero_amounts_public_hint
                        },
                    ),
                    !setup.amountsHidden, vm.monero::setAmountsPublic, "settings-monero-amounts",
                    enabled = (monero.amountsSupported == true || setup.amountsHidden) && !monero.busy,
                )
            }
            if (monero.hasViewKey) {
                TextButton(onClick = vm.monero::deleteViewKey) { Text(stringResource(R.string.settings_monero_delete_view_key)) }
            }
            Spacer(Modifier.height(Spacing.l))
            Text(stringResource(R.string.settings_amount_unit), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("settings-amount-unit")) {
                AmountUnit.entries.forEachIndexed { i, u ->
                    SegmentedButton(
                        selected = monero.unit == u, onClick = { vm.monero.setUnit(u) },
                        shape = SegmentedButtonDefaults.itemShape(i, AmountUnit.entries.size, baseShape = MaterialTheme.shapes.medium), modifier = Modifier.testTag("settings-amount-unit-${u.name.lowercase()}"),
                    ) { Text(u.label) }
                }
            }
            Text(stringResource(R.string.settings_amount_unit_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.l))
            Text(stringResource(R.string.settings_presets, monero.unit.label), style = MaterialTheme.typography.titleMedium)
            monero.presets.forEachIndexed { i, v ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = v, onValueChange = { nv -> vm.monero.editPreset(i, nv) }, singleLine = true,
                        label = { Text(stringResource(R.string.settings_preset_n, i + 1)) }, isError = monero.error != null, modifier = Modifier.weight(1f).testTag("settings-preset-$i"),
                        shape = MaterialTheme.shapes.medium,
                    )
                    IconButton(onClick = { vm.monero.removePreset(i) }, enabled = monero.presets.size > 1, modifier = Modifier.testTag("settings-preset-remove-$i")) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_preset_remove))
                    }
                }
            }
            TextButton(onClick = vm.monero::addPreset, enabled = monero.canAddPreset, modifier = Modifier.testTag("settings-preset-add")) { Text(stringResource(R.string.settings_preset_add)) }
            monero.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { vm.monero.savePresets() }, modifier = Modifier.fillMaxWidth().testTag("settings-presets-save"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.settings_presets_save)) }
            Spacer(Modifier.height(Spacing.xl))
            OutlinedButton(onClick = onMuted, modifier = Modifier.fillMaxWidth().testTag("settings-muted"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.mute_settings_title)) }
            Spacer(Modifier.height(Spacing.s))
            OutlinedButton(onClick = onSupport, modifier = Modifier.fillMaxWidth().testTag("settings-support"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.support_title)) }
            Spacer(Modifier.height(Spacing.s))
            OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth().testTag("settings-about"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.about_title)) }
            Spacer(Modifier.height(Spacing.xl))
            val logoutBusy by vm.logoutBusy.collectAsStateWithLifecycle()
            OutlinedButton(onClick = { vm.logout(onBack) }, enabled = !logoutBusy, modifier = Modifier.fillMaxWidth().testTag("settings-logout"), shape = MaterialTheme.shapes.medium) {
                if (logoutBusy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.s))
                }
                Text(stringResource(R.string.settings_logout))
            }
        }
    }
}

/** 0.24.3: says how the app's relays differ from the published NIP-65 list, right above the button that publishes them. */
@Composable
fun RelayListDiffHint(diff: RelayListDiff) {
    val lines = buildList {
        if (diff.noneFound) add(stringResource(R.string.relays_none_found))
        if (diff.unpublished > 0) add(pluralStringResource(R.plurals.relays_unpublished, diff.unpublished, diff.unpublished))
        if (diff.missing > 0) add(pluralStringResource(R.plurals.relays_missing_here, diff.missing, diff.missing))
    }
    Spacer(Modifier.height(Spacing.s))
    Text(lines.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("settings-relay-diff"))
    Spacer(Modifier.height(Spacing.xs))
}

/** The name of a relay's mode. */
@Composable
private fun relayModeLabel(mode: RelayMode): String = stringResource(
    when (mode) { RelayMode.BOTH -> R.string.relay_mode_both; RelayMode.READ -> R.string.relay_mode_read; RelayMode.WRITE -> R.string.relay_mode_write },
)

/** A compact menu on the relay row: a 48 dp button naming the relay and its mode, the three modes to choose from. */
@Composable
private fun RelayModeMenu(url: String, mode: RelayMode, onMode: (String, RelayMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.relay_mode_description, relayShownUrl(url), relayModeLabel(mode))
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("relay-mode-$url")) {
            Icon(Icons.Filled.MoreVert, contentDescription = description)
        }
        GuardedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // the row may shorten the address: here it reads in full
            Text(
                url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s).testTag("relay-mode-$url-address"),
            )
            RelayMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(relayModeLabel(m), fontWeight = if (m == mode) FontWeight.Bold else null) },
                    onClick = { open = false; if (m != mode) onMode(url, m) },
                    modifier = Modifier.testTag("relay-mode-$url-$m"),
                )
            }
        }
    }
}

/**
 * One line with the middle left out: the end of a host says whose it is (`relay.damus.io.evil.example`), so it is not cut
 * off there. A long press shows the whole address, a screen reader always reads it in full.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RelayAddress(url: String, tagPrefix: String) {
    var full by remember(url) { mutableStateOf(false) }
    Text(
        if (full) url else relayShownUrl(url), style = MaterialTheme.typography.bodyMedium,
        maxLines = if (full) Int.MAX_VALUE else 1, overflow = if (full) TextOverflow.Clip else TextOverflow.MiddleEllipsis,
        modifier = Modifier
            .combinedClickable(onClick = {}, onLongClick = { full = !full })
            .clearAndSetSemantics { contentDescription = url }
            .testTag("$tagPrefix-address-${url.hashCode().toUInt()}"),
    )
}

/** One relay list with status lines (optional), an add field and a remove icon per row. */
@Composable
private fun RelayListEditor(
    title: String,
    relays: List<String>,
    live: List<RelayInfo>?,
    error: UiText?,
    onAdd: (String) -> Boolean,
    onRemove: (String) -> Unit,
    tagPrefix: String,
    hint: String? = null,
    /** 0.24.3: epoch seconds each relay was last seen connected (the feed relays only). */
    seen: Map<String, Long> = emptyMap(),
    /** The main list only: each relay's stored mode (a relay not in it reads and writes) and the change of it; null hides the control. */
    modes: Map<String, RelayMode>? = null,
    onMode: ((String, RelayMode) -> Unit)? = null,
    footer: @Composable () -> Unit = {},
) {
    var newRelay by rememberSaveable(tagPrefix) { mutableStateOf("") }
    Text(title, style = MaterialTheme.typography.titleMedium)
    hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    relays.forEach { url ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RelayAddress(url, tagPrefix)
                // a relay that reads and writes needs no note; a one-way relay says what it does
                val mode = modes?.get(url)
                if (mode != null && mode != RelayMode.BOTH) Text(relayModeLabel(mode), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (live != null) {
                    val info = relayInfoFor(live, url)
                    val line = relayStatusLine(info, seen[(info?.url ?: url).trimEnd('/')], System.currentTimeMillis() / 1000, onion = RelayHosts.isOnionHost(url))
                    val parts = listOfNotNull(
                        line.state.asString(),
                        line.lastSeenAgo?.let { stringResource(R.string.relay_last_seen, it.asString()) },
                        if (line.neverSeen) stringResource(R.string.relay_never_seen) else null,
                    )
                    Text(
                        parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = (info?.state ?: RelayState.DISCONNECTED).statusColor(),
                        modifier = Modifier.testTag("$tagPrefix-status-${url.hashCode().toUInt()}"),
                    )
                }
            }
            RelaySignupButton(url)
            if (modes != null && onMode != null) RelayModeMenu(url, modes[url] ?: RelayMode.BOTH, onMode)
            IconButton(onClick = { onRemove(url) }, modifier = Modifier.testTag("$tagPrefix-remove-${url.hashCode().toUInt()}")) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_remove)) }
        }
        HorizontalDivider()
    }
    Spacer(Modifier.height(Spacing.s))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newRelay, onValueChange = { newRelay = it }, modifier = Modifier.weight(1f).testTag("$tagPrefix-input"), singleLine = true,
            label = { Text(stringResource(R.string.settings_relay_placeholder)) }, isError = error != null, supportingText = { error?.let { Text(it.asString()) } },
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.width(Spacing.s))
        OutlinedButton(onClick = { if (onAdd(newRelay)) newRelay = "" }, enabled = newRelay.isNotBlank(), modifier = Modifier.testTag("$tagPrefix-add"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.action_add)) }
    }
    footer()
}

/** DM inbox relays (kind 10050) plus the two notification-preview switches Task 10's notifier reads. */
@Composable
private fun MessagesSection(
    dmState: SettingsUiState,
    onAdd: (String) -> Boolean,
    onRemove: (String) -> Unit,
    previewName: Boolean,
    onPreviewName: (Boolean) -> Unit,
    previewText: Boolean,
    onPreviewText: (Boolean) -> Unit,
    showMissing: Boolean,
    showSecond: Boolean,
    onReplace: (String, String) -> Unit,
    onDismissHint: (String) -> Unit,
) {
    Text(stringResource(R.string.settings_messages), style = MaterialTheme.typography.titleMedium)
    if (showMissing) {
        var choose by remember { mutableStateOf(false) }
        InboxHintCard(
            text = stringResource(R.string.dm_relay_hint_missing), tag = "dm-hint-missing",
            onDismiss = { onDismissHint(DmInboxHints.HINT_MISSING) }, dismissTag = "dm-hint-dismiss-missing",
        ) {
            TextButton(
                onClick = { if (DmInboxHints.full(dmState.relays)) choose = true else onAdd(DmInboxHints.MONOSTR) },
                modifier = Modifier.testTag("dm-hint-add"),
            ) { Text(stringResource(R.string.dm_relay_hint_add)) }
        }
        if (choose) {
            GuardedAlertDialog(
                onDismissRequest = { choose = false },
                title = { Text(stringResource(R.string.dm_relay_hint_replace)) },
                text = {
                    Column {
                        dmState.relays.forEachIndexed { i, url ->
                            TextButton(onClick = { choose = false; onReplace(url, DmInboxHints.MONOSTR) }, modifier = Modifier.testTag("dm-hint-replace-$i")) { Text(url.removePrefix("wss://")) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { choose = false }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }
    if (showSecond) {
        InboxHintCard(
            text = stringResource(R.string.dm_relay_hint_second), tag = "dm-hint-second",
            onDismiss = { onDismissHint(DmInboxHints.HINT_SECOND) }, dismissTag = "dm-hint-dismiss-second",
        ) {
            DmInboxHints.suggestions(dmState.relays).forEachIndexed { i, url ->
                TextButton(onClick = { onAdd(url) }, modifier = Modifier.testTag("dm-hint-suggest-$i")) { Text(url.removePrefix("wss://")) }
            }
        }
    }
    RelayListEditor(
        title = stringResource(R.string.settings_dm_relays), relays = dmState.relays, live = null, error = dmState.error,
        onAdd = onAdd, onRemove = onRemove, tagPrefix = "settings-dm-relay",
        hint = stringResource(R.string.settings_dm_relays_hint),
    )
    SettingSwitch(stringResource(R.string.settings_dm_preview_name), "", previewName, onPreviewName, "settings-dm-preview-name")
    SettingSwitch(stringResource(R.string.settings_dm_preview_text), "", previewText, onPreviewText, "settings-dm-preview-text")
}

/** A titled switch row; the row itself (not just the thumb) toggles the value. */
@Composable
private fun SettingSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = Spacing.s).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Spacing.l))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** "Unlock" for a paid Monero relay: opens its page, where a one-time payment adds the npub to its write list. */
@Composable
private fun RelaySignupButton(url: String) {
    val page = RelaySuggestions.signupUrl(url) ?: return
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { runCatching { uriHandler.openUri(page) } }, modifier = Modifier.testTag("relay-signup-${url.hashCode().toUInt()}")) {
        Text(stringResource(R.string.settings_relay_signup))
    }
}

/** Optional relays with a one-tap add; hidden once every suggestion is in the list. */
@Composable
private fun SuggestedRelays(suggestions: List<RelaySuggestion>, onAdd: (String) -> Unit) {
    if (suggestions.isEmpty()) return
    Spacer(Modifier.height(Spacing.l))
    Text(stringResource(R.string.settings_suggested_relays), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.settings_suggested_relays_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    suggestions.forEach { s ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.url, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.settings_suggested_relay_fee, s.feeXmr), style = MaterialTheme.typography.labelSmall)
            }
            RelaySignupButton(s.url)
            IconButton(onClick = { onAdd(s.url) }, modifier = Modifier.testTag("settings-suggested-add-${s.url.hashCode().toUInt()}")) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add)) }
        }
        HorizontalDivider()
    }
}

/** Spec 6: one inbox hint with its actions and "Hide" (per account, once). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxHintCard(text: String, tag: String, onDismiss: () -> Unit, dismissTag: String, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = Spacing.s).testTag(tag), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(Spacing.m)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                actions()
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(dismissTag)) { Text(stringResource(R.string.dm_relay_hint_dismiss)) }
            }
        }
    }
}

/**
 * Settings → "Show key" for a key login (spec 2026-10-17 §6/§11): the warning alone opens the key screen.
 * No screen-lock prompt (user decision 2026-10-17: the old confirm-credential screen often offers only the
 * PIN, which locked people out of their own key).
 */
@Composable
private fun ShowKeyButton(onShowKey: () -> Unit) {
    var warn by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { warn = true }, modifier = Modifier.fillMaxWidth().testTag("settings-show-key"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.settings_show_key)) }
    if (warn) {
        GuardedAlertDialog(
            onDismissRequest = { warn = false },
            text = { Text(stringResource(R.string.settings_show_key_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    warn = false
                    ShowKeyGate.grant()
                    onShowKey()
                }, modifier = Modifier.testTag("show-key-confirm")) { Text(stringResource(R.string.settings_show_key_confirm)) }
            },
            dismissButton = { TextButton(onClick = { warn = false }) { Text(stringResource(R.string.settings_show_key_cancel)) } },
            modifier = Modifier.testTag("show-key-warning"),
        )
    }
}
