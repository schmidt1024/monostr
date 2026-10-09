package com.monostr.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class UiSettingsStoreTest {
    @TempDir lateinit var dir: Path

    private fun store() =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            dir.resolve("ui.preferences_pb").toFile()
        }

    @Test
    fun `defaults, round trip and unknown values`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("ui.preferences_pb").toFile() }
        val settings = PrefsUiSettingsStore(store)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode.first())
        assertEquals(Accent.ORANGE, settings.accent.first()) // v0.12.8: Monero orange is the default, nothing stored or an unknown value gives it
        settings.setThemeMode(ThemeMode.DARK)
        settings.setAccent(Accent.ORANGE)
        assertEquals(ThemeMode.DARK, settings.themeMode.first())
        assertEquals(Accent.ORANGE, settings.accent.first())
        // spec 11.4 §3: the custom hue defaults, round-trips, and an invalid value falls back
        assertEquals(200f, settings.customHue.first())
        // NIP-89 "via Monostr" tag: on by default, round-trips
        assertTrue(settings.clientTag.first())
        settings.setClientTag(false)
        assertFalse(settings.clientTag.first())
        assertTrue(settings.primalStats.first())
        settings.setPrimalStats(false)
        assertFalse(settings.primalStats.first())
        settings.setCustomHue(33.5f); assertEquals(33.5f, settings.customHue.first())
        settings.setCustomHue(400f); assertEquals(200f, settings.customHue.first())
        settings.setAccent(Accent.CUSTOM); assertEquals(Accent.CUSTOM, settings.accent.first())
        // a value written by a newer or older app version falls back to the defaults
        store.edit { it[stringPreferencesKey("ui.theme_mode")] = "SEPIA"; it[stringPreferencesKey("ui.accent")] = "" }
        assertEquals(ThemeMode.SYSTEM, settings.themeMode.first())
        assertEquals(Accent.ORANGE, settings.accent.first()) // v0.12.8: Monero orange is the default, nothing stored or an unknown value gives it
        // v0.12.7: red and green left the picker; a stored one becomes the custom accent with that hue, a stored hue wins
        store.edit { it[stringPreferencesKey("ui.accent")] = "RED"; it.remove(floatPreferencesKey("ui.custom_hue")) }
        assertEquals(Accent.CUSTOM, settings.accent.first()); assertEquals(0f, settings.customHue.first())
        store.edit { it[stringPreferencesKey("ui.accent")] = "GREEN" }
        assertEquals(Accent.CUSTOM, settings.accent.first()); assertEquals(135f, settings.customHue.first())
        settings.setCustomHue(20f); assertEquals(20f, settings.customHue.first())
        scope.cancel()
    }

    @Test
    fun `media and outbox defaults, then set`() = runTest {
        val s = PrefsUiSettingsStore(store())
        assertTrue(s.blurSensitive.first()); assertFalse(s.mediaOnTap.first()); assertTrue(s.authorRelays.first())
        s.setBlurSensitive(false); s.setMediaOnTap(true); s.setAuthorRelays(false)
        assertFalse(s.blurSensitive.first()); assertTrue(s.mediaOnTap.first()); assertFalse(s.authorRelays.first())
    }

    @Test
    fun `the media server defaults to the project's and survives a round trip`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("media.preferences_pb").toFile() }
        val settings = PrefsUiSettingsStore(store)
        assertEquals(MediaServer.DEFAULT, settings.mediaServer.first())
        settings.setMediaServer("https://blossom.example")
        assertEquals("https://blossom.example", settings.mediaServer.first())
        // an emptied value falls back to the default instead of leaving the app without a server
        store.edit { it[stringPreferencesKey("ui.media_server")] = " " }
        assertEquals(MediaServer.DEFAULT, settings.mediaServer.first())
        scope.cancel()
    }
}
