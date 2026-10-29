package com.monostr.app.ui.article

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArticleMarkdownTest {
    private fun parse(s: String) = ArticleMarkdown.parse(s)
    private fun allText(b: List<MdBlock>): String = b.joinToString("|") {
        when (it) {
            is MdBlock.Heading -> it.text.text
            is MdBlock.Paragraph -> it.text.text
            is MdBlock.Quote -> allText(it.blocks)
            is MdBlock.ListBlock -> it.items.joinToString("|") { i -> allText(i) }
            is MdBlock.Code -> it.text
            else -> ""
        }
    }

    @Test fun headingsClamp() {
        val b = parse("# a\n\n## b\n\n###### c")
        assertEquals(listOf(1, 2, 3), b.map { (it as MdBlock.Heading).level })
    }

    @Test fun inlineSpans() {
        val p = parse("x **b** *i* ~~s~~ `c` [l](https://e.com)")[0] as MdBlock.Paragraph
        assertEquals("x b i s c l", p.text.text)
        assertEquals(
            listOf(
                MdSpan.Bold(2, 3), MdSpan.Italic(4, 5), MdSpan.Strike(6, 7),
                MdSpan.InlineCode(8, 9), MdSpan.Link(10, 11, "https://e.com"),
            ),
            p.text.spans,
        )
    }

    @Test fun quoteCodeRule() {
        val b = parse("> q\n\n```\ncode\n```\n\n---")
        assertEquals(MdBlock.Quote(listOf(MdBlock.Paragraph(MdText("q", emptyList())))), b[0])
        assertEquals(MdBlock.Code("code"), b[1])
        assertEquals(MdBlock.Rule, b[2])
    }

    @Test fun orderedStart() {
        val l = parse("3. a\n4. b")[0] as MdBlock.ListBlock
        assertTrue(l.ordered)
        assertEquals(3, l.start)
        assertEquals(2, l.items.size)
    }

    @Test fun nestedListFlattensBeyondTwoLevels() {
        val l = parse("- a\n  - b\n    - c\n      - d")[0] as MdBlock.ListBlock
        val inner = l.items[0].filterIsInstance<MdBlock.ListBlock>().single()
        assertTrue(inner.items[0].none { it is MdBlock.ListBlock })
        assertTrue(allText(listOf(l)).contains("c") && allText(listOf(l)).contains("d"))
    }

    private fun para(s: String) = (parse(s)[0] as MdBlock.Paragraph).text.text

    @Test fun htmlDropped() {
        assertEquals(emptyList<MdBlock>(), parse("<script>alert(1)</script>"))
        assertEquals("x", para("<b>x</b>"))
        assertEquals("a x c", para("a <b>x</b> c"))
        assertEquals("a  c", para("a <script>alert(1)</script> c"))
        assertEquals("a", para("a <style>p{}</style><iframe src=x>never"))
        assertEquals("hello", para("<div>\nhello\n</div>"))
        assertEquals(emptyList<MdBlock>(), parse("<iframe src=\"x\"></iframe>"))
        assertEquals("ok", para("<div><script>evil()</script>ok</div>"))
    }

    @Test fun htmlBlockTagsEndLines() {
        assertEquals("a\nb", para("<p>a</p><p>b</p>"))
        assertEquals("a\nb", para("<div>a<br>b</div>"))
        assertEquals("one\ntwo", para("<ul><li>one</li><li>two</li></ul>"))
        assertEquals("T\nx", para("<h2>T</h2>x"))
    }

    @Test fun htmlNoQuadratic() {
        val t0 = System.nanoTime()
        parse("<b>".repeat(70_000))
        parse("<script>".repeat(30_000))
        assertTrue((System.nanoTime() - t0) < 1_000_000_000L)
    }

    @Test fun deepNestingSurvives() {
        val t0 = System.nanoTime()
        parse(">".repeat(10_000) + " x")
        parse("*a ".repeat(5_000) + "b" + "*".repeat(5_000))
        parse("- ".repeat(5_000) + "x")
        assertTrue((System.nanoTime() - t0) < 1_000_000_000L)
    }

    @Test fun linkSchemes() {
        val p = parse("[a](javascript:x) [b](DATA:x) [c](file:///x) [d](intent:x) [e](HTTPS://e.com) [f](nostr:npub1x) [g](mailto:a@b.c)")[0] as MdBlock.Paragraph
        assertEquals(listOf("HTTPS://e.com", "nostr:npub1x", "mailto:a@b.c"), p.text.spans.map { (it as MdSpan.Link).url })
        assertEquals("a b c d e f g", p.text.text)
    }

    @Test fun pictureFilter() {
        assertEquals(emptyList<MdBlock>(), parse("![a](http://example.com/a.png)"))
        assertEquals(emptyList<MdBlock>(), parse("![a](https://192.168.1.2/a.png)"))
        assertEquals(listOf(MdBlock.Picture("https://example.com/a.png", "alt")), parse("![alt](https://example.com/a.png)"))
    }

    @Test fun inlinePictureSplitOut() {
        val b = parse("before ![alt](https://example.com/a.png) after")
        assertEquals(2, b.size)
        assertEquals("before  after", (b[0] as MdBlock.Paragraph).text.text)
        assertEquals(MdBlock.Picture("https://example.com/a.png", "alt"), b[1])
    }

    @Test fun nostrKept() {
        val s = "hi nostr:npub1abcdef and nostr:note1xyz"
        assertEquals(s, (parse(s)[0] as MdBlock.Paragraph).text.text)
    }

    @Test fun table() {
        val t = parse("| a | b |\n|---|---|\n| 1 | **2** |\n| 3 | 4 |")[0] as MdBlock.Table
        assertEquals(listOf("a", "b"), t.header.map { it.text })
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), t.rows.map { r -> r.map { it.text } })
        assertEquals(listOf(MdSpan.Bold(0, 1)), t.rows[0][1].spans)
    }

    @Test fun readingMinutes() {
        assertEquals(2, ArticleMarkdown.readingMinutes(List(460) { "w" }.joinToString(" ")))
        assertEquals(1, ArticleMarkdown.readingMinutes(""))
        assertEquals(1, ArticleMarkdown.readingMinutes(List(230) { "w" }.joinToString(" ")))
    }
}
