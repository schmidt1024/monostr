package com.monostr.app.ui

import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.feed.FeedController
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Like X's "new posts": live notes above the newest shown wait behind a pill; a refresh or a tap on it shows them. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedNewNotesTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val profiles = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", "https://x/a.png", null, null)))

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.controller(feed: FakeFeed) = FeedController(
        feed, profiles, FakePublish(), TipDecorations(FakeTips(), FakePendingTips(), profiles, FakePublish(), eager()) { 5_000 },
        BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(),
    )

    private val old = note('1', alice, 1000)
    private val new = note('2', bob, 2000)

    @Test
    fun `a live note above the newest shown waits behind the pill`() = runTest {
        val feed = FakeFeed().apply { current = listOf(old) }
        val c = controller(feed)
        c.start(); advanceUntilIdle()
        feed.liveUpdates.emit(listOf(new, old)); advanceUntilIdle()
        assertEquals(listOf(old.id), c.state.value.notes.map { it.note.id })
        assertEquals(listOf(new.id), c.state.value.newAbove.map { it.note.id })
    }

    @Test
    fun `the pill shows the waiting notes on top`() = runTest {
        val feed = FakeFeed().apply { current = listOf(old) }
        val c = controller(feed)
        c.start(); advanceUntilIdle()
        feed.liveUpdates.emit(listOf(new, old)); advanceUntilIdle()
        c.showNew(); advanceUntilIdle()
        assertEquals(listOf(new.id, old.id), c.state.value.notes.map { it.note.id })
        assertTrue(c.state.value.newAbove.isEmpty())
    }

    @Test
    fun `a refresh shows the waiting notes at once`() = runTest {
        val feed = FakeFeed().apply { current = listOf(old) }
        val c = controller(feed)
        c.start(); advanceUntilIdle()
        feed.liveUpdates.emit(listOf(new, old)); advanceUntilIdle()
        feed.current = listOf(new, old)
        c.refresh(); advanceUntilIdle()
        assertEquals(listOf(new.id, old.id), c.state.value.notes.map { it.note.id })
        assertTrue(c.state.value.newAbove.isEmpty())
    }

    @Test
    fun `a live update of what is shown waits for nothing`() = runTest {
        val feed = FakeFeed().apply { current = listOf(new, old) }
        val c = controller(feed)
        c.start(); advanceUntilIdle()
        feed.liveUpdates.emit(listOf(new, old)); advanceUntilIdle()
        assertEquals(listOf(new.id, old.id), c.state.value.notes.map { it.note.id })
        assertTrue(c.state.value.newAbove.isEmpty())
    }
}
