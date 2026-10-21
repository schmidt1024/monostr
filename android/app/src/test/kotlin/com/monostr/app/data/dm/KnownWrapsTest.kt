package com.monostr.app.data.dm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KnownWrapsTest {
    @Test
    fun `a load runs once, adds what the database holds and a clear discards a load it overtook`() {
        val k = KnownWraps()
        val token = k.startLoad()!!
        assertNull(k.startLoad(), "already loading")
        k.add("new")
        k.loaded(token, listOf("old"))
        assertTrue("old" in k); assertTrue("new" in k); assertFalse("other" in k)
        assertNull(k.startLoad(), "loaded")
        k.clear()
        assertFalse("old" in k)
        val next = k.startLoad()!!
        k.clear() // a clear while that load runs
        k.loaded(next, listOf("stale"))
        assertFalse("stale" in k, "the clear came after the load started")
        val retry = k.startLoad()!!
        k.failed(retry)
        assertNotNull(k.startLoad(), "a failed load can start again")
    }
}
