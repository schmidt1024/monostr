package com.monostr.app.work

import androidx.work.ExistingPeriodicWorkPolicy
import com.google.common.util.concurrent.ListenableFuture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The bounded wait of the app's start ([scheduleWith], [awaitWorkQueue]) on a stand-in for WorkManager's serial task executor, with a slow
 * task queued first in the place of WorkManager's start-up cleanup. What these tests do not show is that WorkManager really queues its
 * cleanup (ForceStopRunnable) first on that same executor, and the read and enqueue behind it: that is WorkManager 2.11.2's code, read
 * from its bytecode (see the comment on work-runtime in libs.versions.toml), and [WorkRuntimeVersionTest] fails when the version changes.
 * Why the start waits at all: [BackgroundCheckWorker.scheduleAtStart].
 */
class StartupScheduleTest {
    /** Stands in for WorkManager's serial task executor. */
    private val queue = Executors.newSingleThreadExecutor()
    private val log: MutableList<String> = Collections.synchronizedList(ArrayList())

    @AfterEach fun stop() { queue.shutdownNow() }

    private fun <T> onQueue(step: String, value: () -> T): ListenableFuture<T> = CompletableListenable<T>().also { done ->
        queue.execute { log += step; done.complete(value()) }
    }

    /** Queued before anything the start asks, as WorkManager's cleanup is: slow, as on a phone whose job binds first. */
    private fun slowTaskFirst(ms: Long = 300) = queue.execute { Thread.sleep(ms); log += "cleanup" }

    private fun startup(current: Long?, torOn: Boolean, timeoutMs: Long = 5_000): Boolean = awaitWorkQueue(
        scheduleWith(onQueue("read") { current }, backgroundIntervalMinutes(torOn)) { policy -> onQueue("enqueue $policy") { null } },
        timeoutMs,
    )

    @Test
    fun `the wait returns only after the tasks queued before it, the read and the enqueue have run`() {
        slowTaskFirst()
        assertTrue(startup(current = 60 * 60_000L, torOn = true))
        assertEquals(listOf("cleanup", "read", "enqueue ${ExistingPeriodicWorkPolicy.KEEP}"), log.toList())
    }

    @Test
    fun `the same period is enqueued with KEEP before the wait returns`() {
        slowTaskFirst()
        assertTrue(startup(current = 15 * 60_000L, torOn = false))
        assertEquals("enqueue ${ExistingPeriodicWorkPolicy.KEEP}", log.last())
    }

    @Test
    fun `another period is enqueued with UPDATE before the wait returns`() {
        slowTaskFirst()
        assertTrue(startup(current = 15 * 60_000L, torOn = true))
        assertEquals(listOf("cleanup", "read", "enqueue ${ExistingPeriodicWorkPolicy.UPDATE}"), log.toList())
    }

    @Test
    fun `a queue that hangs does not hold the start longer than the limit`() {
        val release = CountDownLatch(1)
        queue.execute { release.await() }
        val started = System.nanoTime()
        assertFalse(startup(current = null, torOn = true, timeoutMs = 200))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        release.countDown()
    }

    @Test
    fun `a failing read or enqueue still lets the start go on`() {
        val failed = CompletableListenable<Long?>().apply { completeExceptionally(IllegalStateException("db")) }
        assertTrue(awaitWorkQueue(scheduleWith(failed, 60) { throw IllegalStateException("enqueue") }, 1_000))
    }

    @Test
    fun `an interrupted wait ends at once and keeps the interrupt flag`() {
        queue.execute { Thread.sleep(5_000) }
        Thread.currentThread().interrupt()
        try {
            assertFalse(startup(current = null, torOn = true))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
