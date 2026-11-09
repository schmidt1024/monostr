package com.monostr.app.net

import android.util.Log
import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Which way a switch still running goes. */
enum class TorTurn { ON, OFF }

/** The last switch failed; what is true now is in [TorUi.active] and [TorUi.state]. */
enum class TorSwitchError { ON_FAILED, OFF_FAILED }

/** What the screens show and ask about Tor. A switch started here runs on when the screen leaves. */
interface TorUi {
    /** The route is Tor, as the gate says. All a screen reads with the switch off. */
    val active: StateFlow<Boolean>
    /** Null unless [active]. Never [TorState.Off]: with the gate on Tor and nobody running Tor nothing can leave, which is a failure unless a switch is on its way. */
    val state: StateFlow<TorState?>
    val turning: StateFlow<TorTurn?>
    val error: StateFlow<TorSwitchError?>
    fun turnOn()
    fun turnOff()
    /** True when it goes through a whole switch, which rebuilds the session. */
    fun retry(): Boolean
    fun clearError()
    /** The number for the question before [turnOff]. */
    suspend fun tipsToDrop(): Int
    /** [turnOn] would end the process and start it again, read when asked. */
    val restartsOnSwitch: Boolean get() = false
    /** Where to come back to, should the next [turnOn] end the process. Before it: the end does not wait. */
    fun armRestartReturn(route: String?) {}
    /** The route of the process before this one, once. */
    fun takeRestartReturn(): String? = null
}

/**
 * Holds no coroutine of its own unless a switch runs or an error stands.
 *
 * @param torState touches Tor, and is called in Tor mode only: with the switch off nothing of Tor loads.
 * @param minTurningMs how long [turning] stays at least, so that the line saying so can be read once the screen is back.
 * @param scope the application's: a screen leaving must not cancel a switch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppTorUi(
    private val gate: NetworkGate,
    private val store: TorSwitchStore,
    private val switch: TorSwitch,
    private val torState: () -> StateFlow<TorState>,
    private val torRetry: () -> Unit,
    private val portConflict: () -> Boolean = { false },
    private val minTurningMs: Long = 0,
    private val scope: CoroutineScope,
    private val directRelayRouteUsed: () -> Boolean = { false },
    private val restartReturn: TorRestartReturn? = null,
) : TorUi {
    override val active: StateFlow<Boolean> = Derived({ gate.mode.value is NetworkMode.Tor }, gate.mode.map { it is NetworkMode.Tor })

    private val turningNow = MutableStateFlow<TorTurn?>(null)
    override val turning: StateFlow<TorTurn?> = turningNow

    private val errorNow = MutableStateFlow<TorSwitchError?>(null)
    /** Never against the gate: a turnOn that failed past the flip left Tor on, a turnOff that failed and still went direct left it off. */
    override val error: StateFlow<TorSwitchError?> =
        Derived({ errorAgainst(errorNow.value, gate.mode.value) }, combine(errorNow, gate.mode, ::errorAgainst))

    override val state: StateFlow<TorState?> = Derived(
        { shown((gate.mode.value as? NetworkMode.Tor)?.let { torState().value }, turningNow.value) },
        combine(gate.mode.flatMapLatest { if (it is NetworkMode.Tor) torState() else flowOf(null) }, turningNow, ::shown),
    )

    private fun errorAgainst(error: TorSwitchError?, mode: NetworkMode) = when (error) {
        TorSwitchError.ON_FAILED -> error.takeIf { mode !is NetworkMode.Tor }
        TorSwitchError.OFF_FAILED -> error.takeIf { mode is NetworkMode.Tor }
        null -> null
    }

    private fun shown(tor: TorState?, turn: TorTurn?) = when {
        tor != TorState.Off -> tor
        turn != null -> TorState.Connecting(null)
        else -> TorState.Failed
    }

    private val lock = Any()
    /** The latest request; TorSwitch runs them in order, so this one ends last and an overtaken one has nothing to say. */
    private var latest = 0
    /** Only while an error stands: with the switch off and no failure, nothing of this class runs. */
    private var errorWatch: Job? = null

    override fun turnOn() = turn(TorTurn.ON, TorSwitchError.ON_FAILED) { switch.turnOn() }

    override fun turnOff() = turn(TorTurn.OFF, TorSwitchError.OFF_FAILED) { switch.turnOff() }

    private fun turn(to: TorTurn, failure: TorSwitchError, request: suspend () -> Unit) {
        val me = synchronized(lock) {
            setError(null)
            turningNow.value = to
            ++latest
        }
        scope.launch {
            var failed: TorSwitchError? = null
            val shown = launch { delay(minTurningMs) }
            try {
                request()
                shown.join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { Log.w("TorUi", "switch failed: ${e.javaClass.simpleName}") }
                shown.cancel()
                failed = failure
            } finally {
                synchronized(lock) {
                    if (me == latest) {
                        setError(failed)
                        turningNow.value = null
                    }
                }
            }
        }
    }

    /** Under [lock] or not, the last call wins. An error goes with the next way into Connected; a turnOff that left Tor connected is not one, so it stands. */
    private fun setError(error: TorSwitchError?) {
        errorWatch?.cancel()
        errorWatch = null
        errorNow.value = error
        if (error != null && gate.mode.value is NetworkMode.Tor) {
            errorWatch = scope.launch {
                torState().dropWhile { it == TorState.Connected }.first { it == TorState.Connected }
                errorNow.value = null
            }
        }
    }

    /**
     * With the marker off a turnOff stopped half way, and Tor comes back through a whole switch; so it does on new ports after a port
     * conflict, which a new attempt on the old ones cannot cure. Otherwise the app is the owner again, in case it was not.
     */
    override fun retry(): Boolean {
        clearError()
        if (!store.enabled.value) {
            turnOn()
            return true
        }
        if (portConflict()) {
            // another owner may have kept that Tor, on Failed: then only this moves it to the new ports
            turn(TorTurn.ON, TorSwitchError.ON_FAILED) {
                switch.newPorts()
                torRetry()
            }
            return true
        }
        switch.onForeground()
        torRetry()
        return false
    }

    override fun clearError() = setError(null)

    override suspend fun tipsToDrop(): Int = switch.pendingUnderTor()

    override val restartsOnSwitch: Boolean get() = directRelayRouteUsed()

    override fun armRestartReturn(route: String?) { restartReturn?.arm(route) }

    override fun takeRestartReturn(): String? = restartReturn?.take()
}

/** A [StateFlow] whose value is read where it lives, so it is never behind and nothing runs until someone collects. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class Derived<T>(private val read: () -> T, private val changes: Flow<T>) : StateFlow<T> {
    override val value: T get() = read()
    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        changes.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
