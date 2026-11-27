package com.monostr.app.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.monostr.app.data.Accent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NewAccountSettingsTest {
    @TempDir lateinit var dir: Path

    private fun store(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { dir.resolve("$name.preferences_pb").toFile() }

    /** Global Constraints: the copy list, verbatim. */
    private val copyList = setOf(
        "ui.theme_mode", "ui.accent", "ui.custom_hue", "ui.quick_reactions", "ui.blur_sensitive", "ui.media_on_tap", "ui.gif_mode",
        "ui.link_previews", "ui.link_always_open", "ui.author_relays", "ui.pow_filter_bits", "reader_dark", "reader_sepia",
        "reader_text_step", "polls.first_vote_shown", "translate.mode",
    )

    private suspend fun fullSource(): DataStore<Preferences> = store("a").apply {
        edit {
            it[stringPreferencesKey("ui.theme_mode")] = "BLACK"
            it[stringPreferencesKey("ui.accent")] = "ORANGE"
            it[floatPreferencesKey("ui.custom_hue")] = 120f
            it[stringPreferencesKey("ui.quick_reactions")] = "+,🔥"
            it[booleanPreferencesKey("ui.blur_sensitive")] = false
            it[booleanPreferencesKey("ui.media_on_tap")] = false
            it[stringPreferencesKey("ui.gif_mode")] = "OFF"
            it[booleanPreferencesKey("ui.link_previews")] = true
            it[booleanPreferencesKey("ui.link_always_open")] = true
            it[booleanPreferencesKey("ui.author_relays")] = false
            it[intPreferencesKey("ui.pow_filter_bits")] = 16
            it[booleanPreferencesKey("reader_dark")] = true
            it[booleanPreferencesKey("reader_sepia")] = true
            it[intPreferencesKey("reader_text_step")] = 3
            it[booleanPreferencesKey("polls.first_vote_shown")] = true
            it[stringPreferencesKey("translate.mode")] = "OFF"
            // never copied: identity, server-visible or account content
            it[stringPreferencesKey("relays")] = "wss://personal.example"
            it[stringPreferencesKey("relay_modes")] = "wss://personal.example=READ"
            it[stringPreferencesKey("search_relays")] = "wss://search.example"
            it[stringPreferencesKey("dm_relays")] = "wss://inbox.example"
            it[booleanPreferencesKey("dm_list_adopted")] = true
            it[stringPreferencesKey("ui.media_server")] = "https://media.personal.example"
            it[booleanPreferencesKey("ui.client_tag")] = true
            it[intPreferencesKey("ui.pow_bits")] = 20
            it[stringPreferencesKey("translate.custom_url")] = "https://translate.personal.example"
            it[booleanPreferencesKey("translate.consent")] = true
            it[stringPreferencesKey("translate.consent_host")] = "translate.personal.example"
            it[stringPreferencesKey("monero.setup")] = "{}"
            it[booleanPreferencesKey("tips.anonymous")] = false
            it[stringPreferencesKey("drafts.x")] = "draft"
            it[stringSetPreferencesKey("search.recent")] = setOf("bitcoin")
        }
    }

    @Test
    fun `copies only allow list`() = runTest {
        val from = fullSource()
        val to = store("b")
        NewAccountSettings.copy(from, to, Accent::nextPreset)
        val copied = to.data.first().asMap().keys.map { it.name }.toSet()
        assertEquals(copyList, copied)
        assertEquals(copyList, NewAccountSettings.COPIED.map { it.name }.toSet())
        val p = to.data.first()
        assertEquals("BLACK", p[stringPreferencesKey("ui.theme_mode")])
        assertEquals(120f, p[floatPreferencesKey("ui.custom_hue")])
        assertEquals(3, p[intPreferencesKey("reader_text_step")])
        assertEquals("OFF", p[stringPreferencesKey("translate.mode")])
    }

    @Test
    fun `accent shifted`() = runTest {
        val from = fullSource()
        val to = store("b")
        NewAccountSettings.copy(from, to, Accent::nextPreset)
        assertEquals("CHAMPAGNE", to.data.first()[stringPreferencesKey("ui.accent")])
        // the picker order wraps over the presets; custom (and retired accents living on as custom) and none stored
        assertEquals("CHAMPAGNE", Accent.nextPreset(null))
        assertEquals("MONO", Accent.nextPreset("CHAMPAGNE"))
        assertEquals("ORANGE", Accent.nextPreset("MONO"))
        assertEquals("ORANGE", Accent.nextPreset("CUSTOM"))
        assertEquals("ORANGE", Accent.nextPreset("BLUE"))
        // a source without a stored accent shows the default: the new account gets the one after it
        val empty = store("c")
        val target = store("d")
        NewAccountSettings.copy(empty, target) { "X:$it" }
        assertEquals(mapOf("ui.accent" to "X:null"), target.data.first().asMap().mapKeys { it.key.name })
    }

    @Test
    fun `media server and relays not copied`() = runTest {
        val from = fullSource()
        val to = store("b")
        NewAccountSettings.copy(from, to, Accent::nextPreset)
        val names = to.data.first().asMap().keys.map { it.name }
        for (k in listOf("relays", "relay_modes", "search_relays", "dm_relays", "dm_list_adopted", "ui.media_server", "ui.client_tag", "ui.pow_bits")) {
            assertFalse(k in names, k)
        }
    }
}
