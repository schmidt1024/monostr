package com.monostr.app.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EngineDirTest {
    @TempDir lateinit var files: File
    private val account get() = File(files, "nostr-0123456789abcdef")

    @Test
    fun `a new account folder gets a subfolder, and the same one at every later start`() {
        val first = EngineDir.of(account)
        assertEquals(account, first.parentFile)
        assertTrue(first.isDirectory)
        assertEquals(first, EngineDir.of(account))
    }

    @Test
    fun `an account from before keeps its database in the folder itself`() {
        account.mkdirs()
        File(account, "data.mdb").writeText("x")
        assertEquals(account, EngineDir.of(account))
        assertEquals(account, EngineDir.of(account))
    }

    @Test
    fun `a folder removed and made again never gets the path it had`() {
        val before = EngineDir.of(account)
        assertTrue(account.deleteRecursively())
        assertNotEquals(before, EngineDir.of(account))
        // an account from before, removed: not the folder itself again
        account.deleteRecursively()
        account.mkdirs()
        File(account, "data.mdb").writeText("x")
        assertEquals(account, EngineDir.of(account))
        account.deleteRecursively()
        assertNotEquals(account, EngineDir.of(account))
    }

    @Test
    fun `a marker that names anything but a database folder is not followed`() {
        account.mkdirs()
        File(account, EngineDir.MARKER).writeText("../elsewhere")
        val dir = EngineDir.of(account)
        assertEquals(account, dir.parentFile)
        assertTrue(dir.name.startsWith("db-"))
    }

    @Test
    fun `marks without a database are removed with the fresh folder, beside a database they stay`() {
        account.mkdirs()
        File(account, "sync-marks.json").writeText("{}")
        val dir = EngineDir.of(account)
        assertEquals(setOf(EngineDir.MARKER, dir.name), account.list()!!.toSet())
        File(account, "sync-marks.json").writeText("{}")
        EngineDir.of(account)
        assertTrue(File(account, "sync-marks.json").exists())
    }
}
