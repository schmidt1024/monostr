package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AccountPrefsTest {
    @TempDir lateinit var dir: Path

    private val accent = stringPreferencesKey("ui.accent")
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    private fun file(name: String) = dir.resolve("$name.preferences_pb").toFile()

    private fun globalStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { file("global") }

    private fun prefs(global: DataStore<Preferences> = globalStore()) = AccountPrefs(
        global = global,
        open = { pk, scope -> PreferenceDataStoreFactory.create(scope = scope) { file("monostr-$pk") } },
        deleteFile = { pk -> file("monostr-$pk").delete() },
    )

    @Test
    fun `reads and writes go to active account file`() = runTest {
        val p = prefs()
        p.activate(a)
        p.edit { it[accent] = "RED" }
        assertEquals("RED", p.storeFor(a).data.first()[accent])
        assertNull(p.storeFor(b).data.first()[accent])
        assertTrue(file("monostr-${pk16(a)}").exists())
        assertEquals(pk16(a), p.activePk16.value)
    }

    @Test
    fun `switch changes emitted values`() = runTest {
        val p = prefs()
        p.storeFor(a).edit { it[accent] = "RED" }
        p.storeFor(b).edit { it[accent] = "BLUE" }
        p.activate(a)
        val seen = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val job = launch(Dispatchers.IO) { p.data.map { it[accent] }.collect { seen.add(it) } }
        withTimeout(5000) { while (seen.isEmpty()) delay(10) }
        p.activate(b)
        withTimeout(5000) { while (seen.size < 2) delay(10) }
        job.cancel()
        assertEquals(listOf<String?>("RED", "BLUE"), seen.toList())
    }

    @Test
    fun `no active account uses global`() = runTest {
        val g = globalStore()
        val p = prefs(g)
        p.activate(null)
        p.edit { it[accent] = "GLOBAL" }
        assertEquals("GLOBAL", g.data.first()[accent])
        p.activate(a)
        assertNull(p.data.first()[accent])
        p.activate(null)
        assertEquals("GLOBAL", p.data.first()[accent])
    }

    @Test
    fun `nothing is read or written before the first activation`() = runTest {
        val g = globalStore()
        g.edit { it[accent] = "GLOBAL" }
        val p = prefs(g)
        p.storeFor(a).edit { it[accent] = "RED" }
        val read = async(Dispatchers.IO) { p.data.first()[accent] }
        val write = async(Dispatchers.IO) { p.edit { it[accent] = "LATE" } }
        Thread.sleep(200)
        assertFalse(read.isCompleted, "no value before the active account is known")
        assertFalse(write.isCompleted, "no write before the active account is known")
        p.initialize(a)
        // the waiting read and write race once released: the read sees the account's value, before or after the write
        assertTrue(read.await() in setOf("RED", "LATE"))
        write.await()
        assertEquals("LATE", p.storeFor(a).data.first()[accent])
        assertEquals("GLOBAL", g.data.first()[accent], "the global file is untouched")
    }

    @Test
    fun `initialize never overrides an activation`() = runTest {
        val p = prefs()
        p.activate(b)
        p.initialize(a)
        assertEquals(pk16(b), p.activePk16.value)
    }

    @Test
    fun `storeFor returns same instance`() {
        val p = prefs()
        assertSame(p.storeFor(a), p.storeFor(a))
    }

    @Test
    fun `delete removes file and refuses active`() = runTest {
        val p = prefs()
        p.storeFor(a).edit { it[accent] = "RED" }
        p.storeFor(b).edit { it[accent] = "BLUE" }
        p.activate(a)
        try { p.delete(a); fail("expected IllegalStateException") } catch (_: IllegalStateException) {}
        p.delete(b)
        assertFalse(file("monostr-${pk16(b)}").exists())
        // re-add opens a fresh instance on the same file path
        assertNull(p.storeFor(b).data.first()[accent])
    }

    @Test
    fun `deleting the files takes the scratch file of an interrupted write too`() {
        val f = file("monostr-x")
        f.writeText("p")
        val tmp = java.io.File(f.path + ".tmp").apply { writeText("half") }
        AccountPrefs.deleteFiles(f)
        assertFalse(f.exists())
        assertFalse(tmp.exists())
    }

    @Test
    fun `delete waits for the store's own work and no store opens on the file meanwhile`() = runTest {
        val opened = java.util.concurrent.atomic.AtomicInteger()
        val gate = java.util.concurrent.CountDownLatch(1)
        lateinit var self: AccountPrefs
        val p = AccountPrefs(
            global = globalStore(),
            open = { pk, scope -> opened.incrementAndGet(); PreferenceDataStoreFactory.create(scope = scope) { file("monostr-$pk") } },
            deleteFile = { pk ->
                // while the file goes, the account cannot be opened again
                val reopened = runCatching { self.storeFor(b) }.isSuccess
                gate.countDown()
                assertFalse(reopened)
                AccountPrefs.deleteFiles(file("monostr-$pk"))
            },
        )
        self = p
        p.storeFor(b).edit { it[accent] = "BLUE" }
        p.activate(a)
        p.delete(b)
        assertEquals(0L, gate.count)
        assertFalse(file("monostr-${pk16(b)}").exists())
        assertEquals(1, opened.get())
        assertNull(p.storeFor(b).data.first()[accent])
        assertEquals(2, opened.get())
    }
}
