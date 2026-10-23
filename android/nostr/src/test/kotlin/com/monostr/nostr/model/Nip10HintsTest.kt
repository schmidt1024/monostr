package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class Nip10HintsTest {
    private val r = "1".repeat(64); private val p = "2".repeat(64)
    private val authorA = "a".repeat(64); private val authorB = "b".repeat(64)

    private fun note(tags: List<List<String>>, mentioned: List<String> = emptyList(), replyHint: RefHint? = null) =
        Note(id = "i", author = "x", content = "", createdAt = 0, kind = 1, rootId = null, replyToId = null,
            repostOf = null, mentionedPubkeys = mentioned, replyHint = replyHint)

    @Test
    fun `marked tags carry relay and author`() {
        val refs = Nip10.parse(listOf(
            listOf("e", r, "wss://a.example", "root", authorA),
            listOf("e", p, "wss://b.example/", "reply", authorB),
        ))
        assertEquals(RefHint("wss://a.example", authorA), refs.rootHint)
        assertEquals(RefHint("wss://b.example", authorB), refs.replyHint)
    }

    @Test
    fun `positional tags take first and last`() {
        val refs = Nip10.parse(listOf(
            listOf("e", r, "wss://first.example"),
            listOf("e", "3".repeat(64), "wss://mid.example"),
            listOf("e", p, "wss://last.example"),
        ))
        assertEquals("wss://first.example", refs.rootHint?.relay)
        assertEquals("wss://last.example", refs.replyHint?.relay)
    }

    @Test
    fun `junk is dropped`() {
        val refs = Nip10.parse(listOf(listOf("e", r, "http://x", "reply", "abc")))
        assertNull(refs.replyHint)
        assertNull(Nip10.parse(listOf(listOf("e", r, "", "reply"))).replyHint)
        val upper = Nip10.parse(listOf(listOf("e", r, "", "reply", authorA.uppercase()))).replyHint
        assertEquals(RefHint(null, authorA), upper)
        assertNull(Nip10.parse(listOf(listOf("e", r, "ws://ok.example", "reply", "z".repeat(64)))).replyHint?.author)
    }

    @Test
    fun `root only reply`() {
        val refs = Nip10.parse(listOf(listOf("e", r, "wss://a.example", "root", authorA)))
        assertEquals(refs.rootHint, refs.replyHint)
    }

    @Test
    fun `parentCandidates falls back to first three p tags`() {
        val ps = (1..5).map { it.toString().repeat(64).take(64) }
        assertEquals(ps.take(3), note(emptyList(), mentioned = ps).parentCandidates())
        assertEquals(listOf(authorA), note(emptyList(), mentioned = ps, replyHint = RefHint(null, authorA)).parentCandidates())
        assertEquals(listOf(authorA), note(emptyList(), mentioned = listOf("zz", authorA.uppercase())).parentCandidates())
    }
}
