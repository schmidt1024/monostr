package com.monostr.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        // NIP-89 "via monostr" tag: off by default (0.18), round-trips
        assertFalse(settings.clientTag.first())
        settings.setClientTag(true)
        assertTrue(settings.clientTag.first())
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
        // 2026-10-30: purple made room for champagne the same way
        store.edit { it[stringPreferencesKey("ui.accent")] = "PURPLE" }
        assertEquals(Accent.CUSTOM, settings.accent.first()); assertEquals(259f, settings.customHue.first())
        settings.setCustomHue(20f); assertEquals(20f, settings.customHue.first())
        scope.cancel()
    }

    @Test
    fun `media and outbox defaults, then set`() = runTest {
        val s = PrefsUiSettingsStore(store())
        assertTrue(s.blurSensitive.first()); assertTrue(s.mediaOnTap.first()); assertTrue(s.authorRelays.first())
        s.setBlurSensitive(false); s.setMediaOnTap(false); s.setAuthorRelays(false)
        assertFalse(s.blurSensitive.first()); assertFalse(s.mediaOnTap.first()); assertFalse(s.authorRelays.first())
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

    @Test
    fun `link previews are off by default and round-trip`() = runTest {
        val settings = PrefsUiSettingsStore(store())
        assertFalse(settings.linkPreviews.first())
        settings.setLinkPreviews(true)
        assertTrue(settings.linkPreviews.first())
        settings.setLinkPreviews(false)
        assertFalse(settings.linkPreviews.first())
    }

    @Test
    fun `proof of work is 8 bits by default, off stays off and an old 24 becomes 20`() = runTest {
        // user 2026-10-11: a small PoW on every note by default (Off · 8 · 16 · 20), still switchable off
        val data = store()
        val settings = PrefsUiSettingsStore(data)
        assertEquals(listOf(0, 8, 16, 20), POW_CHOICES)
        assertEquals(8, settings.powBits.first())
        settings.setPowBits(0)
        assertEquals(0, settings.powBits.first())
        data.edit { it[androidx.datastore.preferences.core.intPreferencesKey("ui.pow_bits")] = 24 }
        assertEquals(20, settings.powBits.first())
        settings.setPowBits(13)
        assertEquals(8, settings.powBits.first())
    }

    @Test
    fun `stored values from before 0_18 are kept`() = runTest {
        val st = store()
        val s = PrefsUiSettingsStore(st)
        st.edit {
            it[booleanPreferencesKey("ui.client_tag")] = true
            it[booleanPreferencesKey("ui.media_on_tap")] = false
        }
        assertTrue(s.clientTag.first()); assertFalse(s.mediaOnTap.first())
    }
}
