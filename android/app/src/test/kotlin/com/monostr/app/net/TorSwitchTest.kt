package com.monostr.app.net

import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalCoroutinesApi::class)
class TorSwitchTest {
    @TempDir lateinit var dir: File

    /** Every step is written down with the mode the gate shows at that moment, so the flip is pinned between two steps. */
    private inner class Rig(start: NetworkMode = NetworkMode.Direct, marker: File = File(dir, "tor-enabled"), pickPorts: () -> Pair<Int, Int> = { 1001 to 1002 }) {
        val log = ArrayList<String>()
        val store = TorSwitchStore(marker)
        val gate = NetworkGate(pickPorts = pickPorts).apply { set(start) }
        var holdClose: CompletableDeferred<Unit>? = null
        private fun step(name: String) { log += "$name@${gate.mode.value.name()}" }
        val session = object : SwitchedSession {
            override suspend fun stop() = step("stop")
            override suspend fun close() { holdClose?.await(); step("close") }
            override suspend fun rebuild() = step("rebuild")
        }
        var dropFails = false
        val scheduled = ArrayList<Pair<Boolean, String>>()
        var scheduleFails = false
        val inner = com.monostr.app.ui.FakePendingTips()
        val tips = object : com.monostr.app.data.PendingTipStore by inner {
            override suspend fun dropUnderTor(): Int { step("tips"); return inner.dropUnderTor() }
        }
        /** Whether this process ever gave a relay a direct route, as `DirectRelayRoute` says. */
        var directUsed = false
        /** What the marker said at the moment the process ended: that is what the next start reads. */
        var markerAtEnd: Boolean? = null
        val switch = TorSwitch(store, gate, { if (dropFails) error("drop failed"); step("drop") }, session, tips, { step("acquire") }, { step("release") }, { step("releaseNow") }, schedule = { scheduled += it to gate.mode.value.name(); if (scheduleFails) error("not initialised") },
            directRelayRouteUsed = { directUsed }, endProcess = { markerAtEnd = marker.exists(); step("end") })
    }

    @Test
    fun `turnOn after a direct route - the process ends right after the connections go, with no close and no rebuild`() = runTest {
        val r = Rig()
        r.directUsed = true
        r.switch.onForeground()
        r.switch.turnOn()
        assertEquals(listOf("stop@Direct", "drop@Tor", "end@Tor"), r.log)
        assertEquals(true, r.markerAtEnd, "the next start must read it")
        assertEquals(listOf(true to "Tor"), r.scheduled)
    }

    @Test
    fun `turnOn with no direct route stays in the process, with close and rebuild`() = runTest {
        val r = Rig()
        r.directUsed = false
        r.switch.onForeground()
        r.switch.turnOn()
        assertEquals(listOf("stop@Direct", "drop@Tor", "close@Tor", "acquire@Tor", "rebuild@Tor"), r.log)
        assertEquals(null, r.markerAtEnd)
    }

    @Test
    fun `a direct route that came up until the connections went still counts`() = runTest {
        val r = Rig()
        val seen = ArrayList<String>()
        val switch = TorSwitch(r.store, r.gate, { r.directUsed = true; seen += "drop" }, r.session, r.tips, {}, {}, {}, schedule = {}, directRelayRouteUsed = { r.directUsed }, endProcess = { seen += "end" })
        switch.turnOn()
        assertEquals(listOf("drop", "end"), seen)
    }

    @Test
    fun `connections that fail to drop do not save the process`() = runTest {
        val r = Rig()
        r.directUsed = true
        r.dropFails = true
        // the real end does not come back to throw
        runCatching { r.switch.turnOn() }
        assertEquals(listOf("stop@Direct", "end@Tor"), r.log)
    }

    @Test
    fun `a marker that cannot be written does not end the process`() = runTest {
        val blocker = File(dir, "blocker").apply { writeText("x") }
        val r = Rig(marker = File(blocker, "tor-enabled"))
        r.gate.set(NetworkMode.Direct)
        r.directUsed = true
        assertTrue(runCatching { r.switch.turnOn() }.exceptionOrNull() is IOException)
        assertEquals(emptyList<String>(), r.log)
    }

    @Test
    fun `ports that cannot be picked do not end the process`() = runTest {
        val r = Rig(pickPorts = { throw IOException("no ports") })
        r.directUsed = true
        assertTrue(runCatching { r.switch.turnOn() }.exceptionOrNull() is IOException)
        assertEquals(emptyList<String>(), r.log)
    }

    @Test
    fun `asking for Tor when it is on does not end the process`() = runTest {
        val r = Rig(NetworkMode.Tor(1001, 1002))
        r.store.set(true)
        r.directUsed = true
        r.switch.turnOn()
        assertEquals(emptyList<String>(), r.log)
    }

    @Test
    fun `turning off and new ports stay in the process whatever ran direct - an orphan there can only reach a local port`() = runTest {
        var next = 1001
        val r = Rig(pickPorts = { next++ to next++ })
        r.store.set(true)
        r.gate.set(r.gate.torMode())
        r.directUsed = true
        r.switch.newPorts()
        assertFalse(r.log.any { it.startsWith("end") }, r.log.toString())
        assertEquals("rebuild@Tor", r.log.last())
        r.switch.turnOff()
        assertFalse(r.log.any { it.startsWith("end") }, r.log.toString())
        assertEquals("rebuild@Direct", r.log.last())
    }

    @Test
    fun `off and on in one process - the on ends it`() = runTest {
        val r = Rig(NetworkMode.Tor(1001, 1002))
        r.store.set(true)
        r.switch.turnOff()
        r.directUsed = true // what the rebuilt direct session does
        r.log.clear()
        r.switch.turnOn()
        assertEquals(listOf("stop@Direct", "drop@Tor", "end@Tor"), r.log)
    }

    private fun NetworkMode.name() = when (this) { NetworkMode.Direct -> "Direct"; NetworkMode.Unknown -> "Unknown"; is NetworkMode.Tor -> "Tor" }

    @Test
    fun `turnOn - the engine stops, the gate flips, connections and session go, Tor is acquired, the session comes back`() = runTest {
        val r = Rig()
        r.switch.onForeground()
        r.switch.turnOn()
        assertEquals(listOf("stop@Direct", "drop@Tor", "close@Tor", "acquire@Tor", "rebuild@Tor"), r.log)
        assertTrue(r.store.enabled.value)
        assertEquals(NetworkMode.Tor(1001, 1002), r.gate.mode.value)
    }

    @Test
    fun `turnOff - session and connections go first, then Tor, and only then the gate opens direct`() = runTest {
        val r = Rig(NetworkMode.Tor(1001, 1002))
        r.store.set(true)
        r.switch.onForeground()
        r.log.clear()
        r.switch.turnOff()
        assertEquals(listOf("stop@Tor", "close@Tor", "drop@Tor", "releaseNow@Tor", "tips@Tor", "rebuild@Direct"), r.log)
        assertFalse(r.store.enabled.value)
        assertEquals(NetworkMode.Direct, r.gate.mode.value)
    }

    @Test
    fun `turning on asks for the hourly schedule, turning off for the old one, each once the gate has moved`() = runTest {
        val r = Rig()
        r.switch.turnOn()
        assertEquals(listOf(true to "Tor"), r.scheduled)
        r.switch.turnOff()
        assertEquals(listOf(true to "Tor", false to "Direct"), r.scheduled)
    }

    @Test
    fun `a switch that changes nothing leaves the schedule alone`() = runTest {
        val r = Rig()
        r.switch.turnOff()
        assertEquals(emptyList<Pair<Boolean, String>>(), r.scheduled)
    }

    @Test
    fun `a schedule that fails does not fail the switch, the next start sets it`() = runTest {
        val r = Rig()
        r.scheduleFails = true
        r.switch.turnOn()
        assertTrue(r.store.enabled.value)
        assertEquals("rebuild@Tor", r.log.last())
        r.switch.turnOff()
        assertEquals(NetworkMode.Direct, r.gate.mode.value)
        assertEquals("rebuild@Direct", r.log.last())
    }

    @Test
    fun `a marker that cannot be written changes nothing and reaches the caller`() = runTest {
        val blocker = File(dir, "blocker").apply { writeText("x") }
        val r = Rig(marker = File(blocker, "tor-enabled"))
        // the unreadable path reads as on; a fresh look at the state tells the two steps apart
        r.gate.set(NetworkMode.Direct)
        val failed = runCatching { r.switch.turnOn() }
        assertTrue(failed.exceptionOrNull() is IOException, "got $failed")
        assertEquals(emptyList<String>(), r.log)
        assertEquals(NetworkMode.Direct, r.gate.mode.value)
    }

    @Test
    fun `turning off with a marker that cannot be deleted stays on Tor`() = runTest {
        // a directory with content cannot be deleted as a file
        val marker = File(dir, "tor-enabled").apply { mkdirs(); File(this, "child").writeText("x") }
        val r = Rig(NetworkMode.Tor(1001, 1002), marker)
        val failed = runCatching { r.switch.turnOff() }
        assertTrue(failed.exceptionOrNull() is IOException, "got $failed")
        assertEquals(emptyList<String>(), r.log)
        assertEquals(NetworkMode.Tor(1001, 1002), r.gate.mode.value)
        assertTrue(r.store.enabled.value)
    }

    @Test
    fun `rapid toggling does not interleave, and the last request decides`() = runTest {
        val r = Rig()
        r.switch.onForeground()
        val closing = CompletableDeferred<Unit>()
        r.holdClose = closing
        val on = launch { r.switch.turnOn() }
        advanceUntilIdle()
        val off = launch { r.switch.turnOff() }
        val on2 = launch { r.switch.turnOn() }
        val off2 = launch { r.switch.turnOff() }
        advanceUntilIdle()
        val early = r.log.toList()
        closing.complete(Unit) // before any assert: a started switch cannot be cancelled, a failing test would hang on it
        assertEquals(listOf("stop@Direct", "drop@Tor"), early, "the others wait for the first to finish")
        listOf(on, off, on2, off2).forEach { it.join() }
        assertEquals(NetworkMode.Direct, r.gate.mode.value)
        assertFalse(r.store.enabled.value)
        assertFalse(File(dir, "tor-enabled").exists())
        assertEquals("rebuild@Direct", r.log.last())
        assertEquals(listOf("rebuild@Tor", "rebuild@Direct"), r.log.filter { it.startsWith("rebuild") }, "the two in the middle were superseded")
    }

    @Test
    fun `a step that fails still gives the session back, and turning off stays on Tor`() = runTest {
        val log = ArrayList<String>()
        val gate = NetworkGate(pickPorts = { 1001 to 1002 }).apply { set(NetworkMode.Tor(1001, 1002)) }
        val store = TorSwitchStore(File(dir, "tor-enabled")).apply { set(true) }
        val session = object : SwitchedSession {
            override suspend fun stop() { log += "stop" }
            override suspend fun close() { log += "close" }
            override suspend fun rebuild() { log += "rebuild" }
        }
        val switch = TorSwitch(store, gate, { error("drop failed") }, session, com.monostr.app.ui.FakePendingTips(), {}, {}, {}, {}, directRelayRouteUsed = { false }, endProcess = { error("must not end") })
        assertTrue(runCatching { switch.turnOff() }.isFailure)
        assertEquals(listOf("stop", "close", "rebuild"), log)
        assertEquals(NetworkMode.Tor(1001, 1002), gate.mode.value)
    }

    @Test
    fun `on, off, on - the off in the middle is dropped, so no direct session is ever built`() = runTest {
        val r = Rig()
        val closing = CompletableDeferred<Unit>()
        r.holdClose = closing
        val on = launch { r.switch.turnOn() }
        advanceUntilIdle()
        val off = launch { r.switch.turnOff() }
        val on2 = launch { r.switch.turnOn() }
        advanceUntilIdle()
        closing.complete(Unit)
        listOf(on, off, on2).forEach { it.join() }
        assertEquals(listOf("stop@Direct", "drop@Tor", "close@Tor", "rebuild@Tor"), r.log)
        assertTrue(r.store.enabled.value)
    }

    @Test
    fun `ports that cannot be picked change nothing and the session is not stopped`() = runTest {
        val r = Rig(pickPorts = { throw IOException("no ports") })
        val failed = runCatching { r.switch.turnOn() }
        assertTrue(failed.exceptionOrNull() is IOException, "got $failed")
        assertEquals(emptyList<String>(), r.log)
        assertFalse(r.store.enabled.value)
        assertFalse(File(dir, "tor-enabled").exists())
        assertEquals(NetworkMode.Direct, r.gate.mode.value)
    }

    @Test
    fun `a turnOff that failed half way still lets Tor go when leaving the foreground`() = runTest {
        val r = Rig(NetworkMode.Tor(1001, 1002))
        r.store.set(true)
        r.switch.onForeground()
        r.dropFails = true
        assertTrue(runCatching { r.switch.turnOff() }.isFailure)
        assertFalse(r.store.enabled.value)
        r.log.clear()
        r.switch.onBackground()
        assertEquals(listOf("release@Tor"), r.log)
    }

    @Test
    fun `asking for what is already so does nothing`() = runTest {
        val r = Rig()
        r.switch.turnOff()
        assertEquals(emptyList<String>(), r.log)
        r.switch.turnOn()
        r.log.clear()
        r.switch.turnOn()
        assertEquals(emptyList<String>(), r.log)
    }

    @Test
    fun `a cancelled caller does not leave a half switch`() = runTest {
        val r = Rig()
        val closing = CompletableDeferred<Unit>()
        r.holdClose = closing
        val job = launch { r.switch.turnOn() }
        advanceUntilIdle()
        val cancelled = async(Dispatchers.Unconfined) { job.cancelAndJoin() }
        closing.complete(Unit)
        cancelled.await()
        assertEquals("rebuild@Tor", r.log.last())
    }

    @Test
    fun `with the switch off the app's lifecycle touches nothing of Tor`() = runTest {
        val r = Rig()
        r.switch.onForeground()
        r.switch.onBackground()
        assertEquals(emptyList<String>(), r.log)
    }

    @Test
    fun `with the switch on the foreground holds Tor and the background lets it go`() = runTest {
        val r = Rig(NetworkMode.Tor(1001, 1002))
        r.store.set(true)
        r.switch.onForeground()
        r.switch.onBackground()
        assertEquals(listOf("acquire@Tor", "release@Tor"), r.log)
    }

    @Test
    fun `turned on in the background Tor waits for the foreground`() = runTest {
        val r = Rig()
        r.switch.turnOn()
        assertFalse(r.log.any { it.startsWith("acquire") }, r.log.toString())
        r.switch.onForeground()
        assertEquals("acquire@Tor", r.log.last())
    }

    @Test
    fun `leaving the foreground after turning off does not touch Tor again`() = runTest {
        val r = Rig(NetworkMode.Tor(1001, 1002))
        r.store.set(true)
        r.switch.onForeground()
        r.switch.turnOff()
        r.log.clear()
        r.switch.onBackground()
        assertEquals(emptyList<String>(), r.log)
    }

    @Test
    fun `the readiness of Tor builds nothing until collected`() = runTest {
        var built = 0
        val state = MutableStateFlow<TorState>(TorState.Connecting(null))
        val controller = TorController(object : TorEngine {
            override suspend fun start(ports: NetworkMode.Tor) = state
            override suspend fun stop() {}
        }, backgroundScope, log = {}) { NetworkMode.Tor(1, 2) }
        val flow = torConnected { built++; controller }
        assertEquals(0, built)
        assertFalse(flow.first())
        assertEquals(1, built)
    }

    @Test
    fun `new ports - the session stops first, nothing is ever direct, Tor and the session come back on the new pair`() = runTest {
        var next = 1001
        val r = Rig(pickPorts = { next++ to next++ })
        r.store.set(true)
        r.gate.set(r.gate.torMode())
        r.switch.onForeground()
        r.log.clear()
        r.switch.newPorts()
        assertEquals(listOf("stop@Tor", "releaseNow@Tor", "drop@Tor", "close@Tor", "acquire@Tor", "rebuild@Tor"), r.log)
        assertEquals(NetworkMode.Tor(1003, 1004), r.gate.mode.value)
        assertEquals(NetworkMode.Tor(1003, 1004), r.gate.torMode())
        assertTrue(r.store.enabled.value)
    }

    @Test
    fun `new ports that cannot be had leave gate and Tor as they were`() = runTest {
        var fail = false
        val r = Rig(pickPorts = { if (fail) throw IOException("none") else 1001 to 1002 })
        r.store.set(true)
        r.gate.set(r.gate.torMode())
        r.log.clear()
        fail = true
        org.junit.jupiter.api.assertThrows<IOException> { r.switch.newPorts() }
        assertEquals(listOf("stop@Tor", "rebuild@Tor"), r.log, "the session comes back, Tor was not touched")
        assertEquals(NetworkMode.Tor(1001, 1002), r.gate.mode.value)
        assertEquals(NetworkMode.Tor(1001, 1002), r.gate.torMode())
    }

    @Test
    fun `new ports overtaken by a turnOff do nothing, and with the gate direct neither`() = runTest {
        val r = Rig()
        r.switch.newPorts()
        assertEquals(emptyList<String>(), r.log)
        assertEquals(NetworkMode.Direct, r.gate.mode.value)
    }

    private val anonTip = com.monostr.app.data.PendingTip("a".repeat(64), null, 5, 4_000, anonEvent = "{}", anonRelays = listOf("wss://w.example"), underTor = true)
    private val publicTip = com.monostr.app.data.PendingTip("b".repeat(64), "1".repeat(64), 5, 4_000, underTor = true)
    private val directTip = com.monostr.app.data.PendingTip("c".repeat(64), "2".repeat(64), 5, 4_000)

    @Test
    fun `turnOff drops underTor tips, returns the count, and the next start sends nothing`() = runTest {
        val r = Rig(NetworkMode.Tor(1001, 1002))
        r.store.set(true)
        r.inner.add(anonTip); r.inner.add(publicTip); r.inner.add(directTip)
        assertEquals(2, r.switch.pendingUnderTor(now = 5_000))
        assertEquals(2, r.switch.turnOff())
        assertEquals(0, r.switch.pendingUnderTor(now = 5_000))
        assertEquals(listOf(directTip), r.inner.state.value)

        val tips = com.monostr.app.ui.FakeTips()
        val publish = com.monostr.app.ui.FakePublish()
        val send = SendGate(r.gate, { error("not asked in direct") }, kotlinx.coroutines.flow.emptyFlow())
        r.inner.add(anonTip) // what a send still under way wrote after the drop
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        com.monostr.app.ui.tips.TipDecorations(tips, r.inner, com.monostr.app.ui.FakeProfiles(emptyMap()), publish, scope, sendGate = send) { 5_000 }.start()
        advanceUntilIdle()
        scope.cancel()
        assertTrue(tips.anonymousResends.isEmpty(), "never over the direct route")
        assertEquals(listOf("resend:" + "c".repeat(4)), publish.calls, "the tip made in direct mode goes out as ever")
    }

    @Test
    fun `the tips go after Tor has ended and before the gate opens direct`() = runTest {
        val r = Rig(NetworkMode.Tor(1001, 1002))
        r.store.set(true)
        r.switch.turnOff()
        assertEquals(listOf("stop@Tor", "close@Tor", "drop@Tor", "releaseNow@Tor", "tips@Tor", "rebuild@Direct"), r.log)
    }

    @Test
    fun `tips that cannot be dropped keep the gate on Tor, and the caller hears it`() = runTest {
        val r = Rig(NetworkMode.Tor(1001, 1002))
        r.store.set(true)
        r.inner.add(anonTip)
        r.inner.dropError = java.io.IOException("disk")
        assertTrue(runCatching { r.switch.turnOff() }.exceptionOrNull() is java.io.IOException)
        assertEquals(NetworkMode.Tor(1001, 1002), r.gate.mode.value)
        assertTrue("rebuild@Tor" in r.log, "the session still comes back: ${r.log}")
        // asked again it finishes
        r.inner.dropError = null
        assertEquals(1, r.switch.turnOff())
        assertEquals(NetworkMode.Direct, r.gate.mode.value)
    }

    @Test
    fun `a turnOff that waited and was overtaken returns 0, the one already running does the drop`() = runTest {
        val r = Rig(NetworkMode.Tor(1001, 1002))
        r.store.set(true)
        r.inner.add(anonTip)
        val first = CompletableDeferred<Unit>()
        r.holdClose = first
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val running = scope.async { r.switch.turnOff() }   // waits in close, it has its turn
        val overtaken = scope.async { r.switch.turnOff() }
        val on = scope.launch { r.switch.turnOn() }
        first.complete(Unit)
        assertEquals(1, running.await())
        assertEquals(0, overtaken.await())
        on.join()
        assertTrue(r.inner.state.value.isEmpty())
        scope.cancel()
    }
}
