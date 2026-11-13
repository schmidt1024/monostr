package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag

/** NIP-88 polls: kind 1068 tags to a [Poll], and the note mapping. */
class PollTest {
    private fun opt(id: String, label: String) = listOf("option", id, label)

    @Test
    fun `parse reads options, type, end and relays`() {
        val p = Polls.parse(listOf(
            opt("a", "Yes"), opt("b", "No"), listOf("polltype", "multiplechoice"),
            listOf("endsAt", "1800000000"), listOf("relay", "wss://r.example"),
        ))!!
        assertEquals(listOf(PollOption("a", "Yes"), PollOption("b", "No")), p.options)
        assertTrue(p.multiple)
        assertEquals(1800000000L, p.endsAt)
        assertEquals(listOf("wss://r.example"), p.relays)
    }

    @Test
    fun `a missing polltype is single choice`() {
        assertFalse(Polls.parse(listOf(opt("a", "Yes"), opt("b", "No")))!!.multiple)
    }

    @Test
    fun `duplicate ids keep the first, empty labels drop, at most 10 shown`() {
        val dup = Polls.parse(listOf(opt("a", "First"), opt("a", "Second"), opt("b", "  "), opt("c", "Third")))!!
        assertEquals(listOf(PollOption("a", "First"), PollOption("c", "Third")), dup.options)
        val many = Polls.parse((1..15).map { opt("o$it", "Option $it") })!!
        assertEquals(Polls.MAX_SHOWN, many.options.size)
        assertEquals("o10", many.options.last().id)
    }

    @Test
    fun `an option with a blank id drops`() {
        val p = Polls.parse(listOf(opt("", "Empty"), opt("  ", "Blank"), opt("a", "Yes"), opt("b", "No")))!!
        assertEquals(listOf(PollOption("a", "Yes"), PollOption("b", "No")), p.options)
    }

    @Test
    fun `fewer than two options is no poll`() {
        assertNull(Polls.parse(listOf(opt("a", "Only"))))
        assertNull(Polls.parse(listOf(opt("a", "Same"), opt("a", "Dup"), opt("b", ""))))
        assertNull(Polls.parse(emptyList()))
    }

    @Test
    fun `no endsAt never ends`() {
        val p = Polls.parse(listOf(opt("a", "Yes"), opt("b", "No")))!!
        assertNull(p.endsAt)
        assertFalse(p.ended(Long.MAX_VALUE))
    }

    @Test
    fun `an endsAt that is not a number counts as missing`() {
        val p = Polls.parse(listOf(opt("a", "Yes"), opt("b", "No"), listOf("endsAt", "soon")))!!
        assertNull(p.endsAt)
    }

    @Test
    fun `ended is true from endsAt on`() {
        val p = Polls.parse(listOf(opt("a", "Yes"), opt("b", "No"), listOf("endsAt", "100")))!!
        assertFalse(p.ended(99))
        assertTrue(p.ended(100))
    }

    private fun event(content: String, tags: List<List<String>>) =
        EventBuilder(Kind(1068u), content).tags(tags.map { Tag.parse(it) }).signWithKeys(Keys.generate())

    @Test
    fun `a kind 1068 maps to a note with its poll, content as text`() {
        val n = NoteMapper.note(event("Which one? #nostr", listOf(opt("a", "Yes"), opt("b", "No"), listOf("t", "Vote"))))!!
        assertEquals(1068, n.kind)
        assertEquals("Which one? #nostr", n.content)
        assertEquals(listOf("vote"), n.hashtags)
        assertNotNull(n.poll)
        assertTrue(n.isPoll)
        assertEquals(2, n.poll!!.options.size)
    }

    @Test
    fun `a kind 1068 without a valid poll maps as a plain note`() {
        val n = NoteMapper.note(event("broken", listOf(opt("a", "Only"))))!!
        assertEquals(1068, n.kind)
        assertEquals("broken", n.content)
        assertNull(n.poll)
        assertFalse(n.isPoll)
    }
}
