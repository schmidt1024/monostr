package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.model.NoteMapper
import kotlinx.coroutines.CancellationException
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind

/**
 * NIP-09 for events the database could not refuse: it drops a note only when it held the note
 * before the author's kind 5 arrived. A note that comes later (a relay without NIP-09, a search
 * relay, the embedded original of a repost) is stored and must be kept off the screen here. Only a
 * request by the note's own author counts; `a` tags are not read.
 */
object Withdrawn {
    private val WITHDRAWABLE = setOf(1, 6, 7)

    suspend fun filter(engine: NostrEngine, events: List<Event>): List<Event> {
        // a repost points at its original: the verified embedded copy, else the stored event its `e` tag names
        val originalOf = HashMap<String, Event>()
        for (ev in events) {
            if (ev.kind().asU16().toInt() != 6) continue
            val original = NoteMapper.verifiedEmbedded(ev) ?: run {
                val id = ev.tags().toVec().map { it.asVec() }.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                id?.let { stored(engine, it) }
            }
            if (original != null) originalOf[ev.id().toHex()] = original
        }
        // notes, and reposts and reactions themselves (an un-repost or un-like is a kind 5 naming them)
        val candidates = events.filter { it.kind().asU16().toInt() in WITHDRAWABLE } + originalOf.values
        if (candidates.isEmpty()) return events
        val gone = requested(engine, candidates)
        if (gone.isEmpty()) return events
        return events.filter { ev ->
            if (ev.id().toHex() in gone) return@filter false
            // a repost whose original is withdrawn goes with it
            val original = originalOf[ev.id().toHex()]?.id()?.toHex()
            original == null || original !in gone
        }
    }

    /** The stored event [id], null when it is missing or the read fails; cancellation still propagates. */
    private suspend fun stored(engine: NostrEngine, id: String): Event? = try {
        engine.eventById(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun isWithdrawn(engine: NostrEngine, event: Event): Boolean = filter(engine, listOf(event)).isEmpty()

    /** Ids among [notes] that a stored kind 5 of the same author names. One query per call, chunked by 100 ids. */
    private suspend fun requested(engine: NostrEngine, notes: List<Event>): Set<String> {
        val authorOf = notes.associate { it.id().toHex() to it.author().toHex() }
        val gone = HashSet<String>()
        for (chunk in authorOf.keys.chunked(100)) {
            val requests = engine.query(Filter().kind(Kind(5u)).events(chunk.map { EventId.parse(it) }))
            for (r in requests) {
                val by = r.author().toHex()
                r.tags().toVec().map { it.asVec() }
                    .filter { it.size >= 2 && it[0] == "e" && authorOf[it[1]] == by }
                    .forEach { gone += it[1] }
            }
        }
        return gone
    }
}
