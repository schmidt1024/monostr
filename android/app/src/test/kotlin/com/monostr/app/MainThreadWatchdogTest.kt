package com.monostr.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainThreadWatchdogTest {
    private var t = 0L
    private val posted = mutableListOf<Runnable>()
    private val reports = mutableListOf<String>()
    private val stack = arrayOf(StackTraceElement("com.example.Feed", "load", "Feed.kt", 42))
    private val dog = MainThreadWatchdog(post = { posted += it }, mainStack = { stack }, report = { reports += it }, now = { t }, thresholdMs = 700, maxSamples = 2)

    @Test
    fun `a probe that runs in time reports nothing`() {
        dog.tick()
        t = 100
        posted.removeAt(0).run()
        t = 350
        dog.tick()
        assertEquals(emptyList<String>(), reports)
        assertEquals(1, posted.size) // the next probe
    }

    @Test
    fun `a stall past the threshold reports the main stack, at most maxSamples times, then its length`() {
        dog.tick()
        t = 500; dog.tick()
        assertEquals(0, reports.size)
        t = 800; dog.tick()
        t = 1100; dog.tick()
        t = 1400; dog.tick() // past maxSamples
        assertEquals(2, reports.size)
        assertTrue(reports[0].startsWith("main thread blocked 800 ms"))
        assertTrue(reports[0].contains("at com.example.Feed.load(Feed.kt:42)"))
        assertEquals(1, posted.size) // no second probe while one waits
        t = 3300
        posted.removeAt(0).run()
        assertEquals("main thread free after 3300 ms", reports.last())
    }
}
