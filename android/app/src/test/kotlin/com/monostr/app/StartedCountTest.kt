package com.monostr.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartedCountTest {
    @Test
    fun `one instance makes the app visible on start and hidden on stop`() {
        val started = StartedCount()
        assertTrue(started.start())
        assertTrue(started.stop())
    }

    @Test
    fun `a switch between two tasks keeps the app visible`() {
        // emulator 2026-11-09: the newer instance starts before the older one stops; a flag set per instance read «background»
        // while one was on screen, and turning Tor on ended the process without bringing it back
        val started = StartedCount()
        assertTrue(started.start())
        assertFalse(started.start())
        assertFalse(started.stop())
        assertTrue(started.stop())
    }

    @Test
    fun `a stop without a start does not go below nothing`() {
        val started = StartedCount()
        assertTrue(started.stop())
        assertTrue(started.start())
    }
}
