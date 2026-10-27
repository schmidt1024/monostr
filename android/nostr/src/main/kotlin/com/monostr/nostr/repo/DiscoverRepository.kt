package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import rust.nostr.sdk.Filter
import java.time.Duration

/** Spec 2026-10-27 §3: the curated lists of the monostr account, in the order the app shows them. */
object DiscoverLists {
    const val AUTHOR = "1601fcacdf227ddda10e33e71917930b49be436b43bdd030b799948a9f274ed3"
    val D_TAGS = listOf("monero", "popular", "art", "tech")
    val ADDRESSES: List<ListAddress> = D_TAGS.map { ListAddress(ListAddress.FOLLOW_SET, AUTHOR, it) }
}

/** Spec 2026-10-27 §8: the curated lists, from the database first, refreshed from the relays at most once per gate. */
interface DiscoverRepository {
    /** The stored lists in [DiscoverLists.D_TAGS] order, missing ones left out; never asks a relay, never throws. */
    suspend fun local(): List<FollowSet>
    /** One request for all of them (normal relays plus the extra ones as temporary); true when at least one is stored afterwards. */
    suspend fun refresh(): Boolean
}

class NostrDiscoverRepository(
    private val engine: NostrEngine,
    /** Asked beside the normal relays (the app's defaults: relay.monostr.com holds the lists even when the user dropped it). */
    private val extraRelays: List<String>,
    private val author: String = DiscoverLists.AUTHOR,
    private val fetchTimeout: Duration = Duration.ofSeconds(5),
    private val gateSeconds: Long = 3600,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : DiscoverRepository {
    private val mutex = Mutex()
    private var refreshedAt: Long? = null
    private var lastResult = false

    private fun filter(): Filter = Filter.fromJson(
        buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(ListAddress.FOLLOW_SET))))
            put("authors", JsonArray(listOf(JsonPrimitive(author))))
            put("#d", JsonArray(DiscoverLists.D_TAGS.map { JsonPrimitive(it) }))
        }.toString(),
    )

    override suspend fun local(): List<FollowSet> = try {
        val byD = engine.queryDecoded(filter())
            .filter { it.pubkey == author }
            .groupBy { ev -> ev.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) }
            .mapValues { (_, evs) -> evs.maxBy { it.createdAt } }
        DiscoverLists.D_TAGS.mapNotNull { d -> byD[d]?.let { FollowSetCodec.decode(it, null, interactive = false) } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    override suspend fun refresh(): Boolean = mutex.withLock {
        val t = now()
        refreshedAt?.let { if (t - it < gateSeconds) return@withLock lastResult }
        try {
            fetchWide(engine, filter(), fetchTimeout) { extraRelays }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // what the database holds still counts
        }
        lastResult = local().isNotEmpty()
        // only a refresh that brought lists holds the gate: after one that found nothing, «Retry» asks again at once
        refreshedAt = if (lastResult) t else null
        lastResult
    }
}
