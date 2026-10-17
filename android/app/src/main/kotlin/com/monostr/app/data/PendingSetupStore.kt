package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.monostr.app.session.PendingSetup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.IOException

/** Spec 2026-10-17 §7: what a new account still has to publish, per pubkey; it leaves the device with the account at logout. */
interface PendingSetupStore {
    suspend fun load(pubkey: String): PendingSetup?
    suspend fun save(pubkey: String, setup: PendingSetup)
    suspend fun remove(pubkey: String)
}

class PrefsPendingSetupStore(private val store: DataStore<Preferences>) : PendingSetupStore {
    /** A failed read yields no record instead of crashing the session start (the official DataStore pattern). */
    private val data: Flow<Preferences> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    private fun key(pubkey: String) = stringPreferencesKey("$PREFIX$pubkey")

    /** An unreadable record counts as none: nothing is published from it. */
    override suspend fun load(pubkey: String): PendingSetup? =
        data.first()[key(pubkey)]?.let { raw -> runCatching { json.decodeFromString(PendingSetup.serializer(), raw) }.getOrNull() }

    override suspend fun save(pubkey: String, setup: PendingSetup) {
        store.edit { it[key(pubkey)] = json.encodeToString(PendingSetup.serializer(), setup) }
    }

    override suspend fun remove(pubkey: String) {
        store.edit { it.remove(key(pubkey)) }
    }

    private companion object {
        const val PREFIX = "pending_setup."
        val json = Json { ignoreUnknownKeys = true }
    }
}
