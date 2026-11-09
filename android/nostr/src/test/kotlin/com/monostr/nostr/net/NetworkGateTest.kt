package com.monostr.nostr.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkGateTest {
    @Test
    fun `starts Unknown`() {
        assertEquals(NetworkMode.Unknown, NetworkGate().mode.value)
    }

    @Test
    fun `awaitKnown suspends until set`() = runTest {
        val gate = NetworkGate()
        val got = async { gate.awaitKnown() }
        advanceUntilIdle()
        assertFalse(got.isCompleted)
        gate.set(NetworkMode.Direct)
        assertEquals(NetworkMode.Direct, got.await())
    }

    @Test
    fun `awaitKnown returns at once when known`() = runTest {
        val gate = NetworkGate()
        val tor = NetworkMode.Tor(9050, 9051)
        gate.set(tor)
        assertEquals(tor, gate.awaitKnown())
    }

    @Test
    fun `set Direct then Tor emits both`() {
        val gate = NetworkGate()
        gate.set(NetworkMode.Direct)
        assertEquals(NetworkMode.Direct, gate.mode.value)
        val tor = gate.torMode()
        gate.set(tor)
        assertEquals(tor, gate.mode.value)
    }

    @Test
    fun `tor ports are free loopback ports, distinct and kept for the process`() {
        val gate = NetworkGate()
        val first = gate.torMode()
        assertNotEquals(first.sessionPort, first.isolatedPort)
        assertTrue(first.sessionPort in 1024..65535 && first.isolatedPort in 1024..65535)
        gate.set(NetworkMode.Direct)
        assertEquals(first, gate.torMode())
    }

    @Test
    fun `initFrom sets Direct or Tor from the setting`() = runTest {
        val a = NetworkGate(); a.initFrom { false }
        assertEquals(NetworkMode.Direct, a.mode.value)
        val b = NetworkGate(); b.initFrom { true }
        assertTrue(b.mode.value is NetworkMode.Tor)
    }

    @Test
    fun `a failing read fails closed without throwing`() = runTest {
        val gate = NetworkGate()
        gate.initFrom { throw java.io.IOException("disk") }
        assertEquals(NetworkMode.Unknown, gate.mode.value)
    }

    @Test
    fun `a failing torMode leaves Unknown without throwing`() = runTest {
        val gate = NetworkGate { throw java.io.IOException("no ports") }
        gate.initFrom { true }
        assertEquals(NetworkMode.Unknown, gate.mode.value)
    }

    @Test
    fun `a set before init finishes is not overwritten`() = runTest {
        val gate = NetworkGate()
        val read = CompletableDeferred<Boolean>()
        val init = async { gate.initFrom { read.await() } }
        advanceUntilIdle()
        val tor = NetworkMode.Tor(1111, 2222)
        gate.set(tor)
        read.complete(false)
        init.await()
        assertEquals(tor, gate.mode.value)
    }

    @Test
    fun `initFrom rethrows cancellation`() = runTest {
        val gate = NetworkGate()
        val r = runCatching { gate.initFrom { throw kotlinx.coroutines.CancellationException("x") } }
        assertTrue(r.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(NetworkMode.Unknown, gate.mode.value)
    }
}
