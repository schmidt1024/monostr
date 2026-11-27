package com.monostr.app.net

import com.monostr.app.session.TorRouteHistory
import com.monostr.app.session.followTor
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec «Tor isolation per account» and «Tor wait»: SIGNAL NEWNYM between two accounts' sessions, at most one per 10 s. */
@OptIn(ExperimentalCoroutinesApi::class)
class TorNewIdentityTest {
    /** [answer] decides each NEWNYM; [signals] holds the virtual time of every one sent. */
    private class FakeEngine(val clock: () -> Long, var answer: suspend () -> Boolean = { true }) : TorEngine {
        val signals = mutableListOf<Long>()
        override suspend fun start(ports: NetworkMode.Tor): Flow<TorState> = emptyFlow()
        override suspend fun stop() = Unit
        override suspend fun newIdentity(): Boolean {
            signals += clock()
            return answer()
        }
    }

    private fun TestScope.setUp(): Pair<FakeEngine, TorController> {
        val engine = FakeEngine({ testScheduler.currentTime })
        return engine to TorController(engine, backgroundScope, log = {}) { NetworkMode.Tor(9101, 9102) }
    }

    private fun TestScope.now(): () -> Long = { testScheduler.currentTime }

    @Test
    fun `first switch signals at once`() = runTest {
        val (engine, c) = setUp()
        assertTrue(c.newIdentity(now()))
        assertEquals(listOf(0L), engine.signals)
        assertEquals(FreshRoute.Ready, c.freshRoute.value)
    }

    @Test
    fun `second switch within window waits`() = runTest {
        val (engine, c) = setUp()
        assertTrue(c.newIdentity(now()))
        advanceTimeBy(4_000)
        val second = async { c.newIdentity(now()) }
        advanceTimeBy(6_499)
        runCurrent()
        assertEquals(listOf(0L), engine.signals, "tor's 10 s window and the half second of margin are not over yet")
        advanceTimeBy(1)
        runCurrent()
        assertTrue(second.await())
        assertEquals(listOf(0L, 10_500L), engine.signals)
    }

    @Test
    fun `the window runs on the monotonic clock it is given, a wall clock jump does not shorten it`() = runTest {
        val (engine, c) = setUp()
        // what the default reads: elapsed time since boot, which a user or network time change does not move
        var monotonic = 1_000_000L
        assertTrue(c.newIdentity { monotonic })
        val second = async { c.newIdentity { monotonic } }
        runCurrent()
        assertEquals(FreshRoute.Waiting(11), c.freshRoute.value)
        monotonic += 10_499
        advanceTimeBy(10_499)
        runCurrent()
        assertEquals(1, engine.signals.size)
        monotonic += 1
        advanceTimeBy(1)
        runCurrent()
        assertTrue(second.await())
        assertEquals(2, engine.signals.size)
    }

    @Test
    fun `a pending route clears on the acknowledgement only`() = runTest {
        val (engine, c) = setUp()
        c.setRoutePending(true)
        assertTrue(c.routePending.value)
        engine.answer = { awaitCancellation() }
        val wait = launch { c.awaitNewIdentity(now = now()) }
        advanceTimeBy(5_000)
        wait.cancel() // Tor dropped or the session closed: the next start or a new wait decides, nothing leaks meanwhile
        runCurrent()
        assertTrue(c.routePending.value)
        engine.answer = { true }
        c.awaitNewIdentity(now = now())
        assertFalse(c.routePending.value)
    }

    @Test
    fun `a failed NEWNYM keeps the route pending`() = runTest {
        val (engine, c) = setUp()
        c.setRoutePending(true)
        engine.answer = { false }
        assertFalse(c.newIdentity(now()))
        assertTrue(c.routePending.value)
    }

    @Test
    fun `freshRoute counts down then ready`() = runTest {
        val (_, c) = setUp()
        assertTrue(c.newIdentity(now()))
        advanceTimeBy(2_500)
        val seen = mutableListOf<FreshRoute>()
        val watch = launch { c.freshRoute.collect { seen += it } }
        runCurrent()
        val second = async { c.newIdentity(now()) }
        advanceUntilIdle()
        assertTrue(second.await())
        watch.cancel()
        // 7 500 ms left: 8 until the next whole second, then one step each second
        val expected = listOf(FreshRoute.Ready) + (8 downTo 1).map { FreshRoute.Waiting(it) } + FreshRoute.Ready
        assertEquals(expected, seen)
    }

    @Test
    fun `freshRoute stalled after 15 s`() = runTest {
        val (engine, c) = setUp()
        engine.answer = { awaitCancellation() }
        val attempt = async { c.newIdentity(now()) }
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(FreshRoute.Ready, c.freshRoute.value, "no countdown when the window is open, no stall yet")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(FreshRoute.Stalled, c.freshRoute.value)
        assertFalse(attempt.await())
    }

    @Test
    fun `failure returns false after timeout`() = runTest {
        val (engine, c) = setUp()
        engine.answer = { awaitCancellation() }
        val began = testScheduler.currentTime
        assertFalse(c.newIdentity(now()))
        assertEquals(15_000L, testScheduler.currentTime - began)
        // a failed NEWNYM opens no window: the retry goes out at once once tor answers
        engine.answer = { true }
        assertTrue(c.newIdentity(now()))
        assertEquals(listOf(0L, 15_000L), engine.signals)
        assertEquals(FreshRoute.Ready, c.freshRoute.value)
    }

    @Test
    fun `retries every 10 s until acknowledged, stays stalled meanwhile`() = runTest {
        val (engine, c) = setUp()
        var refusals = 2
        engine.answer = { refusals-- <= 0 }
        val wait = launch { c.awaitNewIdentity(now = now()) }
        runCurrent()
        assertEquals(FreshRoute.Stalled, c.freshRoute.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(FreshRoute.Stalled, c.freshRoute.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(wait.isCompleted)
        assertEquals(listOf(0L, 10_000L, 20_000L), engine.signals)
        assertEquals(FreshRoute.Ready, c.freshRoute.value)
    }

    @Test
    fun `a wait nobody needs any more leaves no countdown behind`() = runTest {
        val (_, c) = setUp()
        assertTrue(c.newIdentity(now()))
        val wait = launch { c.awaitNewIdentity(now = now()) }
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(c.freshRoute.value is FreshRoute.Waiting)
        wait.cancel() // the session closed (next switch) or Tor dropped
        runCurrent()
        assertEquals(FreshRoute.Ready, c.freshRoute.value)
    }

    @Test
    fun `direct mode never signals`() {
        val history = TorRouteHistory()
        assertFalse(history.needsFreshRoute("a", viaTor = false))
        assertFalse(history.needsFreshRoute("a", viaTor = true), "no account went via this tor before")
        history.opened("a")
        assertFalse(history.needsFreshRoute("b", viaTor = false), "direct: nothing to isolate")
        assertTrue(history.needsFreshRoute("b", viaTor = true))
        history.opened("b")
        assertFalse(history.needsFreshRoute("b", viaTor = true), "the same account again (Tor switch rebuild, restore)")
        assertTrue(history.needsFreshRoute("a", viaTor = true), "b's circuits may still be in tor")
    }

    @Test
    fun `an account that never opened does not own tor's circuits`() {
        val history = TorRouteHistory()
        history.opened("a")
        assertTrue(history.needsFreshRoute("b", viaTor = true))
        // b torn down before its NEWNYM was acknowledged (Tor off and on, re-login, a start that threw): a's circuits are still there
        assertTrue(history.needsFreshRoute("b", viaTor = true))
        assertFalse(history.needsFreshRoute("a", viaTor = true), "back to a: its own circuits")
    }

    @Test
    fun `engine opens only after fresh route`() = runTest {
        val state = MutableStateFlow<TorState>(TorState.Connecting(null))
        val route = CompletableDeferred<Unit>()
        var fresh = 0
        var opens = 0
        val job = launch {
            followTor(state, hold = {}, freshRoute = { fresh++; route.await() }) { opens++ }
        }
        advanceUntilIdle()
        assertEquals(0, fresh, "no NEWNYM before Tor is connected")
        state.value = TorState.Connected
        advanceUntilIdle()
        assertEquals(1, fresh)
        assertEquals(0, opens)
        route.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, opens)
        // a later reconnect of the same session needs no new route
        state.value = TorState.Failed
        advanceUntilIdle()
        state.value = TorState.Connected
        advanceUntilIdle()
        assertEquals(1, fresh)
        assertEquals(2, opens)
        job.cancel()
    }

    @Test
    fun `Tor dropping during the wait asks for the fresh route again`() = runTest {
        val state = MutableStateFlow<TorState>(TorState.Connected)
        var fresh = 0
        var opens = 0
        val route = CompletableDeferred<Unit>()
        val job = launch { followTor(state, hold = {}, freshRoute = { fresh++; route.await() }) { opens++ } }
        advanceUntilIdle()
        state.value = TorState.Failed
        advanceUntilIdle()
        route.complete(Unit)
        state.value = TorState.Connected
        advanceUntilIdle()
        assertEquals(2, fresh)
        assertEquals(1, opens)
        job.cancel()
    }

    @Test
    fun `on the acknowledgement the pool goes before the gate opens`() = runTest {
        val (_, c) = setUp()
        c.setRoutePending(true)
        val order = mutableListOf<String>()
        c.awaitNewIdentity(now = now(), onAck = { order += "drop pending=${c.routePending.value}" })
        order += "after pending=${c.routePending.value}"
        assertEquals(listOf("drop pending=true", "after pending=false"), order)
    }

    @Test
    fun `an account change to one not yet known needs a fresh route once tor carried an account`() {
        val history = TorRouteHistory()
        assertFalse(history.needsFreshRoute(null, viaTor = true))
        history.opened("a")
        assertTrue(history.needsFreshRoute(null, viaTor = true))
        assertFalse(history.needsFreshRoute(null, viaTor = false))
    }
}
