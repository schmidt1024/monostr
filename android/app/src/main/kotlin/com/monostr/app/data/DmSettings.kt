package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Spec §3.4/5.4: where the account's own DM (NIP-17 gift wrap) relays are published, and how much a notification previews. */
interface DmSettingsStore {
    val relays: Flow<List<String>>
    val previewName: Flow<Boolean>
    val previewText: Flow<Boolean>
    /** The account's own kind 10050 was settled (adopted or published) once; reset on logout with [relays]. */
    val listAdopted: Flow<Boolean>
    suspend fun setRelays(relays: List<String>)
    suspend fun setPreviewName(on: Boolean)
    suspend fun setPreviewText(on: Boolean)
    suspend fun setListAdopted(adopted: Boolean)
}

class PrefsDmSettingsStore(private val store: DataStore<Preferences>) : DmSettingsStore {
    override val relays: Flow<List<String>> = store.data.map { p -> p[RELAYS]?.sorted() ?: DEFAULT_RELAYS }
    override val previewName: Flow<Boolean> = store.data.map { it[PREVIEW_NAME] ?: true }
    override val previewText: Flow<Boolean> = store.data.map { it[PREVIEW_TEXT] ?: false }
    override val listAdopted: Flow<Boolean> = store.data.map { it[LIST_ADOPTED] ?: false }

    override suspend fun setRelays(relays: List<String>) {
        store.edit { p -> p[RELAYS] = relays.map { it.trim().trimEnd('/') }.filter { isValidRelayUrl(it) }.toSet() }
    }

    override suspend fun setPreviewName(on: Boolean) { store.edit { it[PREVIEW_NAME] = on } }
    override suspend fun setPreviewText(on: Boolean) { store.edit { it[PREVIEW_TEXT] = on } }
    override suspend fun setListAdopted(adopted: Boolean) { store.edit { it[LIST_ADOPTED] = adopted } }

    companion object {
        val DEFAULT_RELAYS = listOf("wss://relay.monostr.com")
        private val RELAYS = stringSetPreferencesKey("dm_relays")
        private val PREVIEW_NAME = booleanPreferencesKey("dm_preview_name")
        private val PREVIEW_TEXT = booleanPreferencesKey("dm_preview_text")
        private val LIST_ADOPTED = booleanPreferencesKey("dm_list_adopted")
    }
}
