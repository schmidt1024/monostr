package com.monostr.nostr.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class NetworkGateNewPortsTest {
    @Test
    fun `new ports are the mode and the cached pair in one step`() {
        var next = 1
        val gate = NetworkGate(pickPorts = { next++ to next++ })
        gate.set(gate.torMode())
        gate.newTorMode()
        assertEquals(NetworkMode.Tor(3, 4), gate.mode.value)
        assertEquals(NetworkMode.Tor(3, 4), gate.torMode())
    }

    @Test
    fun `ports that cannot be had change neither`() {
        var fail = false
        val gate = NetworkGate(pickPorts = { if (fail) throw IOException("none") else 1 to 2 })
        gate.set(gate.torMode())
        fail = true
        assertThrows<IOException> { gate.newTorMode() }
        assertEquals(NetworkMode.Tor(1, 2), gate.mode.value)
        assertEquals(NetworkMode.Tor(1, 2), gate.torMode())
    }
}
