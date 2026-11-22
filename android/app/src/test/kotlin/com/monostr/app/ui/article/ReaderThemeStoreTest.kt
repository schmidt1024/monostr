package com.monostr.app.ui.article

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ReaderThemeStoreTest {
    @TempDir lateinit var dir: Path

    private fun store() =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            dir.resolve("rt.preferences_pb").toFile()
        }

    @Test
    fun `store absent follows the app`() = runTest {
        assertNull(PrefsReaderThemeStore(store()).dark.first())
    }

    @Test
    fun `store set persists`() = runTest {
        val ds = store()
        val s = PrefsReaderThemeStore(ds)
        s.setDark(true); assertEquals(true, s.dark.first())
        s.setDark(false); assertEquals(false, PrefsReaderThemeStore(ds).dark.first())
    }

    @Test
    fun `unrelated settings write emits no new value`() = runTest {
        val ds = store()
        val s = PrefsReaderThemeStore(ds)
        val seen = mutableListOf<Boolean?>()
        val job = launch(Dispatchers.Unconfined) { s.dark.collect { seen.add(it) } }
        ds.edit { it[stringPreferencesKey("draft")] = "a" }
        ds.edit { it[stringPreferencesKey("draft")] = "b" }
        s.setDark(true)
        // wait until the real change arrived
        while (seen.size < 2) kotlinx.coroutines.delay(10)
        job.cancel()
        assertEquals(listOf<Boolean?>(null, true), seen)
    }
}
