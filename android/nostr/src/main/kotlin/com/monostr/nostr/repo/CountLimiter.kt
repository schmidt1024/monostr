package com.monostr.nostr.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Spec perf2 A6: at most [perRelay] NIP-45 COUNTs in flight per relay URL, shared by the note counts and the follower
 * counts (public relays allow ~10–20 subscriptions per connection, and the feed, DMs and lists hold theirs). A freed
 * permit goes to the oldest priority waiter (a thread's focused note) before any normal one.
 */
class CountLimiter(private val perRelay: Int = PER_RELAY) {
    private class Lane {
        var inUse = 0
        val high = ArrayDeque<CompletableDeferred<Unit>>()
        val low = ArrayDeque<CompletableDeferred<Unit>>()
    }

    private val lanes = HashMap<String, Lane>()

    suspend fun <T> withPermit(url: String, priority: Boolean = false, block: suspend () -> T): T {
        acquire(url, priority)
        try {
            return block()
        } finally {
            release(url)
        }
    }

    /** Permits held for [url] right now. */
    fun inUse(url: String): Int = synchronized(lanes) { lanes[url]?.inUse ?: 0 }

    private suspend fun acquire(url: String, priority: Boolean) {
        val waiter = synchronized(lanes) {
            val lane = lanes.getOrPut(url) { Lane() }
            if (lane.inUse < perRelay) {
                lane.inUse++
                null
            } else {
                CompletableDeferred<Unit>().also { if (priority) lane.high.addLast(it) else lane.low.addLast(it) }
            }
        } ?: return
        try {
            waiter.await()
        } catch (e: CancellationException) {
            // still queued: leave the queue; handed a permit at the same moment: pass it on
            val queued = synchronized(lanes) { lanes[url]?.let { it.high.remove(waiter) || it.low.remove(waiter) } == true }
            if (!queued) release(url)
            throw e
        }
    }

    /** Hands the permit straight to the next waiter (priority first); without one it goes back. */
    private fun release(url: String) {
        val next = synchronized(lanes) {
            val lane = lanes[url] ?: return
            val n = lane.high.removeFirstOrNull() ?: lane.low.removeFirstOrNull()
            if (n == null) {
                lane.inUse--
                if (lane.inUse == 0) lanes.remove(url)
            }
            n
        }
        next?.complete(Unit)
    }

    companion object {
        const val PER_RELAY = 6
    }
}
