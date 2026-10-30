package com.monostr.app.ui.follows

import com.monostr.app.ui.FakeProfiles
import com.monostr.nostr.repo.FollowListsRepository
import com.monostr.nostr.repo.FollowersPage
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FollowsControllerTest {
    private fun key(i: Int) = i.toString().padStart(64, '0')

    private class FakeLists(val follows: List<String>, val pages: MutableList<FollowersPage>) : FollowListsRepository {
        val untils = ArrayList<Long?>()
        override suspend fun following(pubkey: String) = follows
        override suspend fun followers(pubkey: String, until: Long?, seen: Set<String>): FollowersPage {
            untils += until
            return if (pages.isEmpty()) FollowersPage(emptyList(), null) else pages.removeAt(0)
        }
    }

    @Test
    fun `following shows its profiles a page at a time and ends with the list`() = runTest {
        val lists = FakeLists((1..45).map(::key), mutableListOf())
        val c = FollowsController("a".repeat(64), lists, FakeProfiles(), this, pageSize = 30)
        c.start(); advanceUntilIdle()
        assertEquals(30, c.state.value.following.people.size)
        assertFalse(c.state.value.following.ended)
        c.moreFollowing(); advanceUntilIdle()
        assertEquals(45, c.state.value.following.people.size)
        assertTrue(c.state.value.following.ended)
        assertEquals(key(1), c.state.value.following.people.first().pubkey)
    }

    @Test
    fun `followers go on from the page's oldest time and end when the relays have no more`() = runTest {
        val lists = FakeLists(emptyList(), mutableListOf(FollowersPage(listOf(key(1), key(2)), 500), FollowersPage(listOf(key(3)), 400)))
        val c = FollowsController("a".repeat(64), lists, FakeProfiles(), this)
        c.start(); advanceUntilIdle()
        c.moreFollowers(); advanceUntilIdle()
        c.moreFollowers(); advanceUntilIdle()
        assertEquals(listOf(key(1), key(2), key(3)), c.state.value.followers.people.map { it.pubkey })
        assertEquals(listOf<Long?>(null, 500, 400), lists.untils)
        assertTrue(c.state.value.followers.ended)
    }

    @Test
    fun `followers stop at the cap`() = runTest {
        val big = (1..FollowsController.MAX_FOLLOWERS + 20).map(::key)
        val lists = FakeLists(emptyList(), mutableListOf(FollowersPage(big, 100)))
        val c = FollowsController("a".repeat(64), lists, FakeProfiles(), this)
        c.start(); advanceUntilIdle()
        assertEquals(FollowsController.MAX_FOLLOWERS, c.state.value.followers.people.size)
        assertTrue(c.state.value.followers.ended)
    }
}
