package com.monostr.app.ui

import com.monostr.app.ui.common.NoteLinks
import com.monostr.app.ui.common.NoteSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Coordinate
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Nip19Coordinate

class NoteLinksTest {
    private val keys = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val npub = keys.publicKey().toBech32()
    private fun naddr(kind: Int, d: String) = Nip19Coordinate(Coordinate(Kind(kind.toUShort()), keys.publicKey(), d), emptyList()).toBech32()

    @Test
    fun `an naddr mid text becomes an address link, a non addressable kind stays plain`() {
        val link = "nostr:" + naddr(30311, "live")
        val spans = NoteLinks.annotate("watch $link now")
        assertEquals(3, spans.size)
        assertEquals(NoteSpan.Plain("watch "), spans[0])
        val ref = (spans[1] as NoteSpan.AddressRef).ref
        assertEquals(30311, ref.kind)
        assertEquals("live", ref.identifier)
        assertEquals(link, spans[1].text)
        assertEquals(NoteSpan.Plain(" now"), spans[2])
        // spec 2026-10-29 naddr: only kinds 30000-39999 are cards, anything else stays raw text
        val plain = "see nostr:" + naddr(1, "x")
        assertEquals(listOf(NoteSpan.Plain(plain)), NoteLinks.annotate(plain))
    }

    @Test
    fun `an all caps naddr link is matched`() {
        val link = ("nostr:" + naddr(30023, "post")).uppercase()
        val spans = NoteLinks.annotate(link)
        assertEquals(1, spans.size)
        assertEquals("post", (spans[0] as NoteSpan.AddressRef).ref.identifier)
    }

    @Test
    fun `a long link is shown shortened and still opens in full`() {
        val url = "https://codeberg.org/schmidt1024/monostr/releases/tag/v0.22.4"
        assertEquals(listOf(NoteSpan.Url("codeberg.org/schmidt1024/monost…", url)), NoteLinks.annotate(url))
        // up to 32 characters a link reads in full
        assertEquals("en.wikipedia.org/wiki/Foo_(bar)", NoteLinks.shownUrl("https://en.wikipedia.org/wiki/Foo_(bar)"))
        assertEquals("a.example/" + "b".repeat(22), NoteLinks.shownUrl("https://a.example/" + "b".repeat(22)))
        assertEquals("a.example/" + "b".repeat(21) + "…", NoteLinks.shownUrl("https://a.example/" + "b".repeat(23)))
    }

    @Test
    fun `shortening never hides the host`() {
        // the host decides where a link goes: it is always shown in full, only the path is cut
        val host = "paypal.com.login-verify-account.evil.example"
        assertEquals("$host/…", NoteLinks.shownUrl("https://$host/signin"))
        assertEquals(host, NoteLinks.shownUrl("https://$host"))
        assertEquals("a.example:8443/" + "b".repeat(16) + "…", NoteLinks.shownUrl("https://a.example:8443/" + "b".repeat(30)))
        // with a user part the text before "@" looks like a host: such a link is never cut
        val tricky = "https://paypal.com@evil.example/" + "x".repeat(40)
        assertEquals(tricky.removePrefix("https://"), NoteLinks.shownUrl(tricky))
    }

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
                NoteSpan.Plain("see "), NoteSpan.Url("x.org/a#top", "https://x.org/a#top"), NoteSpan.Plain(" and "),
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
            listOf(NoteSpan.Plain("see "), NoteSpan.Url("x.org/a", "https://x.org/a"), NoteSpan.Plain(".")),
            NoteLinks.annotate("see https://x.org/a."),
        )
        assertEquals(
            listOf(NoteSpan.Plain("("), NoteSpan.Url("x.org/a", "https://x.org/a"), NoteSpan.Plain(")")),
            NoteLinks.annotate("(https://x.org/a)"),
        )
        assertEquals(
            listOf(
                NoteSpan.Url("en.wikipedia.org/wiki/Foo_(bar)", "https://en.wikipedia.org/wiki/Foo_(bar)"),
                NoteSpan.Plain(" x"),
            ),
            NoteLinks.annotate("https://en.wikipedia.org/wiki/Foo_(bar) x"),
        )
        assertEquals(
            listOf(NoteSpan.Url("x.org/a?q=1", "https://x.org/a?q=1"), NoteSpan.Plain(",")),
            NoteLinks.annotate("https://x.org/a?q=1,"),
        )
    }

    @Test
    fun `an upper-case nostr link is a mention too`() {
        val link = "NOSTR:" + npub.uppercase()
        assertEquals(listOf(NoteSpan.Plain("hi "), NoteSpan.Mention(link, keys.publicKey().toHex())), NoteLinks.annotate("hi $link"))
    }

    @Test
    fun `a link shows without scheme, www and a lone trailing slash, and opens the full address`() {
        assertEquals(
            listOf(NoteSpan.Url("monostr.com", "https://monostr.com/"), NoteSpan.Plain(" "), NoteSpan.Url("example.org/a/", "http://www.example.org/a/")),
            NoteLinks.annotate("https://monostr.com/ http://www.example.org/a/"),
        )
    }
}
