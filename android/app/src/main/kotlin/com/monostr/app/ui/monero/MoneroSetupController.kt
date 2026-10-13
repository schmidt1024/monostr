package com.monostr.app.ui.monero

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
import com.monostr.monero.Address
import com.monostr.monero.AddressException
import com.monostr.monero.Mnemonic
import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.monero.hexToBytes
import com.monostr.monero.toHex
import com.monostr.nostr.WatcherProfile
import com.monostr.nostr.repo.TipsRepository
import com.monostr.tips.NetworkTag
import com.monostr.tips.PaymentInfo
import com.monostr.tips.ProtocolVersion
import com.monostr.tips.Watcher
import com.monostr.tips.WatcherStatus
import com.monostr.tips.event.isHex64
import com.monostr.tips.watcher.WatcherException
import com.monostr.tips.watcher.WatcherInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Random

sealed class SetupStep {
    data class Watcher(val url: String, val loading: Boolean = false) : SetupStep()
    /** [canReuse]: a stored address + view key for the same network can be moved to this watcher (spec 6.5). */
    data class Choose(val info: WatcherInfo, val canReuse: Boolean) : SetupStep()
    data class Seed(val words: List<String>, val restoreHeight: Long) : SetupStep()
    /** Three 0-based word positions to confirm (spec 6.2 step 4). */
    data class Verify(val positions: List<Int>, val wrong: Boolean = false) : SetupStep()
    data class Existing(val address: String, val viewKey: String) : SetupStep() {
        /** The raw key never appears in logs or a state dump (spec: view key never in toString()). */
        override fun toString() = "Existing(address=$address, viewKey=<redacted ${viewKey.length} chars>)"
    }
    /** Spec 6.3 step 4: the privacy warning before anything leaves the device. */
    /** [amountsHidden]: the watcher speaks protocol 0.5, so the payment info will hide tip amounts. */
    data class Confirm(val address: String, val restoreHeight: Long?, val amountsHidden: Boolean = false) : SetupStep()
    /** Spec 0.4 §4.3: the watcher's nprofile (or npub/hex plus [relay]). */
    data class Personal(val input: String, val relay: String, val loading: Boolean = false) : SetupStep()
    /** A fresh status of a personal watcher for this account; confirming announces it in pubkey form. */
    data class PersonalConfirm(val status: WatcherStatus, val relays: List<String>) : SetupStep()
    data object Registering : SetupStep()
    /** [amountsHidden]: the published payment info hides tip amounts (protocol 0.5). */
    data class Done(val address: String, val restoreHeight: Long?, val amountsHidden: Boolean = false) : SetupStep()
}

data class SetupState(
    val step: SetupStep = SetupStep.Watcher(Presets.DEFAULT_WATCHER, loading = true),
    val error: UiText? = null,
    /** Shown right after login as a skippable screen (spec 6). */
    val intro: Boolean = false,
    val finished: Boolean = false,
    /** The tip address this account already announces (kind 10037) without a setup on this device: going on replaces it. */
    val announced: String? = null,
)

/**
 * Spec 6: watcher selection, path A (new tip wallet: keys → 25 words → verify three → register)
 * and path B (existing wallet: address + view key checked locally → warning → register).
 * The spend key never leaves this class and is wiped once the registration finished or failed.
 */
class MoneroSetupController(
    intro: Boolean,
    private val gateway: WatcherGateway,
    private val tips: TipsRepository,
    private val settings: TipSettingsStore,
    private val secrets: SecretStore,
    /** Adds the watcher's relays to the user's list and publishes kind 10002 if missing (NostrSession.ensureRelays). */
    private val relays: suspend (List<String>) -> Unit,
    private val scope: CoroutineScope,
    /** The logged-in user: what the relays announce for them decides whether a failed setup may remove the watcher account. */
    private val selfPubkey: String,
    private val keyGen: () -> MoneroKeys = { MoneroKeys.generate() },
    private val random: Random = SecureRandom(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** Epoch seconds as a localized short date-time, for "offline since". */
    private val formatTime: (Long) -> String = ::localTime,
) {
    private val _state = MutableStateFlow(SetupState(intro = intro))
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private var existing: MoneroSetup? = null
    private var url: String = Presets.DEFAULT_WATCHER
    private var info: WatcherInfo? = null
    private var network: Network = Network.MAINNET
    private var keys: MoneroKeys? = null
    private var seedWords: List<String> = emptyList()
    private var positions: List<Int> = emptyList()
    private var pendingAddress: Address? = null
    private var pendingViewKey: ByteArray? = null
    private var personalProfile: WatcherProfile? = null
    private var personalStatus: WatcherStatus? = null

    fun start() {
        scope.launch {
            existing = settings.setup.first()
            // a personal setup has no URL: switching to HTTP starts at the default watcher
            url = existing?.watcherUrl?.takeIf { it.isNotEmpty() } ?: Presets.DEFAULT_WATCHER
            // set up on another device or before a reinstall: the intro must not offer to replace a working tip address
            val announced = if (existing != null) null else announcedAddress()
            if (announced != null && _state.value.intro) return@launch skip()
            _state.update { it.copy(step = SetupStep.Watcher(url), announced = announced) }
        }
    }

    /** A forced relay fetch: on a fresh install nothing is cached yet. Unreachable relays count as nothing announced. */
    private suspend fun announcedAddress(): String? = try {
        (tips.paymentInfo(selfPubkey, maxAgeSeconds = 0) as? PaymentInfo.Enabled)?.address?.encode()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    fun setWatcherUrl(input: String) {
        url = input.trim()
        _state.update { it.copy(step = SetupStep.Watcher(url), error = null) }
    }

    /** Spec 6.1: loads `/v1/info`, shows the watcher's key, network and height. */
    fun loadWatcher() {
        val parsed = url.toHttpUrlOrNull()
        val allowed = parsed != null && (parsed.scheme == "https" || (parsed.scheme == "http" && parsed.host in LOOPBACK))
        if (!allowed) {
            _state.update { it.copy(step = SetupStep.Watcher(url), error = ERR_URL) }
            return
        }
        url = parsed.toString().trimEnd('/')
        _state.update { it.copy(step = SetupStep.Watcher(url, loading = true), error = null) }
        scope.launch {
            try {
                val loaded = gateway.info(url)
                val net = NetworkTag.parse(loaded.network) ?: throw SetupFailure(ERR_WATCHER_NETWORK)
                if (!isHex64(loaded.pubkey)) throw WatcherException.Protocol("watcher pubkey")
                info = loaded
                network = net
                val stored = existing
                // a personal setup's address does not belong to the stored view key (D5)
                val canReuse = stored != null && !stored.isPersonal && stored.network == loaded.network && secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY) != null
                _state.update { it.copy(step = SetupStep.Choose(loaded, canReuse), error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SetupFailure) {
                _state.update { it.copy(step = SetupStep.Watcher(url), error = e.text) }
            } catch (e: Exception) {
                _state.update { it.copy(step = SetupStep.Watcher(url), error = e.userMessage()) }
            }
        }
    }

    /** Spec 6.2 steps 2–3: fresh keys, 25 words, restore height from the watcher. */
    fun startNew() {
        val loaded = info ?: return
        val k = keyGen()
        keys = k
        seedWords = Mnemonic.encode(k.spendSecret)
        _state.update { it.copy(step = SetupStep.Seed(seedWords, loaded.height), error = null) }
    }

    fun seedWritten() {
        positions = (0 until Mnemonic.WORD_COUNT).shuffled(random).take(3).sorted()
        _state.update { it.copy(step = SetupStep.Verify(positions), error = null) }
    }

    fun backToSeed() {
        val loaded = info ?: return
        _state.update { it.copy(step = SetupStep.Seed(seedWords, loaded.height), error = null) }
    }

    /** Spec 6.2 step 4: without the right words there is no way forward. */
    fun verify(answers: List<String>) {
        val k = keys ?: return
        val loaded = info ?: return
        val ok = answers.size == positions.size && answers.zip(positions).all { (a, p) -> a.trim().lowercase() == seedWords[p] }
        if (!ok) {
            _state.update { it.copy(step = SetupStep.Verify(positions, wrong = true)) }
            return
        }
        pendingAddress = k.address(network)
        pendingViewKey = k.viewSecret.copyOf()
        register(restoreHeight = loaded.height)
    }

    fun startExisting() {
        keys = null
        _state.update { it.copy(step = SetupStep.Existing("", ""), error = null) }
    }

    fun setAddress(input: String) = _state.update { s -> s.copy(step = (s.step as? SetupStep.Existing)?.copy(address = input) ?: s.step, error = null) }

    fun setViewKey(input: String) = _state.update { s -> s.copy(step = (s.step as? SetupStep.Existing)?.copy(viewKey = input) ?: s.step, error = null) }

    /** Spec 6.3 steps 1–3: primary address only, right network, view key must belong to the address. */
    fun checkExisting() {
        val step = _state.value.step as? SetupStep.Existing ?: return
        val address = try {
            Address.parse(step.address)
        } catch (e: AddressException.SubaddressNotAllowed) {
            return fail(ERR_SUBADDRESS)
        } catch (e: AddressException.IntegratedNotAllowed) {
            return fail(ERR_INTEGRATED)
        } catch (e: AddressException) {
            return fail(ERR_ADDRESS)
        }
        if (address.network != network) return fail(ERR_NETWORK)
        val hex = step.viewKey.trim().lowercase()
        if (!isHex64(hex)) return fail(ERR_VIEW_KEY_FORMAT)
        val viewKey = hex.hexToBytes()
        if (!MoneroKeys.viewKeyMatches(address, viewKey)) return fail(ERR_VIEW_KEY_MISMATCH)
        pendingAddress = address
        pendingViewKey = viewKey
        _state.update { it.copy(step = SetupStep.Confirm(address.encode(), null, hidesAmounts()), error = null) }
    }

    /** Spec 6.5: move the stored wallet to the newly chosen watcher. */
    fun reuseStored() {
        val stored = existing ?: return
        val raw = secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY) ?: return fail(ERR_VIEW_KEY_FORMAT)
        val hex = raw.trim().lowercase()
        if (!isHex64(hex)) return fail(ERR_VIEW_KEY_FORMAT)
        val address = runCatching { Address.parse(stored.address) }.getOrNull() ?: return fail(ERR_ADDRESS)
        if (address.network != network) return fail(ERR_NETWORK)
        pendingAddress = address
        pendingViewKey = hex.hexToBytes()
        _state.update { it.copy(step = SetupStep.Confirm(address.encode(), null, hidesAmounts()), error = null) }
    }

    fun startPersonal() {
        _state.update { it.copy(step = SetupStep.Personal("", ""), error = null) }
    }

    fun setPersonalInput(input: String) =
        _state.update { s -> s.copy(step = (s.step as? SetupStep.Personal)?.copy(input = input) ?: s.step, error = null) }

    fun setPersonalRelay(input: String) =
        _state.update { s -> s.copy(step = (s.step as? SetupStep.Personal)?.copy(relay = input) ?: s.step, error = null) }

    fun backToWatcher() {
        _state.update { it.copy(step = SetupStep.Watcher(url), error = null) }
    }

    /**
     * Spec 0.4 §4.3: reads the watcher's key and relays, then its newest status (kind 10038) from
     * exactly those relays. It must name this account, report a known network matching its
     * address (D1), and be fresh. No view key and no seed on this path.
     */
    fun loadPersonal() {
        val step = _state.value.step as? SetupStep.Personal ?: return
        if (step.loading) return
        val back = SetupStep.Personal(step.input, step.relay)
        val profile = WatcherProfile.parse(step.input, step.relay)
            ?: return _state.update { it.copy(step = back, error = ERR_PERSONAL_INPUT) }
        if (profile.relays.isEmpty()) return _state.update { it.copy(step = back, error = ERR_PERSONAL_RELAY) }
        val started = back.copy(loading = true)
        _state.update { it.copy(step = started, error = null) }
        scope.launch {
            try {
                val status = tips.watcherStatus(profile.pubkey, profile.relays) ?: throw SetupFailure(ERR_PERSONAL_NOT_FOUND)
                if (status.recipient != selfPubkey) throw SetupFailure(ERR_PERSONAL_OTHER_ACCOUNT)
                val net = status.network
                if (net == null || net != status.address.network) throw SetupFailure(ERR_PERSONAL_NETWORK)
                if (!status.isOnline(now())) throw SetupFailure(uiText(R.string.setup_personal_err_offline, formatTime(status.createdAt)))
                // the user may have left this step (back) while the status was on its way: then it is no longer wanted
                if (_state.value.step != started) return@launch
                personalProfile = profile
                personalStatus = status
                network = net
                _state.update { it.copy(step = SetupStep.PersonalConfirm(status, profile.relays), error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SetupFailure) {
                _state.update { if (it.step == started) it.copy(step = back, error = e.text) else it }
            } catch (e: Exception) {
                _state.update { if (it.step == started) it.copy(step = back, error = e.userMessage()) else it }
            }
        }
    }

    /**
     * Spec 0.4 §4.3: announces the personal watcher in pubkey form, persists, then unregisters a
     * previous HTTP watcher. Until the payment info is out, the old setup stays the announced one.
     */
    fun confirmPersonal() {
        val step = _state.value.step as? SetupStep.PersonalConfirm ?: return
        val profile = personalProfile ?: return
        val status = personalStatus ?: return
        _state.update { it.copy(step = SetupStep.Registering, error = null) }
        scope.launch {
            try {
                val old = existing
                val hidden = ProtocolVersion.supportsHiddenAmounts(status.protocol)
                if (!tips.publishPaymentInfo(status.address, Watcher.Personal(profile.pubkey, profile.relays), hidden).sentToAny) throw SetupFailure(ERR_NOT_PUBLISHED)
                settings.setSetup(MoneroSetup(status.address.encode(), "", profile.pubkey, NetworkTag.toTag(status.network ?: return@launch), now(), profile.relays, hidden))
                settings.setOnboardingSeen()
                if (old != null && !old.isPersonal) {
                    // only now: until the new payment info is out, the old watcher is the announced one
                    try {
                        gateway.unregister(old.watcherUrl)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // the old watcher may be gone; open intents there expire anyway (spec 6.5)
                    }
                }
                try {
                    relays(profile.relays)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // receipts may arrive later via the watcher's relays; the setup itself is complete
                }
                existing = settings.setup.first()
                _state.update { it.copy(step = SetupStep.Done(status.address.encode(), null, hidden), error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SetupFailure) {
                _state.update { it.copy(step = step, error = e.text) }
            } catch (e: Exception) {
                _state.update { it.copy(step = step, error = e.userMessage()) }
            }
        }
    }

    /** Path B, or a retry after a failed registration: a path-A retry keeps the restore height. */
    fun confirm() = register(restoreHeight = (_state.value.step as? SetupStep.Confirm)?.restoreHeight)

    fun skip() {
        scope.launch {
            runCatching { settings.setOnboardingSeen() }
            _state.update { it.copy(finished = true) }
        }
    }

    fun finish() = _state.update { it.copy(finished = true) }

    /** Wipes key material of an abandoned setup (the screen's ViewModel is cleared). */
    fun clear() = wipe()

    /** Spec 6.2 step 6 / 6.3 step 5: POST at the watcher, publish payment info, persist, then DELETE at a watcher the setup left. */
    private fun register(restoreHeight: Long?) {
        val address = pendingAddress ?: return
        val viewKey = pendingViewKey ?: return
        val loaded = info ?: return
        val hidden = ProtocolVersion.supportsHiddenAmounts(loaded.protocol)
        _state.update { it.copy(step = SetupStep.Registering, error = null) }
        scope.launch {
            try {
                val old = existing
                val watcherPubkey = gateway.register(url, address, viewKey)
                try {
                    if (watcherPubkey != loaded.pubkey) throw WatcherException.Protocol("watcher pubkey differs from /v1/info")
                    // without a relay nobody can find the payment info; nothing is persisted, the user retries (spec 6.2 step 6)
                    if (!tips.publishPaymentInfo(address, Watcher.Http(url, watcherPubkey), hidden).sentToAny) throw SetupFailure(ERR_NOT_PUBLISHED)
                } catch (e: Exception) {
                    // cancellation included: the watcher must not keep watching a wallet nobody announced
                    withContext(NonCancellable) { undoRegistration(old) }
                    throw e
                }
                secrets.put(KeystoreSecretStore.SECRET_VIEW_KEY, viewKey.toHex())
                settings.setSetup(MoneroSetup(address.encode(), url, watcherPubkey, NetworkTag.toTag(network), now(), amountsHidden = hidden))
                settings.setOnboardingSeen()
                if (old != null && !old.isPersonal && old.watcherUrl != url) {
                    // only now: until the new payment info is out, the old watcher is the announced one
                    try {
                        gateway.unregister(old.watcherUrl)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // the old watcher may be gone; open intents there expire anyway (spec 6.5)
                    }
                }
                try {
                    relays(loaded.relays)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // receipts may arrive later via the watcher's relays; the setup itself is complete
                }
                existing = settings.setup.first()
                wipe()
                _state.update { it.copy(step = SetupStep.Done(address.encode(), restoreHeight, hidden), error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SetupFailure) {
                _state.update { it.copy(step = SetupStep.Confirm(address.encode(), restoreHeight, hidden), error = e.text) }
            } catch (e: Exception) {
                _state.update { it.copy(step = SetupStep.Confirm(address.encode(), restoreHeight, hidden), error = e.userMessage()) }
            }
        }
    }

    /**
     * Takes back a registration whose payment info was not published: the watcher holds the view
     * key and would go on watching a wallet nobody was told about. What the relays announce
     * decides what "back" means:
     * - a setup at this watcher existed: its wallet (the announced one) is registered again;
     * - no setup on this device, but the relays still name this watcher for the user (after a
     *   logout, or a setup made on another device): the account stays, senders rely on it;
     * - otherwise (a first setup, or a move to this watcher from another one): the account is removed.
     * Best effort: the next successful setup replaces whatever is left.
     */
    private suspend fun undoRegistration(previous: MoneroSetup?) {
        try {
            if (previous != null && previous.watcherUrl == url) {
                val key = secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY)?.trim()?.lowercase()?.takeIf(::isHex64)?.hexToBytes()
                val address = runCatching { Address.parse(previous.address) }.getOrNull()
                if (key != null && address != null) {
                    try {
                        gateway.register(url, address, key)
                    } finally {
                        key.fill(0)
                    }
                    return
                }
                key?.fill(0)
            } else if (previous == null) {
                val announced = tips.paymentInfo(selfPubkey, maxAgeSeconds = 0)
                if (announced is PaymentInfo.Enabled && (announced.watcher as? Watcher.Http)?.url == url) return
            }
            gateway.unregister(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the watcher is unreachable; a retry of the setup registers again
        }
    }

    private fun wipe() {
        pendingViewKey?.fill(0)
        pendingViewKey = null
        pendingAddress = null
        keys?.let { it.spendSecret.fill(0); it.viewSecret.fill(0) }
        keys = null
        seedWords = emptyList()
    }

    /** Whether the loaded HTTP watcher speaks protocol 0.5: the payment info then hides tip amounts. */
    private fun hidesAmounts(): Boolean = ProtocolVersion.supportsHiddenAmounts(info?.protocol)

    private fun fail(text: UiText) = _state.update { it.copy(error = text) }

    private class SetupFailure(val text: UiText) : Exception()

    companion object {
        val ERR_URL = uiText(R.string.setup_err_url)
        val ERR_WATCHER_NETWORK = uiText(R.string.setup_err_watcher_network)
        val ERR_SUBADDRESS = uiText(R.string.setup_err_subaddress)
        val ERR_INTEGRATED = uiText(R.string.setup_err_integrated)
        val ERR_ADDRESS = uiText(R.string.setup_err_address)
        val ERR_NETWORK = uiText(R.string.setup_err_network)
        val ERR_VIEW_KEY_FORMAT = uiText(R.string.setup_err_view_key_format)
        val ERR_VIEW_KEY_MISMATCH = uiText(R.string.setup_err_view_key_mismatch)
        val ERR_NOT_PUBLISHED = uiText(R.string.setup_err_not_published)
        val ERR_PERSONAL_INPUT = uiText(R.string.setup_personal_err_input)
        val ERR_PERSONAL_RELAY = uiText(R.string.setup_personal_err_relay)
        val ERR_PERSONAL_NOT_FOUND = uiText(R.string.setup_personal_err_not_found)
        val ERR_PERSONAL_OTHER_ACCOUNT = uiText(R.string.setup_personal_err_other_account)
        val ERR_PERSONAL_NETWORK = uiText(R.string.setup_personal_err_network)
        private val LOOPBACK = setOf("127.0.0.1", "localhost", "10.0.2.2", "::1")
    }
}

/** Epoch seconds → localized short date-time in the device's current zone. */
internal fun localTime(epochSeconds: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))
