package com.monostr.app.ui

import com.monostr.app.ui.common.ReplyContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** "Replying to @name" on reply rows (v0.13.1). */
class ReplyContextTest {
    private val author = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)

    @Test
    fun `the p tags of a reply without the author and without repeats, in tag order`() {
        val reply = note('x', author = author, replyTo = "r".repeat(64)).copy(mentionedPubkeys = listOf(carol, author, bob, carol))
        assertEquals(listOf(carol, bob), ReplyContext.targets(reply))
    }

    @Test
    fun `a root note, and a reply that tags only its author, have no targets`() {
        assertTrue(ReplyContext.targets(note('x', author = author).copy(mentionedPubkeys = listOf(bob))).isEmpty())
        assertTrue(ReplyContext.targets(note('x', author = author, replyTo = "r".repeat(64)).copy(mentionedPubkeys = listOf(author))).isEmpty())
    }

    @Test
    fun `two names show, the rest is counted`() {
        val many = List(5) { i -> ('d' + i).toString().repeat(64) }
        assertEquals(ReplyContext.Shown(many.take(2), 3), ReplyContext.shown(many))
        assertEquals(ReplyContext.Shown(listOf(bob), 0), ReplyContext.shown(listOf(bob)))
    }
}
