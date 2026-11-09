package com.monostr.app.net

import com.monostr.app.data.PendingTip
import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendGateTest {
    private val tor = NetworkMode.Tor(1, 2)

    /** Touching Tor at all in Direct or Unknown is what the switch-off path must never do. */
    private class Untouched {
        var asks = 0
        val gate = NetworkGate()
        val send = SendGate(gate, { asks++; true }, flow { asks++; emit(true) })
    }

    @Test
    fun `direct can send, and Tor is never asked, neither by reading nor by collecting`() = runTest {
        val u = Untouched()
        u.gate.set(NetworkMode.Direct)
        assertTrue(u.send.canSend.value)
        val seen = ArrayList<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { u.send.canSend.collect { seen += it } }
        job.cancel()
        assertEquals(listOf(true), seen)
        assertEquals(0, u.asks)
    }

    @Test
    fun `unknown cannot send on the first read, and Tor is not asked`() {
        val u = Untouched()
        assertFalse(u.send.canSend.value)
        assertEquals(0, u.asks)
    }

    @Test
    fun `in Tor only connected can send, and every read is the state of that moment`() {
        val connected = MutableStateFlow(false)
        val gate = NetworkGate().apply { set(NetworkMode.Direct) }
        val send = SendGate(gate, { connected.value }, connected)
        assertTrue(send.canSend.value)
        // no dispatcher runs between the change and the read: there is nothing stale to see
        gate.set(tor)
        assertFalse(send.canSend.value)
        connected.value = true
        assertTrue(send.canSend.value)
        connected.value = false
        assertFalse(send.canSend.value)
    }

    @Test
    fun `a collector follows mode and Tor, each change once`() = runTest {
        val connected = MutableStateFlow(true)
        val gate = NetworkGate().apply { set(NetworkMode.Unknown) }
        val send = SendGate(gate, { connected.value }, connected)
        val seen = ArrayList<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { send.canSend.collect { seen += it } }
        gate.set(NetworkMode.Direct)
        gate.set(tor)
        connected.value = false
        connected.value = true
        gate.set(NetworkMode.Direct)
        job.cancel()
        assertEquals(listOf(false, true, false, true), seen)
    }

    @Test
    fun `what may go out again depends on the tip's route and the route now`() {
        val direct = PendingTip("a".repeat(64), null, 1, 1)
        val underTor = direct.copy(underTor = true)
        val connected = MutableStateFlow(true)
        val gate = NetworkGate().apply { set(tor) }
        val send = SendGate(gate, { connected.value }, connected)
        assertTrue(send.mayResend(direct), "a direct tip may go through Tor")
        assertTrue(send.mayResend(underTor))
        connected.value = false
        assertFalse(send.mayResend(direct))
        assertFalse(send.mayResend(underTor))
        gate.set(NetworkMode.Direct)
        assertTrue(send.mayResend(direct))
        assertFalse(send.mayResend(underTor), "a tip sent under Tor never goes direct")
        gate.set(NetworkMode.Unknown)
        assertFalse(send.mayResend(direct))
    }

    @Test
    fun `viaTor tells the route of this moment`() {
        val gate = NetworkGate()
        val send = SendGate(gate, { true }, MutableStateFlow(true))
        assertFalse(send.viaTor)
        gate.set(tor)
        assertTrue(send.viaTor)
        gate.set(NetworkMode.Direct)
        assertFalse(send.viaTor)
    }
}
