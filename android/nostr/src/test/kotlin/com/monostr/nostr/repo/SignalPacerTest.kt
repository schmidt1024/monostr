package com.monostr.nostr.repo

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec perf2 C4, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignalPacerTest {
    @Test
    fun `twenty signals inside one window make one read`() = runTest {
        val p = SignalPacer(windowMs = 150)
        val reads = ArrayList<Long>()
        backgroundScope.launch { p.run { reads += testScheduler.currentTime } }
        repeat(20) { p.signal(); advanceTimeBy(5) }
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf(150L), reads)
    }

    @Test
    fun `a signal during a read makes exactly one more read`() = runTest {
        val p = SignalPacer(windowMs = 150)
        var version = 0
        val seen = ArrayList<Int>()
        // a slow database read: it sees the state at its start
        backgroundScope.launch { p.run { val v = version; delay(50); seen += v } }
        version = 1; p.signal()
        advanceTimeBy(170) // the read runs from 150 to 200
        version = 2; p.signal()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun `a milestone reads at once and cuts a running window`() = runTest {
        val p = SignalPacer(windowMs = 150)
        val reads = ArrayList<Long>()
        backgroundScope.launch { p.run { reads += testScheduler.currentTime } }
        p.signal(milestone = true); runCurrent()
        assertEquals(listOf(0L), reads)
        p.signal(); advanceTimeBy(50)
        p.signal(milestone = true); runCurrent()
        assertEquals(listOf(0L, 50L), reads)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(0L, 50L), reads, "the plain signal was covered by the milestone's read")
    }

    @Test
    fun `steady signals are read every window and the last one is read`() = runTest {
        val p = SignalPacer(windowMs = 150)
        var version = 0
        val seen = ArrayList<Int>()
        val times = ArrayList<Long>()
        backgroundScope.launch { p.run { seen += version; times += testScheduler.currentTime } }
        repeat(75) { version++; p.signal(); advanceTimeBy(10) } // a constant stream over 750 ms
        val during = times.toList()
        assertTrue(during.size >= 4, "a debounce that resets would starve: $during")
        during.zipWithNext { a, b -> assertTrue(b - a in 150..160, "cadence (window plus the 10 ms to the next signal): $during") }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(75, seen.last(), "the last change is never dropped")
    }
}
