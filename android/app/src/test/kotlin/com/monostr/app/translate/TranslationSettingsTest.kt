package com.monostr.app.translate

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class TranslationSettingsTest {
    @TempDir lateinit var dir: Path

    private fun store() =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            dir.resolve("tr.preferences_pb").toFile()
        }

    @Test
    fun `defaults and round trip`() = runTest {
        val s = PrefsTranslationSettingsStore(store())
        assertEquals(TranslationMode.MONOSTR, s.mode.first())
        assertEquals("", s.customUrl.first())
        assertFalse(s.consentGiven.first())
        s.setMode(TranslationMode.CUSTOM); assertEquals(TranslationMode.CUSTOM, s.mode.first())
        s.setMode(TranslationMode.OFF); assertEquals(TranslationMode.OFF, s.mode.first())
        assertTrue(s.setCustomUrl("  HTTPS://Translate.Example.org/ "))
        assertEquals("https://translate.example.org", s.customUrl.first())
        s.setConsentGiven(); assertTrue(s.consentGiven.first())
    }

    @Test
    fun `invalid custom url stores nothing`() = runTest {
        val s = PrefsTranslationSettingsStore(store())
        s.setCustomUrl("https://a.example")
        assertFalse(s.setCustomUrl("ftp://nope"))
        assertFalse(s.setCustomUrl("http://evil.example"))
        assertEquals("https://a.example", s.customUrl.first())
        assertTrue(s.setCustomUrl("")); assertEquals("", s.customUrl.first())
    }

    @Test
    fun `normalize`() {
        assertEquals("https://h.example:8443", TranslateUrl.normalize("https://h.example:8443/"))
        assertEquals("http://localhost:8080", TranslateUrl.normalize("http://localhost:8080"))
        assertEquals("http://127.0.0.1:1", TranslateUrl.normalize("http://127.0.0.1:1"))
        assertEquals("http://10.0.2.2:1", TranslateUrl.normalize("http://10.0.2.2:1"))
        assertNull(TranslateUrl.normalize("http://example.org"))
        assertNull(TranslateUrl.normalize("https://h.example/path"))
        assertNull(TranslateUrl.normalize("https://u:p@h.example"))
        assertNull(TranslateUrl.normalize("h.example"))
        assertNull(TranslateUrl.normalize("https://a b"))
    }
}
