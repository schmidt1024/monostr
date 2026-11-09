package com.monostr.app.ui.tips

import com.monostr.app.net.SendGate
import com.monostr.app.data.PendingTip
import com.monostr.app.data.PendingTipStore
import com.monostr.app.data.PendingTips
import com.monostr.nostr.repo.TipsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The sender's view of their own profile tips to one person: [pendingTip] while an intent waits
 * for its receipt, [arrived] once the watcher confirmed it. Receipts are asked for only while
 * such a tip is pending and the profile is visible, from shortly before the oldest one was sent;
 * the profile shows no sum and no list. Public tips are watched over the user's relays. Anonymous
 * ones are watched over a connection of their own, on the tip's own watcher relays, because the
 * question itself would give the sender away; the match is the intent id, which only this device knows. [tips] is the running
 * session's repository: without one (logged out) nothing is asked, a new one (Tor switched) asks again over its route.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileTipWatch(
    private val recipient: String,
    private val tips: Flow<TipsRepository?>,
    private val pending: PendingTipStore,
    private val scope: CoroutineScope,
    private val retryMs: Long = RETRY_MS,
    /** Whether the route is Tor now; open only in tests. */
    private val sendGate: SendGate = SendGate.OPEN,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val _pendingTip = MutableStateFlow(false)
    val pendingTip: StateFlow<Boolean> = _pendingTip.asStateFlow()
    private val arrivals = Channel<Unit>(Channel.CONFLATED)
    /** One signal per settled batch; one settled while nobody collects reaches the next collector. */
    val arrived: Flow<Unit> = arrivals.receiveAsFlow()
    private val visible = MutableStateFlow(true)
    private val mine = MutableStateFlow<List<PendingTip>>(emptyList())

    /** The profile is on screen (true) or not: receipts are asked for only while it is. */
    fun setVisible(visible: Boolean) { this.visible.value = visible }

    fun start() {
        scope.launch {
            pending.pending
                .catch { /* store read failed: no marker */ }
                .map { all -> PendingTips.waiting(all, now()).filter(::isMine) }
                .collect { own ->
                    mine.value = own
                    _pendingTip.value = own.isNotEmpty()
                }
        }
        // public tips: a live subscription over the user's relays
        scope.launch {
            combine(visible, mine, tips) { shown, own, repo ->
                val oldest = if (shown) own.filter { it.anonEvent == null && askable(it) }.minOfOrNull { it.createdAt } else null
                if (oldest == null || repo == null) null else repo to oldest
            }
                .distinctUntilChanged()
                .flatMapLatest { asked ->
                    val (repo, oldest) = asked ?: return@flatMapLatest emptyFlow()
                    repo.observeProfileReceipts(recipient, oldest - SINCE_MARGIN).catch { /* relay or db error: the entry expires after 24 h */ }
                }
                .collect { confirmed -> settle(confirmed) }
        }
        // anonymous tips: a live subscription over a connection of their own, never over the user's
        scope.launch {
            combine(visible, mine, tips) { shown, own, repo ->
                val anonymous = if (shown) own.filter { it.anonEvent != null && askable(it) } else emptyList()
                val oldest = anonymous.minOfOrNull { it.createdAt }
                val relays = anonymous.flatMap { it.anonRelays }.distinct().sorted()
                if (oldest == null || relays.isEmpty() || repo == null) null else AnonymousLookup(repo, oldest - SINCE_MARGIN, relays)
            }
                .distinctUntilChanged()
                .flatMapLatest { lookup ->
                    if (lookup == null) emptyFlow()
                    else lookup.tips.observeAnonymousProfileReceipts(recipient, lookup.since, lookup.relays)
                        // the connection could not be set up: try again later, the tip is still pending
                        .retryWhen { cause, _ -> if (cause is CancellationException) false else { delay(retryMs); true } }
                }
                .collect { confirmed -> settle(confirmed) }
        }
    }

    private data class AnonymousLookup(val tips: TipsRepository, val since: Long, val relays: List<String>)

    /**
     * A question about this recipient on the tip's relays, from the user's IP, is the link to an intent sent under Tor (spec 5.4). The session
     * goes through Loading before a switch flips the gate, so [tips] asks again here when the route has changed.
     */
    private fun askable(tip: PendingTip): Boolean = !tip.underTor || sendGate.viaTor

    private fun isMine(tip: PendingTip): Boolean = tip.noteId == null && tip.recipient == recipient

    private suspend fun settle(confirmed: Set<String>) {
        if (confirmed.isEmpty()) return
        try {
            val done = pending.pending.first().filter { isMine(it) && it.intentId in confirmed }.map { it.intentId }
            if (done.isEmpty()) return
            // once removed, the arrival must be signalled, whatever happens to this coroutine meanwhile
            // (the removal itself ends the watch for this tip)
            withContext(NonCancellable) {
                pending.settle(done)
                arrivals.trySend(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the marker stays until the next receipt or its 24 h expiry
        }
    }

    companion object {
        /** The watcher's clock may run behind the phone's: ask from five minutes before the intent. */
        const val SINCE_MARGIN = 300L

        /** How long a failed watch for the receipt of an anonymous profile tip waits before it is set up again. */
        const val RETRY_MS = 20_000L
    }
}
