package com.monostr.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Spec perf2 A2: the network leg of a session's background work waits here until the home feed's first fresh page
 * is in (or [TimedStartupGate.timeoutMs] passed, the user went to another screen, a worker or a write needs it).
 * Rule (spec §3.0): [await] only inside background launches, never on a screen's render path, never under a mutex.
 * Not [RefreshGate], which dedupes refreshes per key with a TTL.
 */
interface StartupGate {
    val isOpen: Boolean

    /** Returns at once once open; throws only a CancellationException (the caller's, or the session closing). */
    suspend fun await()

    /** Idempotent; the first call wins and is marked `startup gate open (<reason>)`. */
    fun open(reason: String)

    companion object {
        /** Never waits: tests, workers, screens built without a session. */
        val OPEN: StartupGate = object : StartupGate {
            override val isOpen: Boolean = true
            override suspend fun await() {}
            override fun open(reason: String) {}
        }
    }
}

/** One per session, on the engine's background scope: opens itself after [timeoutMs]; closing the scope (logout) ends every waiter. */
class TimedStartupGate(scope: CoroutineScope, val timeoutMs: Long = TIMEOUT_MS) : StartupGate {
    private val opened = CompletableDeferred<Unit>()
    private val timer: Job = scope.launch {
        delay(timeoutMs)
        open("timeout")
    }

    init {
        // a waiter on a screen's scope would otherwise outlive the session and run its network leg after logout
        scope.coroutineContext[Job]?.invokeOnCompletion { opened.cancel() }
    }

    override val isOpen: Boolean get() = opened.isCompleted && !opened.isCancelled

    override suspend fun await() = opened.await()

    override fun open(reason: String) {
        if (opened.complete(Unit)) {
            timer.cancel()
            PerfTrace.mark("startup gate open ($reason)")
        }
    }

    companion object {
        const val TIMEOUT_MS = 3_000L
    }
}
