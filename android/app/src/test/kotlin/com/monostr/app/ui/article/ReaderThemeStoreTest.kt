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

    @Test
    fun `sepia defaults off and persists`() = runTest {
        val ds = store()
        val s = PrefsReaderThemeStore(ds)
        assertEquals(false, s.sepia.first())
        s.setSepia(true)
        assertEquals(true, PrefsReaderThemeStore(ds).sepia.first())
        assertNull(s.dark.first())
    }

    @Test
    fun `text step defaults to 1 persists and clamps`() = runTest {
        val ds = store()
        val s = PrefsReaderThemeStore(ds)
        assertEquals(1, s.textStep.first())
        s.setTextStep(3)
        assertEquals(3, PrefsReaderThemeStore(ds).textStep.first())
        s.setTextStep(99); assertEquals(4, s.textStep.first())
        s.setTextStep(-5); assertEquals(0, s.textStep.first())
        // a bad stored value reads clamped too
        ds.edit { it[androidx.datastore.preferences.core.intPreferencesKey("reader_text_step")] = 42 }
        assertEquals(4, s.textStep.first())
        assertEquals(1.0f, ReaderText.scale(1))
        assertEquals(1.5f, ReaderText.scale(7))
    }

    @Test
    fun `swatch maps to the two stores and back`() {
        assertEquals(false to false, ReaderSwatch.WHITE.stores())
        assertEquals(false to true, ReaderSwatch.SEPIA.stores())
        assertEquals(true to null, ReaderSwatch.DARK.stores())
        assertEquals(ReaderSwatch.WHITE, ReaderSwatch.of(false, false))
        assertEquals(ReaderSwatch.SEPIA, ReaderSwatch.of(false, true))
        assertEquals(ReaderSwatch.DARK, ReaderSwatch.of(true, true))
        assertEquals(ReaderSwatch.DARK, ReaderSwatch.of(true, false))
    }
}
