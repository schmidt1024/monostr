package com.monostr.app.ui.article

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArticleTextTest {
    private val style = ArticleTextStyle(Color.Blue, "note", "event", emptyMap(), Color.Gray)
    private val opened = ArrayList<String>()
    private val actions = ArticleLinkActions({ opened += it }, { opened += "#$it" }, { opened += "p:$it" }, { opened += "n:$it" }, { opened += "a:${it.identifier}" })

    @Test
    fun `bold spans, markdown links and bare urls are styled and tappable`() {
        val text = MdText("Hello bold see docs and https://example.com/x #nostr", listOf(MdSpan.Bold(6, 10), MdSpan.Link(15, 19, "https://docs.example")))
        val out = articleText(text, style, actions)
        assertEquals("Hello bold see docs and example.com/x #nostr", out.text)
        assertEquals(FontWeight.Bold, out.spanStyles.single { it.item.fontWeight == FontWeight.Bold }.item.fontWeight)
        val links = out.getLinkAnnotations(0, out.length)
        assertEquals(3, links.size)
        links.forEach { (it.item as LinkAnnotation.Clickable).linkInteractionListener?.onClick(it.item) }
        assertEquals(listOf("https://docs.example", "https://example.com/x", "#nostr"), opened)
    }

    @Test
    fun `inline code is not scanned for links`() {
        val out = articleText(MdText("see https://example.com", listOf(MdSpan.InlineCode(0, 23))), style, actions)
        assertEquals("see https://example.com", out.text)
        assertEquals(0, out.getLinkAnnotations(0, out.length).size)
    }

    @Test
    fun `styles map through replaced mentions and shortened urls`() {
        val npub = "nostr:npub1hwamhwamhwamhwamhwamhwamhwamhwamhwamhwamhwamhwamhwasxw04hu"
        val full = "$npub hi https://www.example.com/ end"
        // bold over "hi", italic over the whole text
        val hi = full.indexOf("hi")
        val out = articleText(MdText(full, listOf(MdSpan.Bold(hi, hi + 2), MdSpan.Italic(0, full.length))), style, actions)
        assertTrue(out.text.startsWith("@") && out.text.endsWith(" hi example.com end"), out.text)
        val bold = out.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("hi", out.text.substring(bold.start, bold.end))
        val italic = out.spanStyles.single { it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic }
        assertEquals(0, italic.start)
        assertEquals(out.length, italic.end)
    }

    @Test
    fun `a hostile 64 kB paragraph of bold tokens styles fast, spans beyond the cap stay plain`() {
        val md = "**a** ".repeat(64 * 1024 / 6)
        val text = (ArticleMarkdown.parse(md).single() as MdBlock.Paragraph).text
        assertTrue(text.spans.size > MAX_SPANS)
        val t0 = System.nanoTime()
        val out = articleText(text, style, actions)
        assertTrue(System.nanoTime() - t0 < 1_000_000_000L, "took ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertEquals(text.text, out.text)
        assertEquals(MAX_SPANS, out.spanStyles.count { it.item.fontWeight == FontWeight.Bold })
    }
}
