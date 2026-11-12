package com.monostr.app.ui.settings

import com.monostr.app.net.SendGate
import com.monostr.app.session.AdoptResult
import kotlinx.coroutines.flow.first
import com.monostr.app.ui.tips.watcher
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
import com.monostr.nostr.repo.OwnPayto
import com.monostr.nostr.repo.PaytoWrite
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
    /** Without a setup on this device: the public Monero address (NIP-A3 payto), possibly published by another client. */
    val payto: String? = null,
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
    /** The stored presets, formatted like [presets]: the fields differ from them only while there is something to save. */
    val savedPresets: List<String> = presets,
) {
    val canAddPreset: Boolean get() = presets.size < Presets.MAX

    /** UX P2-13: «Save» shows only while the fields hold a change (a comma counts as the decimal point, as [Presets.parse] reads it). */
    val presetsDirty: Boolean get() = presets.map { it.trim().replace(',', '.') } != savedPresets

    /**
     * The address to show with «Change» / «Remove» / the receipts upgrade: only for an account with just a payto.
     * An announced watcher setup from another device keeps its own notice, or «Remove» would only strip the payto
     * while tips keep arriving at the announced address.
     */
    val paytoOnly: String? get() = payto.takeIf { setup == null && announced == null }
}

/** Settings → Monero (spec 6.4–6.6) and the NIP-65 relay adoption. */
class MoneroSettingsController(
    private val settings: TipSettingsStore,
    private val secrets: SecretStore,
    private val tips: TipsRepository,
    private val gateway: WatcherGateway,
    private val adoptRelays: suspend () -> AdoptResult,
    private val scope: CoroutineScope,
    /** The logged-in account; null in tests that do not look at announced payment info. */
    private val selfPubkey: String? = null,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** With [selfPubkey]: a successful choice here also answers the one-time amounts question. */
    private val hints: HintStore? = null,
    /** Whether anything may leave now; open only in tests. */
    private val sendGate: SendGate = SendGate.OPEN,
) {
    private var statusJob: Job? = null
    private var supportJob: Job? = null
    private var paytoJob: Job? = null
    private var announcedJob: Job? = null
    /** Counts published removals: a read that started before one must not bring the address back. */
    private var paytoRemovals = 0
    private val visibility = AmountsVisibility(tips, gateway, settings, sendGate)
    private var followed = false

    private val _state = MutableStateFlow(MoneroSettingsState())
    val state: StateFlow<MoneroSettingsState> = _state.asStateFlow()

    fun start() {
        scope.launch {
            settings.setup.collect { s ->
                setupLoaded = true
                _state.update { it.copy(setup = s, hasViewKey = secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY) != null) }
                // after a reinstall or on a second device "not set up" would be wrong: tips still arrive at the announced address
                if (s == null && selfPubkey != null) readAnnounced(selfPubkey, forced = false) else { announcedJob?.cancel(); _state.update { it.copy(announced = null) } }
                // read in the background: relays may be slow and must not hold back the rest of the state
                if (s == null) readPayto() else { paytoJob?.cancel(); _state.update { it.copy(payto = null) } }
                statusJob?.cancel()
                _state.update { it.copy(watcherStatus = null, statusChecked = false, personalLine = PersonalWatcherLine.Unknown) }
                supportJob?.cancel()
                _state.update { it.copy(amountsSupported = null) }
                if (s != null) supportJob = launch {
                    val ok = visibility.supported(s)
                    _state.update { it.copy(amountsSupported = ok) }
                }
                // once per screen: a choice made here afterwards is not checked against (maybe stale) relays again
                if (s != null && selfPubkey != null && !followed) { followed = true; launch { followAnnounced(selfPubkey, s) } }
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
                val formatted = p.map { v -> Presets.format(v, u) }
                _state.update { it.copy(presets = formatted, savedPresets = formatted, unit = u) }
            }
        }
    }

    /** Reads the own payto in the background; a removal published meanwhile wins over what the read found. */
    private fun readPayto() {
        paytoJob?.cancel()
        paytoJob = scope.launch {
            val removals = paytoRemovals
            val known = try {
                tips.ownPayto() as? OwnPayto.Known
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // unknown (relays silent, nothing stored): keep what is shown rather than call it "not set up"
            if (known != null && removals == paytoRemovals) _state.update { it.copy(payto = known.address.takeIf { _ -> it.setup == null }) }
        }
    }

    /**
     * Reads the announced tip address (kind 10037) in the background. The first read after a setup change counts
     * unreachable relays as nothing announced; a [forced] read from [resumed] asks the relays and, when that
     * fails, keeps what is shown.
     */
    private fun readAnnounced(pubkey: String, forced: Boolean) {
        announcedJob?.cancel()
        announcedJob = scope.launch {
            val info = try {
                if (forced) tips.paymentInfo(pubkey, maxAgeSeconds = 0) else tips.paymentInfo(pubkey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (forced) return@launch else null
            }
            val address = (info as? PaymentInfo.Enabled)?.address?.encode()
            _state.update { it.copy(announced = address.takeIf { _ -> it.setup == null }) }
        }
    }

    /** The stored setup has been read once; before that [start] does the first payto read itself. */
    private var setupLoaded = false

    /**
     * The overview or the Monero page is (back) in front, also after the app returns: the setup screen or another
     * client may have published, changed or removed the address without touching the stored setup, so the own
     * payto is read again, and so is the announced tip address (owner 2026-11-12). Every resume counts, not only
     * the second one; the controller lives with the settings graph and is started long before the Monero page
     * opens. A read still running is reused.
     */
    fun resumed() {
        if (!setupLoaded || _state.value.setup != null) return
        val paytoRunning = paytoJob?.isActive == true
        val announcedRunning = selfPubkey == null || announcedJob?.isActive == true
        if (paytoRunning && announcedRunning) return
        // moving between the overview and a page resumes each time: one relay read per minute is enough, unless this
        // app changed the payto meanwhile (the setup screen's address step leaves the stored setup alone): then at once
        val t = now()
        val writes = tips.ownPaytoWrites
        if (lastResumeRead != 0L && t - lastResumeRead < RESUME_READ_GAP_S && writes == seenPaytoWrites) return
        lastResumeRead = t
        seenPaytoWrites = writes
        if (!paytoRunning) readPayto()
        if (!announcedRunning && selfPubkey != null) readAnnounced(selfPubkey, forced = true)
    }

    /** Epoch seconds of the last read [resumed] started; 0 = none yet. */
    private var lastResumeRead = 0L

    /** [TipsRepository.ownPaytoWrites] at that read. */
    private var seenPaytoWrites = 0

    /** The status must name this account and the stored address, or the watcher watches something else (a null [selfPubkey] skips the account check). */
    private fun personalLine(setup: MoneroSetup, st: WatcherStatus?): PersonalWatcherLine = when {
        st == null -> PersonalWatcherLine.NotFound
        (selfPubkey != null && st.recipient != selfPubkey) || st.address.encode() != setup.address -> PersonalWatcherLine.Mismatch
        st.isOnline(now()) -> PersonalWatcherLine.Online
        else -> PersonalWatcherLine.Offline(st.createdAt)
    }

    /** Another device may have switched the amounts: the announced payment info wins when it names this address and watcher. */
    private suspend fun followAnnounced(pubkey: String, s: MoneroSetup) {
        val info = try {
            tips.paymentInfo(pubkey, maxAgeSeconds = 0) as? PaymentInfo.Enabled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        if (info.address.encode() != s.address || info.watcher != s.watcher() || info.amountsHidden == s.amountsHidden) return
        // the setup changed while the relays answered (a switch here, a new setup): that is newer than the lookup
        if (settings.setup.first() != s) return
        settings.setSetup(s.copy(amountsHidden = info.amountsHidden))
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
            // the store does not emit again for equal values ("0.0010" saved over "0.001"): the fields take the saved form here
            val formatted = parsed.map { Presets.format(it, _state.value.unit) }
            _state.update { it.copy(presets = formatted, savedPresets = formatted, error = null, message = uiText(R.string.settings_msg_presets_saved)) }
        }
    }

    /** Spec 6.5 "Abschalten": DELETE at the watcher, address-less payment info, local status cleared. The view key stays (spec 6.4). */
    fun disable() {
        val setup = _state.value.setup ?: return
        if (refused()) return
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
                // the public address would keep receiving tips; failing to remove it must not undo the switch-off
                val left = removeOwnPayto() != PaytoWrite.Published
                _state.update { it.copy(busy = false, payto = if (left) it.payto else null, message = uiText(if (left) R.string.settings_msg_disabled_payto_left else R.string.settings_msg_disabled)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }

    private suspend fun removeOwnPayto(): PaytoWrite = try {
        tips.setOwnPayto(null).also { if (it == PaytoWrite.Published) { paytoRemovals++; paytoJob?.cancel() } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PaytoWrite.Unknown
    }

    /** Takes the public Monero address off the account (no setup on this device); nothing changes unless it was published. */
    fun removePayto() {
        if (_state.value.busy || refused()) return
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (removeOwnPayto()) {
                PaytoWrite.Published -> _state.update { it.copy(busy = false, payto = null, message = uiText(R.string.settings_msg_payto_removed)) }
                PaytoWrite.NotPublished -> _state.update { it.copy(busy = false, error = ERR_NOT_PUBLISHED) }
                PaytoWrite.Unknown -> _state.update { it.copy(busy = false, error = uiText(R.string.setup_err_relays_unknown)) }
            }
        }
    }

    /** Switch "show tip amounts publicly": republishes the payment info; nothing changes if that fails. */
    fun setAmountsPublic(public: Boolean) {
        val st = _state.value
        if (st.setup == null || st.busy) return
        // switching back to public is always allowed; hiding needs a watcher that speaks 0.5
        if (if (public) !st.setup.amountsHidden else st.amountsSupported != true) return
        if (refused()) return
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
            val result = try {
                adoptRelays()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AdoptResult.NONE
            }
            val message = when (result) {
                AdoptResult.ADOPTED -> uiText(R.string.settings_msg_relays_adopted)
                AdoptResult.MARKERS_IGNORED -> uiText(R.string.relay_adopt_markers_ignored)
                AdoptResult.NONE -> uiText(R.string.settings_msg_no_relay_list)
            }
            _state.update { it.copy(busy = false, message = message) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun refused(): Boolean {
        if (sendGate.canSend.value) return false
        _state.update { it.copy(error = uiText(R.string.send_blocked_tor)) }
        return true
    }

    companion object {
        /** "Presets: valid … amounts", naming the unit the fields are typed in. */
        fun errPresets(unit: AmountUnit) = uiText(R.string.settings_err_presets, unit.label)
        val ERR_NOT_PUBLISHED = uiText(R.string.setup_err_not_published)
    }
}

/** Least time between two payto and announced-address reads started by [MoneroSettingsController.resumed]. */
private const val RESUME_READ_GAP_S = 60L
