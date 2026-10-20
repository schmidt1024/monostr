package com.monostr.nostr.repo

import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One own list as last read or written (spec perf 3.7): what the tab row and the list feed need before the
 * database or the signer are touched. [members] holds the decrypted private members too: the summary lives in
 * app-private storage only and leaves the device at logout ([OwnListsCache.remove]).
 */
@Serializable
data class CachedList(
    val address: String,
    val eventId: String,
    val title: String,
    val description: String? = null,
    val members: List<String>,
    val private: Boolean,
    val createdAt: Long,
    val extra: List<List<String>> = emptyList(),
) {
    /** The list as the app shows it; null when [address] is not a follow set. Never locked: a locked list is not kept. */
    fun toSet(): FollowSet? {
        val a = ListAddress.parse(address) ?: return null
        return FollowSet(a, title, description, members, private, a.pubkey, createdAt, locked = false, extra = extra, eventId = eventId)
    }

    companion object {
        fun of(set: FollowSet) = CachedList(set.address.toString(), set.eventId, set.title, set.description, set.members, set.private, set.createdAt, set.extra)
    }
}

/** Where the app keeps the own lists' summary per account (spec perf 3.7); it leaves the device with the account at logout. */
interface OwnListsCache {
    suspend fun load(pubkey: String): List<CachedList>?
    suspend fun save(pubkey: String, lists: List<CachedList>)
    suspend fun remove(pubkey: String)
}

/** The summary file's content: `{"version":1,"lists":[…]}`; another version or a broken file counts as none. */
object OwnListsJson {
    private const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Stored(val version: Int, val lists: List<CachedList>)

    fun encode(lists: List<CachedList>): String = json.encodeToString(Stored.serializer(), Stored(VERSION, lists))

    fun decode(text: String): List<CachedList>? = try {
        json.decodeFromString(Stored.serializer(), text).takeIf { it.version == VERSION }?.lists
    } catch (e: Exception) {
        null
    }
}
