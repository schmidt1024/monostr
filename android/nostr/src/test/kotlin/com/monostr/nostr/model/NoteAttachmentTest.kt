package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoteAttachmentTest {
    private fun pic(n: Int, blurhash: String? = "LNM}7u}qfQ}q") = NoteAttachment(
        url = "https://media.monostr.com/${n.toString().repeat(64)}.jpg", mime = "image/jpeg",
        sha256 = n.toString().repeat(64), size = 1000L * n, width = 400 * n, height = 300 * n, blurhash = blurhash,
    )

    @Test
    fun `urls go to the end of the text, one per line, after a blank line`() {
        assertEquals("hello", NoteAttachment.content("  hello  ", emptyList()))
        assertEquals("hello\n\n${pic(1).url}", NoteAttachment.content("hello ", listOf(pic(1))))
        assertEquals("hello\n\n${pic(1).url}\n${pic(2).url}", NoteAttachment.content("hello", listOf(pic(1), pic(2))))
        // a note of pictures only has no leading blank line
        assertEquals("${pic(2).url}\n${pic(1).url}", NoteAttachment.content("   ", listOf(pic(2), pic(1))))
        assertEquals("", NoteAttachment.content("", emptyList()))
    }

    @Test
    fun `one imeta tag per picture, with the blurhash only when there is one`() {
        val tags = NoteAttachment.tags(listOf(pic(1), pic(2, blurhash = null)), sensitive = false)
        assertEquals(
            listOf(
                listOf("imeta", "url ${pic(1).url}", "m image/jpeg", "x ${"1".repeat(64)}", "size 1000", "dim 400x300", "blurhash LNM}7u}qfQ}q"),
                listOf("imeta", "url ${pic(2).url}", "m image/jpeg", "x ${"2".repeat(64)}", "size 2000", "dim 800x600"),
            ),
            tags,
        )
    }

    @Test
    fun `sensitive adds a content warning, but only to a note with pictures`() {
        assertEquals(listOf("content-warning", ""), NoteAttachment.tags(listOf(pic(1)), sensitive = true).last())
        assertTrue(NoteAttachment.tags(emptyList(), sensitive = true).isEmpty())
        assertTrue(NoteAttachment.tags(listOf(pic(1)), sensitive = false).none { it[0] == "content-warning" })
    }

    @Test
    fun `the app reads its own notes back as text plus media`() {
        val pics = listOf(pic(1), pic(2))
        val parsed = NoteMedia.parse(NoteAttachment.content("look at this", pics), NoteAttachment.tags(pics, sensitive = false))
        assertEquals("look at this", parsed.displayContent)
        assertEquals(pics.map { it.url }, parsed.media.map { it.url })
        assertEquals(400 to 300, parsed.media[0].dim)
        assertEquals("LNM}7u}qfQ}q", parsed.media[0].blurhash)
        assertEquals(NoteMedia.Kind.IMAGE, parsed.media[1].kind)
    }
}
