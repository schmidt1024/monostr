package com.monostr.app.ui.tips

import com.monostr.app.R
import com.monostr.app.data.PendingTip
import com.monostr.app.data.PendingTipStore
import com.monostr.app.data.Presets
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.data.WatcherGateway
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.common.userMessage
import com.monostr.monero.MoneroUri
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.TipsRepository
import com.monostr.tips.PaymentInfo
import com.monostr.tips.TipRequest
import com.monostr.tips.TipType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed class TipPhase {
    data object Loading : TipPhase()
    /** Spec 5.5: the recipient has no valid payment info. */
    data object NoMonero : TipPhase()
    data object Ready : TipPhase()
    data object Sending : TipPhase()
    /** The intent is published. [walletMissing] is null until the UI tried to open the wallet; [arrived] once the watcher's receipt settled the pending tip. */
    data class Pay(val uri: String, val address: String, val amount: Long, val walletMissing: Boolean? = null, val arrived: Boolean = false) : TipPhase()
}

data class TipSheetState(
    val target: TipTarget? = null,
    val phase: TipPhase = TipPhase.Loading,
    val presets: List<Long> = Presets.DEFAULT,
    val selected: Long? = null,
    val custom: String = "",
    /** The sender is not named (protocol 0.2 `anon`); on unless the user switched it off. */
    val anonymous: Boolean = true,
    val error: UiText? = null,
    val notice: UiText? = null,
) {
    val visible: Boolean get() = target != null

    /** Piconero from the selected preset or the free field; null when nothing valid is entered. */
    val amount: Long?
        get() = selected ?: custom.takeIf { it.isNotBlank() }?.let { text ->
            runCatching { MoneroUri.parseAmount(text) }.getOrNull()?.takeIf { it > 0 }
        }
}

/**
 * Spec 5.5, sender side: loads the recipient's payment info fresh from the relays (cache only as
 * fallback), validates the amount, publishes the intent, records the pending tip, and hands the
 * `monero:` URI to the UI. A tip is an amount and nothing else: no comment, no kind 7, no kind 6.
 * A public intent goes to the user's relays and the watcher's; an anonymous one is signed with a
 * one-time key and goes to the watcher's relays only, over its own connection. Opening the wallet
 * is the UI's job (it needs an Activity); the UI reports back via [walletResult].
 */
class TipSheetController(
    private val tips: TipsRepository,
    private val watchers: WatcherGateway,
    private val settings: TipSettingsStore,
    private val pending: PendingTipStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val _state = MutableStateFlow(TipSheetState())
    val state: StateFlow<TipSheetState> = _state.asStateFlow()
    private var recipient: PaymentInfo.Enabled? = null
    /** Waits for the open sheet's pending tip to be settled; see [watchArrival]. */
    private var arrival: Job? = null

    fun open(note: Note) = open(TipTarget.Note(note))

    fun open(target: TipTarget) {
        arrival?.cancel()
        recipient = null
        _state.value = TipSheetState(target = target)
        scope.launch {
            val presets = settings.presets.first()
            val anonymous = settings.anonymous.first()
            // the middle preset (0.001 XMR by default) is the sensible starting point, not the smallest
            _state.update { it.copy(presets = presets, selected = presets.getOrNull(presets.size / 2), anonymous = anonymous) }
            val info = try {
                // ask the relays first: a cached copy may still say "enabled" after the recipient disabled tips
                withTimeoutOrNull(FRESH_INFO_TIMEOUT_MS) { tips.paymentInfo(target.recipient, maxAgeSeconds = 0) }
                    ?: tips.cachedPaymentInfo(target.recipient) // relays too slow: the local copy, without asking them again
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PaymentInfo.Disabled
            }
            if (_state.value.target?.key != target.key) return@launch // dismissed or reopened meanwhile
            recipient = info as? PaymentInfo.Enabled
            _state.update { it.copy(phase = if (recipient == null) TipPhase.NoMonero else TipPhase.Ready) }
        }
    }

    fun dismiss() {
        arrival?.cancel()
        recipient = null
        _state.value = TipSheetState()
    }

    fun select(preset: Long) = _state.update { it.copy(selected = preset, custom = "", error = null) }

    fun setCustom(text: String) = _state.update { it.copy(custom = text, selected = null, error = null) }

    /** The choice is remembered for the next tip. */
    fun setAnonymous(value: Boolean) {
        _state.update { it.copy(anonymous = value, error = null) }
        scope.launch {
            try {
                settings.setAnonymous(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // not remembered: the sheet still honours the switch for this tip
            }
        }
    }

    fun send() {
        val s = _state.value
        val target = s.target ?: return
        val info = recipient ?: return
        if (s.phase != TipPhase.Ready) return
        val amount = s.amount
        if (amount == null) {
            _state.update { it.copy(error = ERROR_AMOUNT) }
            return
        }
        val key = target.key
        val anonymous = s.anonymous
        scope.launch {
            if (_state.value.target?.key != key) return@launch // dismissed or reopened meanwhile
            _state.update { it.copy(phase = TipPhase.Sending, error = null) }
            try {
                val prepared = TipRequest.prepare(info, target.recipient, target.noteId, amount, TipType.TIP, "", now(), anonymous = anonymous)
                val watcherRelays = try {
                    withTimeoutOrNull(WATCHER_TIMEOUT_MS) { watchers.info(info.watcherUrl).relays } ?: emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList() // spec 5.9: for a public tip the watcher catches up over relays
                }
                val sentToAny: Boolean
                val intentId: String
                if (anonymous) {
                    if (watcherRelays.isEmpty()) {
                        // an anonymous intent has nowhere else to go, and a public tip is never sent in its place
                        if (_state.value.target?.key == key) _state.update { it.copy(phase = TipPhase.Ready, error = ERROR_ANON_UNAVAILABLE) }
                        return@launch
                    }
                    val sent = tips.sendAnonymousIntent(prepared, watcherRelays)
                    intentId = sent.result.eventId
                    pending.add(
                        PendingTip(
                            sent.result.eventId, target.noteId, amount, now(), target.recipient,
                            anonEvent = sent.eventJson, anonRelays = watcherRelays, anonDelivered = sent.result.sentToAny,
                        ),
                    )
                    sentToAny = sent.result.sentToAny
                } else {
                    val result = tips.sendIntent(prepared, watcherRelays)
                    intentId = result.eventId
                    pending.add(PendingTip(result.eventId, target.noteId, amount, now(), target.recipient))
                    sentToAny = result.sentToAny
                }
                if (_state.value.target?.key != key) return@launch // the tip went out; the sheet now shows another target
                _state.update {
                    it.copy(
                        phase = TipPhase.Pay(prepared.paymentUri, prepared.integratedAddress, amount),
                        notice = if (sentToAny) null else NOTICE_OFFLINE,
                    )
                }
                watchArrival(key, intentId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.target?.key != key) return@launch
                _state.update { it.copy(phase = TipPhase.Ready, error = e.userMessage()) }
            }
        }
    }

    /**
     * Shows "arrived" in the open sheet once the pending tip [intentId] is gone from the store,
     * which happens when its receipt came in (the screen behind the sheet settles it). The sheet
     * asks no relay itself: a request for this one note or person right after an anonymous intent
     * would point at the sender.
     */
    private fun watchArrival(key: String, intentId: String) {
        arrival?.cancel()
        arrival = scope.launch {
            try {
                // gone only counts after it was seen: the store answers a failed read with an empty list
                var seen = false
                pending.pending.first { all ->
                    val present = all.any { it.intentId == intentId }
                    if (present) seen = true
                    seen && !present
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch // the store cannot be read: the sheet keeps saying "waiting"
            }
            _state.update { s ->
                val pay = s.phase as? TipPhase.Pay
                if (s.target?.key == key && pay != null) s.copy(phase = pay.copy(arrived = true)) else s
            }
        }
    }

    /** The UI reports whether a wallet app took the URI. */
    fun walletResult(opened: Boolean) {
        val pay = _state.value.phase as? TipPhase.Pay ?: return
        _state.update { it.copy(phase = pay.copy(walletMissing = !opened)) }
    }

    companion object {
        val ERROR_AMOUNT = uiText(R.string.tip_error_amount)
        val ERROR_ANON_UNAVAILABLE = uiText(R.string.tip_error_anon_unavailable)
        val NO_MONERO = uiText(R.string.tip_no_monero)
        val NOTICE_OFFLINE = uiText(R.string.tip_notice_offline)
        const val FRESH_INFO_TIMEOUT_MS = 3_000L
        const val WATCHER_TIMEOUT_MS = 5_000L
    }
}
