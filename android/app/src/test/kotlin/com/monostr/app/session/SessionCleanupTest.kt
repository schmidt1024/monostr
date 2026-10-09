package com.monostr.app.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionCleanupTest {
    @Test
    fun `every step runs even when an earlier one fails, and the failure is swallowed`() = runTest {
        val ran = ArrayList<String>()
        SessionCleanup.closeAll({ ran += "dms"; error("boom") }, { ran += "engine" }, { ran += "db" })
        assertEquals(listOf("dms", "engine", "db"), ran)
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        val result = runCatching { SessionCleanup.closeAll({ throw CancellationException("stop") }) }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
}
