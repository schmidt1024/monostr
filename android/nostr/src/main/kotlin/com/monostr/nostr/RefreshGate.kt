package com.monostr.nostr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap

/**
 * Spec perf 3.1: one background refresh per key at a time, run on [scope] (the engine's background scope, so
 * leaving a screen never cancels it), and none again for [ttlSeconds] after one finished in this process. A
 * refresh that fails counts as done (best effort: the next one is due after the TTL); a cancelled one does not.
 */
class RefreshGate<K : Any>(
    private val scope: CoroutineScope,
    private val ttlSeconds: Long,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val running = ConcurrentHashMap<K, Deferred<Unit>>()
    private val doneAt = ConcurrentHashMap<K, Long>()

    /** Epoch seconds when the last refresh of [key] finished in this process; null before one. */
    fun fetchedAt(key: K): Long? = doneAt[key]

    /** A refresh of [key] finished less than [ttlSeconds] ago. */
    fun isFresh(key: K): Boolean = doneAt[key]?.let { now() - it < ttlSeconds } == true

    fun isRunning(key: K): Boolean = running[key]?.isCompleted == false

    /**
     * Starts [block] for [key] and returns at once: the running refresh when one is in flight (never a second
     * one), null when [key] is fresh and [force] is false.
     */
    fun launch(key: K, force: Boolean = false, block: suspend () -> Unit): Deferred<Unit>? =
        launchStamped(key, force) { block(); true }

    /**
     * [launch] for a [block] that says whether the refresh got an answer: false ends the run without stamping the
     * key, so it stays stale and the next call runs again (offline, no relay replied). Decided here, not by the
     * caller, because the run outlives a cancelled caller. A failure (exception) still counts as done.
     */
    fun launchStamped(key: K, force: Boolean = false, block: suspend () -> Boolean): Deferred<Unit>? {
        if (!force && isFresh(key)) return null
        var created: Deferred<Unit>? = null
        val job = running.compute(key) { _, current ->
            if (current != null && !current.isCompleted) current
            else scope.async(start = CoroutineStart.LAZY) { run(key, block) }.also { created = it }
        }!!
        created?.let { d ->
            d.invokeOnCompletion { running.remove(key, d) }
            d.start()
        }
        return job
    }

    /** [launch], then waits for that refresh; returns at once when [key] is fresh. Cancelling the caller leaves the refresh running. */
    suspend fun await(key: K, force: Boolean = false, block: suspend () -> Unit) = awaitStamped(key, force) { block(); true }

    /** [launchStamped], then waits for that refresh; see [await]. */
    suspend fun awaitStamped(key: K, force: Boolean = false, block: suspend () -> Boolean) {
        val job = launchStamped(key, force, block) ?: return
        try {
            job.await()
        } catch (e: CancellationException) {
            // the caller was cancelled: rethrow; otherwise the gate's scope ended and there is nothing to wait for
            currentCoroutineContext().ensureActive()
        }
    }

    fun invalidate(key: K) {
        doneAt.remove(key)
    }

    fun clear() = doneAt.clear()

    private suspend fun run(key: K, block: suspend () -> Boolean) {
        var answered = true
        try {
            answered = block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Swallowed on purpose: a refresh is best effort and must never crash a screen. What is stored
            // stays, and the refresh counts as done (the next one is due after the TTL).
        }
        if (answered) doneAt[key] = now()
    }
}
