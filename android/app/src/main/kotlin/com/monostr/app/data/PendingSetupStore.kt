package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.monostr.app.session.PendingSetup
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

/** Bound to [pubkey]'s own file ([ownedBy]), as the drafts. */
class PrefsPendingSetupStore(private val store: DataStore<Preferences>) : PendingSetupStore {
    /** A failed read yields no record instead of crashing the session start (the official DataStore pattern). */
    private suspend fun data(pubkey: String): Preferences =
        store.ownedBy(pubkey)?.data?.catch { if (it is IOException) emit(emptyPreferences()) else throw it }?.first() ?: emptyPreferences()

    private fun key(pubkey: String) = stringPreferencesKey("$PREFIX$pubkey")

    /** An unreadable record counts as none: nothing is published from it. */
    override suspend fun load(pubkey: String): PendingSetup? =
        data(pubkey)[key(pubkey)]?.let { raw -> runCatching { json.decodeFromString(PendingSetup.serializer(), raw) }.getOrNull() }

    override suspend fun save(pubkey: String, setup: PendingSetup) {
        store.ownedBy(pubkey)?.edit { it[key(pubkey)] = json.encodeToString(PendingSetup.serializer(), setup) }
    }

    override suspend fun remove(pubkey: String) {
        store.ownedBy(pubkey)?.edit { it.remove(key(pubkey)) }
    }

    internal companion object {
        const val PREFIX = "pending_setup."
        val json = Json { ignoreUnknownKeys = true }
    }
}
