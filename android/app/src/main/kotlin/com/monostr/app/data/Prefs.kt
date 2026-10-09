package com.monostr.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A corrupted preferences file is replaced with empty preferences instead of failing every read. */
val Context.monostrPrefs: DataStore<Preferences> by preferencesDataStore(
    name = "monostr",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Which signer the user logged in with. Amber sessions keep pubkey and signer package. */
data class SessionInfo(val pubkey: String, val signerType: SignerType, val amberPackage: String?)

enum class SignerType { LOCAL, AMBER }

interface SessionStore {
    val session: Flow<SessionInfo?>
    suspend fun save(info: SessionInfo)
    suspend fun clear()
}

interface RelayStore {
    val relays: Flow<List<String>>
    suspend fun set(relays: List<String>)
}

/** Accepts ws/wss URLs with a host and no whitespace; the same rule the engine can parse. */
fun isValidRelayUrl(url: String): Boolean {
    val u = url.trim()
    if (u.any { it.isWhitespace() }) return false
    val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return false
    return (uri.scheme == "wss" || uri.scheme == "ws") && !uri.host.isNullOrBlank()
}

class PrefsSessionStore(private val store: DataStore<Preferences>) : SessionStore {
    override val session: Flow<SessionInfo?> = store.data.map { p ->
        val pubkey = p[PUBKEY] ?: return@map null
        val type = p[SIGNER]?.let { runCatching { SignerType.valueOf(it) }.getOrNull() } ?: return@map null
        SessionInfo(pubkey, type, p[AMBER_PACKAGE])
    }

    override suspend fun save(info: SessionInfo) {
        store.edit { p ->
            p[PUBKEY] = info.pubkey; p[SIGNER] = info.signerType.name
            if (info.amberPackage != null) p[AMBER_PACKAGE] = info.amberPackage else p.remove(AMBER_PACKAGE)
        }
    }

    override suspend fun clear() {
        store.edit { p -> p.remove(PUBKEY); p.remove(SIGNER); p.remove(AMBER_PACKAGE) }
    }

    private companion object {
        val PUBKEY = stringPreferencesKey("session.pubkey")
        val SIGNER = stringPreferencesKey("session.signer")
        val AMBER_PACKAGE = stringPreferencesKey("session.amber_package")
    }
}

class PrefsRelayStore(private val store: DataStore<Preferences>) : RelayStore {
    override val relays: Flow<List<String>> = store.data.map { p -> p[RELAYS]?.sorted() ?: DEFAULT_RELAYS }

    override suspend fun set(relays: List<String>) {
        store.edit { p -> p[RELAYS] = relays.map { it.trim().trimEnd('/') }.filter { isValidRelayUrl(it) }.toSet() }
    }

    suspend fun current(): List<String> = relays.first()

    companion object {
        val DEFAULT_RELAYS = listOf("wss://relay.monostr.com", "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")
        private val RELAYS = stringSetPreferencesKey("relays")
    }
}

/** Relays used only for NIP-50 search (spec 3.4); an empty list is allowed and means local search only. */
interface SearchRelayStore : RelayStore {
    suspend fun reset()
}

class PrefsSearchRelayStore(private val store: DataStore<Preferences>) : SearchRelayStore {
    override val relays: Flow<List<String>> = store.data.map { p -> p[SEARCH_RELAYS]?.sorted() ?: DEFAULT_SEARCH_RELAYS }

    override suspend fun set(relays: List<String>) {
        store.edit { p -> p[SEARCH_RELAYS] = relays.map { it.trim().trimEnd('/') }.filter { isValidRelayUrl(it) }.toSet() }
    }

    override suspend fun reset() {
        store.edit { p -> p.remove(SEARCH_RELAYS) }
    }

    companion object {
        val DEFAULT_SEARCH_RELAYS = listOf("wss://search.monostr.com", "wss://search.nos.today")
        private val SEARCH_RELAYS = stringSetPreferencesKey("search_relays")
    }
}
