package com.monostr.app.translate

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.URI

enum class TranslationMode { MONOSTR, CUSTOM, OFF }

/** A custom translate server as stored: `https://host[:port]`; plain http only for local test hosts. */
object TranslateUrl {
    private val LOCAL = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun normalize(input: String): String? {
        val v = input.trim().trimEnd('/')
        if (v.any { it.isWhitespace() }) return null
        val https = v.startsWith("https://", ignoreCase = true)
        val http = v.startsWith("http://", ignoreCase = true)
        if (!https && !http) return null
        val uri = runCatching { URI(v) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()
        if (host.isNullOrEmpty() || !uri.path.isNullOrEmpty() || uri.query != null || uri.fragment != null || uri.userInfo != null) return null
        if (http && host !in LOCAL) return null
        return (if (https) "https://" else "http://") + host + if (uri.port >= 0) ":${uri.port}" else ""
    }
}

interface TranslationSettingsStore {
    val mode: Flow<TranslationMode>
    val customUrl: Flow<String>
    val consentGiven: Flow<Boolean>
    suspend fun setMode(m: TranslationMode)
    /** Stores the normalized address; blank clears it. False (nothing stored) for an invalid address. */
    suspend fun setCustomUrl(u: String): Boolean
    suspend fun setConsentGiven()
}

class PrefsTranslationSettingsStore(private val store: DataStore<Preferences>) : TranslationSettingsStore {
    override val mode: Flow<TranslationMode> = store.data.map { p ->
        p[MODE]?.let { n -> TranslationMode.entries.firstOrNull { it.name == n } } ?: TranslationMode.MONOSTR
    }
    override val customUrl: Flow<String> = store.data.map { it[CUSTOM_URL] ?: "" }
    override val consentGiven: Flow<Boolean> = store.data.map { it[CONSENT] ?: false }

    override suspend fun setMode(m: TranslationMode) { store.edit { it[MODE] = m.name } }

    override suspend fun setCustomUrl(u: String): Boolean {
        if (u.isBlank()) { store.edit { it[CUSTOM_URL] = "" }; return true }
        val n = TranslateUrl.normalize(u) ?: return false
        store.edit { it[CUSTOM_URL] = n }
        return true
    }

    override suspend fun setConsentGiven() { store.edit { it[CONSENT] = true } }

    companion object {
        private val MODE = stringPreferencesKey("translate.mode")
        private val CUSTOM_URL = stringPreferencesKey("translate.custom_url")
        private val CONSENT = booleanPreferencesKey("translate.consent")
    }
}
