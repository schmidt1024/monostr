package com.monostr.app.translate

import com.monostr.app.ui.article.MdBlock
import com.monostr.app.ui.article.MdSpan
import com.monostr.app.ui.article.MdText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleTranslationTest {
    private class FakeClient {
        val calls = mutableListOf<List<String>>()
        val timeouts = mutableListOf<Long>()
        var inFlight = 0
        var maxInFlight = 0
        var answer: (Int, List<String>) -> TranslateResult = { _, texts -> TranslateResult.Ok(texts.map { "DE " + it }) }
        suspend fun translate(server: TranslateServer, texts: List<String>, source: String, target: String, timeoutMs: Long): TranslateResult {
            calls += texts
            timeouts += timeoutMs
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                delay(1_000) // the server's time: a second call meanwhile would show in maxInFlight
                return answer(calls.size, texts)
            } finally {
                inFlight--
            }
        }
    }

    private fun para(i: Int) = MdBlock.Paragraph(MdText("paragraph number $i", emptyList()))
    private fun texts(blocks: List<MdBlock>) = blocks.map { (it as MdBlock.Paragraph).text.text }
    private fun translation(c: FakeClient) = ArticleTranslation(c::translate, TranslateServer.MONOSTR, "fr", "de")

    private fun long(i: Int) = MdBlock.Paragraph(MdText("x".repeat(400) + " paragraph $i", emptyList()))

    @Test
    fun `one block per request, one request at a time, in reading order`() = runTest {
        val c = FakeClient()
        val t = translation(c)
        t.run("", null, (1..5).map(::long))
        assertEquals(List(5) { 1 }, c.calls.map { it.size })
        assertEquals((1..5).map { "x".repeat(400) + " paragraph $it" }, c.calls.flatten())
        assertEquals(1, c.maxInFlight, "never two requests at once")
        assertEquals((1..5).map { "DE " + "x".repeat(400) + " paragraph $it" }, texts(t.blocks.value))
        assertFalse(t.partial.value)
    }

    @Test
    fun `very short neighbours share a request up to 300 characters`() = runTest {
        val c = FakeClient()
        val t = translation(c)
        t.run("", null, (1..20).map(::para)) // 18-19 characters each
        assertEquals(listOf(8, 8, 4), c.calls.map { it.size })
        assertEquals((1..20).map { "paragraph number $it" }, c.calls.flatten())
        assertEquals((1..20).map { "DE paragraph number $it" }, texts(t.blocks.value))
    }

    // I5: the first request is small so the top of the article shows fast

    @Test
    fun `the title goes alone first`() = runTest {
        val c = FakeClient()
        translation(c).run("Le titre", null, (1..3).map(::long))
        assertEquals(listOf("Le titre"), c.calls[0])
        assertEquals(listOf(1, 1, 1, 1), c.calls.map { it.size })
    }

    @Test
    fun `each block shows as soon as its answer lands`() = runTest {
        val c = FakeClient()
        val t = translation(c)
        val run = launch { t.run("Le titre", null, (1..3).map(::long)) }
        runCurrent()
        assertEquals("Le titre", t.title.value)
        advanceTimeBy(1_001) // the title's answer
        assertEquals("DE Le titre", t.title.value)
        assertEquals(listOf(false, false, false), texts(t.blocks.value).map { it.startsWith("DE ") })
        advanceTimeBy(1_000) // the first paragraph's
        assertEquals(listOf(true, false, false), texts(t.blocks.value).map { it.startsWith("DE ") })
        advanceTimeBy(1_000)
        assertEquals(listOf(true, true, false), texts(t.blocks.value).map { it.startsWith("DE ") })
        run.join()
        assertEquals(listOf(true, true, true), texts(t.blocks.value).map { it.startsWith("DE ") })
    }

    @Test
    fun `batch composition`() {
        // fresh run with a title: the title alone, then one block each when they are long
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3)), ArticleTranslation.batches(listOf(20, 400, 301, 1000), lead = 1))
        // short neighbours together while <= 300 characters and <= 8 texts
        assertEquals(listOf(listOf(0, 1, 2), listOf(3), listOf(4, 5)), ArticleTranslation.batches(listOf(100, 100, 100, 250, 60, 60), lead = 0))
        assertEquals(listOf((0..7).toList(), listOf(8, 9)), ArticleTranslation.batches(List(10) { 10 }, lead = 0))
        // the title is alone even when short
        assertEquals(listOf(listOf(0), listOf(1, 2)), ArticleTranslation.batches(listOf(10, 10, 10), lead = 1))
        assertEquals(emptyList<List<Int>>(), ArticleTranslation.batches(emptyList(), lead = 1))
        // every request within the limits
        val lengths = List(40) { 50 + (it * 137) % 1400 }
        for (b in ArticleTranslation.batches(lengths, lead = 1)) {
            assertTrue(b.size <= ArticleTranslation.MAX_TEXTS)
            assertTrue(b.size == 1 || b.sumOf { lengths[it] } <= ArticleTranslation.COMBINE_CHARS)
        }
    }

    @Test
    fun `the call timeout follows the length`() = runTest {
        assertEquals(10_000L, ArticleTranslation.timeoutMs(0))
        assertEquals(10_000L + 12 * 1324, ArticleTranslation.timeoutMs(1324))
        assertEquals(60_000L, ArticleTranslation.timeoutMs(5000), "capped")
        val c = FakeClient()
        translation(c).run("Le titre", null, listOf(long(1)))
        assertEquals(listOf(ArticleTranslation.timeoutMs(c.calls[0][0].length), ArticleTranslation.timeoutMs(c.calls[1][0].length)), c.timeouts)
    }

    // M1: a block longer than the server's char limit is kept, not sent forever

    @Test
    fun `a block over the server limit is kept and the rest is translated`() = runTest {
        val c = FakeClient()
        val huge = MdBlock.Paragraph(MdText("word ".repeat(1100), emptyList()))
        val t = translation(c)
        t.run("", null, listOf(para(1), huge, para(2)))
        assertTrue(c.calls.flatten().none { it.length > ArticleTranslation.MAX_PIECE_CHARS }, "never sent")
        assertEquals(listOf("paragraph number 1", "paragraph number 2"), c.calls.flatten())
        assertEquals(huge, t.blocks.value[1])
        assertEquals("DE paragraph number 2", (t.blocks.value[2] as MdBlock.Paragraph).text.text)
        assertTrue(t.partial.value)
        assertFalse(t.stopped.value, "nothing to retry")
    }

    @Test
    fun `a 429 mid-article keeps the blocks before it and leaves the rest original until retry`() = runTest {
        val c = FakeClient()
        c.answer = { n, texts -> if (n == 3) TranslateResult.RateLimited else TranslateResult.Ok(texts.map { "DE $it" }) }
        val t = translation(c)
        val blocks = (1..6).map(::long)
        t.run("Le titre", null, blocks)
        assertEquals(3, c.calls.size, "nothing after the refused request")
        assertEquals("DE Le titre", t.title.value)
        val shown = texts(t.blocks.value)
        assertEquals(listOf("DE " + texts(blocks)[0]), shown.take(1))
        assertEquals(texts(blocks).drop(1), shown.drop(1))
        assertTrue(t.partial.value)
        assertTrue(t.stopped.value)

        c.answer = { _, texts -> TranslateResult.Ok(texts.map { "DE $it" }) }
        t.retry()
        assertEquals(listOf(texts(blocks)[1]), c.calls[3], "resumes at the first untranslated block, alone")
        assertEquals(8, c.calls.size)
        assertEquals(1, c.maxInFlight)
        assertEquals(texts(blocks).map { "DE $it" }, texts(t.blocks.value))
        assertFalse(t.partial.value)
        assertFalse(t.stopped.value)
    }

    @Test
    fun `code and pictures are never sent, quotes lists and tables are`() = runTest {
        val c = FakeClient()
        val blocks = listOf(
            MdBlock.Code("val x = 1"),
            MdBlock.Picture("https://img.example/a.png", "alt"),
            MdBlock.Quote(listOf(MdBlock.Paragraph(MdText("quoted words", emptyList())))),
            MdBlock.ListBlock(false, 1, listOf(listOf(MdBlock.Paragraph(MdText("item words", emptyList()))))),
            MdBlock.Table(listOf(MdText("head", emptyList())), listOf(listOf(MdText("cell", emptyList())))),
            MdBlock.Heading(2, MdText("a heading", emptyList())),
            MdBlock.Rule,
        )
        val t = translation(c)
        t.run("", null, blocks)
        val sent = c.calls.flatten()
        assertEquals(listOf("quoted words", "item words", "head", "cell", "a heading"), sent)
        assertFalse(sent.any { "val x" in it || "img.example" in it })
        val out = t.blocks.value
        assertEquals(blocks[0], out[0])
        assertEquals(blocks[1], out[1])
        assertEquals("DE quoted words", ((out[2] as MdBlock.Quote).blocks[0] as MdBlock.Paragraph).text.text)
        assertEquals("DE item words", ((out[3] as MdBlock.ListBlock).items[0][0] as MdBlock.Paragraph).text.text)
        assertEquals("DE cell", (out[4] as MdBlock.Table).rows[0][0].text)
        assertEquals(MdBlock.Rule, out[6])
    }

    @Test
    fun `a block whose tags come back broken keeps its original and marks partial`() = runTest {
        val c = FakeClient()
        c.answer = { _, texts -> TranslateResult.Ok(texts.map { it.replace("<b>", "").replace("</b>", "") }) }
        val bold = MdBlock.Paragraph(MdText("very bold words", listOf(MdSpan.Bold(5, 9))))
        val t = translation(c)
        t.run("", null, listOf(bold, para(1)))
        assertEquals(bold, t.blocks.value[0])
        assertTrue(t.partial.value)
        assertFalse(t.stopped.value)
    }

    @Test
    fun `a paragraph that is only a nostr reference or a url is not sent`() = runTest {
        val c = FakeClient()
        val t = translation(c)
        t.run("", null, listOf(MdBlock.Paragraph(MdText("https://example.org/only", emptyList())), para(1)))
        assertEquals(listOf(listOf("paragraph number 1")), c.calls)
    }
}
