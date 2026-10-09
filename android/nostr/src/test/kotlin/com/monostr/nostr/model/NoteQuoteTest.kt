package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Nip19Event
import rust.nostr.sdk.RelayUrl

class NoteQuoteTest {
    private val keys = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val id = "ab".repeat(32)
    private val other = "cd".repeat(32)
    private fun note1(hex: String) = EventId.parse(hex).toNostrUri()
    private fun nevent(hex: String, relay: String? = null) =
        Nip19Event(EventId.parse(hex), keys.publicKey(), Kind(1u), listOfNotNull(relay).map { RelayUrl.parse(it) }).toNostrUri()
    private fun parse(content: String, tags: List<List<String>> = emptyList()) = NoteMedia.parse(content, tags)

    @Test
    fun `a trailing nevent becomes the quote with its relay hint and leaves the text`() {
        val p = parse("look at this\n\n${nevent(id, "wss://hint.example")}")
        assertEquals(QuoteRef(id, listOf("wss://hint.example")), p.quote)
        assertEquals("look at this", p.displayContent)
    }

    @Test
    fun `a link on its own line is removed, one inside a sentence or before text on its line stays`() {
        assertEquals("first\nlast", parse("first\n${note1(id)}\nlast").displayContent)
        val inSentence = "I think ${note1(id)} is right"
        assertEquals(inSentence, parse(inSentence).displayContent)
        assertEquals(id, parse(inSentence).quote?.id)
        val leading = "${note1(id)} is great"
        assertEquals(leading, parse(leading).displayContent)
        assertEquals(id, parse(leading).quote?.id)
    }

    @Test
    fun `a trailing link on the same line as text is removed, a following dot keeps it`() {
        assertEquals("look at this", parse("look at this ${note1(id)}").displayContent)
        val dotted = "look at ${note1(id)}."
        assertEquals(dotted, parse(dotted).displayContent)
        assertEquals(id, parse(dotted).quote?.id)
    }

    @Test
    fun `a q tag wins, adds its hint and only its own link leaves the text`() {
        val tags = listOf(listOf("q", id, "wss://q.example/", keys.publicKey().toHex()))
        val p = parse("see ${note1(other)} here\n${nevent(id, "wss://n.example")}", tags)
        assertEquals(QuoteRef(id, listOf("wss://q.example", "wss://n.example")), p.quote)
        assertEquals("see ${note1(other)} here", p.displayContent)
    }

    @Test
    fun `only the first link becomes the card, later ones stay text links`() {
        val p = parse("${note1(id)}\n${note1(other)}")
        assertEquals(id, p.quote?.id)
        assertEquals(note1(other), p.displayContent)
    }

    @Test
    fun `invalid q tags are ignored, withQuote false keeps everything, media and quote combine`() {
        assertEquals(id, parse("x\n${note1(id)}", listOf(listOf("q", "nothex"))).quote?.id)
        val raw = "x\n${note1(id)}"
        val off = NoteMedia.parse(raw, emptyList(), withQuote = false)
        assertNull(off.quote)
        assertEquals(raw, off.displayContent)
        val both = parse("pic https://x.example/a.jpg\n${nevent(id)}")
        assertEquals(1, both.media.size)
        assertEquals("pic", both.displayContent)
        assertEquals(id, both.quote?.id)
        assertNull(parse("no links here").quote)
    }
}
