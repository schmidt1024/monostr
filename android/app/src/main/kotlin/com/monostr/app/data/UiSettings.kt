package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.monostr.app.ui.theme.AccentSeed
import androidx.datastore.preferences.core.floatPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Spec 4.3: the accent only colours the primary tokens; MONO keeps the interface black and white. */
enum class Accent(val seedArgb: Long) {
    MONO(0xFF111111),
    ORANGE(0xFFFF6600), // Monero orange
    BLUE(0xFF2F6FED),
    PURPLE(0xFF7A4FD6),
    /** Spec 11.4 §3: the seed is a placeholder; the real one comes from [UiSettingsStore.customHue]. */
    CUSTOM(0xFF2F6FED);

    companion object {
        /** v0.12.8: Monero orange out of the box — the default is the app for most people, and it says what Monostr is; Mono is one tap away. */
        val DEFAULT = ORANGE
        /** Accents that left the picker in v0.12.7 and live on as [CUSTOM] with their hue. */
        val LEGACY_HUES = mapOf("RED" to 0f, "GREEN" to 135f)
    }
}

/** Device-wide appearance settings (spec 4.4); not per account, untouched by logout. */
interface UiSettingsStore {
    val themeMode: Flow<ThemeMode>
    val accent: Flow<Accent>
    /** Spec 3.2: blur media of sensitive notes until tapped. */
    val blurSensitive: Flow<Boolean>
    /** Spec 3.2: never load pictures until tapped. */
    val mediaOnTap: Flow<Boolean>
    /** Spec 5.2: attach an author's write relays while their profile is open. */
    val authorRelays: Flow<Boolean>
    /** Spec 5.4 (Plan 10e): the Blossom server pictures are uploaded to; never empty. */
    val mediaServer: Flow<String>
    /** Spec 11.4 §3: the hue (0 ≤ h < 360) of [Accent.CUSTOM]; an invalid stored value falls back to the default. */
    val customHue: Flow<Float>
    /** NIP-89: notes, likes and reposts carry a `client` tag, so other apps show "via Monostr"; on by default. */
    val clientTag: Flow<Boolean>
    /** Follower numbers from Primal's cache (a proprietary service); on by default, off leaves the relays' NIP-45 COUNTs. */
    val primalStats: Flow<Boolean>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setAccent(accent: Accent)
    suspend fun setBlurSensitive(on: Boolean)
    suspend fun setMediaOnTap(on: Boolean)
    suspend fun setAuthorRelays(on: Boolean)
    suspend fun setMediaServer(url: String)
    suspend fun setCustomHue(hue: Float)
    suspend fun setClientTag(on: Boolean)
    suspend fun setPrimalStats(on: Boolean)
}

class PrefsUiSettingsStore(private val store: DataStore<Preferences>) : UiSettingsStore {
    private val data: Flow<Preferences> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    override val themeMode: Flow<ThemeMode> = data.map { p -> p[THEME_MODE]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.SYSTEM }
    override val accent: Flow<Accent> = data.map { p -> p[ACCENT]?.let { v -> Accent.entries.firstOrNull { it.name == v } ?: if (v in Accent.LEGACY_HUES) Accent.CUSTOM else null } ?: Accent.DEFAULT }
    override val blurSensitive: Flow<Boolean> = data.map { it[BLUR_SENSITIVE] ?: true }
    override val mediaOnTap: Flow<Boolean> = data.map { it[MEDIA_ON_TAP] ?: false }
    override val authorRelays: Flow<Boolean> = data.map { it[AUTHOR_RELAYS] ?: true }
    override val mediaServer: Flow<String> = data.map { it[MEDIA_SERVER]?.takeIf { v -> v.isNotBlank() } ?: MediaServer.DEFAULT }
    override val clientTag: Flow<Boolean> = data.map { it[CLIENT_TAG] ?: true }
    override val primalStats: Flow<Boolean> = data.map { it[PRIMAL_STATS] ?: true }
    override val customHue: Flow<Float> = data.map { p -> p[CUSTOM_HUE]?.takeIf { h -> h >= 0f && h < 360f } ?: Accent.LEGACY_HUES[p[ACCENT]] ?: AccentSeed.DEFAULT_HUE }

    override suspend fun setThemeMode(mode: ThemeMode) { store.edit { it[THEME_MODE] = mode.name } }
    override suspend fun setAccent(accent: Accent) { store.edit { it[ACCENT] = accent.name } }
    override suspend fun setCustomHue(hue: Float) { store.edit { it[CUSTOM_HUE] = if (hue >= 0f && hue < 360f) hue else AccentSeed.DEFAULT_HUE } }
    override suspend fun setBlurSensitive(on: Boolean) { store.edit { it[BLUR_SENSITIVE] = on } }
    override suspend fun setMediaOnTap(on: Boolean) { store.edit { it[MEDIA_ON_TAP] = on } }
    override suspend fun setAuthorRelays(on: Boolean) { store.edit { it[AUTHOR_RELAYS] = on } }
    override suspend fun setMediaServer(url: String) { store.edit { it[MEDIA_SERVER] = url } }
    override suspend fun setClientTag(on: Boolean) { store.edit { it[CLIENT_TAG] = on } }
    override suspend fun setPrimalStats(on: Boolean) { store.edit { it[PRIMAL_STATS] = on } }

    private companion object {
        val THEME_MODE = stringPreferencesKey("ui.theme_mode")
        val ACCENT = stringPreferencesKey("ui.accent")
        val CUSTOM_HUE = floatPreferencesKey("ui.custom_hue")
        val CLIENT_TAG = booleanPreferencesKey("ui.client_tag")
        val PRIMAL_STATS = booleanPreferencesKey("ui.primal_stats")
        val BLUR_SENSITIVE = booleanPreferencesKey("ui.blur_sensitive")
        val MEDIA_ON_TAP = booleanPreferencesKey("ui.media_on_tap")
        val AUTHOR_RELAYS = booleanPreferencesKey("ui.author_relays")
        val MEDIA_SERVER = stringPreferencesKey("ui.media_server")
    }
}
