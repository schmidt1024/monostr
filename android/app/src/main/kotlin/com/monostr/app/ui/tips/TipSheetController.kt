package com.monostr.app.ui.tips

import com.monostr.app.net.SendGate
import kotlinx.coroutines.CoroutineStart
import com.monostr.app.BuildConfig
import com.monostr.tips.RelayHosts
import com.monostr.app.data.AmountUnit
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
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.AuthorRelaysRepository
import com.monostr.nostr.repo.TipsRepository
import com.monostr.tips.PaymentInfo
import com.monostr.tips.Watcher
import com.monostr.tips.TipRequest
import com.monostr.tips.TipType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
    /**
     * The recipient has no payment info but a payto address: pay it by hand, nothing is published or tracked. [walletMissing] is null until the UI tried to open the wallet.
     * [name]: the recipient's stored display name for the title, null when none is known.
     */
    data class PayTo(val address: String, val uri: String, val walletMissing: Boolean? = null, val name: String? = null) : TipPhase()
    data object Ready : TipPhase()
    data object Sending : TipPhase()
    /** Tor is not connected and the payment info is not known: nothing can be said about the recipient, [TipSheetState.error] says why. */
    data object Blocked : TipPhase()
    /** The intent is published. [walletMissing] is null until the UI tried to open the wallet; [arrived] once the watcher's receipt settled the pending tip. */
    data class Pay(val uri: String, val address: String, val amount: Long, val walletMissing: Boolean? = null, val arrived: Boolean = false) : TipPhase()
}

data class TipSheetState(
    val target: TipTarget? = null,
    val phase: TipPhase = TipPhase.Loading,
    val presets: List<Long> = Presets.DEFAULT,
    val selected: Long? = null,
    val custom: String = "",
    /** The unit the presets read in and the free field is typed in. */
    val unit: AmountUnit = AmountUnit.XMR,
    /** The sender is not named (protocol 0.2 `anon`); on unless the user switched it off. */
    val anonymous: Boolean = true,
    val error: UiText? = null,
    val notice: UiText? = null,
    /** Under Tor, the first time on this account: the wallet goes its own way (spec 5.6). */
    val walletNote: Boolean = false,
) {
    val visible: Boolean get() = target != null

    /** Piconero from the selected preset or the free field; null when nothing valid is entered. */
    val amount: Long?
        get() = selected ?: custom.takeIf { it.isNotBlank() }?.let { Presets.parse(it, unit) }
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
    private val authorRelays: AuthorRelaysRepository,
    /** The "connect the author's relays" setting: off, the recipient's own relays are never asked for its payto. */
    private val authorRelaysEnabled: suspend () -> Boolean,
    /** The recipient names the watcher URL: a LAN or loopback one is never asked (debug builds allow it for local test watchers). */
    private val watcherAllowed: (String) -> Boolean = { BuildConfig.DEBUG || RelayHosts.isPublic(it) },
    /** Whether anything may leave now; open only in tests. */
    private val sendGate: SendGate = SendGate.OPEN,
    /** True once: the one-time note about the wallet is due now. Asked only when the sheet reaches a phase that leads to the wallet. */
    private val takeWalletNote: suspend () -> Boolean = { false },
    /** The recipient's stored name (no relay asked), for the payto title; owner 2026-11-12. */
    private val recipientName: suspend (String) -> String? = { null },
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val _state = MutableStateFlow(TipSheetState())
    val state: StateFlow<TipSheetState> = _state.asStateFlow()
    private var recipient: PaymentInfo.Enabled? = null
    /** Waits for the open sheet's pending tip to be settled; see [watchArrival]. */
    private var arrival: Job? = null
    /** Loads the open sheet's payment info and payto; cancelled on dismiss and reopen, which releases the recipient's relays at once. */
    private var loading: Job? = null

    fun open(note: Note) = open(TipTarget.Note(note))

    fun open(target: TipTarget) {
        arrival?.cancel()
        loading?.cancel()
        recipient = null
        _state.value = TipSheetState(target = target)
        loading = scope.launch {
            val presets = settings.presets.first()
            val anonymous = settings.anonymous.first()
            val unit = settings.amountUnit.first()
            // the middle preset (0.001 XMR by default) is the sensible starting point, not the smallest
            _state.update { it.copy(presets = presets, selected = presets.getOrNull(presets.size / 2), anonymous = anonymous, unit = unit) }
            // Amethyst/Ditto users keep their payto (kind 10133) on their own write relays only: asked alongside the payment info
            val outbox = async { fetchOutboxPayto(target.recipient) }
            try {
                val info = try {
                    var fresh: PaymentInfo? = null
                    // one budget from opening for both: the outbox fetch is awaited only without a valid payment info
                    withTimeoutOrNull(FRESH_INFO_TIMEOUT_MS) {
                        // ask the relays first: a cached copy may still say "enabled" after the recipient disabled tips
                        fresh = try {
                            tips.paymentInfo(target.recipient, maxAgeSeconds = 0)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            PaymentInfo.Disabled // the outbox payto may still answer
                        }
                        if (fresh !is PaymentInfo.Enabled) outbox.await()
                    }
                    fresh ?: tips.cachedPaymentInfo(target.recipient) // relays too slow: the local copy, without asking them again
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PaymentInfo.Disabled
                }
                if (_state.value.target?.key != target.key) return@launch // dismissed or reopened meanwhile
                recipient = info as? PaymentInfo.Enabled
                val phase = if (recipient != null) TipPhase.Ready else if (!sendGate.canSend.value) TipPhase.Blocked else {
                    val payto = try {
                        tips.cachedPayto(target.recipient)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (_state.value.target?.key != target.key) return@launch
                    if (payto == null) TipPhase.NoMonero else {
                        val name = try {
                            recipientName(target.recipient)?.takeIf { it.isNotBlank() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null // the plain title then
                        }
                        if (_state.value.target?.key != target.key) return@launch
                        TipPhase.PayTo(payto, "monero:$payto", name = name)
                    }
                }
                PerfTrace.mark("tip sheet phase ${phase::class.simpleName}")
                val walletNote = (phase == TipPhase.Ready || phase is TipPhase.PayTo) && try {
                    takeWalletNote()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false // a hint store that fails costs a note, never the tip
                }
                if (_state.value.target?.key != target.key) return@launch
                _state.update { it.copy(phase = phase, walletNote = walletNote, error = if (phase == TipPhase.Blocked) ERROR_TOR else it.error) }
            } finally {
                outbox.cancel()
            }
        }
    }

    /** The recipient's kind 10133 from up to three of its write relays outside the user's list, into the database; never throws except on cancellation. */
    private suspend fun fetchOutboxPayto(pubkey: String) {
        try {
            if (!authorRelaysEnabled()) return
            val relays = authorRelays.writeRelays(pubkey)
            if (relays.isNotEmpty()) tips.fetchPayto(pubkey, relays)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // no outbox payto: the user's own relays' answer stands
        }
    }

    fun dismiss() {
        arrival?.cancel()
        loading?.cancel()
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
        if (!sendGate.canSend.value) {
            _state.update { it.copy(error = ERROR_TOR) }
            return
        }
        val key = target.key
        val anonymous = s.anonymous
        val viaTor = sendGate.viaTor
        scope.launch {
            if (_state.value.target?.key != key) return@launch // dismissed or reopened meanwhile
            _state.update { it.copy(phase = TipPhase.Sending, error = null) }
            try {
                val prepared = TipRequest.prepare(info, target.recipient, target.noteId, amount, TipType.TIP, "", now(), anonymous = anonymous)
                val watcherRelays = watcherRelays(info)
                if (!sendGate.canSend.value) return@launch refused(key)
                val sentToAny: Boolean
                val intentId: String
                if (anonymous) {
                    if (watcherRelays.isEmpty()) {
                        // an anonymous intent has nowhere else to go, and a public tip is never sent in its place
                        if (_state.value.target?.key == key) _state.update { it.copy(phase = TipPhase.Ready, error = ERROR_ANON_UNAVAILABLE) }
                        return@launch
                    }
                    val sent = tips.sendAnonymousIntent(prepared, watcherRelays, torOnly = viaTor)
                    if (lostTor(sent.result.sentToAny)) return@launch refused(key)
                    intentId = sent.result.eventId
                    pending.add(
                        PendingTip(
                            sent.result.eventId, target.noteId, amount, now(), target.recipient,
                            anonEvent = sent.eventJson, anonRelays = watcherRelays, anonDelivered = sent.result.sentToAny, underTor = viaTor || sendGate.viaTor,
                        ),
                    )
                    sentToAny = sent.result.sentToAny
                } else {
                    val result = tips.sendIntent(prepared, watcherRelays)
                    if (lostTor(result.sentToAny)) return@launch refused(key)
                    intentId = result.eventId
                    pending.add(
                        PendingTip(
                            result.eventId, target.noteId, amount, now(), target.recipient,
                            watcherRelays = watcherRelays, watcherDelivered = result.reached(watcherRelays), underTor = viaTor || sendGate.viaTor,
                        ),
                    )
                    sentToAny = result.sentToAny
                }
                redeliver(intentId, info)
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
                _state.update { it.copy(phase = TipPhase.Ready, error = if (sendGate.canSend.value) e.userMessage() else ERROR_TOR) }
            }
        }
    }

    /** No relay took the intent, and Tor is the reason: that is a refusal, not an intent waiting to go out. */
    private fun lostTor(sentToAny: Boolean): Boolean = !sentToAny && !sendGate.canSend.value

    private fun refused(key: String) {
        if (_state.value.target?.key == key) _state.update { it.copy(phase = TipPhase.Ready, error = ERROR_TOR) }
    }

    /**
     * Sends the intent [intentId] again a few times while no watcher relay has it. The next start would be too
     * late: the watcher looks for the payment from the moment it knows the intent, and the wallet is open now.
     * It is not a queue: the moment sending stops, the schedule ends, also when Tor is back before the next send.
     */
    private fun redeliver(intentId: String, info: PaymentInfo.Enabled) {
        scope.launch {
            val schedule = launch(start = CoroutineStart.LAZY) {
                for (pause in REDELIVER_DELAYS_MS) {
                    try {
                        val stored = pending.pending.first().firstOrNull { it.intentId == intentId } ?: return@launch
                        if (stored.isDelivered) return@launch
                        delay(pause)
                        val tip = pending.pending.first().firstOrNull { it.intentId == intentId } ?: return@launch
                        // before withWatcherRelays, which asks the watcher over HTTP
                        if (!sendGate.mayResend(tip)) return@launch
                        if (deliverToWatcher(tips, pending, withWatcherRelays(tip, info), sendGate)) return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // still offline: the next pause tries again, then the next start
                    }
                }
            }
            val stop = launch(start = CoroutineStart.UNDISPATCHED) {
                sendGate.canSend.first { !it }
                schedule.cancel()
            }
            schedule.join()
            stop.cancel()
        }
    }

    /** [tip], for a public one with the relays of a watcher that did not answer at the send, if it answers now. */
    private suspend fun withWatcherRelays(tip: PendingTip, info: PaymentInfo.Enabled): PendingTip {
        if (tip.anonEvent != null || tip.watcherRelays.isNotEmpty()) return tip
        val relays = watcherRelays(info)
        if (relays.isEmpty()) return tip
        pending.setWatcherRelays(tip.intentId, relays)
        return tip.copy(watcherRelays = relays)
    }

    /** Spec 0.4 §4.2: a personal watcher's relays are in its tag, no `/v1/info`; an HTTP watcher is asked, empty when it does not answer in time. */
    private suspend fun watcherRelays(info: PaymentInfo.Enabled): List<String> = when (val w = info.watcher) {
        is Watcher.Personal -> w.relays
        is Watcher.Http -> if (!watcherAllowed(w.url)) emptyList() else try {
            withTimeoutOrNull(WATCHER_TIMEOUT_MS) { watchers.info(w.url).relays.filter(RelayHosts::isPublicWss) } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList() // spec 5.9: for a public tip the watcher catches up over relays
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

    /** The UI reports whether a wallet app took the payto URI. */
    fun payToWalletResult(opened: Boolean) {
        val payTo = _state.value.phase as? TipPhase.PayTo ?: return
        _state.update { it.copy(phase = payTo.copy(walletMissing = !opened)) }
    }

    companion object {
        val ERROR_AMOUNT = uiText(R.string.tip_error_amount)
        val ERROR_TOR = uiText(R.string.send_blocked_tor)
        val ERROR_ANON_UNAVAILABLE = uiText(R.string.tip_error_anon_unavailable)
        val NO_MONERO = uiText(R.string.tip_no_monero)
        val NOTICE_OFFLINE = uiText(R.string.tip_notice_offline)
        const val FRESH_INFO_TIMEOUT_MS = 3_000L
        const val WATCHER_TIMEOUT_MS = 5_000L
        /** Pauses between the sends right after a tip no watcher relay took: the watcher must know the intent before the payment is scanned. */
        val REDELIVER_DELAYS_MS = listOf(10_000L, 30_000L, 60_000L)
    }
}
