package com.monostr.app.net

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class TorSwitchStoreTest {
    @TempDir lateinit var dir: File

    private fun marker() = File(dir, "tor-enabled")

    @Test
    fun `default is off`() {
        val s = TorSwitchStore(marker())
        assertFalse(s.readAtStart())
        assertFalse(s.enabled.value)
    }

    @Test
    fun `on survives a new instance and writes the file`() = runTest {
        TorSwitchStore(marker()).set(true)
        assertTrue(marker().exists())
        val again = TorSwitchStore(marker())
        assertTrue(again.readAtStart())
        assertTrue(again.enabled.value)
    }

    @Test
    fun `off deletes the file`() = runTest {
        val s = TorSwitchStore(marker())
        s.set(true)
        s.set(false)
        assertFalse(marker().exists())
        assertFalse(s.enabled.value)
        assertFalse(TorSwitchStore(marker()).readAtStart())
    }

    @Test
    fun `an unreadable path reads fail closed and set(true) throws`() = runTest {
        val blocker = File(dir, "blocker").apply { writeText("x") }
        val s = TorSwitchStore(File(blocker, "tor-enabled")) // parent is a file
        assertTrue(s.readAtStart())
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { s.set(true) } }
        assertTrue(s.enabled.value) // unchanged: a failed set does not flip the state, and the start read was closed
    }

    @Test
    fun `concurrent writes leave file and state agreeing`() = kotlinx.coroutines.runBlocking {
        val s = TorSwitchStore(marker())
        repeat(20) { round ->
            kotlinx.coroutines.coroutineScope {
                repeat(16) { i -> launch(kotlinx.coroutines.Dispatchers.Default) { s.set(i % 2 == 0) } }
            }
            assertTrue(marker().exists() == s.enabled.value, "round $round")
        }
    }

    @Test
    fun `a sync that fails leaves no marker and no state, and the caller hears it`() = runTest {
        val marker = File(dir, "tor-enabled")
        val store = TorSwitchStore(marker, syncFile = { throw IOException("disk") })
        assertTrue(runCatching { store.set(true) }.exceptionOrNull() is IOException)
        assertFalse(marker.exists(), "file and state agree")
        assertFalse(store.enabled.value)
    }

    @Test
    fun `a directory that cannot be synced does not fail the switch, that part is best effort`() = runTest {
        val marker = File(dir, "tor-enabled")
        val store = TorSwitchStore(marker, syncDirectory = { throw IOException("refused") })
        store.set(true)
        assertTrue(marker.exists())
        assertTrue(store.enabled.value)
    }

    @Test
    fun `turning on syncs the new file, then its directory, and an existing marker neither`() = runTest {
        val marker = File(dir, "tor-enabled")
        val synced = ArrayList<String>()
        val store = TorSwitchStore(marker, syncFile = { synced += it.name }, syncDirectory = { synced += "dir:" + it.name })
        store.set(true)
        assertEquals(listOf("tor-enabled", "dir:" + dir.name), synced)
        synced.clear()
        store.set(true)
        assertEquals(emptyList<String>(), synced)
    }

    @Test
    fun `the real sync of file and directory runs on this file system`() = runTest {
        val marker = File(dir, "tor-enabled")
        TorSwitchStore(marker).set(true)
        assertTrue(marker.exists())
        syncDirectoryOf(dir)
    }
}
