package com.monostr.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshGateTest {
    private var clock = 1_000L
    private fun TestScope.gate(ttl: Long = 60) =
        RefreshGate<String>(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), ttl) { clock }

    @Test
    fun `one refresh per key at a time, a second caller shares it`() = runTest {
        val g = gate()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val a = g.launch("k") { runs++; release.await() }
        val b = g.launch("k") { runs++ }
        assertSame(a, b)
        assertTrue(g.isRunning("k"))
        release.complete(Unit); runCurrent()
        assertEquals(1, runs)
        assertEquals(1_000L, g.fetchedAt("k"))
        assertFalse(g.isRunning("k"))
    }

    @Test
    fun `a fresh key is not refreshed again before the ttl passed, force and invalidate run it`() = runTest {
        val g = gate(ttl = 60)
        var runs = 0
        g.await("k") { runs++ }
        assertNull(g.launch("k") { runs++ })
        clock += 59
        g.await("k") { runs++ }
        assertEquals(1, runs)
        g.await("k", force = true) { runs++ }
        assertEquals(2, runs)
        clock += 60
        g.await("k") { runs++ }
        assertEquals(3, runs)
        g.invalidate("k")
        g.await("k") { runs++ }
        assertEquals(4, runs)
        g.clear()
        assertFalse(g.isFresh("k"))
    }

    @Test
    fun `a cancelled waiter leaves the refresh running`() = runTest {
        val g = gate()
        val release = CompletableDeferred<Unit>()
        val waiter = launch { g.await("k") { release.await() } }
        runCurrent()
        waiter.cancelAndJoin()
        assertTrue(g.isRunning("k"))
        release.complete(Unit); runCurrent()
        assertEquals(1_000L, g.fetchedAt("k"))
    }

    @Test
    fun `a failing refresh counts as done and does not throw at the waiter`() = runTest {
        val g = gate()
        g.await("k") { error("relay down") }
        assertEquals(1_000L, g.fetchedAt("k"))
        assertTrue(g.isFresh("k"))
    }

    @Test
    fun `a run whose block says no answer is not stamped, one that answered is`() = runTest {
        val g = gate()
        var runs = 0
        g.awaitStamped("k") { runs++; false }
        assertFalse(g.isFresh("k"))
        assertNull(g.fetchedAt("k"))
        g.awaitStamped("k") { runs++; false }
        assertEquals(2, runs)
        g.awaitStamped("k") { runs++; true }
        assertTrue(g.isFresh("k"))
        g.awaitStamped("k") { runs++; true }
        assertEquals(3, runs)
    }

    @Test
    fun `a cancelled caller does not make an unanswered run count as done`() = runTest {
        val g = gate()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val caller = launch { g.awaitStamped("k") { runs++; release.await(); false } }
        runCurrent()
        caller.cancelAndJoin()
        assertTrue(g.isRunning("k"))
        release.complete(Unit); runCurrent()
        assertFalse(g.isFresh("k"))
        g.awaitStamped("k") { runs++; true }
        assertEquals(2, runs)
    }
}
