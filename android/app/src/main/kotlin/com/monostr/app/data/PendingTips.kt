package com.monostr.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.monostr.tips.TipIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

/** A sent intent without receipt yet (spec 5.5 step 5, spec 5.8 "Pending-Tips"). */
@Serializable
data class PendingTip(
    val intentId: String,
    /** The tipped note; null for a tip to the profile of [recipient]. */
    val noteId: String?,
    val amount: Long,
    val createdAt: Long,
    /** Whom the tip is for; null in entries written before 0.8. */
    val recipient: String? = null,
    /** Anonymous tips only: the signed intent (JSON). It lives here, not in the Nostr database, so nothing can send it over the user's own connection. */
    val anonEvent: String? = null,
    /** Anonymous tips only: the watcher relays the intent goes to. */
    val anonRelays: List<String> = emptyList(),
    /** Anonymous tips only: a watcher relay accepted the intent, so it is not sent again. */
    val anonDelivered: Boolean = false,
)

object PendingTips {
    private val json = Json { ignoreUnknownKeys = true }

    /** Tips younger than the intent lifetime; older ones disappear silently (spec 5.5). */
    fun active(all: List<PendingTip>, now: Long): List<PendingTip> = all.filter { now - it.createdAt < TipIntent.TTL_SECONDS }

    fun encode(list: List<PendingTip>): String = json.encodeToString(ListSerializer(PendingTip.serializer()), list)

    /** Anything unreadable yields an empty list; a corrupt entry must not break the feed. */
    fun decode(text: String): List<PendingTip> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString(ListSerializer(PendingTip.serializer()), text) }.getOrDefault(emptyList())
}

interface PendingTipStore {
    val pending: Flow<List<PendingTip>>
    suspend fun add(tip: PendingTip)
    suspend fun remove(intentIds: Collection<String>)
    suspend fun prune(now: Long)
    /** Notes that a watcher relay accepted the anonymous intent [intentId]; no-op if it is not pending. */
    suspend fun markDelivered(intentId: String)
    /** Drops every pending tip (logout). */
    suspend fun clear()
}

class PrefsPendingTipStore(private val store: DataStore<Preferences>) : PendingTipStore {
    /** A failed read yields no pending tips instead of crashing the collectors (the official DataStore pattern). */
    private val data: Flow<Preferences> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    override val pending: Flow<List<PendingTip>> = data.map { PendingTips.decode(it[PENDING] ?: "") }

    override suspend fun add(tip: PendingTip) = update { list -> if (list.any { it.intentId == tip.intentId }) list else list + tip }

    override suspend fun remove(intentIds: Collection<String>) = update { list -> list.filter { it.intentId !in intentIds } }

    override suspend fun prune(now: Long) = update { PendingTips.active(it, now) }

    override suspend fun markDelivered(intentId: String) =
        update { list -> list.map { if (it.intentId == intentId) it.copy(anonDelivered = true) else it } }

    override suspend fun clear() { store.edit { it.remove(PENDING) } }

    private suspend fun update(f: (List<PendingTip>) -> List<PendingTip>) {
        store.edit { p -> p[PENDING] = PendingTips.encode(f(PendingTips.decode(p[PENDING] ?: ""))) }
    }

    private companion object {
        val PENDING = stringPreferencesKey("tips.pending")
    }
}
