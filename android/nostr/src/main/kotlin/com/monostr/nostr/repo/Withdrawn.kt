package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.NoteMapper
import com.monostr.tips.Kinds
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import rust.nostr.sdk.Event
import rust.nostr.sdk.Filter
import com.monostr.tips.event.Event as JsonEvent

/**
 * NIP-09 for events the database could not refuse: it drops a note only when it held the note
 * before the author's kind 5 arrived. A note that comes later (a relay without NIP-09, a search
 * relay, the embedded original of a repost) is stored and must be kept off the screen here. Only a
 * request by the note's own author counts; `a` tags are not read.
 */
object Withdrawn {
    private val WITHDRAWABLE = setOf(1, 6, 7, 16, Kinds.COMMENT)
    private val HEX64 = Regex("[0-9a-f]{64}")
    private const val ID_CHUNK = 100

    // read in Kotlin: one FFI call per event instead of one per field and tag (slow on a phone)
    suspend fun filter(engine: NostrEngine, events: List<Event>): List<Event> =
        filterDecoded(engine, events.map { it to NoteMapper.decoded(it) }).map { it.first }

    /** [filter] for events already read in Kotlin (spec perf 3.1): no second decode. */
    suspend fun filterDecoded(engine: NostrEngine, events: List<Pair<Event, JsonEvent>>): List<Pair<Event, JsonEvent>> {
        // a repost points at its original: the verified embedded copy, else the stored event its `e` tag names
        val originalOf = HashMap<String, Pair<String, String>>() // repost id -> (original id, original author)
        val bare = HashMap<String, String>() // repost id -> the id its `e` tag names, looked up together below
        for ((_, ev) in events) {
            if (ev.kind != 6) continue
            val embedded = NoteMapper.verifiedEmbedded(ev)
            if (embedded != null) originalOf[ev.id] = embedded.id().toHex() to embedded.author().toHex()
            else ev.firstTagValue("e")?.let { bare[ev.id] = it }
        }
        if (bare.isNotEmpty()) {
            val authorOf = storedAuthors(engine, bare.values.toSet())
            for ((repost, original) in bare) authorOf[original]?.let { originalOf[repost] = original to it }
        }
        // notes, and reposts and reactions themselves (an un-repost or un-like is a kind 5 naming them)
        val candidates = events.filter { it.second.kind in WITHDRAWABLE }.associate { it.second.id to it.second.pubkey } + originalOf.values
        if (candidates.isEmpty()) return events
        val gone = requested(engine, candidates)
        if (gone.isEmpty()) return events
        return events.filter { (_, ev) ->
            if (ev.id in gone) return@filter false
            // a repost whose original is withdrawn goes with it
            val original = originalOf[ev.id]?.first
            original == null || original !in gone
        }
    }

    suspend fun isWithdrawn(engine: NostrEngine, event: Event): Boolean = filter(engine, listOf(event)).isEmpty()

    /**
     * Authors of the stored events [ids], one database read per [ID_CHUNK] ids (spec perf 3.6: was one `eventById`
     * per repost). A malformed id is left out (a relay may send any `e` tag); a failing read leaves its chunk out,
     * so those reposts stay, as with a failing single read before.
     */
    private suspend fun storedAuthors(engine: NostrEngine, ids: Set<String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (chunk in ids.filter { HEX64.matches(it) }.chunked(ID_CHUNK)) {
            try {
                for (ev in engine.query(idsFilter(chunk)).map { NoteMapper.decoded(it) }) out[ev.id] = ev.pubkey
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // these reposts stay
            }
        }
        return out
    }

    /** Ids among [authorOf] (note id -> author) that a stored kind 5 of the same author names. One query per [ID_CHUNK] ids. */
    private suspend fun requested(engine: NostrEngine, authorOf: Map<String, String>): Set<String> {
        val gone = HashSet<String>()
        for (chunk in authorOf.keys.chunked(ID_CHUNK)) {
            val filter = Filter.fromJson(
                buildJsonObject {
                    put("kinds", JsonArray(listOf(JsonPrimitive(5))))
                    put("#e", JsonArray(chunk.map { JsonPrimitive(it) }))
                }.toString(),
            )
            // each request read once in Kotlin, not field by field and tag by tag through the FFI
            for (r in engine.query(filter).map { NoteMapper.decoded(it) }) {
                r.tags.filter { it.size >= 2 && it[0] == "e" && authorOf[it[1]] == r.pubkey }.forEach { gone += it[1] }
            }
        }
        return gone
    }
}
