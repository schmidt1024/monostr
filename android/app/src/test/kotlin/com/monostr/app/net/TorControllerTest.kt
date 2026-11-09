package com.monostr.app.net

import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TorControllerTest {
    private val ports = NetworkMode.Tor(9101, 9102)
    private val grace = 1_000L
    private val timeout = 5_000L

    /** Each start gets a fresh channel; a test pushes states into [states]. Records calls in order. */
    private class FakeEngine(val stopTakesMs: Long = 0, val startThrows: () -> Exception? = { null }) : TorEngine {
        val calls = mutableListOf<String>()
        var states = Channel<TorState>(Channel.UNLIMITED)
        var startedWith: NetworkMode.Tor? = null

        override suspend fun start(ports: NetworkMode.Tor): Flow<TorState> {
            calls += "start"
            startedWith = ports
            startThrows()?.let { throw it }
            states = Channel(Channel.UNLIMITED)
            return states.receiveAsFlow()
        }

        override suspend fun stop() {
            delay(stopTakesMs)
            calls += "stop"
        }
    }

    private fun TestScope.controller(engine: TorEngine, pick: () -> NetworkMode.Tor = { ports }) =
        TorController(engine, backgroundScope, graceMs = grace, bootstrapTimeoutMs = timeout, log = {}, ports = pick)

    private fun TestScope.connected(engine: FakeEngine, c: TorController, owner: String = "a") {
        c.acquire(owner)
        runCurrent()
        engine.states.trySend(TorState.Connected)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
    }

    @Test
    fun `a port someone else holds is known as such until the next attempt, any other failure is not`() = runTest {
        var failure: Exception? = java.net.BindException("in use")
        val engine = FakeEngine(startThrows = { failure })
        val c = controller(engine)
        c.acquire("a")
        runCurrent()
        assertEquals(TorState.Failed, c.state.value)
        assertTrue(c.portConflict)
        failure = IOException("cannot bind service")
        c.retry()
        runCurrent()
        assertEquals(TorState.Failed, c.state.value)
        assertEquals(false, c.portConflict)
    }

    @Test
    fun `off until acquired`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun `acquire starts once for two owners`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        c.acquire("a")
        c.acquire("b")
        runCurrent()
        assertEquals(listOf("start"), engine.calls)
        assertEquals(ports, engine.startedWith)
        assertEquals(TorState.Connecting(null), c.state.value)
    }

    @Test
    fun `progress and connected are published`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        c.acquire("a")
        runCurrent()
        engine.states.trySend(TorState.Connecting(42))
        runCurrent()
        assertEquals(TorState.Connecting(42), c.state.value)
        engine.states.trySend(TorState.Connected)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
    }

    @Test
    fun `release keeps running for the grace time, then stops`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c)
        c.release("a")
        advanceTimeBy(grace - 1)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        assertEquals(listOf("start"), engine.calls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
        assertEquals(listOf("start", "stop"), engine.calls)
    }

    @Test
    fun `one of two owners releasing keeps it running past the grace time`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c)
        c.acquire("b")
        c.release("a")
        advanceTimeBy(grace * 3)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        assertEquals(listOf("start"), engine.calls)
    }

    @Test
    fun `acquire during grace keeps Tor running, the grace running out does not stop it`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c)
        c.release("a")
        advanceTimeBy(grace / 2)
        c.acquire("b")
        advanceTimeBy(grace * 3)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        assertEquals(listOf("start"), engine.calls)
    }

    @Test
    fun `release after a cancelled grace waits a full grace again`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c)
        c.release("a")
        advanceTimeBy(grace - 10)
        c.acquire("a")
        c.release("a")
        advanceTimeBy(grace - 10)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        advanceTimeBy(20)
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
    }

    @Test
    fun `acquiring the same owner twice needs only one release`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c)
        c.acquire("a")
        c.release("a")
        advanceTimeBy(grace + 1)
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
        assertEquals(listOf("start", "stop"), engine.calls)
    }

    @Test
    fun `releaseNow of the last owner stops without the grace`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c)
        c.releaseNow("a")
        assertEquals(TorState.Off, c.state.value)
        runCurrent()
        assertEquals(listOf("start", "stop"), engine.calls)
    }

    @Test
    fun `releaseNow keeps Tor for another owner, and that owner's release still has its grace`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c, "foreground")
        c.acquire("worker")
        c.releaseNow("foreground")
        advanceTimeBy(grace * 3); runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        assertEquals(listOf("start"), engine.calls)
        c.release("worker")
        advanceTimeBy(grace - 1); runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        advanceTimeBy(2); runCurrent()
        assertEquals(TorState.Off, c.state.value)
    }

    @Test
    fun `releaseNow during a running grace stops at once, and a new owner starts again`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c)
        c.release("a")
        advanceTimeBy(grace / 2); runCurrent()
        c.releaseNow("a")
        assertEquals(TorState.Off, c.state.value)
        advanceTimeBy(grace); runCurrent()
        assertEquals(TorState.Off, c.state.value)
        c.acquire("b")
        runCurrent()
        assertEquals(listOf("start", "stop", "start"), engine.calls)
    }

    @Test
    fun `a run ending with nobody else stops Tor at once, not a grace later`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c, "worker")
        c.releaseRun("worker")
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
        assertEquals(listOf("start", "stop"), engine.calls)
    }

    @Test
    fun `a run ending while still bootstrapping cancels the attempt`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        c.acquire("worker")
        runCurrent()
        engine.states.trySend(TorState.Connecting(40))
        runCurrent()
        c.releaseRun("worker")
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
        assertEquals(listOf("start", "stop"), engine.calls)
    }

    @Test
    fun `a run ending never stops Tor under the visible app, in either order`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c, "worker") // worker running when the app opens
        c.acquire("foreground")
        c.releaseRun("worker")
        advanceTimeBy(grace * 3)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        c.acquire("worker") // and the app was there first
        runCurrent()
        c.releaseRun("worker")
        advanceTimeBy(grace * 3)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        assertEquals(listOf("start"), engine.calls)
    }

    @Test
    fun `the app closing while a run works keeps its grace past the end of the run`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c, "worker")
        c.acquire("foreground")
        c.release("foreground")
        advanceTimeBy(grace / 2)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        c.releaseRun("worker")
        runCurrent()
        assertEquals(TorState.Connected, c.state.value, "the wallet trip still has the rest of its grace")
        advanceTimeBy(grace / 2 + 1)
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
        assertEquals(listOf("start", "stop"), engine.calls)
    }

    @Test
    fun `the app's grace running out while a run works stops nothing, the end of the run does`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c, "foreground")
        c.release("foreground")
        c.acquire("worker") // a run begins during the grace: no second bootstrap
        advanceTimeBy(grace * 2)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        assertEquals(listOf("start"), engine.calls)
        c.releaseRun("worker")
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
        assertEquals(listOf("start", "stop"), engine.calls)
    }

    @Test
    fun `a run that began and ended within the app's grace leaves the rest of it, no shorter and no longer`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c, "foreground")
        c.release("foreground")
        advanceTimeBy(grace / 4)
        runCurrent()
        c.acquire("worker")
        advanceTimeBy(grace / 4)
        runCurrent()
        c.releaseRun("worker")
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        advanceTimeBy(grace / 2 - 1)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
    }

    @Test
    fun `the deliberate off during a run leaves it to the run, whose end then stops Tor at once`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c, "foreground")
        c.acquire("worker")
        c.releaseNow("foreground")
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        c.releaseRun("worker")
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
    }

    @Test
    fun `releaseRun of an owner that is not there changes nothing`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c, "foreground")
        c.releaseRun("worker")
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
    }

    @Test
    fun `release without acquire is harmless`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        c.release("nobody")
        advanceTimeBy(grace * 2)
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun `release of an unknown owner neither stops nor extends the grace`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c)
        c.release("nobody")
        advanceTimeBy(grace * 2)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        c.release("a")
        advanceTimeBy(grace / 2)
        c.release("nobody")
        advanceTimeBy(grace / 2 + 1)
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
    }

    @Test
    fun `no bootstrap within the timeout is Failed`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        c.acquire("a")
        runCurrent()
        engine.states.trySend(TorState.Connecting(10))
        advanceTimeBy(timeout - 1)
        runCurrent()
        assertEquals(TorState.Connecting(10), c.state.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(TorState.Failed, c.state.value)
        assertEquals(listOf("start", "stop"), engine.calls)
    }

    @Test
    fun `the timeout also covers a start that never returns`() = runTest {
        val engine = object : TorEngine {
            var stopped = 0
            override suspend fun start(ports: NetworkMode.Tor): Flow<TorState> { delay(Long.MAX_VALUE); error("unreachable") }
            override suspend fun stop() { stopped++ }
        }
        val c = controller(engine)
        c.acquire("a")
        advanceTimeBy(timeout + 1)
        runCurrent()
        assertEquals(TorState.Failed, c.state.value)
        assertEquals(1, engine.stopped)
    }

    @Test
    fun `connected in time is not touched by the timeout`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c)
        advanceTimeBy(timeout * 3)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
    }

    @Test
    fun `engine failure is Failed, retry starts again`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        c.acquire("a")
        runCurrent()
        engine.states.trySend(TorState.Failed)
        runCurrent()
        assertEquals(TorState.Failed, c.state.value)
        c.retry()
        assertEquals(TorState.Connecting(null), c.state.value)
        runCurrent()
        assertEquals(listOf("start", "stop", "start"), engine.calls)
        engine.states.trySend(TorState.Connected)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
    }

    @Test
    fun `start throwing is Failed, and the engine is stopped`() = runTest {
        var fail = true
        val engine = FakeEngine(startThrows = { if (fail) IOException("no") else null })
        val c = controller(engine)
        c.acquire("a")
        runCurrent()
        assertEquals(TorState.Failed, c.state.value)
        assertEquals(listOf("start", "stop"), engine.calls)
        fail = false
        c.retry()
        runCurrent()
        assertEquals(TorState.Connecting(null), c.state.value)
    }

    @Test
    fun `no ports is Failed`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine) { throw IOException("no port") }
        c.acquire("a")
        runCurrent()
        assertEquals(TorState.Failed, c.state.value)
    }

    @Test
    fun `flow failing or ending is Failed and stops the engine`() = runTest {
        val cases = listOf<Flow<TorState>>(flow { throw IOException("wrong ports") }, flow { emit(TorState.Connected) })
        for (case in cases) {
            val calls = mutableListOf<String>()
            val engine = object : TorEngine {
                override suspend fun start(ports: NetworkMode.Tor): Flow<TorState> { calls += "start"; return case }
                override suspend fun stop() { calls += "stop" }
            }
            val c = controller(engine)
            c.acquire("a")
            runCurrent()
            assertEquals(TorState.Failed, c.state.value)
            assertEquals(listOf("start", "stop"), calls)
        }
    }

    @Test
    fun `Tor dying while connected is Failed, neither Off nor Connected, and it may come back`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c)
        engine.states.trySend(TorState.Failed)
        runCurrent()
        assertEquals(TorState.Failed, c.state.value)
        // the system restarts the service: the engine reports it again, no new start
        engine.states.trySend(TorState.Connecting(null))
        runCurrent()
        assertEquals(TorState.Connecting(null), c.state.value)
        engine.states.trySend(TorState.Connected)
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        assertEquals(listOf("start"), engine.calls)
    }

    @Test
    fun `a comeback that does not finish in time is Failed`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c)
        engine.states.trySend(TorState.Failed)
        advanceTimeBy(timeout * 2)
        engine.states.trySend(TorState.Connecting(null))
        advanceTimeBy(timeout + 1)
        runCurrent()
        assertEquals(TorState.Failed, c.state.value)
        assertEquals(listOf("start", "stop"), engine.calls)
    }

    @Test
    fun `an engine reporting Off on its own is Failed`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c)
        engine.states.trySend(TorState.Off)
        runCurrent()
        assertEquals(TorState.Failed, c.state.value)
    }

    @Test
    fun `retry does nothing unless Failed with an owner`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        c.retry()
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
        assertEquals(emptyList<String>(), engine.calls)
        c.acquire("a")
        runCurrent()
        c.retry()
        runCurrent()
        assertEquals(listOf("start"), engine.calls)
        engine.states.trySend(TorState.Connected)
        runCurrent()
        c.retry()
        runCurrent()
        assertEquals(listOf("start"), engine.calls)
        assertEquals(TorState.Connected, c.state.value)
    }

    @Test
    fun `two retries in a row start once`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        c.acquire("a")
        runCurrent()
        engine.states.trySend(TorState.Failed)
        runCurrent()
        c.retry()
        c.retry()
        runCurrent()
        assertEquals(listOf("start", "stop", "start"), engine.calls)
    }

    @Test
    fun `retry without an owner does not start`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        c.acquire("a")
        runCurrent()
        engine.states.trySend(TorState.Failed)
        runCurrent()
        c.release("a")
        c.retry()
        runCurrent()
        assertEquals(listOf("start"), engine.calls)
        advanceTimeBy(grace * 10)
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
    }

    @Test
    fun `coming back to a Failed Tor tries again, as does a second owner joining it`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        c.acquire("a")
        runCurrent()
        engine.states.trySend(TorState.Failed)
        runCurrent()
        c.acquire("b")
        assertEquals(TorState.Connecting(null), c.state.value)
        runCurrent()
        assertEquals(listOf("start", "stop", "start"), engine.calls)
        engine.states.trySend(TorState.Failed)
        runCurrent()
        c.release("a")
        c.release("b")
        c.acquire("a")
        runCurrent()
        assertEquals(TorState.Connecting(null), c.state.value)
        assertEquals(listOf("start", "stop", "start", "stop", "start"), engine.calls)
    }

    @Test
    fun `at most one attempt per join, also when each attempt fails`() = runTest {
        val engine = FakeEngine(startThrows = { IOException("no") })
        val c = controller(engine)
        c.acquire("worker")
        runCurrent()
        assertEquals(listOf("start", "stop"), engine.calls)
        c.acquire("foreground")
        c.acquire("foreground") // no join: already an owner
        advanceTimeBy(grace * 10); runCurrent()
        assertEquals(TorState.Failed, c.state.value)
        assertEquals(listOf("start", "stop", "start", "stop"), engine.calls, "a failed attempt starts no next one by itself")
    }

    @Test
    fun `an owner already there asking again does not retry a Failed Tor, nor does a join while one is on its way`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        c.acquire("a")
        runCurrent()
        c.acquire("b") // Connecting: the attempt is running
        runCurrent()
        assertEquals(listOf("start"), engine.calls)
        engine.states.trySend(TorState.Failed)
        runCurrent()
        c.acquire("a")
        c.acquire("b")
        runCurrent()
        assertEquals(TorState.Failed, c.state.value)
        assertEquals(listOf("start"), engine.calls)
    }

    @Test
    fun `joining a connected Tor starts nothing`() = runTest {
        val engine = FakeEngine()
        val c = controller(engine)
        connected(engine, c)
        c.acquire("b")
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        assertEquals(listOf("start"), engine.calls)
    }

    @Test
    fun `no start before the old stop has finished`() = runTest {
        val engine = FakeEngine(stopTakesMs = 350)
        val c = controller(engine)
        connected(engine, c)
        c.release("a")
        advanceTimeBy(grace + 1)
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
        c.acquire("a")
        runCurrent()
        assertEquals(TorState.Connecting(null), c.state.value)
        assertEquals(listOf("start"), engine.calls)
        advanceTimeBy(351)
        runCurrent()
        assertEquals(listOf("start", "stop", "start"), engine.calls)
    }

    @Test
    fun `start and stop stay in order however quickly they alternate`() = runTest {
        val engine = FakeEngine(stopTakesMs = 350)
        val c = TorController(engine, backgroundScope, graceMs = 0, bootstrapTimeoutMs = timeout, log = {}) { ports }
        repeat(5) {
            c.acquire("a")
            runCurrent()
            c.release("a")
            runCurrent()
        }
        c.acquire("a")
        advanceTimeBy(1_000)
        runCurrent()
        // alternating, and the last word is a start
        val alternate = engine.calls.withIndex().all { (i, call) -> call == if (i % 2 == 0) "start" else "stop" }
        assertTrue(alternate, engine.calls.toString())
        assertEquals("start", engine.calls.last())
        assertEquals(TorState.Connecting(null), c.state.value)
    }

    /** An engine whose flow answers its cancellation with an error, as a closing socket does: that attempt is over by then. */
    private class ThrowsOnCancel(val first: TorState = TorState.Connected) : TorEngine {
        var starts = 0
        override suspend fun start(ports: NetworkMode.Tor): Flow<TorState> {
            starts++
            return flow {
                emit(first)
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    throw IOException("socket closed")
                }
            }
        }
        override suspend fun stop() = delay(100)
    }

    @Test
    fun `a failure of an attempt already stopped does not replace Off`() = runTest {
        val engine = ThrowsOnCancel()
        val c = controller(engine)
        c.acquire("a")
        runCurrent()
        assertEquals(TorState.Connected, c.state.value)
        c.release("a")
        advanceTimeBy(grace + 1)
        runCurrent()
        assertEquals(TorState.Off, c.state.value)
    }

    @Test
    fun `a failure of the replaced attempt does not touch the new one`() = runTest {
        val engine = ThrowsOnCancel(first = TorState.Failed)
        val c = controller(engine)
        c.acquire("a")
        runCurrent()
        assertEquals(TorState.Failed, c.state.value)
        c.retry()
        runCurrent()
        // the old one answered its cancellation with an error and is stopping, the new one waits for that
        assertEquals(1, engine.starts)
        assertEquals(TorState.Connecting(null), c.state.value)
    }

    @Test
    fun `the reason of a failure is logged by type only`() = runTest {
        val logged = mutableListOf<String>()
        val engine = object : TorEngine {
            override suspend fun start(ports: NetworkMode.Tor): Flow<TorState> = throw IOException("wss://secret.example")
            override suspend fun stop() = throw IllegalStateException("wss://secret.example")
        }
        val c = TorController(engine, backgroundScope, graceMs = grace, bootstrapTimeoutMs = timeout, log = { logged += it }) { ports }
        c.acquire("a")
        runCurrent()
        assertEquals(TorState.Failed, c.state.value)
        assertEquals(listOf("failed: IOException", "stop failed: IllegalStateException"), logged)
    }

    @Test
    fun `a timeout and an engine that ended tell apart in the log`() = runTest {
        val logged = mutableListOf<String>()
        val hanging = FakeEngine()
        val c = TorController(hanging, backgroundScope, graceMs = grace, bootstrapTimeoutMs = timeout, log = { logged += it }) { ports }
        c.acquire("a")
        advanceTimeBy(timeout + 1)
        runCurrent()
        hanging.states.close()
        c.retry()
        runCurrent()
        hanging.states.close()
        runCurrent()
        assertEquals(listOf("failed: BootstrapTimeoutException", "failed: EngineFinishedException"), logged)
    }

    @Test
    fun `concurrent calls from many threads keep start and stop in order`() = runBlocking {
        val running = AtomicInteger(0)
        val overlaps = AtomicInteger(0)
        val starts = AtomicInteger(0)
        val engine = object : TorEngine {
            override suspend fun start(ports: NetworkMode.Tor): Flow<TorState> {
                if (running.incrementAndGet() != 1) overlaps.incrementAndGet()
                starts.incrementAndGet()
                return flow { emit(TorState.Connected); delay(Long.MAX_VALUE) }
            }
            override suspend fun stop() { if (running.get() > 0) running.decrementAndGet() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val c = TorController(engine, scope, graceMs = 1, bootstrapTimeoutMs = 60_000, log = {}) { ports }
        (0 until 8).map { n ->
            scope.async {
                repeat(500) { i ->
                    c.acquire("o$n")
                    if (i % 7 == 0) c.retry()
                    c.release("o$n")
                    c.release("nobody")
                    if (i % 50 == 0) delay(2)
                }
            }
        }.awaitAll()
        c.acquire("last")
        withTimeout(10_000) { c.state.first { it == TorState.Connected } }
        assertEquals(0, overlaps.get())
        assertEquals(1, running.get())
        assertTrue(starts.get() >= 1)
        scope.cancel()
    }

    @Test
    fun `torrc holds our two ports, isolation on the session one, and no HTTP tunnel`() {
        assertEquals(
            "SocksPort 127.0.0.1:9101 IsolateDestAddr\nSocksPort 127.0.0.1:9102\nHTTPTunnelPort 0\n",
            torrcFor(ports),
        )
    }

    @Test
    fun `listeners match only when exactly our two ports`() {
        assertTrue(socksListenersMatch("\"127.0.0.1:9101\" \"127.0.0.1:9102\"", ports))
        assertTrue(socksListenersMatch("\"127.0.0.1:9102\" \"127.0.0.1:9101\"", ports))
        for (other in listOf(
            "",
            "\"127.0.0.1:9101\"",
            "\"127.0.0.1:9050\"",
            "\"127.0.0.1:9101\" \"127.0.0.1:9103\"",
            "\"127.0.0.1:9101\" \"127.0.0.1:9102\" \"127.0.0.1:9050\"",
            "\"0.0.0.0:9101\" \"127.0.0.1:9102\"",
            "\"127.0.0.1:91011\" \"127.0.0.1:9102\"",
        )) assertTrue(!socksListenersMatch(other, ports), other)
    }

    @Test
    fun `bootstrap progress comes from the event and the getinfo format`() {
        assertEquals(5, bootstrapProgress("NOTICE BOOTSTRAP PROGRESS=5 TAG=conn SUMMARY=\"Connecting to a relay\""))
        assertEquals(100, bootstrapProgress("NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\""))
        assertEquals(null, bootstrapProgress("NOTICE CIRCUIT_ESTABLISHED"))
        assertEquals(null, bootstrapProgress("WARN BOOTSTRAP PROGRESS=abc"))
    }

    @Test
    fun `an older lower progress arriving last does not hide the end of the bootstrap`() = runTest {
        val progress = BootstrapProgress()
        assertEquals(null, progress.percent.value)
        progress.see("NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"")
        progress.see("NOTICE BOOTSTRAP PROGRESS=95 TAG=circuit_create SUMMARY=\"Establishing a Tor circuit\"")
        progress.see("NOTICE CIRCUIT_ESTABLISHED")
        assertEquals(100, progress.percent.value)
        assertEquals(100, withTimeout(1_000) { progress.percent.first { it == 100 } })
    }

    @Test
    fun `the tor process is told by its name, a suffix of the package`() {
        assertTrue(isTorProcessName("com.monostr.app:tor"))
        assertTrue(isTorProcessName("com.monostr.app.debug:tor"))
        assertTrue(!isTorProcessName("com.monostr.app"))
        assertTrue(!isTorProcessName("com.monostr.app.tor"))
        assertTrue(!isTorProcessName(""))
    }
}
