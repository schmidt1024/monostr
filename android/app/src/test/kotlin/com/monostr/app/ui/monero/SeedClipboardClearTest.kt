package com.monostr.app.ui.monero

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeedClipboardClearTest {
    private class Port(var own: Boolean?) : ClipboardPort {
        var cleared = 0
        override fun ownClipPresent() = own
        override fun clear() { cleared++ }
    }

    @Test
    fun `clears our clip after the delay, not before`() = runTest {
        val port = Port(own = true)
        SeedClipboardClear.schedule(this, port, delayMs = 60_000)
        advanceTimeBy(59_000); runCurrent()
        assertEquals(0, port.cleared)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(1, port.cleared)
    }

    @Test
    fun `clears when the clipboard cannot be read, as when the app is in the background`() = runTest {
        val port = Port(own = null)
        SeedClipboardClear.schedule(this, port, delayMs = 60_000)
        advanceTimeBy(60_001); runCurrent()
        assertEquals(1, port.cleared)
    }

    @Test
    fun `leaves a clip that is no longer ours`() = runTest {
        val port = Port(own = false)
        SeedClipboardClear.schedule(this, port, delayMs = 60_000)
        advanceTimeBy(60_001); runCurrent()
        assertEquals(0, port.cleared)
    }

    @Test
    fun `a second copy replaces the pending clear`() = runTest {
        val port = Port(own = true)
        SeedClipboardClear.schedule(this, port, delayMs = 60_000)
        advanceTimeBy(30_000); runCurrent()
        SeedClipboardClear.schedule(this, port, delayMs = 60_000)
        advanceTimeBy(31_000); runCurrent()
        assertEquals(0, port.cleared, "the first timer was cancelled")
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, port.cleared)
    }
}
