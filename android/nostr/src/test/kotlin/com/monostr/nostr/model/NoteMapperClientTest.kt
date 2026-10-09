package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Tag

/** NIP-89: the `client` tag's name is kept for "via …" in the thread; nothing else of the tag matters. */
class NoteMapperClientTest {
    private val keys = Keys.generate()
    private fun event(tags: List<List<String>>) = EventBuilder.textNote("hi").tags(tags.map { Tag.parse(it) }).signWithKeys(keys)

    @Test
    fun `the client name comes from the tag, trimmed`() {
        assertEquals("Amethyst", NoteMapper.note(event(listOf(listOf("client", " Amethyst ", "31990:abc:x", "wss://r"))))!!.client)
        assertEquals("Damus", NoteMapper.note(event(listOf(listOf("client", "Damus"))))!!.client)
    }

    @Test
    fun `no tag, a bare tag or a blank name give null`() {
        assertNull(NoteMapper.note(event(emptyList()))!!.client)
        assertNull(NoteMapper.note(event(listOf(listOf("client"))))!!.client)
        assertNull(NoteMapper.note(event(listOf(listOf("client", "   "))))!!.client)
    }

    @Test
    fun `a long name is cut to 32 characters and only the first tag counts`() {
        val long = "x".repeat(50)
        assertEquals("x".repeat(32), NoteMapper.note(event(listOf(listOf("client", long))))!!.client)
        assertEquals("First", NoteMapper.note(event(listOf(listOf("client", "First"), listOf("client", "Second"))))!!.client)
    }

    @Test
    fun `a repost carries the reposter's client, the embedded note its own`() {
        val original = EventBuilder.textNote("orig").tags(listOf(Tag.parse(listOf("client", "Amethyst")))).signWithKeys(Keys.generate())
        val repost = EventBuilder.repost(original, null).tags(listOf(Tag.parse(listOf("client", "monostr")))).signWithKeys(keys)
        val n = NoteMapper.note(repost)!!
        assertEquals("monostr", n.client)
        assertEquals("Amethyst", n.repostOf!!.client)
    }
}
