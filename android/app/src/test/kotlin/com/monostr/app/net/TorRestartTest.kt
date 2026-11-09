package com.monostr.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TorRestartTest {
    @TempDir lateinit var dir: File

    private val log = ArrayList<String>()
    private fun end(foreground: Boolean, startFails: Boolean = false, persistFails: Boolean = false) = TorProcessEnd(
        inForeground = { foreground },
        persistReturn = { log += "persist"; if (persistFails) error("disk") },
        restartActivity = { log += "start"; if (startFails) error("refused") },
        exit = { log += "exit" },
    )

    @Test
    fun `in the foreground - route, activity, exit, in that order`() {
        end(foreground = true)()
        assertEquals(listOf("persist", "start", "exit"), log)
    }

    @Test
    fun `not in the foreground - no activity is started, the next start reads the marker`() {
        end(foreground = false)()
        assertEquals(listOf("exit"), log, "no activity comes back, so nothing is there to take the route")
    }

    @Test
    fun `nothing that fails on the way keeps the process alive`() {
        end(foreground = true, startFails = true, persistFails = true)()
        assertEquals(listOf("persist", "start", "exit"), log)
    }

    private fun store() = TorRestartReturn(File(dir, "tor-return"))

    @Test
    fun `an armed route survives the process, once`() {
        store().apply { arm("settings?section=privacy"); persist() }
        val next = store()
        assertEquals("settings?section=privacy", next.take())
        assertNull(next.take())
        assertNull(store().take())
        assertFalse(File(dir, "tor-return").exists())
    }

    @Test
    fun `nothing armed, nothing stored, and what was there is gone`() {
        store().apply { arm("messages"); persist() }
        store().apply { arm(null); persist() }
        assertNull(store().take())
        store().persist()
        assertNull(store().take())
    }

    @Test
    fun `arming alone writes nothing, it is the end that writes`() {
        store().arm("messages")
        assertFalse(File(dir, "tor-return").exists())
        assertNull(store().take())
    }

    @Test
    fun `an unreadable store reads as no route`() {
        File(dir, "tor-return").apply { mkdirs(); File(this, "child").writeText("x") }
        assertNull(store().take())
    }

    @Test
    fun `an empty file is no route`() {
        File(dir, "tor-return").writeText("")
        assertNull(store().take())
        assertTrue(!File(dir, "tor-return").exists())
    }
}
