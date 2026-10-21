package com.monostr.nostr.repo

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spec perf2 C4: turns change signals into database reads. After a plain signal (a live event) it waits
 * [windowMs] for more, then reads once; a milestone (a job finished) reads at once and cuts a running window.
 * Both channels are conflated and drained right before the read, so a read covers every signal before it, and a
 * signal that arrives during the read causes exactly one more: the last change is never dropped.
 */
internal class SignalPacer(private val windowMs: Long) {
    private val plain = Channel<Unit>(Channel.CONFLATED)
    private val milestones = Channel<Unit>(Channel.CONFLATED)

    fun signal(milestone: Boolean = false) {
        (if (milestone) milestones else plain).trySend(Unit)
    }

    /** One [read] per paced signal, until cancelled. A throwing [read] ends the loop; callers wrap it. */
    suspend fun run(read: suspend () -> Unit): Nothing {
        while (true) {
            val urgent = select<Boolean> {
                milestones.onReceive { true }
                plain.onReceive { false }
            }
            if (!urgent && windowMs > 0) withTimeoutOrNull(windowMs) { milestones.receive() }
            plain.tryReceive()
            milestones.tryReceive()
            read()
        }
    }
}
