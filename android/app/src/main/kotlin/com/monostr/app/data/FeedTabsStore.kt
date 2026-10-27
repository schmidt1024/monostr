package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.monostr.nostr.repo.CachedTabs
import com.monostr.nostr.repo.FeedTabsCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.IOException

/** Spec 2026-10-18 §3.3: the pinned tabs per account, JSON under `feed_tabs.<pubkey>`; logout removes them. */
class PrefsFeedTabsStore(private val store: DataStore<Preferences>) : FeedTabsCache {
    /** A failed read yields no copy instead of crashing the session start (the official DataStore pattern). */
    private val data: Flow<Preferences> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    private fun key(pubkey: String) = stringPreferencesKey("$PREFIX$pubkey")

    /** An unreadable value counts as none: the relay event fills it again. */
    override suspend fun load(pubkey: String): CachedTabs? {
        val raw = data.first()[key(pubkey)] ?: return null
        return try {
            val o = Json.parseToJsonElement(raw).jsonObject
            CachedTabs(
                o.getValue("tabs").jsonArray.map { it.jsonPrimitive.content },
                o.getValue("createdAt").jsonPrimitive.long,
                // backlog 38: absent in copies written before the order existed
                o["order"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun save(pubkey: String, tabs: CachedTabs) {
        val raw = buildJsonObject {
            put("tabs", JsonArray(tabs.tabs.map { JsonPrimitive(it) }))
            put("createdAt", tabs.createdAt)
            if (tabs.order.isNotEmpty()) put("order", JsonArray(tabs.order.map { JsonPrimitive(it) }))
        }.toString()
        store.edit { it[key(pubkey)] = raw }
    }

    override suspend fun remove(pubkey: String) {
        store.edit { it.remove(key(pubkey)) }
    }

    private companion object {
        const val PREFIX = "feed_tabs."
    }
}
