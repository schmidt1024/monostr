package com.monostr.app.net

import com.monostr.app.data.PendingTip
import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Tor is on and not connected: nothing was asked of a signer, nothing was sent. */
class SendRefused : Exception("tor is not connected: nothing was sent")

/**
 * Whether the user's actions may leave the device now (spec 2026-11-06 5.3): always in Direct, in Tor only while connected, never while
 * the mode is unknown. A refusal is final, nothing here queues. [torConnectedNow] and [torConnected] touch Tor when used, which happens
 * in Tor mode only, so with the switch off nothing of Tor loads.
 */
class SendGate(private val gate: NetworkGate, private val torConnectedNow: () -> Boolean, private val torConnected: Flow<Boolean>) {
    /** Reads the mode and Tor at each [StateFlow.value], so no read is stale and in Direct nothing runs until someone collects. */
    val canSend: StateFlow<Boolean> = Live()

    /** The route of this moment is Tor, connected or not. */
    val viaTor: Boolean get() = gate.mode.value is NetworkMode.Tor

    val direct: Boolean get() = gate.mode.value == NetworkMode.Direct

    /** A tip sent under Tor never goes out on the direct route: that would link an anonymous intent to the user's IP (spec 5.4). */
    fun mayResend(tip: PendingTip): Boolean = canSend.value && (!tip.underTor || viaTor)

    /** For a path that reports through an exception; the UI's `userMessage` turns it into the reason. */
    fun requireCanSend() { if (!canSend.value) throw SendRefused() }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private inner class Live : StateFlow<Boolean> {
        override val value: Boolean
            get() = when (gate.mode.value) {
                NetworkMode.Direct -> true
                NetworkMode.Unknown -> false
                is NetworkMode.Tor -> torConnectedNow()
            }
        override val replayCache: List<Boolean> get() = listOf(value)

        @OptIn(ExperimentalCoroutinesApi::class)
        override suspend fun collect(collector: FlowCollector<Boolean>): Nothing {
            gate.mode.flatMapLatest { mode ->
                when (mode) {
                    NetworkMode.Direct -> flowOf(true)
                    NetworkMode.Unknown -> flowOf(false)
                    is NetworkMode.Tor -> torConnected
                }
            }.distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }

    companion object {
        /** Direct, always: for tests of what the gate does not concern. */
        val OPEN: SendGate = SendGate(NetworkGate().apply { set(NetworkMode.Direct) }, { false }, flowOf(false))
    }
}
