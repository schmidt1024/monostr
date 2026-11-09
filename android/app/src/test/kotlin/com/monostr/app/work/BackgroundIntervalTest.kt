package com.monostr.app.work

import androidx.work.ExistingPeriodicWorkPolicy
import com.monostr.app.net.TorController
import com.monostr.app.net.TorEngine
import com.monostr.app.net.TorState
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundIntervalTest {
    private val tor = NetworkMode.Tor(1001, 1002)

    @Test
    fun `every 60 minutes with Tor, 15 without`() {
        assertEquals(60L, backgroundIntervalMinutes(torOn = true))
        assertEquals(15L, backgroundIntervalMinutes(torOn = false))
    }

    @Test
    fun `the schedule changes only for another period - nothing there or the same period keeps what runs`() {
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, schedulePolicy(currentIntervalMs = null, wantedMinutes = 15))
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, schedulePolicy(currentIntervalMs = 15 * 60_000L, wantedMinutes = 15))
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, schedulePolicy(currentIntervalMs = 60 * 60_000L, wantedMinutes = 60))
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, schedulePolicy(currentIntervalMs = 15 * 60_000L, wantedMinutes = 60))
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, schedulePolicy(currentIntervalMs = 60 * 60_000L, wantedMinutes = 15))
    }

    @Test
    fun `budget and work stay inside the about 10 minutes of WorkManager`() {
        assertEquals(90_000L, TOR_BUDGET_MS)
        assertTrue(TOR_BUDGET_MS + TOR_WORK_LIMIT_MS <= 9 * 60_000L)
    }

    private inner class Run(start: NetworkMode = tor, val connected: MutableStateFlow<Boolean> = MutableStateFlow(false), val account: Boolean = true) {
        val mode = MutableStateFlow(start)
        val log = ArrayList<String>()
        var visible = false
        var checkResult = true
        var checkHangs = false
        val runTor = RunTor(
            acquire = { log += "acquire:$it" },
            release = { log += "release:$it" },
            connected = { log += "connected?"; connected },
        )

        suspend fun go(): Boolean = checkThroughGate(
            mode,
            hasAccount = { log += "account?"; account },
            visible = { visible },
            tor = runTor,
            budgetMs = 1_000,
            workLimitMs = 5_000,
            owner = "worker",
        ) {
            log += "check"
            if (checkHangs) awaitCancellation()
            checkResult
        }
    }

    @Test
    fun `switch off - the check runs as today, nothing of Tor is touched, its answer counts`() = runTest {
        val r = Run(start = NetworkMode.Direct)
        assertTrue(r.go())
        assertEquals(listOf("check"), r.log)
        r.log.clear()
        r.checkResult = false
        assertFalse(r.go())
        assertEquals(listOf("check"), r.log)
    }

    @Test
    fun `a gate not known yet is waited for, nothing runs before`() = runTest {
        val r = Run(start = NetworkMode.Unknown)
        val run = launch { r.go() }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(emptyList<String>(), r.log)
        r.mode.value = NetworkMode.Direct
        run.join()
        assertEquals(listOf("check"), r.log)
    }

    @Test
    fun `a gate that stays unknown ends the run, no check, no Tor`() = runTest {
        val r = Run(start = NetworkMode.Unknown)
        assertTrue(r.go())
        assertEquals(emptyList<String>(), r.log)
    }

    @Test
    fun `a cold process that reads Tor goes through Tor`() = runTest {
        val r = Run(start = NetworkMode.Unknown, connected = MutableStateFlow(true))
        val run = launch { r.go() }
        runCurrent()
        r.mode.value = tor
        run.join()
        assertEquals(listOf("account?", "acquire:worker", "connected?", "check", "release:worker"), r.log)
    }

    @Test
    fun `not connected within the budget ends success without starting the session`() = runTest {
        val r = Run()
        assertTrue(r.go())
        assertEquals(listOf("account?", "acquire:worker", "connected?", "release:worker"), r.log)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun `connected just before the budget ends runs the check`() = runTest {
        val r = Run()
        val run = launch { r.go() }
        advanceTimeBy(999)
        runCurrent()
        r.connected.value = true
        run.join()
        assertEquals(listOf("account?", "acquire:worker", "connected?", "check", "release:worker"), r.log)
    }

    @Test
    fun `logged out - Tor is not started for nothing`() = runTest {
        val r = Run(account = false)
        assertTrue(r.go())
        assertEquals(listOf("account?"), r.log)
    }

    @Test
    fun `the app came up during the wait - it syncs itself, and the session is not started`() = runTest {
        val r = Run()
        val run = launch { r.go() }
        runCurrent()
        r.visible = true
        r.connected.value = true
        run.join()
        assertEquals(listOf("account?", "acquire:worker", "connected?", "release:worker"), r.log)
    }

    @Test
    fun `cancelled during the wait - released, and the check never ran`() = runTest {
        val r = Run()
        val run = launch { r.go() }
        runCurrent()
        run.cancelAndJoin()
        assertEquals(listOf("account?", "acquire:worker", "connected?", "release:worker"), r.log)
    }

    @Test
    fun `a stop by the system during the work releases Tor`() = runTest {
        val r = Run(connected = MutableStateFlow(true))
        r.checkHangs = true
        val run = launch { r.go() }
        runCurrent()
        assertEquals("check", r.log.last())
        run.cancelAndJoin()
        assertEquals("release:worker", r.log.last())
    }

    @Test
    fun `a check that hangs under Tor ends at the limit, Tor released, and the run does not hang`() = runTest {
        val r = Run(connected = MutableStateFlow(true))
        r.checkHangs = true
        assertTrue(r.go())
        assertEquals("release:worker", r.log.last())
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun `under Tor a failed check waits for the next hour - a retry would bootstrap again within seconds`() = runTest {
        val r = Run(connected = MutableStateFlow(true))
        r.checkResult = false
        assertTrue(r.go())
    }

    @Test
    fun `the switch going off during the wait ends the run - no check on the way to direct, Tor released`() = runTest {
        val r = Run()
        val run = launch { r.go() }
        runCurrent()
        r.mode.value = NetworkMode.Direct
        r.connected.value = true
        run.join()
        assertEquals(listOf("account?", "acquire:worker", "connected?", "release:worker"), r.log)
    }

    @Test
    fun `the switch going off during the work ends it and releases Tor`() = runTest {
        val r = Run(connected = MutableStateFlow(true))
        r.checkHangs = true
        val run = launch { r.go() }
        runCurrent()
        r.mode.value = NetworkMode.Direct
        run.join()
        assertEquals("release:worker", r.log.last())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a stopped run still releasing leaves Tor up for its rerun, whose own end stops it`() = runTest {
        val engine = object : TorEngine {
            override suspend fun start(ports: NetworkMode.Tor): Flow<TorState> = flow { emit(TorState.Connected); awaitCancellation() }
            override suspend fun stop() {}
        }
        val controller = TorController(engine, backgroundScope, graceMs = 1_000, bootstrapTimeoutMs = 5_000, log = {}, ports = { tor })
        val mode = MutableStateFlow<NetworkMode>(tor)
        val runTor = RunTor(controller::acquire, controller::releaseRun) { controller.state.map { it == TorState.Connected } }
        // WorkManager runs the same work again as soon as the system stopped it, while the stopped run is still closing its session
        val closing = CompletableDeferred<Unit>()
        val stopped = launch {
            checkThroughGate(mode, hasAccount = { true }, visible = { false }, tor = runTor) {
                try { awaitCancellation() } finally { withContext(NonCancellable) { closing.await() } }
            }
        }
        runCurrent()
        assertEquals(TorState.Connected, controller.state.value)
        stopped.cancel()
        runCurrent()
        val rerunEnds = CompletableDeferred<Unit>()
        var rerunWorked = false
        val rerun = launch {
            checkThroughGate(mode, hasAccount = { true }, visible = { false }, tor = runTor) {
                rerunWorked = true
                rerunEnds.await()
                true
            }
        }
        runCurrent()
        assertTrue(rerunWorked)
        closing.complete(Unit)
        stopped.join()
        runCurrent()
        assertEquals(TorState.Connected, controller.state.value, "the old run's release took Tor from under the rerun")
        rerunEnds.complete(Unit)
        rerun.join()
        runCurrent()
        assertEquals(TorState.Off, controller.state.value)
    }

    private class Ready

    /** The session as the worker sees it. */
    private inner class Sessions(var current: Ready? = null, val buildsOnRestore: Boolean = false, val routeOpen: Flow<Boolean> = flowOf(true)) {
        var visible = false
        val log = ArrayList<String>()
        var workResult = true
        var beforeRestore: suspend () -> Unit = {}
        var duringWork: suspend () -> Unit = {}

        suspend fun go(): Boolean = checkSession(
            restore = { onStarted ->
                beforeRestore()
                if (buildsOnRestore && current == null) Ready().also { current = it; onStarted(it) }
            },
            current = { current },
            route = { routeOpen },
            visible = { visible },
            release = { log += "release"; current = null },
        ) {
            log += "work"
            duringWork()
            workResult
        }
    }

    @Test
    fun `a session built here is released when no app is visible`() = runTest {
        val s = Sessions(buildsOnRestore = true)
        assertTrue(s.go())
        assertEquals(listOf("work", "release"), s.log)
    }

    @Test
    fun `a session the run only waited for during a switch is not released`() = runTest {
        // the state read Loading when the run began; the switch held the lock and built the session
        val s = Sessions()
        s.beforeRestore = { s.current = Ready() }
        assertTrue(s.go())
        assertEquals(listOf("work"), s.log)
        assertTrue(s.current != null)
    }

    @Test
    fun `a running session is not released`() = runTest {
        val s = Sessions(current = Ready())
        s.go()
        assertEquals(listOf("work"), s.log)
    }

    @Test
    fun `a session built here stays when the app came up meanwhile`() = runTest {
        val s = Sessions(buildsOnRestore = true)
        s.duringWork = { s.visible = true }
        s.go()
        assertEquals(listOf("work"), s.log)
    }

    @Test
    fun `a session a switch built in place of ours during the run is not released`() = runTest {
        val s = Sessions(buildsOnRestore = true)
        s.duringWork = { s.current = Ready() }
        s.go()
        assertEquals(listOf("work"), s.log)
    }

    @Test
    fun `a session built here is released when the run is cancelled`() = runTest {
        val s = Sessions(buildsOnRestore = true)
        val entered = CompletableDeferred<Unit>()
        s.duringWork = { entered.complete(Unit); awaitCancellation() }
        val run = launch { s.go() }
        entered.await()
        run.cancelAndJoin()
        assertEquals(listOf("work", "release"), s.log)
    }

    @Test
    fun `cancelled right after the build, before restore returned, it is still released`() = runTest {
        var current: Ready? = null
        val log = ArrayList<String>()
        val run = launch {
            checkSession(
                restore = { onStarted ->
                    Ready().also { current = it; onStarted(it) }
                    throw CancellationException("stopped")
                },
                current = { current },
                route = { flowOf(true) },
                visible = { false },
                release = { log += "release" },
            ) { log += "work"; true }
        }
        run.join()
        assertEquals(listOf("release"), log)
    }

    @Test
    fun `logged out - nothing to do and nothing to retry`() = runTest {
        val s = Sessions()
        assertTrue(s.go())
        assertEquals(emptyList<String>(), s.log)
    }

    @Test
    fun `a failed part asks for another try`() = runTest {
        val s = Sessions(current = Ready())
        s.workResult = false
        assertFalse(s.go())
    }

    @Test
    fun `a route that stays closed ends the run without work`() = runTest {
        val s = Sessions(buildsOnRestore = true, routeOpen = MutableStateFlow(false))
        assertTrue(s.go())
        assertEquals(listOf("release"), s.log)
    }
}
