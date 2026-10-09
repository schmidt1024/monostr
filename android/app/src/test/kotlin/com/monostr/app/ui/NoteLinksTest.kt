package com.monostr.app.ui

import com.monostr.app.ui.common.NoteLinks
import com.monostr.app.ui.common.NoteSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys

class NoteLinksTest {
    private val keys = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val npub = keys.publicKey().toBech32()

    @Test
    fun `hashtags end before punctuation and keep the text as written`() {
        assertEquals(
            listOf(NoteSpan.Plain("I love "), NoteSpan.Hashtag("#Monero", "monero"), NoteSpan.Plain(", really #1")),
            NoteLinks.annotate("I love #Monero, really #1"),
        )
    }

    @Test
    fun `a hash inside a url is part of the url and mentions resolve to hex`() {
        val spans = NoteLinks.annotate("see https://x.org/a#top and nostr:$npub!")
        assertEquals(
            listOf(
                NoteSpan.Plain("see "), NoteSpan.Url("https://x.org/a#top", "https://x.org/a#top"), NoteSpan.Plain(" and "),
                NoteSpan.Mention("nostr:$npub", keys.publicKey().toHex()), NoteSpan.Plain("!"),
            ),
            spans,
        )
    }

    @Test
    fun `invalid nostr link and mid-word hash stay plain`() {
        assertEquals(listOf(NoteSpan.Plain("nostr:npub1nope a#b")), NoteLinks.annotate("nostr:npub1nope a#b"))
        assertEquals(emptyList<NoteSpan>(), NoteLinks.annotate(""))
    }

    @Test
    fun `urls leave sentence punctuation and an unmatched closing paren to the surrounding text`() {
        assertEquals(
            listOf(NoteSpan.Plain("see "), NoteSpan.Url("https://x.org/a", "https://x.org/a"), NoteSpan.Plain(".")),
            NoteLinks.annotate("see https://x.org/a."),
        )
        assertEquals(
            listOf(NoteSpan.Plain("("), NoteSpan.Url("https://x.org/a", "https://x.org/a"), NoteSpan.Plain(")")),
            NoteLinks.annotate("(https://x.org/a)"),
        )
        assertEquals(
            listOf(
                NoteSpan.Url("https://en.wikipedia.org/wiki/Foo_(bar)", "https://en.wikipedia.org/wiki/Foo_(bar)"),
                NoteSpan.Plain(" x"),
            ),
            NoteLinks.annotate("https://en.wikipedia.org/wiki/Foo_(bar) x"),
        )
        assertEquals(
            listOf(NoteSpan.Url("https://x.org/a?q=1", "https://x.org/a?q=1"), NoteSpan.Plain(",")),
            NoteLinks.annotate("https://x.org/a?q=1,"),
        )
    }

    @Test
    fun `an upper-case nostr link is a mention too`() {
        val link = "NOSTR:" + npub.uppercase()
        assertEquals(listOf(NoteSpan.Plain("hi "), NoteSpan.Mention(link, keys.publicKey().toHex())), NoteLinks.annotate("hi $link"))
    }
}
