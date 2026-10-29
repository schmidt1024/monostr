package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArticleTest {
    private val author = "a".repeat(64)
    private fun event(kind: Int = 30023, createdAt: Long = 1000, vararg extra: List<String>) =
        AddressableEvent("e".repeat(64), kind, author, "my-post", createdAt, listOf(listOf("d", "my-post")) + extra, "# Hi")

    @Test
    fun `title summary image published_at and content warning are read`() {
        val a = Article.parse(event(extra = arrayOf(listOf("title", "T"), listOf("summary", "S"), listOf("image", "https://example.com/a.png"), listOf("published_at", "500"), listOf("content-warning", "nsfw"))))!!
        assertEquals("T", a.title); assertEquals("S", a.summary); assertEquals("https://example.com/a.png", a.image)
        assertEquals(500L, a.publishedAt); assertEquals("nsfw", a.contentWarning)
        assertEquals("30023:$author:my-post", a.coordinate)
    }

    @Test
    fun `fallbacks and picture filter`() {
        val a = Article.parse(event(extra = arrayOf(listOf("image", "http://example.com/a.png"), listOf("published_at", "x"))))!!
        assertEquals("", a.title); assertNull(a.summary); assertNull(a.image); assertNull(a.contentWarning)
        assertEquals(1000L, a.publishedAt)
        assertNull(Article.parse(event(extra = arrayOf(listOf("image", "https://127.0.0.1/a.png"))))!!.image)
    }

    @Test
    fun `a draft is not an article`() = assertNull(Article.parse(event(kind = 30024)))

    @Test
    fun `asNote carries the ref and no display text`() {
        val n = Article.parse(event(extra = arrayOf(listOf("content-warning", ""))))!!.asNote()
        assertEquals(30023, n.kind); assertEquals(author, n.author); assertEquals("", n.displayContent)
        assertEquals("", n.contentWarning); assertEquals("30023:$author:my-post", n.addressRef!!.coordinate)
    }
}
