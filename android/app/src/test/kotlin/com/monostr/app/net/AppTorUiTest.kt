package com.monostr.app.net

import com.monostr.app.data.PendingTip
import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/** What the UI is told about Tor comes from the gate and the controller, never from what a switch returned. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppTorUiTest {
    @TempDir lateinit var dir: File

    private inner class Rig(scope: TestScope, start: NetworkMode = NetworkMode.Direct, minTurningMs: Long = 0, pickPorts: () -> Pair<Int, Int> = { 1001 to 1002 }) {
        val store = TorSwitchStore(File(dir, "tor-enabled"))
        val gate = NetworkGate(pickPorts = pickPorts).apply { set(start) }
        val controller = MutableStateFlow<TorState>(TorState.Off)
        var controllerAsked = 0
        var retries = 0
        var acquired = 0
        var closeFails = false
        var conflict = false
        var closes = 0
        var closeCancels = false
        @Volatile var rebuilt = false
        @Volatile var retriedOn: NetworkMode? = null
        val job = SupervisorJob()
        var holdClose: CompletableDeferred<Unit>? = null
        val closing = CompletableDeferred<Unit>()
        val tips = com.monostr.app.ui.FakePendingTips()
        var directUsed = false
        @Volatile var ended = 0
        val session = object : SwitchedSession {
            override suspend fun stop() {}
            override suspend fun close() { closing.complete(Unit); holdClose?.await(); closes++; if (closeCancels) throw kotlinx.coroutines.CancellationException("stray"); if (closeFails) throw IOException("close") }
            override suspend fun rebuild() { rebuilt = true }
        }
        val switch = TorSwitch(store, gate, {}, session, tips, { acquired++; if (controller.value == TorState.Off) controller.value = TorState.Connecting(null) }, {}, { controller.value = TorState.Off }, schedule = {}, directRelayRouteUsed = { directUsed }, endProcess = { ended++ })
        val restartReturn = TorRestartReturn(File(dir, "tor-return"))
        val ui = AppTorUi(gate, store, switch, torState = { controllerAsked++; controller }, torRetry = { retries++; retriedOn = gate.mode.value }, { conflict }, minTurningMs, CoroutineScope(job + UnconfinedTestDispatcher(scope.testScheduler)), directRelayRouteUsed = { directUsed }, restartReturn = restartReturn)
    }

    /** The marker is written on a real IO thread, which the test clock does not wait for. */
    private suspend fun TestScope.settled(r: Rig) {
        withContext(Dispatchers.Default) { withTimeout(5_000) { r.ui.turning.first { it == null } } }
        advanceUntilIdle()
    }

    private suspend fun TestScope.closing(r: Rig) {
        withContext(Dispatchers.Default) { withTimeout(5_000) { r.closing.await() } }
        advanceUntilIdle()
    }

    @Test
    fun `the restart is announced exactly when the process would end`() = runTest {
        val r = Rig(this)
        assertFalse(r.ui.restartsOnSwitch)
        r.directUsed = true
        assertTrue(r.ui.restartsOnSwitch)
    }

    @Test
    fun `a retry with the marker off ends the process like any turnOn after a direct route`() = runTest {
        val r = Rig(this)
        r.directUsed = true
        assertTrue(r.ui.retry())
        settled(r)
        assertEquals(1, r.ended)
        assertEquals(0, r.closes)
        assertFalse(r.rebuilt)
    }

    @Test
    fun `no wait for the turning line before the process ends`() = runTest {
        // a clock nobody advances: anything behind that wait would never run
        val r = Rig(TestScope(), minTurningMs = 60_000)
        r.directUsed = true
        r.ui.turnOn()
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (r.ended == 0) kotlinx.coroutines.delay(5) } }
        assertEquals(1, r.ended)
    }

    @Test
    fun `the return route goes through its store`() = runTest {
        val r = Rig(this)
        r.ui.armRestartReturn("settings?section=privacy")
        r.restartReturn.persist()
        assertEquals("settings?section=privacy", r.ui.takeRestartReturn())
        assertEquals(null, r.ui.takeRestartReturn())
    }

    @Test
    fun `with the switch off nothing asks for the controller, whatever the UI reads`() = runTest {
        val r = Rig(this)
        advanceUntilIdle()
        assertFalse(r.ui.active.value)
        assertNull(r.ui.state.value)
        assertEquals(0, r.controllerAsked)
    }

    @Test
    fun `in Tor mode the state is the controller's`() = runTest {
        val r = Rig(this, NetworkMode.Tor(1, 2))
        r.controller.value = TorState.Connecting(40)
        advanceUntilIdle()
        assertTrue(r.ui.active.value)
        assertEquals(TorState.Connecting(40), r.ui.state.value)
        r.controller.value = TorState.Connected
        advanceUntilIdle()
        assertEquals(TorState.Connected, r.ui.state.value)
    }

    @Test
    fun `the gate on Tor with nobody running Tor is not connected, and connecting only while a switch runs`() = runTest {
        val r = Rig(this, NetworkMode.Tor(1, 2))
        advanceUntilIdle()
        assertEquals(TorState.Failed, r.ui.state.value)
        val hold = CompletableDeferred<Unit>()
        r.holdClose = hold
        r.ui.turnOff()
        closing(r)
        assertEquals(TorTurn.OFF, r.ui.turning.value)
        assertEquals(TorState.Connecting(null), r.ui.state.value)
        hold.complete(Unit)
        settled(r)
        assertNull(r.ui.turning.value)
        assertNull(r.ui.state.value)
    }

    @Test
    fun `a turnOn that cannot get its ports stays off and says so`() = runTest {
        val r = Rig(this, pickPorts = { throw IOException("no port") })
        r.ui.turnOn()
        settled(r)
        assertEquals(TorSwitchError.ON_FAILED, r.ui.error.value)
        assertFalse(r.ui.active.value)
        assertNull(r.ui.turning.value)
        assertFalse(r.store.enabled.value)
    }

    @Test
    fun `a turnOff that fails half way shows Tor still on with the error, and Try again brings Tor back`() = runTest {
        val r = Rig(this, NetworkMode.Tor(1, 2))
        r.store.set(true)
        r.switch.onForeground()
        r.controller.value = TorState.Connected
        r.closeFails = true
        r.ui.turnOff()
        settled(r)
        assertFalse(r.store.enabled.value, "the marker is off")
        assertTrue(r.ui.active.value, "the gate is on Tor, so the UI says so")
        assertEquals(TorState.Connected, r.ui.state.value)
        assertEquals(TorSwitchError.OFF_FAILED, r.ui.error.value)

        r.closeFails = false
        r.ui.retry()
        settled(r)
        assertTrue(r.store.enabled.value)
        assertTrue(r.ui.active.value)
        assertNull(r.ui.error.value)
    }

    @Test
    fun `the same failed turnOff can simply be asked for again`() = runTest {
        val r = Rig(this, NetworkMode.Tor(1, 2))
        r.store.set(true)
        r.closeFails = true
        r.ui.turnOff()
        settled(r)
        r.closeFails = false
        r.ui.turnOff()
        settled(r)
        assertFalse(r.ui.active.value)
        assertNull(r.ui.error.value)
    }

    @Test
    fun `Try again with the switch on asks the controller, with the app as owner`() = runTest {
        val r = Rig(this, NetworkMode.Tor(1, 2))
        r.store.set(true)
        r.controller.value = TorState.Failed
        r.ui.retry()
        settled(r)
        assertEquals(1, r.retries)
        assertEquals(1, r.acquired)
    }

    @Test
    fun `a request overtaken by a newer one shows nothing of its own`() = runTest {
        val r = Rig(this)
        val hold = CompletableDeferred<Unit>()
        r.holdClose = hold
        r.ui.turnOn()
        closing(r)
        assertEquals(TorTurn.ON, r.ui.turning.value)
        r.ui.turnOff()
        r.ui.turnOn()
        assertEquals(TorTurn.ON, r.ui.turning.value)
        hold.complete(Unit)
        settled(r)
        assertNull(r.ui.turning.value)
        assertNull(r.ui.error.value)
        assertTrue(r.ui.active.value)
    }

    @Test
    fun `the count names tips still undelivered and alive, superseded ones too, while turning off drops them all`() = runTest {
        val r = Rig(this, NetworkMode.Tor(1, 2))
        r.store.set(true)
        val now = System.currentTimeMillis() / 1000
        val waiting = PendingTip("a".repeat(64), "1".repeat(64), 5, now, underTor = true)
        val delivered = PendingTip("b".repeat(64), "2".repeat(64), 5, now, watcherDelivered = true, underTor = true)
        val anonDelivered = PendingTip("c".repeat(64), null, 5, now, anonEvent = "{}", anonDelivered = true, underTor = true)
        val superseded = PendingTip("d".repeat(64), "3".repeat(64), 5, now, superseded = true, underTor = true)
        val expired = PendingTip("f".repeat(64), "5".repeat(64), 5, 4_000, underTor = true)
        val expiredToo = PendingTip("0".repeat(64), "6".repeat(64), 5, 4_000, underTor = true)
        val direct = PendingTip("e".repeat(64), "4".repeat(64), 5, now)
        listOf(waiting, delivered, anonDelivered, superseded, expired, expiredToo, direct).forEach { r.tips.add(it) }
        assertEquals(2, r.ui.tipsToDrop())
        r.ui.turnOff()
        settled(r)
        assertEquals(listOf(direct), r.tips.state.value)
    }

    @Test
    fun `a turnOn failing past the flip says nothing the gate contradicts`() = runTest {
        val r = Rig(this)
        r.closeFails = true
        r.ui.turnOn()
        settled(r)
        assertTrue(r.ui.active.value, "the gate is on Tor")
        assertNull(r.ui.error.value)
    }

    @Test
    fun `a turnOff failure is dropped when the gate went direct after all`() = runTest {
        val r = Rig(this, NetworkMode.Tor(1, 2))
        r.store.set(true)
        r.closeFails = true
        r.ui.turnOff()
        settled(r)
        assertEquals(TorSwitchError.OFF_FAILED, r.ui.error.value)
        r.gate.set(NetworkMode.Direct)
        advanceUntilIdle()
        assertNull(r.ui.error.value)
    }

    @Test
    fun `reaching Connected clears the error, staying Connected does not`() = runTest {
        val r = Rig(this, NetworkMode.Tor(1, 2))
        r.store.set(true)
        r.switch.onForeground()
        r.controller.value = TorState.Connected
        advanceUntilIdle()
        r.closeFails = true
        r.ui.turnOff()
        settled(r)
        assertEquals(TorSwitchError.OFF_FAILED, r.ui.error.value, "Tor is still connected, and the error stands")
        r.controller.value = TorState.Failed
        advanceUntilIdle()
        assertEquals(TorSwitchError.OFF_FAILED, r.ui.error.value)
        r.controller.value = TorState.Connected
        advanceUntilIdle()
        assertNull(r.ui.error.value)
    }

    @Test
    fun `Try again clears the error`() = runTest {
        val r = Rig(this, NetworkMode.Tor(1, 2))
        r.store.set(true)
        r.closeFails = true
        r.ui.turnOff() // the marker stays off now, so make it the plain retry below
        settled(r)
        r.store.set(true)
        assertEquals(TorSwitchError.OFF_FAILED, r.ui.error.value)
        assertFalse(r.ui.retry())
        advanceUntilIdle()
        assertNull(r.ui.error.value)
    }

    @Test
    fun `Try again after a port conflict goes through new ports and the rebuild, without one the same ports`() = runTest {
        var next = 1001
        val r = Rig(this, pickPorts = { next++ to next++ })
        r.store.set(true)
        r.gate.set(r.gate.torMode())
        advanceUntilIdle()
        r.controller.value = TorState.Failed
        advanceUntilIdle()
        assertFalse(r.ui.retry())
        assertEquals(NetworkMode.Tor(1001, 1002), r.gate.mode.value)
        r.conflict = true
        assertTrue(r.ui.retry())
        settled(r)
        assertEquals(NetworkMode.Tor(1003, 1004), r.gate.mode.value)
        assertEquals(1, r.closes)
    }

    @Test
    fun `the switching line stays long enough to be read`() = runTest {
        val r = Rig(this, minTurningMs = 1_500)
        r.ui.turnOn()
        // blocking, as runTest would skip the delay while this waits in a coroutine
        blockUntil { r.rebuilt }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TorTurn.ON, r.ui.turning.value)
        advanceTimeBy(600)
        runCurrent()
        blockUntil { r.ui.turning.value == null }
    }

    private fun blockUntil(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < until, "timed out")
            Thread.sleep(2)
        }
    }

    @Test
    fun `a stray cancellation out of a switch does not leave the switching line standing`() = runTest {
        val r = Rig(this, NetworkMode.Tor(1, 2))
        r.store.set(true)
        r.closeCancels = true
        r.ui.turnOff()
        settled(r)
        assertNull(r.ui.turning.value)
    }

    @Test
    fun `after new ports the controller is asked again, on the new pair, for a worker's Tor that stays Failed`() = runTest {
        var next = 1001
        val r = Rig(this, pickPorts = { next++ to next++ })
        r.store.set(true)
        r.gate.set(r.gate.torMode())
        r.conflict = true
        assertTrue(r.ui.retry())
        settled(r)
        assertEquals(1, r.retries)
        assertEquals(NetworkMode.Tor(1003, 1004), r.retriedOn)
    }

    @Test
    fun `with the switch off the app holds no coroutine for Tor at all`() = runTest {
        val r = Rig(this)
        assertEquals(0, r.job.children.count())
        assertNull(r.ui.state.value)
        assertNull(r.ui.error.value)
        assertFalse(r.ui.active.value)
        assertEquals(0, r.controllerAsked)
    }
}
