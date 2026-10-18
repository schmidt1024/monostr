package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.feed.FeedController
import com.monostr.app.ui.feed.HomeTab
import com.monostr.app.ui.feed.HomeTabsController
import com.monostr.app.ui.feed.ListStatus
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.SaveResult
import com.monostr.nostr.repo.FeedSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-18 §2.1, §4.3: the home feed's tabs. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeTabsControllerTest {
    private val self = "e".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.decorations() = TipDecorations(FakeTips(), FakePendingTips(), FakeProfiles(), FakePublish(), eager()) { 5_000 }

    /** The source of every list feed the controller built, in order. */
    private val built = ArrayList<FeedSource.Authors>()

    private fun TestScope.feed(repo: FakeFeed, scope: CoroutineScope, source: FeedSource = FeedSource.Following) =
        FeedController(repo, FakeProfiles(), FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), scope), FakeCounts(), scope, source = source)

    private fun TestScope.controller(pins: FakeFeedTabs, lists: FakeLists, home: FakeFeed = FakeFeed(), listRepo: FakeFeed = FakeFeed()): HomeTabsController {
        val scope = eager()
        val following = feed(home, scope)
        following.start()
        return HomeTabsController(pins, lists, scope, self, following) { source, child -> built += source; feed(listRepo, child, source) }
    }

    @Test
    fun `without pins there is only Following and no row`() = runTest {
        val c = controller(FakeFeedTabs(), FakeLists())
        c.start(); runCurrent()
        assertEquals(listOf<HomeTab>(HomeTab.Following), c.state.value.tabs)
        assertFalse(c.state.value.showRow)
    }

    @Test
    fun `a pinned list is a tab, opening it builds a members-only feed and Following pauses and refreshes on return`() = runTest {
        val friends = followSet("friends", listOf(bob), author = self, title = "Friends")
        val home = FakeFeed()
        val listRepo = FakeFeed().apply { current = listOf(note('1', bob, 1000), note('2', carol, 1001)) }
        val c = controller(FakeFeedTabs(listOf(friends.address)), FakeLists(listOf(friends)), home, listRepo)
        c.start(); runCurrent()
        val tab = c.state.value.tabs[1] as HomeTab.Pinned
        assertEquals("Friends", tab.title)
        assertEquals(ListStatus.READY, tab.status)
        assertTrue(c.state.value.showRow)
        assertTrue(built.isEmpty()) // created when first shown
        c.select(1); runCurrent()
        assertEquals(listOf(FeedSource.Authors(listOf(bob))), built)
        val listFeed = c.feedOf(c.state.value.current)!!
        assertEquals(listOf(note('1', bob, 1000).id), listFeed.state.value.notes.map { it.note.id })
        assertEquals(listFeed, c.state.value.feeds[friends.address.toString()])
        val lives = home.liveCalls
        val refreshes = home.refreshCalls
        c.select(0); runCurrent()
        assertEquals(lives + 1, home.liveCalls)
        assertEquals(refreshes + 1, home.refreshCalls)
    }

    @Test
    fun `a member change rebuilds the list feed, an unpin closes it and shows Following`() = runTest {
        val friends = followSet("friends", listOf(bob), author = self)
        val lists = FakeLists(listOf(friends))
        val pins = FakeFeedTabs(listOf(friends.address))
        val c = controller(pins, lists)
        c.start(); runCurrent()
        c.select(1); runCurrent()
        val first = c.feedOf(c.state.value.current)
        lists.ownState.value = listOf(friends.copy(members = listOf(bob, carol))); runCurrent()
        assertEquals(FeedSource.Authors(listOf(bob, carol)), built.last())
        assertNotSame(first, c.feedOf(c.state.value.current))
        c.unpin(friends.address); runCurrent()
        assertEquals(listOf<HomeTab>(HomeTab.Following), c.state.value.tabs)
        assertEquals(0, c.state.value.selected)
        assertTrue(c.state.value.feeds.isEmpty())
    }

    @Test
    fun `a list found nowhere is unavailable, one without members is empty, an unreadable own one is locked until unlocked`() = runTest {
        val gone = ListAddress(30000, bob, "gone")
        val empty = followSet("empty", emptyList(), author = carol)
        val locked = followSet("locked", emptyList(), author = self, private = true, locked = true)
        val lists = FakeLists(listOf(locked)).apply {
            foreign = mapOf(empty.address to empty)
            unlockTo = locked.copy(locked = false, members = listOf(bob))
        }
        val c = controller(FakeFeedTabs(listOf(gone, empty.address, locked.address)), lists)
        c.start(); runCurrent()
        assertEquals(
            listOf(ListStatus.UNAVAILABLE, ListStatus.EMPTY, ListStatus.LOCKED),
            c.state.value.tabs.drop(1).map { (it as HomeTab.Pinned).status },
        )
        c.select(2); runCurrent()
        assertNull(c.feedOf(c.state.value.current)) // 0 members: no feed, no filter for everyone
        c.select(3); runCurrent()
        assertNull(c.feedOf(c.state.value.current))
        c.unlock(locked.address); runCurrent()
        assertEquals(ListStatus.READY, (c.state.value.current as HomeTab.Pinned).status)
        assertEquals(listOf(FeedSource.Authors(listOf(bob))), built)
    }

    @Test
    fun `a failed fetch keeps a foreign list found in the database`() = runTest {
        val picks = followSet("picks", listOf(bob), author = carol, title = "Picks")
        val lists = FakeLists().apply { foreign = mapOf(picks.address to picks); fetchFails = true }
        val c = controller(FakeFeedTabs(listOf(picks.address)), lists)
        c.start(); runCurrent()
        val tab = c.state.value.tabs[1] as HomeTab.Pinned
        assertEquals(ListStatus.READY, tab.status)
        assertEquals("Picks", tab.title)
    }

    @Test
    fun `a failed unpin offers Retry, which repeats it`() = runTest {
        val friends = followSet("friends", listOf(bob), author = self)
        val pins = FakeFeedTabs(listOf(friends.address)).apply { result = SaveResult.Failed }
        val c = controller(pins, FakeLists(listOf(friends)))
        c.start(); runCurrent()
        c.unpin(friends.address); runCurrent()
        val m = c.state.value.message!!
        assertEquals(uiText(R.string.list_save_failed), m.text)
        assertEquals(2, c.state.value.tabs.size) // rolled back: still pinned
        pins.result = SaveResult.Ok
        m.retry!!()
        runCurrent()
        assertEquals(listOf("unpin:friends", "unpin:friends"), pins.calls)
        assertEquals(1, c.state.value.tabs.size)
    }
}
