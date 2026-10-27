package com.monostr.app.ui.lists

import com.monostr.app.R
import com.monostr.app.ui.FakeBookmarks
import com.monostr.app.ui.FakeCounts
import com.monostr.app.ui.FakeFeed
import com.monostr.app.ui.FakeFeedTabs
import com.monostr.app.ui.FakeLists
import com.monostr.app.ui.FakePendingTips
import com.monostr.app.ui.FakeProfiles
import com.monostr.app.ui.FakePublish
import com.monostr.app.ui.FakeTips
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.feed.FeedController
import com.monostr.app.ui.followSet
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.Profile
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-18 §2.3: the list screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListControllerTest {
    private val self = "e".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.decorations() = TipDecorations(FakeTips(), FakePendingTips(), FakeProfiles(), FakePublish(), eager()) { 5_000 }
    private val built = ArrayList<FeedSource.Authors>()

    private fun TestScope.controller(address: ListAddress, lists: FakeLists, pins: FakeFeedTabs = FakeFeedTabs(), profiles: FakeProfiles = FakeProfiles()): ListController =
        ListController(address, self, lists, pins, profiles, eager()) { source, child ->
            built += source
            FeedController(FakeFeed(), FakeProfiles(), FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), child), FakeCounts(), child, source = source)
        }

    @Test
    fun `an own list shows its header, members and a members-only feed`() = runTest {
        val friends = followSet("friends", listOf(bob, carol), author = self, title = "Friends")
        val profiles = FakeProfiles(mapOf(bob to Profile(bob, "bob", "Bob", null, null, null)))
        val c = controller(friends.address, FakeLists(listOf(friends)), FakeFeedTabs(listOf(friends.address)), profiles)
        c.start(); runCurrent()
        val s = c.state.value
        assertEquals(friends, s.set)
        assertTrue(s.isOwn); assertTrue(s.pinned); assertFalse(s.loading)
        assertEquals(listOf("Bob", Profile.shortPubkey(carol)), s.members.map { it.shownName })
        assertEquals(listOf(FeedSource.Authors(listOf(bob, carol))), built)
        assertNotNull(s.feed)
    }

    @Test
    fun `removing a member updates the list and rebuilds the feed, a failure offers Retry`() = runTest {
        val friends = followSet("friends", listOf(bob, carol), author = self)
        val lists = FakeLists(listOf(friends))
        val c = controller(friends.address, lists)
        c.start(); runCurrent()
        c.removeMember(carol); runCurrent()
        assertEquals(listOf(bob), c.state.value.set!!.members)
        assertEquals(FeedSource.Authors(listOf(bob)), built.last())
        lists.result = SaveResult.Failed
        c.removeMember(bob); runCurrent()
        val m = c.state.value.message!!
        assertEquals(uiText(R.string.list_save_failed), m.text)
        assertEquals(listOf(bob), c.state.value.set!!.members)
        lists.result = SaveResult.Ok
        m.retry!!()
        runCurrent()
        assertEquals(emptyList<String>(), c.state.value.set!!.members)
        assertNull(c.state.value.feed) // no members: no feed
    }

    @Test
    fun `a foreign list found nowhere is unavailable`() = runTest {
        val c = controller(ListAddress(30000, bob, "gone"), FakeLists())
        c.start(); runCurrent()
        assertTrue(c.state.value.unavailable); assertFalse(c.state.value.loading); assertNull(c.state.value.feed)
    }

    @Test
    fun `a foreign list can be pinned`() = runTest {
        val theirs = followSet("picks", listOf(carol), author = bob)
        val pins = FakeFeedTabs()
        val c = controller(theirs.address, FakeLists().apply { foreign = mapOf(theirs.address to theirs) }, pins)
        c.start(); runCurrent()
        assertFalse(c.state.value.isOwn)
        c.togglePin(); runCurrent()
        assertEquals(listOf(theirs.address), pins.state.value)
        assertTrue(c.state.value.pinned)
    }

    @Test
    fun `member and author profiles that land later show`() = runTest {
        val friends = followSet("friends", listOf(bob, carol), author = self, title = "Friends")
        val profiles = FakeProfiles()
        val c = controller(friends.address, FakeLists(listOf(friends)), profiles = profiles)
        c.start(); runCurrent()
        assertEquals(listOf(Profile.shortPubkey(bob), Profile.shortPubkey(carol)), c.state.value.members.map { it.shownName })
        profiles.land(Profile(carol, "carol", "Carol", null, null, null), Profile(self, "me", "Me", null, null, null))
        runCurrent()
        assertEquals(listOf(Profile.shortPubkey(bob), "Carol"), c.state.value.members.map { it.shownName })
        assertEquals("Me", c.state.value.author?.shownName)
    }

    @Test
    fun `the same members in another order keep the feed`() = runTest {
        val friends = followSet("friends", listOf(bob, carol), author = self)
        val lists = FakeLists(listOf(friends))
        val c = controller(friends.address, lists)
        c.start(); runCurrent()
        val feed = c.state.value.feed
        lists.ownState.value = listOf(friends.copy(members = listOf(carol, bob), createdAt = 2000)); runCurrent()
        assertSame(feed, c.state.value.feed)
        assertEquals(1, built.size)
    }

    @Test
    fun `foreign list followAll follows new non-muted members once`() = runTest {
        val dave = "d".repeat(64)
        val theirs = followSet("art", listOf(self, bob, carol, dave), author = "a".repeat(64))
        val lists = FakeLists().also { it.foreign = mapOf(theirs.address to theirs) }
        val follows = com.monostr.app.ui.FakeFollows(mapOf(bob to com.monostr.nostr.repo.FollowState.Following))
        val c = ListController(theirs.address, self, lists, FakeFeedTabs(), FakeProfiles(), eager(), follows = follows, mute = com.monostr.app.ui.FakeMute(setOf(dave))) { source, child ->
            FeedController(FakeFeed(), FakeProfiles(), FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), child), FakeCounts(), child, source = source)
        }
        c.start(); runCurrent()
        assertTrue(c.state.value.canFollow)
        assertEquals(setOf(bob), c.state.value.followed)
        follows.gate = kotlinx.coroutines.CompletableDeferred()
        c.followAll(); c.followAll(); runCurrent() // a double tap while the write is in flight
        follows.gate!!.complete(Unit); runCurrent()
        assertEquals(listOf("followAll:1"), follows.calls)
        assertEquals(setOf(bob, carol), c.state.value.followed)
    }

    @Test
    fun `own list offers no follow`() = runTest {
        val mine = followSet("friends", listOf(bob), author = self)
        val c = ListController(mine.address, self, FakeLists(listOf(mine)), FakeFeedTabs(), FakeProfiles(), eager(), follows = com.monostr.app.ui.FakeFollows(), mute = com.monostr.app.ui.FakeMute()) { source, child ->
            FeedController(FakeFeed(), FakeProfiles(), FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), child), FakeCounts(), child, source = source)
        }
        c.start(); runCurrent()
        assertFalse(c.state.value.canFollow)
    }

    @Test
    fun `the user and muted members get no follow button and do not keep follow all open`() = runTest {
        // final review I5
        val dave = "d".repeat(64)
        val theirs = followSet("art", listOf(self, bob, dave), author = "a".repeat(64))
        val lists = FakeLists().also { it.foreign = mapOf(theirs.address to theirs) }
        val follows = com.monostr.app.ui.FakeFollows(mapOf(bob to com.monostr.nostr.repo.FollowState.Following))
        val c = ListController(theirs.address, self, lists, FakeFeedTabs(), FakeProfiles(), eager(), follows = follows, mute = com.monostr.app.ui.FakeMute(setOf(dave))) { source, child ->
            FeedController(FakeFeed(), FakeProfiles(), FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), child), FakeCounts(), child, source = source)
        }
        c.start(); runCurrent()
        assertEquals(setOf(self, dave), c.state.value.notFollowable)
        assertTrue(c.state.value.allFollowed)
    }
}
