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
import com.monostr.nostr.repo.OwnPayto
import com.monostr.nostr.repo.PaytoWrite
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Random

sealed class SetupStep {
    /**
     * Spec payto §4: the first step, one receiving address (primary or subaddress) published as payto.
     * [loading]: the publish runs. [known]: the field holds the account's own payto entry.
     */
    data class Address(val input: String, val loading: Boolean = false, val known: Boolean = false) : SetupStep()
    /** The address is published; the watcher (receipts) is offered as an upgrade. */
    data class AddressDone(val address: String) : SetupStep()
    /**
     * [loading]: inputs are locked, at first while the stored settings are read, then while a check runs.
     * [checking]: the user started a check (spec §12: progress, "Checking watcher…").
     * [slow]: that check runs for 3 s already (hint + Cancel). [retry]: the last check timed out or hit a network error.
     */
    data class Watcher(val url: String, val loading: Boolean = false, val checking: Boolean = false, val slow: Boolean = false, val retry: Boolean = false) : SetupStep()
    /** [canReuse]: a stored address + view key for the same network can be moved to this watcher (spec 6.5). */
    data class Choose(val info: WatcherInfo, val canReuse: Boolean) : SetupStep()
    data class Seed(val words: List<String>, val restoreHeight: Long) : SetupStep()
    /** Three 0-based word positions to confirm (spec 6.2 step 4). */
    data class Verify(val positions: List<Int>, val wrong: Boolean = false) : SetupStep()
    data class Existing(val address: String, val viewKey: String) : SetupStep() {
        /** The raw key never appears in logs or a state dump (spec: view key never in toString()). */
        override fun toString() = "Existing(address=$address, viewKey=<redacted ${viewKey.length} chars>)"
    }
    /**
     * Spec 6.3 step 4: the privacy warning before anything leaves the device.
     * [amountsHidden]: the watcher speaks protocol 0.5, so the payment info will hide tip amounts.
     * [paytoConflict]: the own payto address when it differs from [address] (spec payto §4); [replacePayto] is the answer.
     * [paytoPending]: the own payto is still being read; confirming waits for it.
     */
    data class Confirm(
        val address: String, val restoreHeight: Long?, val amountsHidden: Boolean = false,
        val paytoConflict: String? = null, val replacePayto: Boolean = true, val paytoPending: Boolean = false,
    ) : SetupStep()
    /** Spec 0.4 §4.3: the watcher's nprofile (or npub/hex plus [relay]). */
    /** [slow] and [retry] as in [Watcher] (spec §12). */
    data class Personal(val input: String, val relay: String, val loading: Boolean = false, val slow: Boolean = false, val retry: Boolean = false) : SetupStep()
    /** A fresh status of a personal watcher for this account; confirming announces it in pubkey form. The payto fields as in [Confirm]. */
    data class PersonalConfirm(
        val status: WatcherStatus, val relays: List<String>,
        val paytoConflict: String? = null, val replacePayto: Boolean = true, val paytoPending: Boolean = false,
    ) : SetupStep()
    data object Registering : SetupStep()
    /**
     * [amountsHidden]: the published payment info hides tip amounts (protocol 0.5).
     * [paytoFailed]: the setup is complete, but the own payto entry could not be set to [address] (spec payto §4).
     */
    data class Done(val address: String, val restoreHeight: Long?, val amountsHidden: Boolean = false, val paytoFailed: Boolean = false) : SetupStep()
}

data class SetupState(
    val step: SetupStep = SetupStep.Watcher(Presets.DEFAULT_WATCHER, loading = true),
    val error: UiText? = null,
    /** Shown right after login as a skippable screen (spec 6). */
    val intro: Boolean = false,
    val finished: Boolean = false,
    /**
     * The tip address this account already announces (kind 10037) without a setup on this device, found in the
     * background: the watcher step offers to keep it, the choose step warns that going on replaces it.
     */
    val announced: String? = null,
)

/**
 * Spec payto §4: without a setup on this device the setup starts with one address ([SetupStep.Address]);
 * the watcher is the upgrade ([upgrade] starts there).
 * Spec 6: watcher selection, path A (new tip wallet: keys → 25 words → verify three → register)
 * and path B (existing wallet: address + view key checked locally → warning → register).
 * The spend key never leaves this class and is wiped once the registration finished or failed.
 */
class MoneroSetupController(
    intro: Boolean,
    /** Opens the watcher step directly (the receipts upgrade), even without a setup on this device. */
    upgrade: Boolean = false,
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
    /** Debug-only timing of the `/v1/info` calls (spec §12); the ViewModel passes a logcat writer in debug builds. */
    private val log: (String) -> Unit = {},
) {
    private val startsAtWatcher = upgrade
    private val _state = MutableStateFlow(
        SetupState(step = if (upgrade) SetupStep.Watcher(Presets.DEFAULT_WATCHER, loading = true) else SetupStep.Address("", loading = true), intro = intro),
    )
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
    /** Spec §12: `/v1/info` of [prefetchUrl], started when the watcher step appears; used once by [loadWatcher]. */
    private var prefetch: Deferred<Result<WatcherInfo>>? = null
    private var prefetchUrl: String? = null
    /** The check the user started (watcher or personal); [cancelCheck] stops it. */
    private var check: Job? = null
    /** The start phase with the announced lookup; "Later" and "Keep it" stop it. */
    private var starting: Job? = null
    private var infoLogged = false
    /** The watcher step was opened by the announced lookup, not by the user. */
    private var watcherForAnnounced = false
    /** The own payto address the field was prefilled with; that input still counts as untouched. */
    private var prefilled: String? = null
    /** Spec payto §4: the own payto as last read or written; an unknown or failed read is not kept and asked again. */
    private var knownPayto: OwnPayto.Known? = null
    /** The one running read of the own payto; prefill, prefetch and the conflict check share it. */
    private var paytoRead: Deferred<OwnPayto?>? = null
    /**
     * Whether the shown payto question rests on a known own payto. When not (relays incomplete), the write
     * re-reads first and never replaces a differing address nobody was asked about.
     */
    private var paytoAsked = false
    /** [paytoAsked] of the setup that ended in [SetupStep.Done], for [retryPayto]. */
    private var retryAsked = false
    private var paytoRetry: Job? = null

    fun start() {
        starting = scope.launch {
            existing = settings.setup.first()
            // a personal setup has no URL: switching to HTTP starts at the default watcher
            url = existing?.watcherUrl?.takeIf { it.isNotEmpty() } ?: Presets.DEFAULT_WATCHER
            // the step is usable at once; the lookups below run behind it
            if (existing != null || startsAtWatcher) {
                _state.update { it.copy(step = SetupStep.Watcher(url)) }
                prefetch()
                // ready before the confirm step asks whether the tip address replaces it
                paytoLookup()
            } else {
                _state.update { it.copy(step = SetupStep.Address("")) }
                launch { prefillOwnPayto() }
            }
            if (existing != null) return@launch
            // set up on another device or before a reinstall: the watcher step offers to keep that tip address
            val asked = System.nanoTime()
            val announced = announcedAddress()
            log("announced tip address (relays): ${(System.nanoTime() - asked) / 1_000_000} ms")
            if (announced == null) return@launch
            var switched = false
            _state.update { s ->
                val step = s.step
                // only an untouched field: typed or pasted input and a shown error stay
                switched = step is SetupStep.Address && !step.loading && s.error == null && (step.input.isEmpty() || step.input == prefilled)
                if (switched) s.copy(step = SetupStep.Watcher(url), announced = announced, error = null) else s.copy(announced = announced)
            }
            if (switched) {
                watcherForAnnounced = true
                prefetch()
            }
        }
    }

    /**
     * Spec payto §4: the own payto entry fills the address field; in the intro it counts as set up and closes it
     * (also over a watcher step the announced lookup opened). A failed or unknown read leaves the field empty.
     */
    private suspend fun prefillOwnPayto() {
        val address = ownPaytoAddress() ?: return
        val s = _state.value
        val step = s.step
        val untouched = (step is SetupStep.Address && !step.loading) || (step is SetupStep.Watcher && watcherForAnnounced && !step.loading)
        if (s.intro && untouched) {
            runCatching { settings.setOnboardingSeen() }
            _state.update { it.copy(finished = true) }
            return
        }
        _state.update { cur ->
            val st = cur.step as? SetupStep.Address
            if (st != null && !st.loading && st.input.isEmpty()) {
                prefilled = address
                cur.copy(step = st.copy(input = address, known = true))
            } else cur
        }
    }

    /**
     * Spec payto §4: checks the address locally (mainnet primary or subaddress), then writes it as the own payto.
     * Only a published entry finishes the step.
     */
    fun publishAddress() {
        val step = _state.value.step as? SetupStep.Address ?: return
        if (step.loading) return
        val trimmed = step.input.trim()
        val net = try {
            Address.checkReceiving(trimmed)
        } catch (e: AddressException.IntegratedNotAllowed) {
            return fail(ERR_ADDRESS_INTEGRATED)
        } catch (e: AddressException) {
            return fail(ERR_ADDRESS)
        }
        if (net != Network.MAINNET) return fail(ERR_MAINNET)
        _state.update { it.copy(step = step.copy(loading = true), error = null) }
        scope.launch {
            try {
                when (tips.setOwnPayto(trimmed)) {
                    PaytoWrite.Published -> {
                        knownPayto = OwnPayto.Known(trimmed)
                        settings.setOnboardingSeen()
                        _state.update { it.copy(step = SetupStep.AddressDone(trimmed), error = null) }
                    }
                    PaytoWrite.NotPublished -> _state.update { it.copy(step = step, error = ERR_NOT_PUBLISHED) }
                    PaytoWrite.Unknown -> _state.update { it.copy(step = step, error = ERR_RELAYS_UNKNOWN) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(step = step, error = e.userMessage()) }
            }
        }
    }

    /** "No Monero wallet yet?": today's watcher path with a new tip wallet. */
    fun createWallet() = openWatcher()

    /** From [SetupStep.AddressDone]: the receipts upgrade, the watcher setup. */
    fun upgrade() = openWatcher()

    private fun openWatcher() {
        watcherForAnnounced = false
        _state.update { it.copy(step = SetupStep.Watcher(url), error = null) }
        prefetch()
    }

    /** Spec §12: a valid https (or loopback http) URL, normalized as the check uses it; null otherwise. */
    private fun checkedUrl(input: String): String? {
        val parsed = input.toHttpUrlOrNull() ?: return null
        val allowed = parsed.scheme == "https" || (parsed.scheme == "http" && parsed.host in LOOPBACK)
        return if (allowed) parsed.toString().trimEnd('/') else null
    }

    /** Spec §12: starts `/v1/info` in the background for the shown URL; its failure stays silent until the user taps. */
    private fun prefetch() {
        val target = checkedUrl(url) ?: return
        if (prefetchUrl == target && prefetch != null) return
        dropPrefetch()
        prefetchUrl = target
        prefetch = scope.async {
            try {
                Result.success(timedInfo(target, "prefetch"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun dropPrefetch() {
        prefetch?.cancel()
        prefetch = null
        prefetchUrl = null
    }

    private suspend fun timedInfo(target: String, what: String): WatcherInfo {
        val started = System.nanoTime()
        try {
            return gateway.info(target)
        } finally {
            val first = !infoLogged
            infoLogged = true
            log("/v1/info $what $target: ${(System.nanoTime() - started) / 1_000_000} ms${if (first) " (first since the setup opened)" else ""}")
        }
    }

    /**
     * A forced relay fetch: on a fresh install nothing is cached yet. Unreachable relays count as nothing announced,
     * and so does a lookup that takes longer than 5 s (spec §12).
     */
    private suspend fun announcedAddress(): String? = try {
        (withTimeoutOrNull(ANNOUNCED_TIMEOUT_MS) { tips.paymentInfo(selfPubkey, maxAgeSeconds = 0) } as? PaymentInfo.Enabled)?.address?.encode()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    fun setWatcherUrl(input: String) {
        watcherForAnnounced = false
        url = input.trim()
        // spec §12: an edited URL invalidates the prefetched result
        if (prefetchUrl != null && checkedUrl(url) != prefetchUrl) dropPrefetch()
        _state.update { it.copy(step = SetupStep.Watcher(url), error = null) }
    }

    /** Spec §12: stops the check the user started and returns to the editable step without an error. */
    fun cancelCheck() {
        check?.cancel()
        check = null
        _state.update { s ->
            when (val step = s.step) {
                is SetupStep.Watcher -> if (step.checking) s.copy(step = SetupStep.Watcher(step.url), error = null) else s
                is SetupStep.Personal -> if (step.loading) s.copy(step = SetupStep.Personal(step.input, step.relay), error = null) else s
                else -> s
            }
        }
    }

    /** Spec §12: after 3 s of a running check, the hint and Cancel appear. */
    private fun CoroutineScope.markSlowLater(slow: (SetupStep) -> SetupStep?) = launch {
        delay(SLOW_MS)
        _state.update { s -> slow(s.step)?.let { s.copy(step = it) } ?: s }
    }

    /**
     * Spec 6.1: loads `/v1/info`, shows the watcher's key, network and height.
     * Spec §12: uses the prefetched result (or joins it), shows progress and gives up after 15 s.
     */
    fun loadWatcher() {
        val target = checkedUrl(url)
        if (target == null) {
            _state.update { it.copy(step = SetupStep.Watcher(url), error = ERR_URL) }
            return
        }
        if ((_state.value.step as? SetupStep.Watcher)?.checking == true) return
        watcherForAnnounced = false
        url = target
        // a prefetch is used once: a later check asks the watcher again
        val prefetched = prefetch?.takeIf { prefetchUrl == target }
        prefetch = null
        prefetchUrl = null
        _state.update { it.copy(step = SetupStep.Watcher(url, loading = true, checking = true), error = null) }
        check?.cancel()
        check = scope.launch {
            val slow = markSlowLater { (it as? SetupStep.Watcher)?.takeIf { w -> w.checking }?.copy(slow = true) }
            val started = System.nanoTime()
            try {
                var reused = false
                val loaded = withTimeout(TIMEOUT_MS) {
                    prefetched?.await()?.getOrNull()?.also { reused = true } ?: timedInfo(target, "check")
                }
                log("check $target: ${(System.nanoTime() - started) / 1_000_000} ms after the tap${if (reused) " (prefetch used)" else ""}")
                val net = NetworkTag.parse(loaded.network) ?: throw SetupFailure(ERR_WATCHER_NETWORK)
                if (!isHex64(loaded.pubkey)) throw WatcherException.Protocol("watcher pubkey")
                info = loaded
                network = net
                val stored = existing
                // a personal setup's address does not belong to the stored view key (D5)
                val canReuse = stored != null && !stored.isPersonal && stored.network == loaded.network && secrets.get(KeystoreSecretStore.SECRET_VIEW_KEY) != null
                _state.update { it.copy(step = SetupStep.Choose(loaded, canReuse), error = null) }
            } catch (e: TimeoutCancellationException) {
                prefetched?.cancel()
                _state.update { it.copy(step = SetupStep.Watcher(url, retry = true), error = ERR_TIMEOUT) }
            } catch (e: CancellationException) {
                prefetched?.cancel()
                throw e
            } catch (e: SetupFailure) {
                _state.update { it.copy(step = SetupStep.Watcher(url), error = e.text) }
            } catch (e: Exception) {
                _state.update { it.copy(step = SetupStep.Watcher(url, retry = e.isNetwork()), error = e.userMessage()) }
            } finally {
                slow.cancel()
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
        val address = k.address(network)
        pendingAddress = address
        pendingViewKey = k.viewSecret.copyOf()
        // spec payto §4: a different own payto is asked about before anything is published; the spinner shows meanwhile
        _state.update { it.copy(step = SetupStep.Registering, error = null) }
        scope.launch {
            val conflict = paytoConflict(address.encode())
            if (_state.value.step != SetupStep.Registering) return@launch
            if (conflict == null) register(restoreHeight = loaded.height)
            else _state.update { it.copy(step = SetupStep.Confirm(address.encode(), loaded.height, hidesAmounts(), conflict), error = null) }
        }
    }

    /** Spec payto §4: an own payto primary address (one the watcher can watch) fills the address field. */
    fun startExisting() {
        keys = null
        _state.update { it.copy(step = SetupStep.Existing("", ""), error = null) }
        scope.launch {
            val own = ownPaytoAddress() ?: return@launch
            if (runCatching { Address.parse(own) }.getOrNull()?.network != network) return@launch
            _state.update { s ->
                val st = s.step as? SetupStep.Existing
                if (st != null && st.address.isEmpty()) s.copy(step = st.copy(address = own)) else s
            }
        }
    }

    /** The address field of [SetupStep.Address] (locked while publishing) or [SetupStep.Existing]. */
    fun setAddress(input: String) = _state.update { s ->
        val step = when (val st = s.step) {
            is SetupStep.Existing -> st.copy(address = input)
            is SetupStep.Address -> if (st.loading) st else st.copy(input = input)
            else -> st
        }
        s.copy(step = step, error = null)
    }

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
        toConfirm(address.encode())
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
        toConfirm(address.encode())
    }

    /**
     * Path B and the reuse: the confirm step at once. The payto question (spec payto §4) follows when the own
     * payto is read; until then confirming waits.
     */
    private fun toConfirm(address: String) {
        val known = knownPayto
        if (known != null) {
            paytoAsked = true
            _state.update { it.copy(step = SetupStep.Confirm(address, null, hidesAmounts(), known.address?.takeIf { a -> a != address }), error = null) }
            return
        }
        val pending = SetupStep.Confirm(address, null, hidesAmounts(), paytoPending = true)
        _state.update { it.copy(step = pending, error = null) }
        scope.launch {
            val conflict = paytoConflict(address)
            _state.update { s ->
                val st = s.step as? SetupStep.Confirm
                if (st != null && st.paytoPending && st.address == address) s.copy(step = st.copy(paytoConflict = conflict, paytoPending = false)) else s
            }
        }
    }

    fun startPersonal() {
        watcherForAnnounced = false
        _state.update { it.copy(step = SetupStep.Personal("", ""), error = null) }
    }

    fun setPersonalInput(input: String) =
        _state.update { s -> s.copy(step = (s.step as? SetupStep.Personal)?.copy(input = input, retry = false) ?: s.step, error = null) }

    fun setPersonalRelay(input: String) =
        _state.update { s -> s.copy(step = (s.step as? SetupStep.Personal)?.copy(relay = input, retry = false) ?: s.step, error = null) }

    fun backToWatcher() {
        check?.cancel()
        check = null
        _state.update { it.copy(step = SetupStep.Watcher(url), error = null) }
        prefetch()
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
        // the step counts as unchanged when only the slow hint was added
        fun current(s: SetupState) = (s.step as? SetupStep.Personal)?.copy(slow = false) == started
        check?.cancel()
        check = scope.launch {
            val slow = markSlowLater { step -> (step as? SetupStep.Personal)?.takeIf { it.copy(slow = false) == started }?.copy(slow = true) }
            try {
                val status = withTimeout(TIMEOUT_MS) { tips.watcherStatus(profile.pubkey, profile.relays) } ?: throw SetupFailure(ERR_PERSONAL_NOT_FOUND)
                if (status.recipient != selfPubkey) throw SetupFailure(ERR_PERSONAL_OTHER_ACCOUNT)
                val net = status.network
                if (net == null || net != status.address.network) throw SetupFailure(ERR_PERSONAL_NETWORK)
                if (!status.isOnline(now())) throw SetupFailure(uiText(R.string.setup_personal_err_offline, formatTime(status.createdAt)))
                // the user may have left this step (back) while the status was on its way: then it is no longer wanted
                if (!current(_state.value)) return@launch
                personalProfile = profile
                personalStatus = status
                network = net
                val tip = status.address.encode()
                val known = knownPayto
                paytoAsked = known != null
                _state.update { it.copy(step = SetupStep.PersonalConfirm(status, profile.relays, known?.address?.takeIf { a -> a != tip }, paytoPending = known == null), error = null) }
                // outside the check: cancelling or leaving the check must not leave the question pending
                if (known == null) scope.launch {
                    val conflict = paytoConflict(tip)
                    _state.update { s ->
                        val st = s.step as? SetupStep.PersonalConfirm
                        if (st != null && st.paytoPending && st.status == status) s.copy(step = st.copy(paytoConflict = conflict, paytoPending = false)) else s
                    }
                }
            } catch (e: TimeoutCancellationException) {
                _state.update { if (current(it)) it.copy(step = back.copy(retry = true), error = ERR_TIMEOUT) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SetupFailure) {
                _state.update { if (current(it)) it.copy(step = back, error = e.text) else it }
            } catch (e: Exception) {
                _state.update { if (current(it)) it.copy(step = back.copy(retry = e.isNetwork()), error = e.userMessage()) else it }
            } finally {
                slow.cancel()
            }
        }
    }

    /**
     * Spec 0.4 §4.3: announces the personal watcher in pubkey form, persists, then unregisters a
     * previous HTTP watcher. Until the payment info is out, the old setup stays the announced one.
     */
    fun confirmPersonal() {
        val step = _state.value.step as? SetupStep.PersonalConfirm ?: return
        if (step.paytoPending) return
        val asked = paytoAsked
        val profile = personalProfile ?: return
        val status = personalStatus ?: return
        _state.update { it.copy(step = SetupStep.Registering, error = null) }
        scope.launch {
            try {
                val old = existing
                val hidden = hidesAmounts(status.protocol)
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
                val address = status.address.encode()
                retryAsked = asked
                val paytoFailed = (step.paytoConflict == null || step.replacePayto) && !publishPayto(address, asked)
                _state.update { it.copy(step = SetupStep.Done(address, null, hidden, paytoFailed), error = null) }
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
    fun confirm() {
        val step = _state.value.step as? SetupStep.Confirm ?: return
        if (step.paytoPending) return
        register(restoreHeight = step.restoreHeight)
    }

    fun skip() {
        starting?.cancel()
        starting = null
        dropPrefetch()
        check?.cancel()
        check = null
        scope.launch {
            runCatching { settings.setOnboardingSeen() }
            _state.update { it.copy(finished = true) }
        }
    }

    fun finish() = _state.update { it.copy(finished = true) }

    /** Spec payto §4: the answer to "replace your public Monero address?" on the confirm steps. */
    fun setReplacePayto(value: Boolean) = _state.update { s ->
        val step = when (val st = s.step) {
            is SetupStep.Confirm -> st.copy(replacePayto = value)
            is SetupStep.PersonalConfirm -> st.copy(replacePayto = value)
            else -> st
        }
        s.copy(step = step)
    }

    /** Spec payto §4: after a payto write failed on [SetupStep.Done], tries only that write again. */
    fun retryPayto() {
        val step = _state.value.step as? SetupStep.Done ?: return
        if (!step.paytoFailed || paytoRetry?.isActive == true) return
        paytoRetry = scope.launch {
            val failed = !publishPayto(step.address, retryAsked)
            _state.update { s -> (s.step as? SetupStep.Done)?.let { s.copy(step = it.copy(paytoFailed = failed)) } ?: s }
        }
    }

    /**
     * The own payto: the known answer, else the running read, else a new one. A known answer is kept for this
     * setup; an unknown or failed read (null) is not, the next caller reads again.
     */
    private fun paytoLookup(): Deferred<OwnPayto?> {
        knownPayto?.let { return CompletableDeferred(it) }
        paytoRead?.takeIf { it.isActive }?.let { return it }
        return scope.async { readOwnPayto() }.also { paytoRead = it }
    }

    private suspend fun readOwnPayto(): OwnPayto? {
        val own = try {
            tips.ownPayto()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (own is OwnPayto.Known) knownPayto = own
        return own
    }

    /** The own payto address (null: none, or not known). */
    private suspend fun ownPaytoAddress(): String? = (paytoLookup().await() as? OwnPayto.Known)?.address

    /**
     * The own payto address when it differs from the tip address [address]: then the user is asked once.
     * Records in [paytoAsked] whether that answer rests on a known own payto.
     */
    private suspend fun paytoConflict(address: String): String? {
        val own = paytoLookup().await() as? OwnPayto.Known
        paytoAsked = own != null
        return own?.address?.takeIf { it != address }
    }

    /**
     * Spec payto §4: the tip address becomes the own payto too. False when that failed; the setup itself stands.
     * Without a known own payto at the question ([asked] false), a fresh read comes first: a differing address
     * nobody was asked about is never replaced.
     */
    private suspend fun publishPayto(address: String, asked: Boolean): Boolean {
        val written = try {
            if (!asked) {
                val now = tips.ownPayto() as? OwnPayto.Known ?: return false
                knownPayto = now
                if (now.address != null && now.address != address) return false
            }
            tips.setOwnPayto(address) == PaytoWrite.Published
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (written) knownPayto = OwnPayto.Known(address)
        return written
    }

    /** Wipes key material of an abandoned setup (the screen's ViewModel is cleared). */
    fun clear() = wipe()

    /** Spec 6.2 step 6 / 6.3 step 5: POST at the watcher, publish payment info, persist, then DELETE at a watcher the setup left. */
    private fun register(restoreHeight: Long?) {
        val address = pendingAddress ?: return
        val viewKey = pendingViewKey ?: return
        val loaded = info ?: return
        val hidden = hidesAmounts(loaded.protocol)
        // the answer to the payto question survives a failed attempt
        val shown = _state.value.step as? SetupStep.Confirm
        val back = SetupStep.Confirm(address.encode(), restoreHeight, hidden, shown?.paytoConflict, shown?.replacePayto ?: true)
        val asked = paytoAsked
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
                // spec payto §4: after the payment info is out; a failure here is a warning, never a rollback
                retryAsked = asked
                val paytoFailed = (back.paytoConflict == null || back.replacePayto) && !publishPayto(back.address, asked)
                _state.update { it.copy(step = SetupStep.Done(back.address, restoreHeight, hidden, paytoFailed), error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SetupFailure) {
                _state.update { it.copy(step = back, error = e.text) }
            } catch (e: Exception) {
                _state.update { it.copy(step = back, error = e.userMessage()) }
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
    private fun hidesAmounts(): Boolean = hidesAmounts(info?.protocol)

    /** A 0.5 watcher hides amounts by default; a setup the user made public stays public across a watcher switch. */
    private fun hidesAmounts(protocol: String?): Boolean =
        ProtocolVersion.supportsHiddenAmounts(protocol) && (existing?.amountsHidden ?: true)

    private fun fail(text: UiText) = _state.update { it.copy(error = text) }

    /** Spec §12: only an unreachable watcher or relay turns the button into "Try again". */
    private fun Exception.isNetwork() = this is WatcherException.Network || this is IOException

    private class SetupFailure(val text: UiText) : Exception()

    companion object {
        val ERR_URL = uiText(R.string.setup_err_url)
        val ERR_WATCHER_NETWORK = uiText(R.string.setup_err_watcher_network)
        val ERR_SUBADDRESS = uiText(R.string.setup_err_subaddress)
        val ERR_INTEGRATED = uiText(R.string.setup_err_integrated)
        /** The Address step takes subaddresses too, so its message must not ask for the primary address. */
        val ERR_ADDRESS_INTEGRATED = uiText(R.string.setup_err_address_integrated)
        val ERR_ADDRESS = uiText(R.string.setup_err_address)
        val ERR_NETWORK = uiText(R.string.setup_err_network)
        val ERR_VIEW_KEY_FORMAT = uiText(R.string.setup_err_view_key_format)
        val ERR_VIEW_KEY_MISMATCH = uiText(R.string.setup_err_view_key_mismatch)
        val ERR_NOT_PUBLISHED = uiText(R.string.setup_err_not_published)
        val ERR_MAINNET = uiText(R.string.setup_err_mainnet)
        val ERR_RELAYS_UNKNOWN = uiText(R.string.setup_err_relays_unknown)
        val ERR_PERSONAL_INPUT = uiText(R.string.setup_personal_err_input)
        val ERR_PERSONAL_RELAY = uiText(R.string.setup_personal_err_relay)
        val ERR_PERSONAL_NOT_FOUND = uiText(R.string.setup_personal_err_not_found)
        val ERR_PERSONAL_OTHER_ACCOUNT = uiText(R.string.setup_personal_err_other_account)
        val ERR_PERSONAL_NETWORK = uiText(R.string.setup_personal_err_network)
        val ERR_TIMEOUT = uiText(R.string.setup_err_timeout)
        /** Spec §12: the slow hint after 3 s, the timeout after 15 s per check. */
        const val SLOW_MS = 3_000L
        const val TIMEOUT_MS = 15_000L
        /** Spec §12: the announced lookup gives up after this; nothing counts as announced then. */
        const val ANNOUNCED_TIMEOUT_MS = 5_000L
        private val LOOPBACK = setOf("127.0.0.1", "localhost", "10.0.2.2", "::1")
    }
}

/** Epoch seconds → localized short date-time in the device's current zone. */
internal fun localTime(epochSeconds: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))
