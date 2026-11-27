package com.monostr.app.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Spec «Adding an account»: what another account added on this device starts with. Only settings that never leave the device
 * are copied from the active account; everything a server sees (relays, media server, client tag, PoW, Monero …) and all
 * account content (drafts, feed tabs, hints, marks, search history) start at defaults or empty.
 */
object NewAccountSettings {
    private val ACCENT = stringPreferencesKey("ui.accent")

    val COPIED: Set<Preferences.Key<*>> = setOf(
        stringPreferencesKey("ui.theme_mode"),
        ACCENT,
        floatPreferencesKey("ui.custom_hue"),
        stringPreferencesKey("ui.quick_reactions"),
        booleanPreferencesKey("ui.blur_sensitive"),
        booleanPreferencesKey("ui.media_on_tap"),
        stringPreferencesKey("ui.gif_mode"),
        booleanPreferencesKey("ui.link_previews"),
        booleanPreferencesKey("ui.link_always_open"),
        booleanPreferencesKey("ui.author_relays"),
        intPreferencesKey("ui.pow_filter_bits"),
        booleanPreferencesKey("reader_dark"),
        booleanPreferencesKey("reader_sepia"),
        intPreferencesKey("reader_text_step"),
        booleanPreferencesKey("polls.first_vote_shown"),
        stringPreferencesKey("translate.mode"),
    )

    /**
     * Copies the [COPIED] keys [from] holds into [to]; the accent becomes [accentAfter] of the source's stored accent (null when
     * none is stored), so the two accounts look different at a glance.
     */
    suspend fun copy(from: DataStore<Preferences>, to: DataStore<Preferences>, accentAfter: (String?) -> String) {
        val source = from.data.first()
        val names = COPIED.mapTo(HashSet()) { it.name }
        to.edit { target ->
            source.asMap().forEach { (k, v) ->
                @Suppress("UNCHECKED_CAST")
                if (k.name in names) target[k as Preferences.Key<Any>] = v
            }
            target[ACCENT] = accentAfter(source[ACCENT])
        }
    }
}
