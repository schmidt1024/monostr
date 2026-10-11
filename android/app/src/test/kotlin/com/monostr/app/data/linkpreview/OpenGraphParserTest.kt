package com.monostr.app.data.linkpreview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Heads modelled on real pages (YouTube, GitHub, a news site), trimmed to the tags that matter. */
class OpenGraphParserTest {
    private val youtube = """
        <html><head><title>Monero explained - YouTube</title>
        <meta name="title" content="Monero explained">
        <meta property="og:title" content="Monero explained">
        <meta property="og:image" content="https://i.ytimg.com/vi/abc/maxresdefault.jpg">
        <meta property="og:image:width" content="1280"><meta property="og:image:height" content="720">
        <meta name="twitter:card" content="player">
        </head><body>…</body></html>
    """.trimIndent()

    private val github = """
        <head><meta charset="utf-8">
        <meta property="og:image" content="https://opengraph.githubassets.com/1/schmidt1024/monostr" />
        <meta name="twitter:card" content="summary_large_image" />
        <meta property="og:title" content="GitHub - schmidt1024/monostr: Nostr client with Monero tips" />
        </head>
    """.trimIndent()

    private val news = """
        <head><title>ignored</title>
        <meta content='Tom &amp; Jerry
           &#8211; Cast &#x26; Crew' property='og:title'>
        <meta name="twitter:image:src" content="/img/lead.png">
        </head>
    """.trimIndent()

    @Test
    fun `og title and image, twitter card decides the shape`() {
        val p = OpenGraphParser.parse(github, "https://github.com/schmidt1024/monostr")!!
        assertEquals("GitHub - schmidt1024/monostr: Nostr client with Monero tips", p.title)
        assertEquals("https://opengraph.githubassets.com/1/schmidt1024/monostr", p.imageUrl)
        assertTrue(p.large)
        assertEquals("https://github.com/schmidt1024/monostr", p.url)
    }

    @Test
    fun `a twitter card other than summary_large_image is small even with a wide picture`() {
        val p = OpenGraphParser.parse(youtube, "https://www.youtube.com/watch?v=abc")!!
        assertEquals("Monero explained", p.title)
        assertFalse(p.large)
    }

    @Test
    fun `without a twitter card a wide picture of at least 400 px is large`() {
        val html = """<meta property="og:title" content="T"><meta property="og:image" content="https://x.org/a.jpg"><meta property="og:image:width" content="800"><meta property="og:image:height" content="400">"""
        assertTrue(OpenGraphParser.parse(html, "https://x.org/")!!.large)
        val narrow = html.replace("800", "390").replace("\"400\"", "\"200\"")
        assertFalse(OpenGraphParser.parse(narrow, "https://x.org/")!!.large)
        val square = html.replace("\"400\"", "\"800\"")
        assertFalse(OpenGraphParser.parse(square, "https://x.org/")!!.large)
        val noSize = """<meta property="og:title" content="T"><meta property="og:image" content="https://x.org/a.jpg">"""
        assertFalse(OpenGraphParser.parse(noSize, "https://x.org/")!!.large)
    }

    @Test
    fun `reversed attributes, single quotes, entities, whitespace and a relative image`() {
        val p = OpenGraphParser.parse(news, "https://news.example/2026/story.html")!!
        assertEquals("Tom & Jerry – Cast & Crew", p.title)
        assertEquals("https://news.example/img/lead.png", p.imageUrl)
        assertFalse(p.large)
    }

    @Test
    fun `protocol-relative image keeps the page scheme, data and javascript images are dropped`() {
        val rel = """<meta property="og:title" content="T"><meta property="og:image" content="//cdn.example/p.webp">"""
        assertEquals("https://cdn.example/p.webp", OpenGraphParser.parse(rel, "https://site.example/a")!!.imageUrl)
        val data = """<meta property="og:title" content="T"><meta property="og:image" content="data:image/png;base64,AAAA">"""
        assertNull(OpenGraphParser.parse(data, "https://site.example/a")!!.imageUrl)
        val js = """<meta property="og:title" content="T"><meta property="og:image" content="javascript:alert(1)"><meta name="twitter:image" content="https://site.example/t.png">"""
        assertEquals("https://site.example/t.png", OpenGraphParser.parse(js, "https://site.example/a")!!.imageUrl)
    }

    @Test
    fun `title falls back to twitter title, then to the title element`() {
        assertEquals("TW", OpenGraphParser.parse("""<meta name="twitter:title" content="TW"><title>Doc</title>""", "https://a.example/")!!.title)
        assertEquals("Only the document title", OpenGraphParser.parse("<head><title>\n  Only the document title\n</title></head>", "https://a.example/")!!.title)
    }

    @Test
    fun `no title or a blank one means no preview`() {
        assertNull(OpenGraphParser.parse("""<head><meta property="og:image" content="https://a.example/i.png"></head>""", "https://a.example/"))
        assertNull(OpenGraphParser.parse("""<meta property="og:title" content="   "><title> </title>""", "https://a.example/"))
    }

    @Test
    fun `the first tag of a key wins and keys are case-insensitive`() {
        val html = """<META PROPERTY="OG:TITLE" CONTENT="First"><meta property="og:title" content="Second">"""
        assertEquals("First", OpenGraphParser.parse(html, "https://a.example/")!!.title)
    }

    @Test
    fun `a very long title is cut to the maximum`() {
        val long = "x".repeat(1000)
        assertEquals(OpenGraphParser.MAX_TITLE, OpenGraphParser.parse("""<meta property="og:title" content="$long">""", "https://a.example/")!!.title.length)
    }

    @Test
    fun `numeric entities outside the valid range become the replacement character`() {
        assertEquals("a\uFFFDb", OpenGraphParser.decodeEntities("a&#0;b"))
        assertEquals("a\uFFFDb", OpenGraphParser.decodeEntities("a&#x110000;b"))
        assertEquals("&unknown;", OpenGraphParser.decodeEntities("&unknown;"))
    }

    @Test
    fun `adversarial heads parse in linear time`() {
        // review I3: unclosed <meta and repeated <title made the regexes quadratic (22 s for 256 KB on a desktop)
        for (html in listOf("<meta ".repeat(256 * 1024 / 6), "<title>".repeat(256 * 1024 / 7), "<meta property=" + "a".repeat(256 * 1024))) {
            val t0 = System.nanoTime()
            OpenGraphParser.parse(html, "https://a.example/")
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms < 500, "took $ms ms")
        }
    }

    @Test
    fun `common named entities and control characters`() {
        val p = OpenGraphParser.parse("""<meta property="og:title" content="Don&rsquo;t &mdash; caf&eacute;&hellip; &laquo;x&raquo;${'\u0007'}">""", "https://a.example/")!!
        assertEquals("Don’t — café… «x»", p.title)
    }

    @Test
    fun `only the head counts, a title in the body is not a page title`() {
        val html = """<head><meta property="og:image" content="https://a.example/i.png"></head><body><svg><title>Icon</title></svg></body>"""
        assertNull(OpenGraphParser.parse(html, "https://a.example/"))
        // tags of a mixed-case head end count up to it
        assertEquals("T", OpenGraphParser.parse("""<head><title>T</title></Head><body><meta property="og:title" content="Body">""", "https://a.example/")!!.title)
    }
}
