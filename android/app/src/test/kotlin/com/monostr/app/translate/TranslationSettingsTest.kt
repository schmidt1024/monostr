package com.monostr.app.translate

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class TranslationSettingsTest {
    @TempDir lateinit var dir: Path

    private fun store(name: String = "tr") =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            dir.resolve("$name.preferences_pb").toFile()
        }

    private fun store(installed: Boolean, name: String = "tr") = PrefsTranslationSettingsStore(store(name), DeviceAppCheck { installed })

    @Test
    fun noStoredMode_appInstalled_isDevice() = runTest {
        assertEquals(TranslationMode.DEVICE, store(installed = true).mode.first())
    }

    @Test
    fun noStoredMode_appMissing_isMonostr() = runTest {
        assertEquals(TranslationMode.MONOSTR, store(installed = false).mode.first())
    }

    @Test
    fun storedMonostr_appInstalled_staysMonostr() = runTest {
        val s = store(installed = true)
        s.setMode(TranslationMode.MONOSTR)
        assertEquals(TranslationMode.MONOSTR, s.mode.first())
    }

    @Test
    fun storedDevice_appMissing_staysDevice() = runTest {
        val s = store(installed = false)
        s.setMode(TranslationMode.DEVICE)
        assertEquals(TranslationMode.DEVICE, s.mode.first())
    }

    @Test
    fun noStoredMode_firstEmissionIsResolved() = runTest {
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        val s = PrefsTranslationSettingsStore(store(), DeviceAppCheck { asked.complete(Unit); answer.await() })
        val modes = Collections.synchronizedList(mutableListOf<TranslationMode>())
        val consents = Collections.synchronizedList(mutableListOf<Boolean>())
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch { s.mode.collect { modes += it } }
        scope.launch { s.consentGiven.collect { consents += it } }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { asked.await() }
            delay(200)
            assertTrue(modes.isEmpty(), "nothing before the check answers: $modes")
            assertTrue(consents.isEmpty(), "no consent value before the check answers: $consents")
            answer.complete(true)
            withTimeout(5_000) { while (modes.isEmpty() || consents.isEmpty()) delay(10) }
        }
        assertEquals(TranslationMode.DEVICE, modes.first())
        assertEquals(true, consents.first())
        scope.cancel()
    }

    @Test
    fun noStoredMode_appInstalled_consentGivenTrue_andSetConsentStoresNothing() = runTest {
        val s = store(installed = true)
        assertTrue(s.consentGiven.first(), "nothing leaves the phone")
        s.setConsentGiven()
        s.setMode(TranslationMode.MONOSTR)
        assertFalse(s.consentGiven.first(), "no Monostr consent recorded while the auto mode was DEVICE")
    }

    @Test
    fun refresh_afterInstall_switchesAutoModeToDevice() = runTest {
        val auto = store(installed = false)
        assertEquals(TranslationMode.MONOSTR, auto.mode.first())
        assertFalse(auto.consentGiven.first())
        auto.deviceAppChecked(true) // Settings › Translation resumed after the install
        assertEquals(TranslationMode.DEVICE, auto.mode.first())
        assertTrue(auto.consentGiven.first())
        auto.deviceAppChecked(false) // uninstalled again
        assertEquals(TranslationMode.MONOSTR, auto.mode.first())

        val stored = store(installed = false, name = "stored")
        stored.setMode(TranslationMode.MONOSTR)
        stored.deviceAppChecked(true)
        assertEquals(TranslationMode.MONOSTR, stored.mode.first(), "a stored mode always wins")
    }

    @Test
    fun concurrentFirstCollectors_checkRunsOnce() = runTest {
        val calls = AtomicInteger()
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        val s = PrefsTranslationSettingsStore(store(), DeviceAppCheck { calls.incrementAndGet(); asked.complete(Unit); answer.await() })
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        // the collector running the check is cancelled mid-check (like a transformLatest restart): its answer still counts
        val dropped = scope.launch { s.mode.first() }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { asked.await() }
            dropped.cancel()
            val firsts = (1..4).map { i -> scope.async { if (i % 2 == 0) s.mode.first() else s.consentGiven.first() } }
            delay(100)
            answer.complete(true)
            withTimeout(5_000) { firsts.awaitAll() }
        }
        assertEquals(TranslationMode.DEVICE, s.mode.first())
        assertTrue(s.consentGiven.first())
        assertEquals(1, calls.get(), "one package query per process")
        scope.cancel()
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
    fun `a consent holds for its server only`() = runTest {
        val s = PrefsTranslationSettingsStore(store())
        s.setConsentGiven(); assertTrue(s.consentGiven.first())
        // another server: asked again
        assertTrue(s.setCustomUrl("https://other.example"))
        s.setMode(TranslationMode.CUSTOM)
        assertFalse(s.consentGiven.first())
        s.setConsentGiven(); assertTrue(s.consentGiven.first())
        // back to the Monostr server: that consent was replaced
        s.setMode(TranslationMode.MONOSTR)
        assertFalse(s.consentGiven.first())
    }

    @Test
    fun deviceMode_noConsentNeeded() = runTest {
        val s = PrefsTranslationSettingsStore(store())
        assertFalse(s.consentGiven.first())
        s.setMode(TranslationMode.DEVICE)
        assertEquals(TranslationMode.DEVICE, s.mode.first())
        assertTrue(s.consentGiven.first(), "nothing leaves the phone")
        // a server again: its consent is still asked for
        s.setMode(TranslationMode.MONOSTR)
        assertFalse(s.consentGiven.first())
    }

    @Test
    fun `a consent given in device mode stores nothing`() = runTest {
        val s = PrefsTranslationSettingsStore(store())
        s.setMode(TranslationMode.DEVICE)
        s.setConsentGiven()
        s.setMode(TranslationMode.MONOSTR)
        assertFalse(s.consentGiven.first(), "no Monostr consent recorded silently")
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
