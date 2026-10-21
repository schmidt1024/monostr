package com.monostr.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupGateTest {
    private fun List<String>.names() = map { it.substringAfter("ms ") }

    @Test
    fun `closed at first, open after three seconds, marked once with its reason`() = runTest {
        val lines = withPerfLines {
            val g = TimedStartupGate(backgroundScope)
            assertFalse(g.isOpen)
            advanceTimeBy(2_999); runCurrent()
            assertFalse(g.isOpen)
            advanceTimeBy(2); runCurrent()
            assertTrue(g.isOpen)
            g.open("feed fresh page")
        }
        assertEquals(listOf("startup gate open (timeout)"), lines.names())
    }

    @Test
    fun `the first open wins, stops the timer and lets the waiters go`() = runTest {
        val lines = withPerfLines {
            val g = TimedStartupGate(backgroundScope)
            var passed = false
            launch { g.await(); passed = true }
            runCurrent()
            assertFalse(passed)
            g.open("feed fresh page")
            g.open("navigation")
            runCurrent()
            assertTrue(passed)
            advanceTimeBy(5_000); runCurrent()
        }
        assertEquals(listOf("startup gate open (feed fresh page)"), lines.names(), "no timeout mark after an open")
    }

    @Test
    fun `await returns at once once open, OPEN never waits`() = runTest {
        val g = TimedStartupGate(backgroundScope)
        g.open("worker")
        g.await()
        StartupGate.OPEN.await()
        assertTrue(StartupGate.OPEN.isOpen)
        StartupGate.OPEN.open("ignored")
    }

    @Test
    fun `closing the session scope ends every waiter with a cancellation, also one on another scope`() = runTest {
        val session = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val g = TimedStartupGate(session)
        val waiter = launch { g.await(); error("ran after logout") }
        runCurrent()
        session.cancel(); runCurrent()
        assertTrue(waiter.isCancelled)
        assertFalse(g.isOpen)
    }
}
