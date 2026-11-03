package com.monostr.app.translate

import com.monostr.app.ui.article.MdSpan
import com.monostr.app.ui.article.MdText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MdHtmlTest {
    private fun roundTrip(t: MdText) = assertEquals(t, MdHtml.fromHtml(MdHtml.toHtml(t), t))

    @Test
    fun `bold and italic map to b and i and come back`() {
        val t = MdText("one two three", listOf(MdSpan.Bold(0, 3), MdSpan.Italic(8, 13)))
        assertEquals("<b>one</b> two <i>three</i>", MdHtml.toHtml(t))
        roundTrip(t)
    }

    @Test
    fun `strike and nested spans round trip`() {
        roundTrip(MdText("gone and bold italic", listOf(MdSpan.Strike(0, 4), MdSpan.Bold(9, 20), MdSpan.Italic(14, 20))))
    }

    @Test
    fun `a link carries only its index and gets the original url back`() {
        val url = "https://example.org/a?b=1&c=2"
        val t = MdText("see the docs here", listOf(MdSpan.Link(8, 12, url)))
        val html = MdHtml.toHtml(t)
        assertEquals("see the <a data-i=\"0\">docs</a> here", html)
        roundTrip(t)
        // the reply's own href is never used
        val back = MdHtml.fromHtml("voir <a data-i=\"0\" href=\"https://evil.example\">la doc</a> ici", t)!!
        assertEquals(MdText("voir la doc ici", listOf(MdSpan.Link(5, 11, url))), back)
    }

    @Test
    fun `inline code stays untranslated`() {
        val t = MdText("run ls -la now", listOf(MdSpan.InlineCode(4, 10)))
        assertEquals("run <span translate=\"no\">ls -la</span> now", MdHtml.toHtml(t))
        roundTrip(t)
        assertEquals(
            MdText("lance ls -la maintenant", listOf(MdSpan.InlineCode(6, 12))),
            MdHtml.fromHtml("lance <span translate='no'>ls -la</span> maintenant", t),
        )
        assertNull(MdHtml.fromHtml("lance <span translate=\"no\">ls -l</span> maintenant", t), "code changed")
    }

    @Test
    fun `less than and ampersand are escaped and decoded`() {
        val t = MdText("a < b & c > d", emptyList())
        assertEquals("a &lt; b &amp; c &gt; d", MdHtml.toHtml(t))
        roundTrip(t)
        assertEquals("x < y & z \"q\" 'r'", MdHtml.fromHtml("x &lt; y &amp; z &quot;q&quot; &#39;r&#39;", MdText("x", emptyList()))!!.text)
    }

    @Test
    fun `bare urls and hashtags in the text are frozen`() {
        val t = MdText("look at https://example.org/x?a=1&b=2 #nostr", emptyList())
        val html = MdHtml.toHtml(t)
        assertEquals("look at <span translate=\"no\">https://example.org/x?a=1&amp;b=2</span> <span translate=\"no\">#nostr</span>", html)
        roundTrip(t)
        assertEquals(
            MdText("regarde https://example.org/x?a=1&b=2 #nostr", emptyList()),
            MdHtml.fromHtml("regarde <span translate=\"no\">https://example.org/x?a=1&amp;b=2</span> <span translate=\"no\">#nostr</span>", t),
        )
    }

    @Test
    fun `reordered tags in the translation give spans at the new offsets`() {
        val t = MdText("red car and blue house", listOf(MdSpan.Bold(0, 7), MdSpan.Link(12, 22, "https://x.org")))
        val back = MdHtml.fromHtml("<a data-i='0'>maison bleue</a> et <strong>voiture rouge</strong>", t)
        assertEquals(MdText("maison bleue et voiture rouge", listOf(MdSpan.Link(0, 12, "https://x.org"), MdSpan.Bold(16, 29))), back)
        // <em> for <i>, upper case and unquoted attributes are fine too
        val i = MdText("very nice", listOf(MdSpan.Italic(0, 4)))
        assertEquals(MdText("sehr schön", listOf(MdSpan.Italic(0, 4))), MdHtml.fromHtml("<EM>sehr</EM> schön", i))
        val l = MdText("a b", listOf(MdSpan.Link(0, 1, "https://y.org")))
        assertEquals(MdText("x b", listOf(MdSpan.Link(0, 1, "https://y.org"))), MdHtml.fromHtml("<a data-i=0>x</a> b", l))
    }

    @Test
    fun `a missing or foreign link index or an extra tag rejects the block`() {
        val t = MdText("one two three", listOf(MdSpan.Link(0, 3, "https://a.org"), MdSpan.Link(8, 13, "https://b.org")))
        assertEquals("<a data-i=\"0\">one</a> two <a data-i=\"1\">three</a>", MdHtml.toHtml(t))
        assertNull(MdHtml.fromHtml("<a data-i=\"0\">un</a> deux trois", t), "index 1 missing")
        assertNull(MdHtml.fromHtml("<a data-i=\"0\">un</a> <a data-i=\"1\">deux</a> <a data-i=\"1\">trois</a>", t), "extra a")
        assertNull(MdHtml.fromHtml("<a data-i=\"0\">un</a> deux <a data-i=\"2\">trois</a>", t), "unknown index")
        assertNull(MdHtml.fromHtml("<a>un</a> deux <a data-i=\"1\">trois</a>", t), "no index")
        assertNull(MdHtml.fromHtml("<a data-i=\"0\">un</a> deux <a data-i=\"1\">trois</a><b>x</b>", t), "extra bold")
        assertNull(MdHtml.fromHtml("<a data-i=\"0\">un</a> <script>deux</script> <a data-i=\"1\">trois</a>", t), "unknown tag")
        assertNull(MdHtml.fromHtml("<a data-i=\"0\">un <b>deux</a></b> <a data-i=\"1\">trois</a>", t), "crossed tags")
        assertNull(MdHtml.fromHtml("<a data-i=\"0\">un</a> deux <a data-i=\"1\">trois", t), "unclosed")
        assertNull(MdHtml.fromHtml("<a data-i=\"0\">un</a> 1 < 2 <a data-i=\"1\">trois</a>", t), "raw less-than")
    }

    @Test
    fun `line breaks travel as br`() {
        val t = MdText("one\ntwo", emptyList())
        assertEquals("one<br>two", MdHtml.toHtml(t))
        roundTrip(t)
        assertEquals("un\ndeux", MdHtml.fromHtml("un<br />deux", t)!!.text)
    }

    @Test
    fun `an entity the server decoded inside a frozen piece still matches`() {
        val t = MdText("voir https://exemple.fr/a?b=1&c=2 ici", emptyList())
        assertEquals("voir <span translate=\"no\">https://exemple.fr/a?b=1&amp;c=2</span> ici", MdHtml.toHtml(t))
        // live translate.monostr.com: the frozen piece comes back with the entity decoded
        val back = MdHtml.fromHtml("see <span translate=\"no\">https://exemple.fr/a?b=1&c=2</span> here", t)
        assertEquals(MdText("see https://exemple.fr/a?b=1&c=2 here", emptyList()), back)
        assertEquals(back, MdHtml.fromHtml("see <span translate=\"no\">https://exemple.fr/a?b=1&amp;c=2</span> here", t))
    }

    @Test
    fun `a frozen piece with a line break decodes`() {
        val t = MdText("run a\nb now", listOf(MdSpan.InlineCode(4, 7)))
        roundTrip(t)
        val want = MdText("lance a\nb maintenant", listOf(MdSpan.InlineCode(6, 9)))
        assertEquals(want, MdHtml.fromHtml("lance <span translate=\"no\">a<br>b</span> maintenant", t))
        assertEquals(want, MdHtml.fromHtml("lance <span translate=\"no\">a<br />b</span> maintenant", t))
        assertNull(MdHtml.fromHtml("lance <span translate=\"no\">a<b>b</b></span> maintenant", t))
    }

    @Test
    fun `a url moved inside a link rejects the block`() {
        val t = MdText("the docs at https://x.org/a", listOf(MdSpan.Link(4, 8, "https://y.org")))
        assertNull(MdHtml.fromHtml("la <a data-i=\"0\">doc <span translate=\"no\">https://x.org/a</span></a>", t))
        // inline code inside a link stays fine
        val c = MdText("use ls here", listOf(MdSpan.Link(0, 6, "https://z.org"), MdSpan.InlineCode(4, 6)))
        assertEquals(
            MdText("nutze ls hier", listOf(MdSpan.Link(0, 8, "https://z.org"), MdSpan.InlineCode(6, 8))),
            MdHtml.fromHtml("<a data-i=\"0\">nutze <span translate=\"no\">ls</span></a> hier", c),
        )
    }

    // I1: the reader re-finds links in the decoded text; glued tokens must not change them

    @Test
    fun `a url glued to the next word rejects the block`() {
        val t = MdText("look at https://x.org/a and more", emptyList())
        assertEquals(listOf("https://x.org/a"), MdHtml.encode(t).readerTokens)
        assertNull(MdHtml.fromHtml("schau <span translate=\"no\">https://x.org/a</span>und mehr", t))
        assertEquals(
            MdText("schau https://x.org/a und mehr", emptyList()),
            MdHtml.fromHtml("schau <span translate=\"no\">https://x.org/a</span> und mehr", t),
        )
    }

    @Test
    fun `a url glued to a hashtag rejects the block`() {
        val t = MdText("https://x.org #nostr rocks", emptyList())
        assertNull(MdHtml.fromHtml("<span translate=\"no\">https://x.org</span><span translate=\"no\">#nostr</span> rockt", t))
    }

    @Test
    fun `a hashtag glued after a word rejects the block`() {
        val t = MdText("we love #nostr", emptyList())
        assertNull(MdHtml.fromHtml("wir lieben<span translate=\"no\">#nostr</span>", t))
    }

    @Test
    fun `a new token in the translated prose rejects the block`() {
        val t = MdText("we love it", emptyList())
        assertNull(MdHtml.fromHtml("wir lieben #es", t))
    }

    // I4: a link whose display text is a URL or token is not prose

    @Test
    fun `an autolink keeps its url text frozen inside its own link`() {
        val url = "https://x.org/a"
        val t = MdText("see $url here", listOf(MdSpan.Link(4, 4 + url.length, url)))
        assertEquals("see <a data-i=\"0\"><span translate=\"no\">$url</span></a> here", MdHtml.toHtml(t))
        roundTrip(t)
        assertEquals(
            MdText("siehe $url hier", listOf(MdSpan.Link(6, 6 + url.length, url))),
            MdHtml.fromHtml("siehe <a data-i=\"0\"><span translate=\"no\">$url</span></a> hier", t),
        )
        assertNull(MdHtml.fromHtml("siehe <a data-i=\"0\"><span translate=\"no\">https://x.org/b</span></a> hier", t), "changed")
        assertNull(MdHtml.fromHtml("siehe <a data-i=\"0\"></a><span translate=\"no\">$url</span> hier", t), "moved out")
    }

    @Test
    fun `a markdown link whose text is a url or a token is frozen`() {
        val t = MdText("read https://y.org or #nostr", listOf(MdSpan.Link(5, 18, "https://y.org"), MdSpan.Link(22, 28, "https://z.org")))
        val html = MdHtml.toHtml(t)
        assertEquals(
            "read <a data-i=\"0\"><span translate=\"no\">https://y.org</span></a> or <a data-i=\"1\"><span translate=\"no\">#nostr</span></a>",
            html,
        )
        roundTrip(t)
        // each frozen piece only inside its own link
        assertNull(
            MdHtml.fromHtml(
                "lies <a data-i=\"1\"><span translate=\"no\">https://y.org</span></a> oder <a data-i=\"0\"><span translate=\"no\">#nostr</span></a>",
                t,
            ),
        )
        // only a link to translate: nothing is prose
        val only = MdText("https://y.org", listOf(MdSpan.Link(0, 13, "https://y.org")))
        assertEquals(false, MdHtml.encode(only).prose)
    }

    @Test
    fun `a link with prose text is still translated`() {
        val t = MdText("the docs", listOf(MdSpan.Link(4, 8, "https://y.org")))
        assertEquals("the <a data-i=\"0\">docs</a>", MdHtml.toHtml(t))
    }
}
