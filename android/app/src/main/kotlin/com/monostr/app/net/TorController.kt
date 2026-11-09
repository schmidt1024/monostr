package com.monostr.app.net

import android.util.Log
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private class BootstrapTimeoutException : Exception()
private class EngineFinishedException : Exception()

sealed interface TorState {
    data object Off : TorState
    /** Bootstrapping; [progress] in percent once Tor has said so. */
    data class Connecting(val progress: Int?) : TorState
    data object Connected : TorState
    /** Tor is not usable and connections on its ports fail. It may come back by itself; if not, [TorController.retry] or a new owner starts it. */
    data object Failed : TorState
}

/**
 * Runs one Tor. The flow reports what is true now: [TorState.Failed] when Tor dies, then, if it comes back on its own,
 * [TorState.Connecting] and [TorState.Connected] again. A flow that throws or ends means this Tor is finished. [stop] is
 * called after every [start], also a failed one, and returns when the Tor is gone.
 */
interface TorEngine {
    suspend fun start(ports: NetworkMode.Tor): Flow<TorState>
    suspend fun stop()
}

/**
 * The shared lifetime of Tor (spec 2026-11-06 5.8): it runs while it has an owner, and stops [graceMs] after the last [release],
 * or at once after a [releaseRun] or [releaseNow]. Safe to call from any thread, all these calls return at once. Holds nothing of Tor itself, the [engine] does.
 *
 * @param log for the reason of a failure: exception types only, as a message may hold a destination.
 * @param ports asked once per start, off the caller's thread.
 */
class TorController(
    private val engine: TorEngine,
    private val scope: CoroutineScope,
    private val graceMs: Long = 300_000,
    private val bootstrapTimeoutMs: Long = 120_000,
    private val log: (String) -> Unit = { Log.w("TorController", it) },
    private val ports: () -> NetworkMode.Tor,
) {
    private val current = MutableStateFlow<TorState>(TorState.Off)
    val state: StateFlow<TorState> = current

    private val lock = Any()
    private val owners = HashSet<String>()
    private var grace: Job? = null
    /** The attempt whose states count; a cancelled one may still be stopping the engine. */
    private var attempt: Job? = null
    /** Held from start to the end of stop: a new Tor waits until the old process is gone. */
    private val engineTurn = Mutex()

    fun acquire(owner: String) {
        synchronized(lock) {
            // a new owner is the one moment to try a Failed Tor again, a second one included: the app coming to the front during a failed run. One attempt per join
            if (!owners.add(owner)) return
            val state = current.value
            if (state == TorState.Off || state == TorState.Failed) startAttempt()
        }
    }

    /** Tor stays for [graceMs] from here at least, whoever else comes and goes meanwhile. */
    fun release(owner: String) {
        synchronized(lock) {
            if (!owners.remove(owner)) return
            grace?.cancel()
            grace = scope.launch {
                delay(graceMs)
                synchronized(lock) {
                    // cancelled under the lock by the next release, so an old timer cannot pass after it
                    if (isActive) {
                        grace = null
                        if (owners.isEmpty()) stopTor()
                    }
                }
            }
        }
    }

    /**
     * The end of a background run (spec 5.7, "a run starts Tor, works, and stops Tor"): no grace of its own, as the process may freeze or go
     * right after the run, and nobody would stop Tor then. Another owner keeps Tor, and so does what is left of a grace, which belongs to
     * the app that left (spec 5.8, a wallet trip): a run must not shorten it.
     */
    fun releaseRun(owner: String) {
        synchronized(lock) {
            if (owners.remove(owner) && owners.isEmpty() && grace == null) stopTor()
        }
    }

    /** The deliberate end: as [release] without the grace, so nothing runs on with nobody wanting it. Another owner keeps Tor, under the usual rules. */
    fun releaseNow(owner: String) {
        synchronized(lock) {
            owners.remove(owner)
            grace?.cancel()
            grace = null
            if (owners.isEmpty()) stopTor()
        }
    }

    /** Under [lock]. */
    private fun stopTor() {
        attempt?.cancel()
        attempt = null
        current.value = TorState.Off
    }

    /** The last attempt ended on a port someone else holds, which a new attempt on the same ports cannot cure. */
    val portConflict: Boolean get() = conflict
    @Volatile private var conflict = false

    /** From [TorState.Failed] only, and only while someone wants Tor. */
    fun retry() {
        synchronized(lock) {
            if (owners.isNotEmpty() && current.value == TorState.Failed) startAttempt()
        }
    }

    /** Under [lock]. The state moves here, not in the coroutine, so a second call right after this one sees it. */
    private fun startAttempt() {
        attempt?.cancel()
        conflict = false
        current.value = TorState.Connecting(null)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            engineTurn.withLock {
                val me = coroutineContext.job
                try {
                    runEngine(me)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("failed: ${e.javaClass.simpleName}")
                    if (e is java.net.BindException) conflict = true
                    publish(me, TorState.Failed)
                } finally {
                    // a stop that fails leaves a process the next start clears away
                    withContext(NonCancellable) {
                        runCatching { engine.stop() }.onFailure { log("stop failed: ${it.javaClass.simpleName}") }
                    }
                }
            }
        }
        attempt = job
        job.start()
    }

    private suspend fun runEngine(me: Job): Nothing = coroutineScope {
        fun watchdog() = launch {
            delay(bootstrapTimeoutMs)
            throw BootstrapTimeoutException()
        }
        // from here on, so that a start hanging before its first state is covered too
        var bootstrapping: Job? = watchdog()
        engine.start(ports()).collect { state ->
            if (state is TorState.Connecting) {
                if (bootstrapping == null) bootstrapping = watchdog()
                publish(me, state)
            } else {
                bootstrapping?.cancel()
                bootstrapping = null
                // nobody but this class says Off
                publish(me, if (state == TorState.Connected) state else TorState.Failed)
            }
        }
        throw EngineFinishedException()
    }

    private fun publish(from: Job, state: TorState) {
        synchronized(lock) { if (attempt === from) current.value = state }
    }
}
