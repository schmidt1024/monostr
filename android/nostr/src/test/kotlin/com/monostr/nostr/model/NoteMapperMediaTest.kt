package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag

class NoteMapperMediaTest {
    private val keys = Keys.generate()
    private fun event(text: String, tags: List<Tag> = emptyList()) = EventBuilder.textNote(text).tags(tags).signWithKeys(keys)

    @Test
    fun `media, display text and hashtags come from the event`() {
        val ev = event("cat https://x.example/a.jpg #Cats", listOf(Tag.parse(listOf("t", "Cats")), Tag.parse(listOf("imeta", "url https://x.example/a.jpg", "dim 10x20"))))
        val n = NoteMapper.note(ev)!!
        assertEquals(listOf("https://x.example/a.jpg"), n.media.map { it.url })
        assertEquals(10 to 20, n.media[0].dim)
        assertEquals("cat  #Cats", n.displayContent)
        assertEquals(listOf("cats"), n.hashtags)
        assertNull(n.contentWarning)
        assertFalse(n.isSensitive)
    }

    @Test
    fun `quoted id and hints come from the q tag, the quote card mapping can be switched off`() {
        val quoted = "ab".repeat(32)
        val uri = EventId.parse(quoted).toNostrUri()
        // raw builder: no automatic tag extraction by rust-nostr, the q tag is exactly ours
        val ev = EventBuilder(Kind(1u), "nice\n\n$uri")
            .tags(listOf(Tag.parse(listOf("q", quoted, "wss://q.example", keys.publicKey().toHex()))))
            .signWithKeys(keys)
        val n = NoteMapper.note(ev)!!
        assertEquals(quoted, n.quotedId)
        assertEquals(listOf("wss://q.example"), n.quoteRelays)
        assertEquals("nice", n.displayContent)
        val plain = NoteMapper.note(ev, withQuote = false)!!
        assertNull(plain.quotedId)
        assertEquals("nice\n\n$uri", plain.displayContent)
    }

    @Test
    fun `content-warning tag, nsfw t-tag and nsfw hashtag each mark the note sensitive`() {
        assertEquals("", NoteMapper.note(event("x", listOf(Tag.parse(listOf("content-warning")))))!!.contentWarning)
        assertEquals("gore", NoteMapper.note(event("x", listOf(Tag.parse(listOf("content-warning", "gore")))))!!.contentWarning)
        assertTrue(NoteMapper.note(event("x", listOf(Tag.parse(listOf("t", "NSFW")))))!!.isSensitive)
        assertTrue(NoteMapper.note(event("late #nsfw"))!!.isSensitive)
        assertFalse(NoteMapper.note(event("#nsfwart"))!!.isSensitive)
    }

    @Test
    fun `a repost carries the original's media`() {
        val original = event("https://x.example/a.jpg")
        val repost = EventBuilder.repost(original, null).signWithKeys(Keys.generate())
        val n = NoteMapper.note(repost)!!
        assertEquals(1, n.repostOf!!.media.size)
        assertTrue(n.media.isEmpty())
    }
}
