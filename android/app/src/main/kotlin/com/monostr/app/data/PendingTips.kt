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
    /** Another attempt on the same target arrived: no longer shown as waiting. Stays stored, so that an undelivered intent of a tip that was paid after all still goes out. */
    val superseded: Boolean = false,
    /** Public tips only: the watcher's relays the intent goes to; empty while the watcher named none. */
    val watcherRelays: List<String> = emptyList(),
    /** Public tips only: one of [watcherRelays] accepted the intent, so it is not sent to them again. */
    val watcherDelivered: Boolean = false,
) {
    /** A watcher relay has the intent, as far as this device knows. */
    val isDelivered: Boolean get() = if (anonEvent != null) anonDelivered else watcherDelivered
}

object PendingTips {
    private val json = Json { ignoreUnknownKeys = true }

    /** Tips younger than the intent lifetime; older ones disappear silently (spec 5.5). */
    fun active(all: List<PendingTip>, now: Long): List<PendingTip> = all.filter { now - it.createdAt < TipIntent.TTL_SECONDS }

    /** [tip] with its watcher delivery noted, on the path it travels. */
    fun delivered(tip: PendingTip): PendingTip = if (tip.anonEvent != null) tip.copy(anonDelivered = true) else tip.copy(watcherDelivered = true)

    /** Active tips to show as waiting for payment. */
    fun waiting(all: List<PendingTip>, now: Long): List<PendingTip> = active(all, now).filter { !it.superseded }

    /** [all] without the arrived [intentIds], every other attempt on one of their targets marked superseded. */
    fun settled(all: List<PendingTip>, intentIds: Collection<String>): List<PendingTip> {
        val arrived = all.filter { it.intentId in intentIds }
        return all.filter { it.intentId !in intentIds }.map { tip -> if (arrived.any { sameTarget(it, tip) }) tip.copy(superseded = true) else tip }
    }

    private fun sameTarget(a: PendingTip, b: PendingTip): Boolean =
        if (a.noteId != null) a.noteId == b.noteId else b.noteId == null && a.recipient == b.recipient

    fun encode(list: List<PendingTip>): String = json.encodeToString(ListSerializer(PendingTip.serializer()), list)

    /** Anything unreadable yields an empty list; a corrupt entry must not break the feed. */
    fun decode(text: String): List<PendingTip> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString(ListSerializer(PendingTip.serializer()), text) }.getOrDefault(emptyList())
}

interface PendingTipStore {
    val pending: Flow<List<PendingTip>>
    suspend fun add(tip: PendingTip)
    /** The receipts of [intentIds] came in: drops them and supersedes the other attempts on their targets, see [PendingTips.settled]. */
    suspend fun settle(intentIds: Collection<String>)
    suspend fun prune(now: Long)
    /** Notes that a watcher relay accepted the intent [intentId]; no-op if it is not pending. */
    suspend fun markDelivered(intentId: String)
    /** Notes the watcher relays of the public intent [intentId], once the watcher named them; no-op if it is not pending. */
    suspend fun setWatcherRelays(intentId: String, relays: List<String>)
    /** Drops every pending tip (logout). */
    suspend fun clear()
}

class PrefsPendingTipStore(private val store: DataStore<Preferences>) : PendingTipStore {
    /** A failed read yields no pending tips instead of crashing the collectors (the official DataStore pattern). */
    private val data: Flow<Preferences> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    override val pending: Flow<List<PendingTip>> = data.map { PendingTips.decode(it[PENDING] ?: "") }

    override suspend fun add(tip: PendingTip) = update { list -> if (list.any { it.intentId == tip.intentId }) list else list + tip }

    override suspend fun settle(intentIds: Collection<String>) = update { list -> PendingTips.settled(list, intentIds) }

    override suspend fun prune(now: Long) = update { PendingTips.active(it, now) }

    override suspend fun markDelivered(intentId: String) =
        update { list -> list.map { if (it.intentId == intentId) PendingTips.delivered(it) else it } }

    override suspend fun setWatcherRelays(intentId: String, relays: List<String>) =
        update { list -> list.map { if (it.intentId == intentId) it.copy(watcherRelays = relays) else it } }

    override suspend fun clear() { store.edit { it.remove(PENDING) } }

    private suspend fun update(f: (List<PendingTip>) -> List<PendingTip>) {
        store.edit { p -> p[PENDING] = PendingTips.encode(f(PendingTips.decode(p[PENDING] ?: ""))) }
    }

    private companion object {
        val PENDING = stringPreferencesKey("tips.pending")
    }
}
