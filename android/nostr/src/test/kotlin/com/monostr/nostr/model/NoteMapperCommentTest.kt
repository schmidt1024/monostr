package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag

/** Backlog 26: a NIP-22 comment (kind 1111) maps like a note, its lowercase `e` is the parent. */
class NoteMapperCommentTest {
    private val keys = Keys.generate()
    private val parent = "a".repeat(64)
    private val scope = "b".repeat(64)

    private fun comment(tags: List<List<String>>) =
        EventBuilder(Kind(1111u), "a comment").tags(tags.map { Tag.parse(it) }).signWithKeys(keys)

    @Test
    fun `a comment on a note is a note replying to its lowercase e`() {
        val n = NoteMapper.note(comment(listOf(listOf("E", scope), listOf("K", "1"), listOf("e", parent), listOf("k", "1111"))))!!
        assertEquals(1111, n.kind)
        assertEquals("a comment", n.content)
        assertEquals(parent, n.replyToId)
    }

    @Test
    fun `a comment without a lowercase e (on an article) is no reply`() {
        val n = NoteMapper.note(comment(listOf(listOf("A", "30023:${"c".repeat(64)}:x"), listOf("K", "30023"), listOf("a", "30023:${"c".repeat(64)}:x"))))!!
        assertNull(n.replyToId)
    }

    private val coordinate = "30023:${"c".repeat(64)}:my post"
    private val articleId = "d".repeat(64)

    @Test
    fun `a top level article comment has the article as its parent`() {
        val n = NoteMapper.note(comment(listOf(
            listOf("A", coordinate), listOf("E", articleId), listOf("K", "30023"), listOf("P", "c".repeat(64)),
            listOf("a", coordinate), listOf("e", articleId), listOf("k", "30023"), listOf("p", "c".repeat(64)),
        )))!!
        assertEquals(AddressRef(30023, "c".repeat(64), "my post"), n.articleParent)
        assertEquals(articleId, n.replyToId)
    }

    @Test
    fun `a comment below a comment has no article parent`() {
        val n = NoteMapper.note(comment(listOf(
            listOf("A", coordinate), listOf("E", articleId), listOf("K", "30023"), listOf("P", "c".repeat(64)),
            listOf("e", parent), listOf("k", "1111"), listOf("p", "e".repeat(64)),
        )))!!
        assertNull(n.articleParent)
        assertEquals(parent, n.replyToId)
    }

    @Test
    fun `a comment under another kind of address has no article parent`() {
        val live = "30311:${"c".repeat(64)}:x"
        val n = NoteMapper.note(comment(listOf(listOf("A", live), listOf("K", "30311"), listOf("a", live), listOf("k", "30311"))))!!
        assertNull(n.articleParent)
    }
}
