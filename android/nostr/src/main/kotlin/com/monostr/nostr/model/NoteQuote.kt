package com.monostr.nostr.model

import rust.nostr.sdk.Nip19
import rust.nostr.sdk.Nip19Enum

/** A quoted note: its id and the relay hints that came with it (q tag, nevent). */
data class QuoteRef(val id: String, val relays: List<String> = emptyList())

/**
 * Spec 3: which note a note quotes and how its link leaves the text. A NIP-18 `q` tag wins over
 * the text; otherwise the first `nostr:note1…`/`nostr:nevent1…` that decodes. Only that one link
 * is removed from the display text, and only when it stands alone: on a line of its own, or as the
 * last token of the text after whitespace. Everything else stays a text link.
 */
object NoteQuote {
    private val REF = Regex("(?i:nostr:(?:note1|nevent1))[0-9a-zA-Z]+") // regex
    private val HEX64 = Regex("^[0-9a-f]{64}$") // regex

    fun find(content: String, tags: List<List<String>>): QuoteRef? {
        val first = tags.firstOrNull { it.size >= 2 && it[0] == "q" && HEX64.matches(it[1].lowercase()) }
        if (first != null) {
            val id = first[1].lowercase()
            // every q tag for this id may carry a hint (a client can add a bare one next to ours)
            val tagHints = tags.filter { it.size >= 3 && it[0] == "q" && it[1].lowercase() == id }
                .map { it[2].trim().trimEnd('/') }
                .filter { it.startsWith("wss://") || it.startsWith("ws://") }
            val textHints = refs(content).firstOrNull { it.second.id == id }?.second?.relays.orEmpty()
            return QuoteRef(id, (tagHints + textHints).distinct())
        }
        return refs(content).firstOrNull()?.second
    }

    fun strip(content: String, id: String): String {
        val range = refs(content).firstOrNull { it.second.id == id }?.first ?: return content
        val before = content.substring(0, range.first)
        val after = content.substring(range.last + 1)
        val ownLine = before.substringAfterLast('\n').isBlank() && after.substringBefore('\n').isBlank()
        val atEnd = after.isBlank() && (before.isEmpty() || before.last().isWhitespace())
        if (!ownLine && !atEnd) return content
        val head = before.trimEnd()
        val tail = after.trimStart()
        return when {
            head.isEmpty() -> tail
            tail.isEmpty() -> head
            else -> head + "\n" + tail
        }
    }

    fun decode(link: String): QuoteRef? {
        val bech32 = link.substringAfter(':').lowercase()
        return when (val e = runCatching { Nip19.fromBech32(bech32).asEnum() }.getOrNull()) {
            is Nip19Enum.Note -> QuoteRef(e.eventId.toHex())
            is Nip19Enum.Event -> QuoteRef(e.event.eventId().toHex(), e.event.relays().map { it.toString().trimEnd('/') })
            else -> null
        }
    }

    private fun refs(content: String): List<Pair<IntRange, QuoteRef>> =
        REF.findAll(content).mapNotNull { m -> decode(m.value)?.let { m.range to it } }.toList()
}
