package com.monostr.app.ui.settings

import com.monostr.app.R
import com.monostr.app.data.KeystoreSecretStore
import com.monostr.app.data.MoneroSetup
import com.monostr.app.data.Presets
import com.monostr.app.data.SecretStore
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.data.WatcherGateway
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.common.userMessage
import com.monostr.nostr.repo.TipsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MoneroSettingsState(
    val setup: MoneroSetup? = null,
    val hasViewKey: Boolean = false,
    /** Presets as XMR decimal strings for the input fields: the saved ones plus the fields the user added (blank until typed). */
    val presets: List<String> = Presets.DEFAULT.map(Presets::format),
    val busy: Boolean = false,
    val error: UiText? = null,
    val message: UiText? = null,
) {
    val canAddPreset: Boolean get() = presets.size < Presets.MAX
}

/** Settings → Monero (spec 6.4–6.6) and the NIP-65 relay adoption. */
class MoneroSettingsController(
    private val settings: TipSettingsStore,
    private val secrets: SecretStore,
    private val tips: TipsRepository,
    private val gateway: WatcherGateway,
    private val adoptRelays: suspend () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(MoneroSettingsState())
    val state: StateFlow<MoneroSettingsState> = _state.asStateFlow()

    fun start() {
        scope.launch { settings.setup.collect { s -> _state.update { it.copy(setup = s, hasViewKey = secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY) != null) } } }
        scope.launch { settings.presets.collect { p -> _state.update { it.copy(presets = p.map(Presets::format)) } } }
    }

    fun editPreset(index: Int, value: String) = _state.update { st ->
        if (index !in st.presets.indices) st else st.copy(presets = st.presets.toMutableList().also { it[index] = value })
    }

    fun addPreset() = _state.update { st -> if (st.canAddPreset) st.copy(presets = st.presets + "") else st }

    /** One field always stays so the list can never be saved empty by accident. */
    fun removePreset(index: Int) = _state.update { st ->
        if (st.presets.size <= 1 || index !in st.presets.indices) st else st.copy(presets = st.presets.filterIndexed { i, _ -> i != index })
    }

    fun savePresets(inputs: List<String> = _state.value.presets) {
        val parsed = Presets.parseXmr(inputs)
        if (parsed == null) {
            _state.update { it.copy(error = ERR_PRESETS) }
            return
        }
        scope.launch {
            settings.setPresets(parsed)
            _state.update { it.copy(error = null, message = uiText(R.string.settings_msg_presets_saved)) }
        }
    }

    /** Spec 6.5 "Abschalten": DELETE at the watcher, address-less payment info, local status cleared. The view key stays (spec 6.4). */
    fun disable() {
        val setup = _state.value.setup ?: return
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                try {
                    gateway.unregister(setup.watcherUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the payment info without address is what makes receipts stop counting (spec 3.4)
                }
                if (!tips.disablePaymentInfo().sentToAny) {
                    // without a relay the old payment info stays valid for others; keep the setup so the user can retry
                    _state.update { it.copy(busy = false, error = ERR_NOT_PUBLISHED) }
                    return@launch
                }
                settings.setSetup(null)
                _state.update { it.copy(busy = false, message = uiText(R.string.settings_msg_disabled)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }

    fun deleteViewKey() {
        secrets.remove(KeystoreSecretStore.SECRET_VIEW_KEY)
        _state.update { it.copy(hasViewKey = false, message = uiText(R.string.settings_msg_view_key_deleted)) }
    }

    fun adoptRelayList() {
        scope.launch {
            _state.update { it.copy(busy = true) }
            val ok = try {
                adoptRelays()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _state.update { it.copy(busy = false, message = if (ok) uiText(R.string.settings_msg_relays_adopted) else uiText(R.string.settings_msg_no_relay_list)) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        val ERR_PRESETS = uiText(R.string.settings_err_presets)
        val ERR_NOT_PUBLISHED = uiText(R.string.setup_err_not_published)
    }
}
