package com.monostr.nostr.repo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FollowListsRepositoryTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    @Test
    fun `following lists the newest follow first, each account once, only well-formed p tags`() {
        val tags = listOf(listOf("p", a), listOf("t", "x"), listOf("p", b), listOf("p", "short"), listOf("p", a), listOf("p", c, "wss://r"))
        assertEquals(listOf(c, a, b), NostrFollowListsRepository.newestFirst(tags))
    }

    @Test
    fun `a followers page is newest first without seen accounts and goes on from its oldest time`() {
        val page = NostrFollowListsRepository.page(listOf(a to 100L, b to 300L, a to 50L, c to 200L), seen = setOf(c))
        assertEquals(listOf(b, a), page.pubkeys)
        assertEquals(50L, page.nextUntil)
    }

    @Test
    fun `a page with nothing new ends the list`() {
        val page = NostrFollowListsRepository.page(listOf(a to 100L), seen = setOf(a))
        assertEquals(emptyList<String>(), page.pubkeys)
        assertEquals(null, page.nextUntil)
        assertEquals(null, NostrFollowListsRepository.page(emptyList(), emptySet()).nextUntil)
    }
}
