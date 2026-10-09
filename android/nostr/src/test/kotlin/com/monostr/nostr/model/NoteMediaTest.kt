package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoteMediaTest {
    private fun parse(content: String, tags: List<List<String>> = emptyList()) = NoteMedia.parse(content, tags)

    @Test
    fun `image urls by extension become media and leave the text`() {
        val p = parse("look https://x.example/a.jpg and https://x.example/b.webp\nmore")
        assertEquals(listOf("https://x.example/a.jpg", "https://x.example/b.webp"), p.media.map { it.url })
        assertTrue(p.media.all { it.kind == NoteMedia.Kind.IMAGE })
        assertEquals("look  and\nmore", p.displayContent)
    }

    @Test
    fun `extension before query and fragment, case-insensitive`() {
        val p = parse("https://x.example/a.PNG?w=1 https://x.example/b.jpg#f https://x.example/c.txt")
        assertEquals(listOf("https://x.example/a.PNG?w=1", "https://x.example/b.jpg#f"), p.media.map { it.url })
        assertEquals("https://x.example/c.txt", p.displayContent)
    }

    @Test
    fun `videos by extension and by imeta mime`() {
        val tags = listOf(listOf("imeta", "url https://v.example/clip", "m video/mp4", "image https://v.example/poster.jpg"))
        val p = parse("https://v.example/clip and https://v.example/x.webm", tags)
        assertEquals(listOf(NoteMedia.Kind.VIDEO, NoteMedia.Kind.VIDEO), p.media.map { it.kind })
        assertEquals("https://v.example/poster.jpg", p.media[0].poster)
        assertNull(p.media[1].poster)
    }

    @Test
    fun `imeta adds blurhash alt and dim to a text url and imeta-only urls come last`() {
        val tags = listOf(
            listOf("imeta", "url https://x.example/a.jpg", "blurhash LEHV6nWB2yk8pyo0adR*.7kCMdnj", "alt a cat", "dim 800x600"),
            listOf("imeta", "url https://x.example/only.png", "m image/png"),
        )
        val p = parse("https://x.example/a.jpg", tags)
        assertEquals(listOf("https://x.example/a.jpg", "https://x.example/only.png"), p.media.map { it.url })
        assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", p.media[0].blurhash)
        assertEquals("a cat", p.media[0].alt)
        assertEquals(800 to 600, p.media[0].dim)
    }

    @Test
    fun `more than four stay links in the text`() {
        val urls = (1..6).map { "https://x.example/$it.jpg" }
        val p = parse(urls.joinToString(" "))
        assertEquals(4, p.media.size)
        assertEquals(urls.drop(4).joinToString(" "), p.displayContent)
    }

    @Test
    fun `displayContent empty when the note is only media`() {
        val p = parse("https://x.example/a.jpg\n")
        assertEquals("", p.displayContent)
        assertEquals(1, p.media.size)
    }

    @Test
    fun `a line that becomes blank is dropped, other blank lines stay`() {
        val p = parse("first\n\nhttps://x.example/a.jpg\nlast")
        assertEquals("first\n\nlast", p.displayContent)
    }

    @Test
    fun `trailing punctuation is not part of the url`() {
        val p = parse("see https://x.example/a.jpg.")
        assertEquals("https://x.example/a.jpg", p.media[0].url)
        assertEquals("see .", p.displayContent)
    }

    @Test
    fun `duplicates count once and non-media urls are ignored`() {
        val p = parse("https://x.example/a.jpg https://x.example/a.jpg https://x.example/page")
        assertEquals(1, p.media.size)
        assertEquals("https://x.example/page", p.displayContent)
    }

    @Test
    fun `imeta with a non-http url or poster is ignored`() {
        val tags = listOf(
            listOf("imeta", "url monero:4AdUndXHHZ6cfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2684Rge", "m image/png"),
            listOf("imeta", "url file:///data/x.png", "m image/png"),
            listOf("imeta", "url HTTPS://x.example/ok.jpg", "image content://media/poster.jpg"),
        )
        val p = parse("hello", tags)
        assertEquals(listOf("HTTPS://x.example/ok.jpg"), p.media.map { it.url })
        assertNull(p.media[0].poster)
    }

    @Test
    fun `a media url is not cut out of a longer url`() {
        val p = parse("https://x.example/a.png and https://x.example/a.png.html")
        assertEquals(listOf("https://x.example/a.png"), p.media.map { it.url })
        assertEquals("and https://x.example/a.png.html", p.displayContent)
    }

    @Test
    fun `nsfw regex matches the hashtag only as a word`() {
        assertTrue(NoteMedia.NSFW.containsMatchIn("late #NSFW post"))
        assertTrue(!NoteMedia.NSFW.containsMatchIn("#nsfwart"))
        assertTrue(!NoteMedia.NSFW.containsMatchIn("nsfw"))
    }
}
