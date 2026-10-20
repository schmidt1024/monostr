package com.monostr.nostr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PerfTraceTest {
    @Test
    fun `a mark prints only when tracing is on, with the time since the process started`() {
        val lines = withPerfLines {
            PerfTrace.enabled = false
            PerfTrace.sinceStart = { 1234 }
            PerfTrace.mark("off")
            assertTrue(isEmpty())
            PerfTrace.enabled = true
            PerfTrace.mark("session ready")
        }
        assertEquals(listOf("MonostrPerf @1234ms session ready"), lines)
    }
}
