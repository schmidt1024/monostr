package com.monostr.app.ui.settings

import com.monostr.app.data.AmountUnit
import kotlinx.coroutines.flow.combine
import com.monostr.app.R
import com.monostr.app.data.HintStore
import com.monostr.app.data.KeystoreSecretStore
import com.monostr.app.data.MoneroSetup
import com.monostr.app.data.Presets
import com.monostr.app.data.SecretStore
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.data.WatcherGateway
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.common.userMessage
import com.monostr.app.ui.tips.AmountsPrompt
import com.monostr.app.ui.tips.AmountsVisibility
import com.monostr.nostr.repo.TipsRepository
import kotlinx.coroutines.CancellationException
import com.monostr.tips.PaymentInfo
import com.monostr.tips.WatcherStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Settings row says about a personal watcher; decided in the controller, the screen only renders it. */
sealed class PersonalWatcherLine {
    /** The lookup has not finished. */
    data object Unknown : PersonalWatcherLine()
    /** The lookup finished without a status. */
    data object NotFound : PersonalWatcherLine()
    data object Online : PersonalWatcherLine()
    /** The newest status is older than [com.monostr.tips.WatcherStatus.ONLINE_SECONDS]; [since] is its created_at. */
    data class Offline(val since: Long) : PersonalWatcherLine()
    /** The status names another account or another address than the stored setup. */
    data object Mismatch : PersonalWatcherLine()
}

data class MoneroSettingsState(
    val setup: MoneroSetup? = null,
    val hasViewKey: Boolean = false,
    /** Presets as decimal strings in [unit] for the input fields: the saved ones plus the fields the user added (blank until typed). */
    val presets: List<String> = Presets.DEFAULT.map { Presets.format(it) },
    val unit: AmountUnit = AmountUnit.XMR,
    val busy: Boolean = false,
    /** Without a setup on this device: the tip address the account announces (kind 10037), set up elsewhere. */
    val announced: String? = null,
    val error: UiText? = null,
    val message: UiText? = null,
    /** Personal watcher only: its newest kind 10038, null when none was found (see [statusChecked]). */
    val watcherStatus: WatcherStatus? = null,
    /** True once the status lookup for a personal watcher has finished. */
    val statusChecked: Boolean = false,
    /** Personal watcher only: the verdict on [watcherStatus] against the stored setup. */
    val personalLine: PersonalWatcherLine = PersonalWatcherLine.Unknown,
    /** Whether the watcher speaks protocol 0.5 (hidden amounts); null while unknown or unreachable. */
    val amountsSupported: Boolean? = null,
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
    /** The logged-in account; null in tests that do not look at announced payment info. */
    private val selfPubkey: String? = null,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** With [selfPubkey]: a successful choice here also answers the one-time amounts question. */
    private val hints: HintStore? = null,
) {
    private var statusJob: Job? = null
    private var supportJob: Job? = null
    private val visibility = AmountsVisibility(tips, gateway, settings)

    private val _state = MutableStateFlow(MoneroSettingsState())
    val state: StateFlow<MoneroSettingsState> = _state.asStateFlow()

    fun start() {
        scope.launch {
            settings.setup.collect { s ->
                _state.update { it.copy(setup = s, hasViewKey = secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY) != null) }
                // after a reinstall or on a second device "not set up" would be wrong: tips still arrive at the announced address
                val announced = if (s == null && selfPubkey != null) announcedAddress(selfPubkey) else null
                _state.update { it.copy(announced = announced.takeIf { _ -> it.setup == null }) }
                statusJob?.cancel()
                _state.update { it.copy(watcherStatus = null, statusChecked = false, personalLine = PersonalWatcherLine.Unknown) }
                supportJob?.cancel()
                _state.update { it.copy(amountsSupported = null) }
                if (s != null) supportJob = launch {
                    val ok = visibility.supported(s)
                    _state.update { it.copy(amountsSupported = ok) }
                }
                if (s != null && s.isPersonal) {
                    statusJob = launch {
                        val st = try {
                            tips.watcherStatus(s.watcherPubkey, s.watcherRelays)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        _state.update { it.copy(watcherStatus = st, statusChecked = true, personalLine = personalLine(s, st)) }
                    }
                }
            }
        }
        scope.launch {
            combine(settings.presets, settings.amountUnit) { p, u -> p to u }.collect { (p, u) ->
                _state.update { it.copy(presets = p.map { v -> Presets.format(v, u) }, unit = u) }
            }
        }
    }

    /** The status must name this account and the stored address, or the watcher watches something else (a null [selfPubkey] skips the account check). */
    private fun personalLine(setup: MoneroSetup, st: WatcherStatus?): PersonalWatcherLine = when {
        st == null -> PersonalWatcherLine.NotFound
        (selfPubkey != null && st.recipient != selfPubkey) || st.address.encode() != setup.address -> PersonalWatcherLine.Mismatch
        st.isOnline(now()) -> PersonalWatcherLine.Online
        else -> PersonalWatcherLine.Offline(st.createdAt)
    }

    /** Unreachable relays count as nothing announced: the row then says "not set up", as before. */
    private suspend fun announcedAddress(pubkey: String): String? = try {
        (tips.paymentInfo(pubkey) as? PaymentInfo.Enabled)?.address?.encode()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    fun editPreset(index: Int, value: String) = _state.update { st ->
        if (index !in st.presets.indices) st else st.copy(presets = st.presets.toMutableList().also { it[index] = value })
    }

    /** XMR or mXMR; the preset fields read again in the new unit. */
    fun setUnit(unit: AmountUnit) {
        scope.launch { settings.setAmountUnit(unit) }
    }

    fun addPreset() = _state.update { st -> if (st.canAddPreset) st.copy(presets = st.presets + "") else st }

    /** One field always stays so the list can never be saved empty by accident. */
    fun removePreset(index: Int) = _state.update { st ->
        if (st.presets.size <= 1 || index !in st.presets.indices) st else st.copy(presets = st.presets.filterIndexed { i, _ -> i != index })
    }

    fun savePresets(inputs: List<String> = _state.value.presets) {
        val parsed = Presets.parseAll(inputs, _state.value.unit)
        if (parsed == null) {
            _state.update { it.copy(error = errPresets(it.unit)) }
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
                    // a personal watcher has no HTTP endpoint: the empty payment info (kind 10037) is all it takes
                    if (!setup.isPersonal) gateway.unregister(setup.watcherUrl)
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

    /** Switch "show tip amounts publicly": republishes the payment info; nothing changes if that fails. */
    fun setAmountsPublic(public: Boolean) {
        val st = _state.value
        if (st.setup == null || st.busy) return
        // switching back to public is always allowed; hiding needs a watcher that speaks 0.5
        if (if (public) !st.setup.amountsHidden else st.amountsSupported != true) return
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val ok = visibility.setHidden(!public)
                if (ok && hints != null && selfPubkey != null) {
                    try {
                        hints.dismiss(selfPubkey, AmountsPrompt.HINT)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // the choice itself took effect; the question stays off via the stored flag or announced info
                    }
                }
                _state.update { it.copy(busy = false, error = if (ok) null else ERR_NOT_PUBLISHED) }
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
        /** "Presets: valid … amounts", naming the unit the fields are typed in. */
        fun errPresets(unit: AmountUnit) = uiText(R.string.settings_err_presets, unit.label)
        val ERR_NOT_PUBLISHED = uiText(R.string.setup_err_not_published)
    }
}
