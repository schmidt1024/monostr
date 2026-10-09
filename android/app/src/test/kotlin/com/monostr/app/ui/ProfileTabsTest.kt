package com.monostr.app.ui

import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.profile.ProfileController
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.repo.ProfileSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Profile tabs "Posts" and "Replies" (2026-10-09). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileTabsTest {
    private val bob = "b".repeat(64)
    private val root = note('r', author = "c".repeat(64))
    private val post = note('p', author = bob, createdAt = 1000)
    private val answer = note('q', author = bob, createdAt = 990, replyTo = root.id)

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.decorations() = TipDecorations(FakeTips(), FakePendingTips(), FakeProfiles(), FakePublish(), eager()) { 5_000 }

    private fun TestScope.controller(feed: FakeFeed, relays: FakeAuthorRelays = FakeAuthorRelays()): ProfileController {
        val scope = eager()
        return ProfileController(bob, "a".repeat(64), feed, FakeProfiles(), FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), scope), FakeCounts(), relays, { relays.writeRelays(bob).isNotEmpty() }, scope, refreshMs = 1_000)
    }

    @Test
    fun `posts show first, replies load when their tab opens`() = runTest {
        val feed = FakeFeed().apply { byAuthor = mapOf(bob to listOf(post, answer)) }
        val c = controller(feed)
        c.start(); runCurrent()
        assertEquals(ProfileSection.POSTS, c.state.value.tab)
        assertEquals(listOf(post.id), c.state.value.shown.map { it.note.id })
        assertNull(c.state.value.replies)
        assertEquals(listOf(ProfileSection.POSTS to true), feed.profileCalls)
        c.selectTab(ProfileSection.REPLIES); runCurrent()
        assertEquals(listOf(answer.id), c.state.value.shown.map { it.note.id })
        assertEquals(ProfileSection.REPLIES to true, feed.profileCalls.last())
        // back and forth again: nothing is loaded twice
        c.selectTab(ProfileSection.POSTS); c.selectTab(ProfileSection.REPLIES); runCurrent()
        assertEquals(2, feed.profileCalls.size)
        c.close()
    }

    @Test
    fun `more appends older notes of the open tab and stops when nothing older comes`() = runTest {
        val older = note('o', author = bob, createdAt = 500)
        val feed = FakeFeed().apply {
            byAuthor = mapOf(bob to listOf(post, answer))
            olderByAuthor = mapOf(bob to listOf(older))
        }
        val c = controller(feed)
        c.start(); runCurrent()
        c.loadMore(); runCurrent()
        assertEquals(listOf(post.id, older.id), c.state.value.shown.map { it.note.id })
        assertEquals(ProfileSection.POSTS to 1000L, feed.moreCalls.single())
        feed.olderByAuthor = emptyMap()
        c.loadMore(); runCurrent()
        assertTrue(ProfileSection.POSTS in c.state.value.ended)
        c.loadMore(); runCurrent()
        assertEquals(2, feed.moreCalls.size) // the end is not asked again
        // the replies tab pages on its own
        c.selectTab(ProfileSection.REPLIES); runCurrent()
        c.loadMore(); runCurrent()
        assertEquals(ProfileSection.REPLIES to 990L, feed.moreCalls.last())
        c.close()
    }

    @Test
    fun `the outbox refresh keeps the older pages and refreshes an opened replies tab`() = runTest {
        val older = note('o', author = bob, createdAt = 500)
        val feed = FakeFeed().apply {
            byAuthor = mapOf(bob to listOf(post, answer))
            olderByAuthor = mapOf(bob to listOf(older))
        }
        val c = controller(feed, FakeAuthorRelays(mapOf(bob to listOf("wss://w1.example"))))
        c.start(); runCurrent()
        c.loadMore(); runCurrent()
        c.selectTab(ProfileSection.REPLIES); runCurrent()
        val newer = note('n', author = bob, createdAt = 2000)
        val newAnswer = note('m', author = bob, createdAt = 1990, replyTo = root.id)
        feed.byAuthor = mapOf(bob to listOf(newer, post, newAnswer, answer))
        advanceTimeBy(1_100)
        assertEquals(listOf(newer.id, post.id, older.id), c.state.value.notes.map { it.note.id })
        assertEquals(listOf(newAnswer.id, answer.id), c.state.value.replies?.map { it.note.id })
        assertEquals(listOf(ProfileSection.POSTS to false, ProfileSection.REPLIES to false), feed.profileCalls.takeLast(2).sortedBy { it.first })
        c.close()
    }
}
