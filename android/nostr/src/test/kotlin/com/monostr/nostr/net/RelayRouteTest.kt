package com.monostr.nostr.net

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class RelayRouteTest {
    private fun gate(mode: NetworkMode) = NetworkGate().apply { set(mode) }

    @Test
    fun `direct has no port and does not wait`() = runTest {
        assertNull(gate(NetworkMode.Direct).sessionSocksPort(10_000))
        assertEquals(0, currentTime)
    }

    @Test
    fun `tor gives the session port, not the isolated one`() = runTest {
        assertEquals(9001, gate(NetworkMode.Tor(sessionPort = 9001, isolatedPort = 9002)).sessionSocksPort(10_000))
    }

    @Test
    fun `unknown throws once the wait is over, never gives a direct route`() = runTest {
        val outcome = runCatching { NetworkGate().sessionSocksPort(10_000) }
        assertTrue(outcome.exceptionOrNull() is IOException, "outcome: $outcome")
        assertEquals(10_000, currentTime)
    }

    @Test
    fun `a mode that becomes known during the wait is used`() = runTest {
        val gate = NetworkGate()
        val waiting = async { gate.sessionSocksPort(10_000) }
        delay(100)
        gate.set(NetworkMode.Tor(9001, 9002))
        assertEquals(9001, waiting.await())
    }
}
