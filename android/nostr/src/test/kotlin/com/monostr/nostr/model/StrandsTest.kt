package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StrandsTest {
    private fun n(id: Char, author: Char, at: Long, replyTo: String? = null, root: String? = null) =
        Note(id.toString().repeat(64), author.toString().repeat(64), "note $id", at, 1, root ?: replyTo, replyTo, null, emptyList())

    private val focused = n('f', 'A', 100)
    private val F = focused.id

    @Test
    fun `each direct reply starts a strand, oldest first, focused author's own first`() {
        val r1 = n('1', 'B', 101, F)
        val r2 = n('2', 'A', 102, F) // by the focused note's author
        val r3 = n('3', 'C', 103, F)
        assertEquals(listOf(r2, r1, r3), Strands.build(focused, listOf(r1, r2, r3)).map { it.reply })
    }

    @Test
    fun `child prefers the reply author's follow-up, then the focused author, then the oldest`() {
        val r = n('1', 'B', 101, F, F)
        val byC = n('2', 'C', 102, r.id, F)
        val byA = n('3', 'A', 103, r.id, F)
        val byB = n('4', 'B', 104, r.id, F)
        assertEquals(byB, Strands.build(focused, listOf(r, byC, byA, byB)).single().child)
        assertEquals(byA, Strands.build(focused, listOf(r, byC, byA)).single().child)
        assertEquals(byC, Strands.build(focused, listOf(r, byC)).single().child)
    }

    @Test
    fun `hidden counts everything below the direct reply except the child`() {
        val r = n('1', 'B', 101, F, F)
        val c1 = n('2', 'C', 102, r.id, F)
        val c2 = n('3', 'D', 103, r.id, F)
        val deep = n('4', 'E', 104, c1.id, F)
        val s = Strands.build(focused, listOf(r, c1, c2, deep)).single()
        assertEquals(c1, s.child)
        assertEquals(2, s.hidden)
    }

    @Test
    fun `strands only below the focused note`() {
        val root = n('r', 'Z', 90)
        val f = n('f', 'A', 100, root.id, root.id)
        val sibling = n('s', 'B', 101, root.id, root.id) // another branch
        val mine = n('m', 'C', 102, f.id, root.id)
        assertEquals(listOf(mine), Strands.build(f, listOf(f, sibling, mine)).map { it.reply })
    }

    @Test
    fun `orphans become strands when the focused note is the root`() {
        val orphan = n('o', 'B', 101, "9".repeat(64), F) // parent unknown
        assertEquals(listOf(orphan), Strands.build(focused, listOf(orphan)).map { it.reply })
    }
}
