package com.monostr.app.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenShieldTest {
    private val nevent = "nostr:npub1p47we20qqrn3rcnrhs22ygt2kayk320fq046y998zscq4hk7tgsqjn2qfl"
    private val invoice = "lnbc210u1p4wwjrypp5kgphh5lvzpptnsxay3rclz803akay574hxz64qv2cpe5qyx66p9qhp54k6ularuecp7avwy2fy0sywvse0gjrxf34ws4tcuyalpukl87pzqcqzysxqrrssrzjqv3dpepm8kfdxrk3sl6wzqdf49s9c0h9ljtjrek6c08r6aejlwcnur0dwyqqvusqqqqqqqlgqqqq86qqjqsp5vxyxnphtncx2scesrfrhmygqz2s4etxsjvzyz8nqwhh0qz75eg8q9qxpqysgqf6uk2y0rqql8re7pyrp9d8cthrlzhdnftx4dahu6jeusvvn0emj88q4280k6f8pmpkpczhvnwx4tm5p6098yuvzxky928ktmyfw7mmgp33s5vq"

    private fun roundTrip(x: String) {
        val s = TokenShield.protect(x)!!
        val back = TokenShield.restore(s.html, s)
        assertEquals(x, back, "html=${s.html}")
        // the renderer finds exactly the shielded tokens again, in order
        assertEquals(s.tokens.filterNot { it == "⟦" || it == "⟧" }, TokenShield.linkTokens(back!!))
    }

    @Test fun `url with query and fragment`() = roundTrip("See https://a.b/c?a=1&b=2#f now")
    @Test fun `nostr ref with trailing comma`() = roundTrip("Look $nevent, wow")
    @Test fun `hashtag in parens`() = roundTrip("Visit (#zürich) today")
    @Test fun `cashtag with period`() = roundTrip("Buy \$XMR.")
    @Test fun `lightning invoice`() = roundTrip("Pay $invoice please")
    @Test fun `emoji between tokens`() = roundTrip("https://a.b 🎉 #nostr 🚀 \$XMR")
    @Test fun `three tokens in a row`() = roundTrip("https://a.b #x \$XMR")
    @Test fun `token at start and end`() = roundTrip("https://a.b hello #end")
    @Test fun `multi line with blank lines`() = roundTrip("one\n\ntwo https://a.b\n\n\nthree\n")
    @Test fun `literal html in note`() = roundTrip("use <b>bold</b> & \"quotes\" and &amp; entity")
    @Test fun `cyrillic`() = roundTrip("Привет мир https://a.b/ж")
    @Test fun `marker chars in note`() = roundTrip("odd ⟦0⟧ text ⟧ ⟦ https://a.b")
    @Test fun `empty`() = roundTrip("")

    @Test fun `links become shielded spans`() {
        val s = TokenShield.protect("Regarde https://a.b/?x=1&y=2 et #nostr !")!!
        assertEquals(listOf("https://a.b/?x=1&y=2", "#nostr"), s.tokens)
        assertEquals("Regarde <span translate=\"no\">⟦0⟧</span> et <span translate=\"no\">⟦1⟧</span> !", s.html)
    }

    @Test fun `plain text is escaped and newlines become br`() {
        val s = TokenShield.protect("a < b & c\nd")!!
        assertEquals("a &lt; b &amp; c<br>d", s.html)
    }

    private val one = listOf("https://a.b")
    private fun span(inner: String) = "<span translate=\"no\">$inner</span>"

    @Test fun `translated sample restores`() =
        assertEquals("Schau https://a.b an!", TokenShield.restore("Schau ${span("⟦0⟧")} an!", one))

    @Test fun `dropped token rejected`() = assertNull(TokenShield.restore("Schau an!", one))
    @Test fun `duplicated token rejected`() = assertNull(TokenShield.restore("${span("⟦0⟧")} ${span("⟦0⟧")}", one))
    @Test fun `space inside marker rejected`() = assertNull(TokenShield.restore(span("⟦ 0⟧"), one))
    @Test fun `leading zero rejected`() = assertNull(TokenShield.restore(span("⟦00⟧"), one))
    @Test fun `foreign index rejected`() = assertNull(TokenShield.restore(span("⟦9⟧"), one))
    @Test fun `bare marker without span rejected`() = assertNull(TokenShield.restore("x ⟦0⟧ y", one))
    @Test fun `changed span content rejected`() = assertNull(TokenShield.restore(span("⟦0⟧ extra"), one))
    @Test fun `foreign tag rejected`() = assertNull(TokenShield.restore("<b>x</b> ${span("⟦0⟧")}", one))
    @Test fun `extra span attribute rejected`() =
        assertNull(TokenShield.restore("<span translate=\"no\" class=\"x\">⟦0⟧</span>", one))

    @Test fun `single quoted attribute accepted`() =
        assertEquals("https://a.b", TokenShield.restore("<span translate='no'>⟦0⟧</span>", one))

    @Test fun `br variants and entity re-encoding accepted`() {
        val r = TokenShield.restore("a<br/>b<br />c<br>d &amp; &#39;e&#39; &quot;f&quot; &lt;g&gt; ${span("⟦0⟧")}", one)
        assertEquals("a\nb\nc\nd & 'e' \"f\" <g> https://a.b", r)
    }

    @Test fun `whitespace around markers may change`() =
        assertEquals("x  https://a.b\n", TokenShield.restore("x  ${span("⟦0⟧")}<br>", one))

    @Test fun `every shielded token survives a pass through the markers`() {
        val s = TokenShield.protect("a $nevent b $invoice c")!!
        assertTrue(s.tokens.containsAll(listOf(nevent, invoice)))
    }

    @Test fun `lossy tokenizer output is refused`() {
        val lossy = listOf<com.monostr.app.ui.common.NoteSpan>(com.monostr.app.ui.common.NoteSpan.Plain("see "))
        assertTrue(!TokenShield.isLossless(lossy, "see https://a.b"))
        assertTrue(TokenShield.isLossless(lossy, "see "))
    }

    @Test fun `protect never refuses the corpus`() {
        val corpus = listOf(
            "See https://a.b/c?a=1&b=2#f now", "Look $nevent, wow", "Visit (#zürich) today", "Buy \$XMR.",
            "Pay $invoice please", "https://a.b 🎉 #nostr 🚀 \$XMR", "one\n\ntwo https://a.b\n\n\nthree\n",
            "use <b>bold</b> & x", "odd ⟦0⟧ text ⟧ ⟦ https://a.b", "",
        )
        corpus.forEach { assertTrue(TokenShield.protect(it) != null, it) }
    }

    // I1: the translator drops the whitespace next to a marker; the renderer must still find the same links

    private fun glued(original: String, translated: (List<String>) -> String): String? {
        val s = TokenShield.protect(original)!!
        val html = translated(s.tokens.indices.map { span("⟦$it⟧") })
        return TokenShield.restore(html, s)
    }

    @Test fun `url glued to the next word gets its space back`() {
        val r = glued("Look at https://a.b and more") { m -> "Schau ${m[0]}und mehr" }
        assertEquals("Schau https://a.b und mehr", r)
        assertEquals(listOf("https://a.b"), TokenShield.linkTokens(r!!))
    }

    @Test fun `url glued to a hashtag gets its space back`() {
        val r = glued("https://a.b #nostr rocks") { m -> "${m[0]}${m[1]} rockt" }
        assertEquals("https://a.b #nostr rockt", r)
    }

    @Test fun `mention glued to the next word gets its space back`() {
        val r = glued("$nevent is great") { m -> "${m[0]}ist toll" }
        assertEquals("$nevent ist toll", r)
        assertEquals(listOf(nevent), TokenShield.linkTokens(r!!))
    }

    @Test fun `hashtag glued after a word gets its space back`() {
        val r = glued("we love #nostr") { m -> "wir lieben${m[0]}" }
        assertEquals("wir lieben #nostr", r)
    }

    @Test fun `cashtag glued after a word gets its space back`() {
        val r = glued("buy \$XMR now") { m -> "kauf${m[0]} jetzt" }
        assertEquals("kauf \$XMR jetzt", r)
    }

    @Test fun `invoice glued to the next word still reads as the invoice`() {
        // the BOLT11 checksum bounds the invoice: the renderer finds it even glued, so nothing needs putting back
        val r = glued("pay $invoice please") { m -> "zahl ${m[0]}bitte" }
        assertEquals(listOf(invoice), TokenShield.linkTokens(r!!))
    }

    @Test fun `glued without whitespace in the original is rejected`() {
        // the original has a comma after the URL: nothing to put back, the URL would swallow «und»
        assertNull(glued("see https://a.b, and more") { m -> "schau ${m[0]}und mehr" })
    }

    @Test fun `glue that still reads as the same tokens is kept as it came`() {
        assertEquals("(https://a.b)", glued("( https://a.b )") { m -> "(${m[0]})" })
    }

    @Test fun `restore with plain token list never pads`() =
        assertNull(TokenShield.restore("${span("⟦0⟧")}und", one))
}
