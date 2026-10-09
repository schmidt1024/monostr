package com.monostr.app.ui.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.data.Accent
import com.monostr.app.data.DmSettingsStore
import com.monostr.app.data.HintStore
import com.monostr.app.data.MediaServer
import com.monostr.app.data.PrefsRelayStore
import com.monostr.app.data.Presets
import com.monostr.app.data.RelayStore
import com.monostr.app.data.RelaySuggestion
import com.monostr.app.data.RelaySuggestions
import com.monostr.app.data.SearchRelayStore
import com.monostr.app.data.SecretStore
import com.monostr.app.data.ThemeMode
import com.monostr.app.ui.theme.AccentSeed
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.data.UiSettingsStore
import com.monostr.app.data.isValidRelayUrl
import com.monostr.app.session.NostrSession
import com.monostr.app.session.RenewResult
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.statusColor
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.common.label
import com.monostr.app.ui.common.uiText
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(val relays: List<String> = emptyList(), val error: UiText? = null)

/** Relay list editing; the session applies changes to the running engine. */
class SettingsController(
    private val relayStore: RelayStore,
    private val apply: suspend (List<String>) -> Unit,
    private val scope: CoroutineScope,
    /** How many relays must stay; 1 for the feed relays, 0 for the search relays (spec 3.4). */
    private val minimum: Int = 1,
) {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun start() { scope.launch { relayStore.relays.collect { r -> _state.update { it.copy(relays = r) } } } }

    /** Returns true when [url] is a valid relay URL (the input field is then cleared). */
    fun add(url: String): Boolean {
        val u = url.trim().trimEnd('/')
        if (!isValidRelayUrl(u)) {
            _state.update { it.copy(error = uiText(R.string.settings_relay_invalid)) }
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
        val current = _state.value.relays
        if (old !in current || u in current) return
        save(current.map { if (it == old) u else it })
    }

    /** Puts [relays] in place of the whole list (back to the default relays); a list below the minimum only shows the error. */
    fun replaceAll(relays: List<String>) {
        val list = relays.map { it.trim().trimEnd('/') }.filter { isValidRelayUrl(it) }.distinct()
        if (list.size < minimum) {
            _state.update { it.copy(error = uiText(R.string.settings_relay_keep_one)) }
            return
        }
        save(list)
    }

    /** Applies [relays]; on failure the previous list stays in state and an error is shown. */
    private fun save(relays: List<String>) {
        val previous = _state.value.relays
        scope.launch {
            _state.update { it.copy(error = null) }
            try {
                apply(relays)
            } catch (e: CancellationException) {
                throw e
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
    private val searchRelayStore: SearchRelayStore,
    settings: TipSettingsStore,
    secrets: SecretStore,
    private val uiSettings: UiSettingsStore,
    private val dmSettings: DmSettingsStore,
    private val hints: HintStore,
) : ViewModel() {
    val controller = SettingsController(relayStore, { session.applyRelays(it) }, viewModelScope)
    private val relays: RelayStore = relayStore
    val search = SettingsController(searchRelayStore, { searchRelayStore.set(it) }, viewModelScope, minimum = 0)
    val dm = SettingsController(
        DmRelayStoreAdapter(dmSettings),
        { list ->
            // spec 11 (Plan 10d): publish first; the list is stored and DmSync restarts only after a relay OK
            applyDmRelays(list, { session.requireReady().publish.dmRelayList(it) }, dmSettings) {
                runCatching { session.requireReady().dms.ownRelaysChanged() }
            }
        },
        viewModelScope,
        minimum = 1,
    )
    val dmMissingDismissed: StateFlow<Boolean> = hints.dismissed(session.requireReady().pubkey, DmInboxHints.HINT_MISSING)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val dmSecondDismissed: StateFlow<Boolean> = hints.dismissed(session.requireReady().pubkey, DmInboxHints.HINT_SECOND)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    fun dismissDmHint(hint: String) = viewModelScope.launch { runCatching { hints.dismiss(session.requireReady().pubkey, hint) } }
    val dmPreviewName: StateFlow<Boolean> = dmSettings.previewName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val dmPreviewText: StateFlow<Boolean> = dmSettings.previewText.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setDmPreviewName(on: Boolean) = viewModelScope.launch { dmSettings.setPreviewName(on) }
    fun setDmPreviewText(on: Boolean) = viewModelScope.launch { dmSettings.setPreviewText(on) }
    val monero = MoneroSettingsController(settings, secrets, session.requireReady().tips, session.requireReady().watchers, { session.adoptRelayList() }, viewModelScope)
    val pubkey: String = session.requireReady().pubkey
    val isAmber: Boolean = session.requireReady().signer is AmberSigner
    private val _notice = MutableStateFlow<UiText?>(null)
    val notice: StateFlow<UiText?> = _notice.asStateFlow()
    private val _renewBusy = MutableStateFlow(false)
    val renewBusy: StateFlow<Boolean> = _renewBusy.asStateFlow()
    val relayStates = session.requireReady().engine.relayStates()
    val themeMode: StateFlow<ThemeMode> = uiSettings.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)
    val accent: StateFlow<Accent> = uiSettings.accent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Accent.DEFAULT)
    val customHue: StateFlow<Float> = uiSettings.customHue.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccentSeed.DEFAULT_HUE)
    val blurSensitive: StateFlow<Boolean> = uiSettings.blurSensitive.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val mediaOnTap: StateFlow<Boolean> = uiSettings.mediaOnTap.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val authorRelays: StateFlow<Boolean> = uiSettings.authorRelays.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { uiSettings.setThemeMode(mode) }
    fun setAccent(accent: Accent) = viewModelScope.launch { uiSettings.setAccent(accent) }
    fun setCustomHue(hue: Float) = viewModelScope.launch { uiSettings.setCustomHue(hue) }
    fun setBlurSensitive(on: Boolean) = viewModelScope.launch { uiSettings.setBlurSensitive(on) }
    fun setMediaOnTap(on: Boolean) = viewModelScope.launch { uiSettings.setMediaOnTap(on) }
    fun setAuthorRelays(on: Boolean) = viewModelScope.launch { uiSettings.setAuthorRelays(on) }
    val clientTag: StateFlow<Boolean> = uiSettings.clientTag.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    fun setClientTag(on: Boolean) = viewModelScope.launch { uiSettings.setClientTag(on) }
    val primalStats: StateFlow<Boolean> = uiSettings.primalStats.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    fun setPrimalStats(on: Boolean) = viewModelScope.launch { uiSettings.setPrimalStats(on) }

    val mediaServer: StateFlow<String> = uiSettings.mediaServer.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaServer.DEFAULT)

    /** False when [input] is no acceptable server address; nothing is stored then (spec 5.4). */
    fun setMediaServer(input: String): Boolean {
        val url = MediaServer.normalize(input) ?: return false
        viewModelScope.launch { uiSettings.setMediaServer(url) }
        return true
    }

    fun resetMediaServer() = viewModelScope.launch { uiSettings.setMediaServer(MediaServer.DEFAULT) }
    fun resetSearchRelays() = viewModelScope.launch { runCatching { searchRelayStore.reset() } }
    private val _relayListBusy = MutableStateFlow(false)
    val relayListBusy: StateFlow<Boolean> = _relayListBusy.asStateFlow()

    /** Back to the app's default relays, e.g. after a published list full of dead relays was adopted. */
    fun resetRelays() = controller.replaceAll(PrefsRelayStore.DEFAULT_RELAYS)

    /** Replaces the user's NIP-65 list by the relays of this app (see [publishRelayList]). */
    fun publishRelays() = viewModelScope.launch {
        if (_relayListBusy.value) return@launch
        _relayListBusy.value = true
        try {
            _notice.value = publishRelayList({ relays.relays.first() }, { session.requireReady().publish.publishRelayList(it) })
        } finally {
            _relayListBusy.value = false
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
    init { controller.start(); search.start(); dm.start(); monero.start() }
    /** Logs out; the session clears its state even when closing the engine fails, so navigation always happens. */
    fun logout(onDone: () -> Unit) = viewModelScope.launch {
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
fun SettingsScreen(onBack: () -> Unit, onMoneroSetup: () -> Unit, onSupport: () -> Unit = {}, onAbout: () -> Unit = {}, onMuted: () -> Unit = {}, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val monero by vm.monero.state.collectAsStateWithLifecycle()
    val live by vm.relayStates.collectAsStateWithLifecycle(initialValue = emptyList<RelayInfo>())
    val snackbar = remember { SnackbarHostState() }
    val moneroMessage = monero.message?.asString()
    LaunchedEffect(moneroMessage) { moneroMessage?.let { snackbar.showSnackbar(it); vm.monero.clearMessage() } }
    val notice by vm.notice.collectAsStateWithLifecycle()
    val noticeText = notice?.asString()
    LaunchedEffect(noticeText) { noticeText?.let { snackbar.showSnackbar(it); vm.clearNotice() } }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.settings_logged_in_as), style = MaterialTheme.typography.labelLarge)
            Text(Profile.shortPubkey(vm.pubkey), style = MaterialTheme.typography.bodyMedium)
            if (vm.isAmber) {
                val renewBusy by vm.renewBusy.collectAsStateWithLifecycle()
                OutlinedButton(onClick = vm::renewAmber, enabled = !renewBusy, modifier = Modifier.fillMaxWidth().testTag("settings-amber-renew")) { Text(stringResource(R.string.settings_amber_renew)) }
            }
            Spacer(Modifier.height(16.dp))
            val mode by vm.themeMode.collectAsStateWithLifecycle()
            val accent by vm.accent.collectAsStateWithLifecycle()
            val customHue by vm.customHue.collectAsStateWithLifecycle()
            AppearanceSection(mode, accent, customHue, vm::setThemeMode, vm::setAccent, vm::setCustomHue)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.settings_media), style = MaterialTheme.typography.titleMedium)
            val blur by vm.blurSensitive.collectAsStateWithLifecycle()
            val onTap by vm.mediaOnTap.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_media_blur), stringResource(R.string.settings_media_blur_hint), blur, vm::setBlurSensitive, "settings-media-blur")
            SettingSwitch(stringResource(R.string.settings_media_on_tap), stringResource(R.string.settings_media_on_tap_hint), onTap, vm::setMediaOnTap, "settings-media-on-tap")
            val mediaServer by vm.mediaServer.collectAsStateWithLifecycle()
            Spacer(Modifier.height(8.dp))
            MediaServerSetting(mediaServer, onSave = vm::setMediaServer, onReset = { vm.resetMediaServer() })
            Spacer(Modifier.height(24.dp))
            RelayListEditor(
                title = stringResource(R.string.settings_relays), relays = state.relays, live = live, error = state.error,
                onAdd = vm.controller::add, onRemove = vm.controller::remove, tagPrefix = "settings-relay",
            )
            SuggestedRelays(suggestions = RelaySuggestions.PAID_MONERO.filter { it.url !in state.relays }, onAdd = { vm.controller.add(it) })
            val relayListBusy by vm.relayListBusy.collectAsStateWithLifecycle()
            RelayListActions(
                isDefault = isDefaultRelayList(state.relays), busy = relayListBusy || monero.busy,
                onPublish = { vm.publishRelays() }, onAdopt = vm.monero::adoptRelayList, onReset = { vm.resetRelays() },
            )
            val authorRelays by vm.authorRelays.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_author_relays), stringResource(R.string.settings_author_relays_hint), authorRelays, vm::setAuthorRelays, "settings-author-relays")
            val clientTag by vm.clientTag.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_client_tag), stringResource(R.string.settings_client_tag_hint), clientTag, vm::setClientTag, "settings-client-tag")
            val primalStats by vm.primalStats.collectAsStateWithLifecycle()
            SettingSwitch(stringResource(R.string.settings_primal_stats), stringResource(R.string.settings_primal_stats_hint), primalStats, vm::setPrimalStats, "settings-primal-stats")
            Spacer(Modifier.height(24.dp))
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
            Spacer(Modifier.height(24.dp))
            val searchState by vm.search.state.collectAsStateWithLifecycle()
            RelayListEditor(
                title = stringResource(R.string.settings_search_relays), relays = searchState.relays, live = null, error = searchState.error,
                onAdd = vm.search::add, onRemove = vm.search::remove, tagPrefix = "settings-search-relay",
                hint = stringResource(R.string.settings_search_relays_hint),
                footer = { TextButton(onClick = vm::resetSearchRelays, modifier = Modifier.testTag("settings-search-relays-reset")) { Text(stringResource(R.string.settings_search_relays_reset)) } },
            )
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.settings_monero), style = MaterialTheme.typography.titleMedium)
            val setup = monero.setup
            if (setup == null) {
                Text(stringResource(R.string.settings_monero_not_set_up), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onMoneroSetup, modifier = Modifier.fillMaxWidth().testTag("settings-monero-setup")) { Text(stringResource(R.string.settings_monero_set_up)) }
            } else {
                Text(stringResource(R.string.settings_monero_address, setup.address.take(8), setup.address.takeLast(6), setup.network), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.settings_monero_watcher, setup.watcherUrl), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onMoneroSetup, modifier = Modifier.testTag("settings-monero-setup")) { Text(stringResource(R.string.settings_monero_switch_watcher)) }
                    OutlinedButton(onClick = vm.monero::disable, enabled = !monero.busy, modifier = Modifier.testTag("settings-monero-disable")) { Text(stringResource(R.string.settings_monero_disable)) }
                }
            }
            if (monero.hasViewKey) {
                TextButton(onClick = vm.monero::deleteViewKey) { Text(stringResource(R.string.settings_monero_delete_view_key)) }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.settings_presets), style = MaterialTheme.typography.titleMedium)
            monero.presets.forEachIndexed { i, v ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = v, onValueChange = { nv -> vm.monero.editPreset(i, nv) }, singleLine = true,
                        label = { Text(stringResource(R.string.settings_preset_n, i + 1)) }, isError = monero.error != null, modifier = Modifier.weight(1f).testTag("settings-preset-$i"),
                    )
                    IconButton(onClick = { vm.monero.removePreset(i) }, enabled = monero.presets.size > 1, modifier = Modifier.testTag("settings-preset-remove-$i")) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_preset_remove))
                    }
                }
            }
            TextButton(onClick = vm.monero::addPreset, enabled = monero.canAddPreset, modifier = Modifier.testTag("settings-preset-add")) { Text(stringResource(R.string.settings_preset_add)) }
            monero.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { vm.monero.savePresets() }, modifier = Modifier.fillMaxWidth().testTag("settings-presets-save")) { Text(stringResource(R.string.settings_presets_save)) }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onMuted, modifier = Modifier.fillMaxWidth().testTag("settings-muted")) { Text(stringResource(R.string.mute_settings_title)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSupport, modifier = Modifier.fillMaxWidth().testTag("settings-support")) { Text(stringResource(R.string.support_title)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth().testTag("settings-about")) { Text(stringResource(R.string.about_title)) }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { vm.logout(onBack) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_logout)) }
        }
    }
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
    footer: @Composable () -> Unit = {},
) {
    var newRelay by rememberSaveable(tagPrefix) { mutableStateOf("") }
    Text(title, style = MaterialTheme.typography.titleMedium)
    hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    val notConnected = stringResource(R.string.relay_not_connected)
    relays.forEach { url ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(url, style = MaterialTheme.typography.bodyMedium)
                if (live != null) {
                    val state = live.firstOrNull { it.url.trimEnd('/') == url }?.state
                    Text(state?.label()?.asString() ?: notConnected, style = MaterialTheme.typography.labelSmall, color = (state ?: RelayState.DISCONNECTED).statusColor())
                }
            }
            IconButton(onClick = { onRemove(url) }, modifier = Modifier.testTag("$tagPrefix-remove-${url.hashCode().toUInt()}")) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_remove)) }
        }
        HorizontalDivider()
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newRelay, onValueChange = { newRelay = it }, modifier = Modifier.weight(1f).testTag("$tagPrefix-input"), singleLine = true,
            label = { Text(stringResource(R.string.settings_relay_placeholder)) }, isError = error != null, supportingText = { error?.let { Text(it.asString()) } },
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { if (onAdd(newRelay)) newRelay = "" }, enabled = newRelay.isNotBlank(), modifier = Modifier.testTag("$tagPrefix-add")) { Text(stringResource(R.string.action_add)) }
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
            AlertDialog(
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
private fun SettingSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Optional relays with a one-tap add; hidden once every suggestion is in the list. */
@Composable
private fun SuggestedRelays(suggestions: List<RelaySuggestion>, onAdd: (String) -> Unit) {
    if (suggestions.isEmpty()) return
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.settings_suggested_relays), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.settings_suggested_relays_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    suggestions.forEach { s ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.url, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.settings_suggested_relay_fee, s.feeXmr), style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { onAdd(s.url) }, modifier = Modifier.testTag("settings-suggested-add-${s.url.hashCode().toUInt()}")) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add)) }
        }
        HorizontalDivider()
    }
}

/** Spec 6: one inbox hint with its actions and "Hide" (per account, once). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxHintCard(text: String, tag: String, onDismiss: () -> Unit, dismissTag: String, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tag)) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                actions()
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(dismissTag)) { Text(stringResource(R.string.dm_relay_hint_dismiss)) }
            }
        }
    }
}
