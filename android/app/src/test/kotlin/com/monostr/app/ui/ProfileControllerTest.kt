package com.monostr.app.ui

import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.R
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.repo.FollowState
import com.monostr.nostr.repo.FollowError
import com.monostr.app.ui.profile.ProfileController
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.ListOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileControllerTest {
    private val bob = "b".repeat(64)

    /**
     * The controller's outbox refresh is an intentionally endless `delay`-loop; a plain
     * `advanceUntilIdle()` would chase its ever-later scheduled delay forever (a documented
     * kotlinx-coroutines-test hazard). Combining `backgroundScope`'s Job (which `advanceUntilIdle()`
     * ignores entirely — only an explicit `advanceTimeBy`/`runCurrent` drives it) with
     * `UnconfinedTestDispatcher` (so work still runs eagerly, without needing `runCurrent()` for the
     * initial load) gives exactly the bounded, one-step-at-a-time control these tests need.
     */
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.decorations() = TipDecorations(FakeTips(), FakePendingTips(), FakeProfiles(), FakePublish(), eager()) { 5_000 }

    private fun TestScope.controller(feed: FakeFeed, relays: FakeAuthorRelays, enabled: Boolean, scope: CoroutineScope) =
        ProfileController(bob, "a".repeat(64), feed, FakeProfiles(), FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), scope), FakeCounts(), relays, { enabled }, scope, refreshMs = 1_000)

    @Test
    fun `the profile of a muted account keeps showing its notes, also when muted from its own note`() = runTest {
        val feed = FakeFeed().apply { byAuthor = mapOf(bob to listOf(note('x', author = bob), note('y', author = bob, createdAt = 900))) }
        val mute = FakeMute(initial = setOf(bob))
        val scope = eager()
        val c = ProfileController(
            bob, "a".repeat(64), feed, FakeProfiles(), FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), scope), FakeCounts(),
            FakeAuthorRelays(), { false }, scope, refreshMs = 1_000, mute = mute,
        )
        c.start(); runCurrent()
        assertEquals(2, c.state.value.notes.size) // spec 9.3: the profile is not hidden
        mute.muted.value = emptySet()
        c.mute(note('x', author = bob)); runCurrent()
        assertEquals(setOf(bob), mute.muted.value)
        assertEquals(2, c.state.value.notes.size)
        assertEquals(bob, c.state.value.message?.undoMute)
    }

    private fun TestScope.muteController(mute: FakeMute, scope: CoroutineScope) = ProfileController(
        bob, "a".repeat(64), FakeFeed(), FakeProfiles(), FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), scope), FakeCounts(),
        FakeAuthorRelays(), { false }, scope, refreshMs = 1_000, mute = mute,
    )

    @Test
    fun `mute and unmute of the profile flip isMuted, mute offers undo`() = runTest {
        val mute = FakeMute()
        val c = muteController(mute, eager())
        c.start(); runCurrent()
        assertEquals(false, c.state.value.isMuted)
        c.mute(); runCurrent()
        assertEquals(true, c.state.value.isMuted)
        assertEquals(bob, c.state.value.message?.undoMute)
        c.unmute(); runCurrent()
        assertEquals(false, c.state.value.isMuted)
    }

    @Test
    fun `a rejected mute leaves isMuted false and shows the failure`() = runTest {
        val mute = FakeMute(outcome = ListOutcome.Rejected)
        val c = muteController(mute, eager())
        c.start(); runCurrent()
        c.mute(); runCurrent()
        assertEquals(false, c.state.value.isMuted)
        assertEquals(uiText(R.string.error_signing_rejected), c.state.value.message?.text)
        assertEquals(null, c.state.value.message?.undoMute)
    }

    @Test
    fun `the menu offers mute by the rule of the note menu, not on a read-only list`() = runTest {
        val open = muteController(FakeMute(), eager())
        open.start(); runCurrent()
        assertTrue(open.state.value.canMute)
        val readOnly = FakeMute(writable = false)
        val c = muteController(readOnly, eager())
        c.start(); runCurrent()
        readOnly.ensureLoaded(); runCurrent()
        assertEquals(false, c.state.value.canMute)
    }

    @Test
    fun `an account muted elsewhere shows as muted at once`() = runTest {
        val mute = FakeMute(initial = setOf(bob))
        val c = muteController(mute, eager())
        c.start(); runCurrent()
        assertTrue(c.state.value.isMuted)
        mute.muted.value = emptySet(); runCurrent()
        assertEquals(false, c.state.value.isMuted)
    }

    @Test
    fun `attaches the author's write relays, fetches from them, shows them and detaches on close`() = runTest {
        val feed = FakeFeed().apply { byAuthor = mapOf(bob to listOf(note('x', author = bob))) }
        val relays = FakeAuthorRelays(mapOf(bob to listOf("wss://w1.example", "wss://w2.example")))
        val c = controller(feed, relays, enabled = true, scope = eager())
        c.start(); advanceUntilIdle()
        assertEquals(listOf(listOf("wss://w1.example", "wss://w2.example")), relays.attached)
        assertEquals(listOf("wss://w1.example", "wss://w2.example"), c.state.value.extraRelays)
        assertEquals(1, relays.fetched.size)
        advanceTimeBy(1_100)
        assertEquals(2, relays.fetched.size) // periodic refresh
        c.close(); advanceUntilIdle()
        assertEquals(listOf(listOf("wss://w1.example", "wss://w2.example")), relays.detached)
    }

    @Test
    fun `setting off or empty list changes nothing`() = runTest {
        val feed = FakeFeed().apply { byAuthor = mapOf(bob to listOf(note('x', author = bob))) }
        val off = FakeAuthorRelays(mapOf(bob to listOf("wss://w1.example")))
        controller(feed, off, enabled = false, scope = eager()).also { it.start() }; advanceUntilIdle()
        assertTrue(off.attached.isEmpty())
        val empty = FakeAuthorRelays()
        val c = controller(feed, empty, enabled = true, scope = eager()); c.start(); advanceUntilIdle()
        assertTrue(empty.attached.isEmpty()); assertTrue(c.state.value.extraRelays.isEmpty())
        assertEquals(1, c.state.value.notes.size) // profile still loads as before
    }

    @Test
    fun `cancelling the scope during setup still detaches`() = runTest {
        val feed = FakeFeed().apply { byAuthor = mapOf(bob to listOf(note('x', author = bob))) }
        val relays = FakeAuthorRelays(mapOf(bob to listOf("wss://w1.example"))).apply { gate = CompletableDeferred() }
        val scope = eager()
        val c = controller(feed, relays, enabled = true, scope = scope)
        c.start(); runCurrent()
        scope.cancel()
        relays.gate?.complete(Unit)
        advanceUntilIdle()
        // detach() always runs from the finally, regardless of whether attach() itself completed before
        // the cancellation landed (attach only stops between URLs by cancellation, so the refcounts stay balanced).
        assertEquals(listOf(listOf("wss://w1.example")), relays.detached)
        assertTrue(relays.attached.size <= 1)
    }

    @Test
    fun `refresh pauses while the profile is hidden`() = runTest {
        val feed = FakeFeed().apply { byAuthor = mapOf(bob to listOf(note('x', author = bob))) }
        val relays = FakeAuthorRelays(mapOf(bob to listOf("wss://w1.example")))
        val c = controller(feed, relays, enabled = true, scope = eager())
        c.start(); advanceUntilIdle()
        advanceTimeBy(1_100)
        assertEquals(2, relays.fetched.size) // one tick
        c.setVisible(false)
        advanceTimeBy(3 * 1_000)
        assertEquals(2, relays.fetched.size) // no fetch while hidden
        c.setVisible(true)
        runCurrent() // backgroundScope work is driven only explicitly; advanceUntilIdle() would ignore it
        assertEquals(3, relays.fetched.size)
        c.close(); advanceUntilIdle()
    }

    @Test
    fun `refresh reloads without fetching and shows notes that appeared meanwhile`() = runTest {
        val feed = FakeFeed().apply { byAuthor = mapOf(bob to listOf(note('x', author = bob))) }
        val relays = FakeAuthorRelays(mapOf(bob to listOf("wss://w1.example")))
        val c = controller(feed, relays, enabled = true, scope = eager())
        c.start(); advanceUntilIdle()
        assertEquals(1, c.state.value.notes.size)
        feed.byAuthor = mapOf(bob to listOf(note('x', author = bob), note('y', author = bob)))
        advanceTimeBy(1_100)
        assertEquals(2, c.state.value.notes.size)
        assertEquals(false, feed.notesByCalls.last())
    }

    @Test
    fun `own profile follows repository updates and is marked as self`() = runTest {
        val me = "a".repeat(64)
        val profiles = FakeProfiles(mapOf(me to Profile(me, "old", null, null, null, null)))
        val feed = FakeFeed().apply { byAuthor = mapOf(me to listOf(note('x', author = me))) }
        val scope = eager()
        val c = ProfileController(me, me, feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), scope), FakeCounts(), FakeAuthorRelays(), { false }, scope, refreshMs = 1_000)
        c.start()
        runCurrent()
        assertTrue(c.state.value.isSelf)
        assertEquals("old", c.state.value.profile.shownName)
        profiles.current.value = mapOf(me to Profile(me, "new", null, null, null, null, banner = "https://x.example/b.jpg"))
        runCurrent()
        assertEquals("new", c.state.value.profile.shownName)
        assertEquals("https://x.example/b.jpg", c.state.value.profile.banner)
        assertEquals("new", c.state.value.notes[0].author.shownName)
        c.close()
    }

    @Test
    fun `outbox refresh keeps the edited profile on the notes`() = runTest {
        val me = "a".repeat(64)
        val profiles = FakeProfiles(mapOf(me to Profile(me, "old", null, null, null, null)))
        val feed = FakeFeed().apply { byAuthor = mapOf(me to listOf(note('x', author = me))) }
        val relays = FakeAuthorRelays(mapOf(me to listOf("wss://w1.example")))
        val scope = eager()
        val c = ProfileController(me, me, feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), scope), FakeCounts(), relays, { true }, scope, refreshMs = 1_000)
        c.start(); advanceUntilIdle()
        profiles.current.value = mapOf(me to Profile(me, "new", null, null, null, null))
        runCurrent()
        advanceTimeBy(1_100) // one refresh reloads the notes
        assertEquals("new", c.state.value.profile.shownName)
        assertEquals("new", c.state.value.notes[0].author.shownName)
        c.close(); advanceUntilIdle()
    }

    private fun TestScope.followController(follows: FakeFollows, self: String = "a".repeat(64), scope: CoroutineScope = eager()) =
        ProfileController(bob, self, FakeFeed(), FakeProfiles(), FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), scope), FakeCounts(), FakeAuthorRelays(), { false }, scope, refreshMs = 1_000, follows = follows)

    @Test
    fun `the follow state comes from the repository and follow writes once while busy`() = runTest {
        val follows = FakeFollows(mapOf(bob to FollowState.NotFollowing)).apply { gate = CompletableDeferred() }
        val c = followController(follows)
        c.start(); runCurrent()
        assertEquals(FollowState.NotFollowing, c.state.value.follow)
        c.follow(); runCurrent()
        assertTrue(c.state.value.followBusy)
        c.follow(); runCurrent() // a second tap while writing is ignored
        follows.gate!!.complete(Unit); runCurrent()
        assertEquals(listOf("follow:bbbb"), follows.calls)
        assertEquals(FollowState.Following, c.state.value.follow)
        assertEquals(false, c.state.value.followBusy)
        c.close()
    }

    @Test
    fun `unfollow asks first and only the confirmation writes`() = runTest {
        val follows = FakeFollows(mapOf(bob to FollowState.Following))
        val c = followController(follows)
        c.start(); runCurrent()
        c.askUnfollow()
        assertTrue(c.state.value.confirmUnfollow)
        c.dismissUnfollow()
        assertEquals(false, c.state.value.confirmUnfollow)
        assertTrue(follows.calls.isEmpty())
        c.askUnfollow(); c.unfollow(); runCurrent()
        assertEquals(listOf("unfollow:bbbb"), follows.calls)
        assertEquals(FollowState.NotFollowing, c.state.value.follow)
        assertEquals(false, c.state.value.confirmUnfollow)
        c.close()
    }

    @Test
    fun `a failed write keeps the state and reports the error`() = runTest {
        val follows = FakeFollows(mapOf(bob to FollowState.NotFollowing)).apply { error = FollowError.NoList() }
        val c = followController(follows)
        c.start(); runCurrent()
        c.follow(); runCurrent()
        assertEquals(FollowState.NotFollowing, c.state.value.follow)
        assertEquals(uiText(R.string.follow_no_list), c.state.value.message?.text)
        assertEquals(false, c.state.value.followBusy)
        follows.error = IllegalStateException("relay said no")
        c.follow(); runCurrent()
        assertEquals(uiText(R.string.error_send_failed), c.state.value.message?.text)
        c.close()
    }

    @Test
    fun `the own profile never follows`() = runTest {
        val follows = FakeFollows()
        val c = followController(follows, self = bob)
        c.start(); runCurrent()
        c.follow(); runCurrent()
        assertTrue(follows.calls.isEmpty())
        c.close()
    }
}
