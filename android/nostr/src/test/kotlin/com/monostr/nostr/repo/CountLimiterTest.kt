package com.monostr.nostr.repo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CountLimiterTest {
    @Test
    fun `at most six per relay run, other relays are not held up`() = runTest {
        val l = CountLimiter()
        val release = CompletableDeferred<Unit>()
        var running = 0
        var most = 0
        repeat(10) { launch { l.withPermit("wss://a") { running++; most = maxOf(most, running); release.await(); running-- } } }
        var other = false
        launch { l.withPermit("wss://b") { other = true } }
        runCurrent()
        assertEquals(6, l.inUse("wss://a"))
        assertTrue(other)
        release.complete(Unit); advanceUntilIdle()
        assertEquals(6, most)
        assertEquals(0, l.inUse("wss://a"))
    }

    @Test
    fun `a priority waiter gets the next permit before earlier normal waiters`() = runTest {
        val l = CountLimiter(perRelay = 1)
        val order = ArrayList<String>()
        val first = CompletableDeferred<Unit>()
        launch { l.withPermit("r") { first.await() } }
        runCurrent()
        launch { l.withPermit("r") { order += "normal" } }
        launch { l.withPermit("r", priority = true) { order += "priority" } }
        runCurrent()
        assertTrue(order.isEmpty())
        first.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("priority", "normal"), order)
    }

    @Test
    fun `a cancelled waiter gives its place back`() = runTest {
        val l = CountLimiter(perRelay = 1)
        val hold = CompletableDeferred<Unit>()
        launch { l.withPermit("r") { hold.await() } }
        runCurrent()
        val waiter = launch { l.withPermit("r") { error("a cancelled waiter ran") } }
        runCurrent()
        waiter.cancel(); runCurrent()
        hold.complete(Unit); advanceUntilIdle()
        var later = false
        launch { l.withPermit("r") { later = true } }
        advanceUntilIdle()
        assertTrue(later)
        assertEquals(0, l.inUse("r"))
    }
}
