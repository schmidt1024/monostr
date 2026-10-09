package com.monostr.app.ui
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.R

import com.monostr.app.data.PendingTip
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.NoteCounts
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.TipSummary
import com.monostr.tips.TipReceipt
import com.monostr.tips.TipType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys
import com.monostr.app.ui.common.NoteActions
import com.monostr.app.ui.common.NoteDeletions
import com.monostr.app.ui.feed.FeedController
import com.monostr.app.ui.feed.target
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.monostr.app.ui.common.NoteVisibility
import com.monostr.nostr.repo.ListOutcome
import com.monostr.nostr.repo.MuteRepository

@OptIn(ExperimentalCoroutinesApi::class)
class FeedControllerTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val profiles = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", "https://x/a.png", null, null)))

    /** Controllers get an eager scope on the test scheduler; long-lived collectors stay suspended and are dropped with the scope. */
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.decorations() = TipDecorations(FakeTips(), FakePendingTips(), profiles, FakePublish(), eager()) { 5_000 }

    @Test
    fun `feed decorates notes with profiles, handles reposts, likes and load more`() = runTest {
        val feed = FakeFeed()
        val n1 = note('1', alice, 1000)
        val n2 = note('2', bob, 2000)
        feed.current = listOf(repostOf(n1, bob, 3000), n2, n1)
        feed.older = listOf(note('3', alice, 500))
        val publish = FakePublish()
        val c = FeedController(feed, profiles, publish, decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        val s = c.state.value
        assertFalse(s.loading)
        assertEquals(1, feed.refreshCalls)
        assertEquals(listOf(3000L, 2000L, 1000L), s.notes.map { it.note.createdAt })
        assertEquals("Alice", s.notes[0].repostedAuthor.shownName) // reposted original by alice
        assertEquals(Profile.shortPubkey(bob), s.notes[0].author.shownName)
        assertEquals(n1.id, s.notes[0].target.id)
        assertTrue(profiles.prefetched.flatten().containsAll(listOf(alice, bob)))
        c.like(s.notes[0])
        advanceUntilIdle()
        assertEquals(listOf("like:1111"), publish.calls)
        assertTrue(c.state.value.notes[0].liked)
        assertTrue(c.state.value.notes[2].liked) // same target
        assertEquals(uiText(R.string.action_like_sent), c.state.value.message?.text)
        c.clearMessage()
        assertNull(c.state.value.message)
        c.loadMore()
        advanceUntilIdle()
        assertEquals(4, c.state.value.notes.size)
        assertEquals(500L, c.state.value.notes.last().note.createdAt)
        feed.liveUpdates.emit(listOf(n2))
        advanceUntilIdle()
        // the live update only carries n2, but n1 and n3 are older than it and are kept
        assertEquals(listOf(n2.id, n1.id, note('3', alice, 500).id), c.state.value.notes.map { it.note.id })
        c.refresh()
        advanceUntilIdle()
        assertEquals(2, feed.refreshCalls)
        assertFalse(c.state.value.refreshing)
    }

    @Test
    fun `loadMore ignores overlapping calls while one is in flight`() = runTest {
        val feed = FakeFeed().apply {
            current = listOf(note('1', alice, 1000))
            older = listOf(note('2', alice, 500))
        }
        val gate = CompletableDeferred<Unit>()
        feed.loadMoreGate = gate
        val c = FeedController(feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        c.loadMore()
        c.loadMore()
        advanceUntilIdle()
        assertEquals(1, feed.loadMoreCalls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, c.state.value.notes.size)
        assertFalse(c.state.value.loadingMore)
    }

    @Test
    fun `feed reports offline sends and errors as messages`() = runTest {
        val feed = FakeFeed().apply { current = listOf(note('1', alice)) }
        val offline = FeedController(feed, profiles, FakePublish(relaysOk = false), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        offline.start()
        advanceUntilIdle()
        offline.repost(offline.state.value.notes[0])
        advanceUntilIdle()
        assertEquals(uiText(R.string.action_no_relay_local), offline.state.value.message?.text)
        assertTrue(offline.state.value.notes[0].reposted)
        val failing = FeedController(feed, profiles, FakePublish(fail = true), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        failing.start()
        advanceUntilIdle()
        failing.like(failing.state.value.notes[0])
        advanceUntilIdle()
        assertEquals(uiText(R.string.error_send_failed), failing.state.value.message?.text)
        assertFalse(failing.state.value.notes[0].liked)
    }

    @Test
    fun `a failing loadMore reports a message and leaves the controller usable`() = runTest {
        val feed = FakeFeed().apply {
            current = listOf(note('1', alice, 1000))
            older = listOf(note('2', alice, 500))
            loadMoreError = IllegalStateException("db closed")
        }
        val c = FeedController(feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        c.loadMore()
        advanceUntilIdle()
        assertEquals(uiText(R.string.feed_error_more), c.state.value.message?.text)
        assertFalse(c.state.value.loadingMore)
        feed.loadMoreError = null
        c.loadMore()
        advanceUntilIdle()
        assertEquals(2, c.state.value.notes.size)
    }

    @Test
    fun `local notes show before the relay refresh finishes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val feed = FakeFeed().apply {
            current = listOf(note('1', alice, 1000))
            refreshGate = gate
        }
        val c = FeedController(feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        assertEquals(listOf(note('1', alice, 1000).id), c.state.value.notes.map { it.note.id })
        assertFalse(c.state.value.loading)
        assertFalse(gate.isCompleted)
        feed.current = listOf(note('2', alice, 2000), note('1', alice, 1000))
        gate.complete(Unit)
        advanceUntilIdle()
        // notes pulled in by the refresh appear without waiting for a live event
        assertEquals(2, c.state.value.notes.size)
    }

    @Test
    fun `feed shows tip summaries and pending markers for visible notes`() = runTest {
        val feed = FakeFeed()
        val n1 = note('1', alice, 1000)
        feed.current = listOf(n1)
        val tips = FakeTips()
        val pending = FakePendingTips()
        val c = FeedController(feed, profiles, FakePublish(), TipDecorations(tips, pending, profiles, FakePublish(), eager()) { 5_000 }, BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        assertEquals(mapOf(n1.id to alice), tips.observed.last())
        assertNull(c.state.value.notes.single().tips)
        pending.add(PendingTip("e".repeat(64), n1.id, 5, 4_000))
        advanceUntilIdle()
        assertTrue(c.state.value.notes.single().pendingTip)
        val receipt = TipReceipt("r".repeat(64), "c".repeat(64), n1.id, alice, bob, 5, "e".repeat(64), TipType.LIKE, 4_500)
        tips.receipts.value = mapOf(n1.id to TipSummary(5, 1, listOf(receipt)))
        advanceUntilIdle()
        assertEquals(5L, c.state.value.notes.single().tips?.total)
        assertFalse(c.state.value.notes.single().pendingTip)
    }

    @Test
    fun `feed shows bookmark flags live and toggles through the repository`() = runTest {
        val feed = FakeFeed(); val n1 = note('1', alice, 1000); feed.current = listOf(n1)
        val bookmarks = FakeBookmarks()
        val c = FeedController(feed, profiles, FakePublish(), decorations(), BookmarkActions(bookmarks, eager()), FakeCounts(), eager())
        c.start(); advanceUntilIdle()
        assertTrue(bookmarks.state.value.loaded, "feed start triggers ensureLoaded")
        assertEquals(listOf(false), bookmarks.loadCalls, "feed open never prompts the signer")
        assertFalse(c.state.value.notes[0].bookmarked)
        c.bookmark(c.state.value.notes[0]); advanceUntilIdle()
        assertEquals(listOf(n1.id), bookmarks.toggled)
        assertTrue(c.state.value.notes[0].bookmarked)
        assertNull(c.state.value.message)
    }

    @Test
    fun `a like shows at once, before the relay answered, and reverts on failure`() = runTest {
        val feed = FakeFeed(); val n1 = note('1', alice, 1000); feed.current = listOf(n1)
        val publish = FakePublish().apply { gate = CompletableDeferred() }
        val c = FeedController(feed, profiles, publish, decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start(); advanceUntilIdle()
        c.like(c.state.value.notes[0])
        assertTrue(c.state.value.notes[0].liked, "heart must fill before the publish completes")
        assertNull(c.state.value.message)
        publish.gate!!.complete(Unit); advanceUntilIdle()
        assertTrue(c.state.value.notes[0].liked)
        assertEquals(uiText(R.string.action_like_sent), c.state.value.message?.text)

        val failing = FakePublish(fail = true).apply { gate = CompletableDeferred() }
        val c2 = FeedController(feed, profiles, failing, decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c2.start(); advanceUntilIdle()
        c2.like(c2.state.value.notes[0])
        assertTrue(c2.state.value.notes[0].liked)
        failing.gate!!.complete(Unit); advanceUntilIdle()
        assertFalse(c2.state.value.notes[0].liked, "a failed publish reverts the optimistic heart")
    }

    @Test
    fun `visible ids are requested and counts decorate the notes`() = runTest {
        val feed = FakeFeed().apply { current = listOf(note('a'), note('b')) }
        val counts = FakeCounts()
        val c = FeedController(feed, FakeProfiles(), FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), counts, eager())
        c.start(); advanceUntilIdle()
        c.visible(listOf("a".repeat(64)))
        assertEquals(listOf(listOf("a".repeat(64))), counts.requested)
        counts.set("a".repeat(64), NoteCounts(likes = 3, reposts = null, replies = 1))
        advanceUntilIdle()
        assertEquals(3L, c.state.value.notes.first { it.note.id == "a".repeat(64) }.counts?.likes)
        assertNull(c.state.value.notes.first { it.note.id == "b".repeat(64) }.counts)
    }

    @Test
    fun `counts survive a live feed rebuild`() = runTest {
        val feed = FakeFeed().apply { current = listOf(note('a')) }
        val counts = FakeCounts()
        val c = FeedController(feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), counts, eager())
        c.start(); advanceUntilIdle()
        counts.set("a".repeat(64), NoteCounts(likes = 3, reposts = null, replies = 1))
        advanceUntilIdle()
        assertEquals(3L, c.state.value.notes.first { it.note.id == "a".repeat(64) }.counts?.likes)
        feed.liveUpdates.emit(listOf(note('a')))
        advanceUntilIdle()
        assertEquals(3L, c.state.value.notes.first { it.note.id == "a".repeat(64) }.counts?.likes)
    }

    @Test
    fun `visible() requests the original for a repost`() = runTest {
        val feed = FakeFeed()
        val n1 = note('1', alice, 1000)
        val repost = repostOf(n1, bob, 3000)
        feed.current = listOf(repost)
        val counts = FakeCounts()
        val c = FeedController(feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), counts, eager())
        c.start(); advanceUntilIdle()
        c.visible(listOf(repost.id))
        assertEquals(listOf(listOf(n1.id)), counts.requested)
    }

    @Test
    fun `mention names of shown notes are resolved and published as a map`() = runTest {
        val carolKeys = Keys.parse("0000000000000000000000000000000000000000000000000000000000000003")
        val carol = carolKeys.publicKey().toHex()
        val dave = "d".repeat(64)
        val withMention = note('1', alice, 1000, content = "hi nostr:${carolKeys.publicKey().toBech32()}").copy(mentionedPubkeys = listOf(dave))
        val known = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", null, null, null), carol to Profile(carol, "carol", "Carol", null, null, null)))
        val feed = FakeFeed().apply { current = listOf(withMention) }
        val c = FeedController(feed, known, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        assertEquals(mapOf(carol to "Carol"), c.names.value) // dave has no profile: the UI shows the short form
        assertTrue(known.prefetched.any { it.containsAll(listOf(carol, dave)) })
    }

    @Test
    fun `a follow change restarts the live list and refreshes from the relays`() = runTest {
        val feed = FakeFeed().apply { current = listOf(note('1', alice, 1000)) }
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val c = FeedController(feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(), followChanges = changes)
        c.start(); advanceUntilIdle()
        assertEquals(1, feed.refreshCalls)
        feed.current = listOf(note('2', bob, 2000), note('1', alice, 1000))
        changes.emit(Unit); advanceUntilIdle()
        assertEquals(2, feed.refreshCalls)
        assertEquals(listOf('2', '1').map { it.toString().repeat(64) }, c.state.value.notes.map { it.note.id })
    }

    @Test
    fun `a mute change restarts the live list, whose author list is read once per start`() = runTest {
        val muted = MutableStateFlow<Set<String>>(emptySet())
        val feed = FakeFeed().apply { current = listOf(note('1', alice, 1000)) }
        val c = controller(feed, muted = muted)
        c.start(); advanceUntilIdle()
        assertEquals(1, feed.liveCalls)
        muted.value = setOf(alice); advanceUntilIdle()
        assertEquals(2, feed.liveCalls)
        muted.value = emptySet(); advanceUntilIdle()
        assertEquals(3, feed.liveCalls)
    }

    private fun TestScope.controller(
        feed: FakeFeed,
        deletions: NoteDeletions = NoteDeletions(),
        muted: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
        mute: MuteRepository? = null,
    ) = FeedController(
        feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(),
        deletions = deletions, visibility = NoteVisibility(deletions, muted), mute = mute,
    )

    @Test
    fun `muting hides the account's notes at once and a later load keeps them out`() = runTest {
        val muted = MutableStateFlow<Set<String>>(emptySet())
        val feed = FakeFeed().apply { current = listOf(note('1', alice, createdAt = 2000), note('2', bob, createdAt = 1000)) }
        val c = controller(feed, muted = muted)
        c.start(); advanceUntilIdle()
        assertEquals(2, c.state.value.notes.size)
        muted.value = setOf(alice); advanceUntilIdle()
        assertEquals(listOf(note('2', bob).id), c.state.value.notes.map { it.note.id })
        c.refresh(); advanceUntilIdle()
        assertEquals(listOf(note('2', bob).id), c.state.value.notes.map { it.note.id })
        muted.value = emptySet(); advanceUntilIdle()
        // the filtered list no longer holds the notes: the restart on the mute change (and any refresh) brings them back
        c.refresh(); advanceUntilIdle()
        assertEquals(2, c.state.value.notes.size)
    }

    @Test
    fun `mute from the menu hides the author, says so and offers undo, which unmutes`() = runTest {
        val mute = FakeMute()
        val feed = FakeFeed().apply { current = listOf(note('1', alice, createdAt = 2000), note('2', bob, createdAt = 1000)) }
        val c = controller(feed, muted = mute.muted, mute = mute)
        c.start(); advanceUntilIdle()
        c.mute(note('1', alice)); advanceUntilIdle()
        assertEquals(listOf(note('2', bob).id), c.state.value.notes.map { it.note.id })
        assertEquals(uiText(R.string.mute_done, "Alice"), c.state.value.message?.text) // the profile's name, not a short pubkey
        assertEquals(alice, c.state.value.message?.undoMute)
        c.clearMessage()
        assertNull(c.state.value.message?.undoMute)
        val refreshes = feed.refreshCalls
        c.unmute(alice); advanceUntilIdle()
        assertEquals(emptySet<String>(), mute.muted.value)
        assertNull(c.state.value.message) // success is silent
        assertEquals(refreshes + 1, feed.refreshCalls)
        assertEquals(listOf(note('1', alice).id, note('2', bob).id), c.state.value.notes.map { it.note.id }) // undo brings the notes back
    }

    @Test
    fun `a later message takes the undo away, it belongs to the mute confirmation only`() = runTest {
        val mute = FakeMute()
        val feed = FakeFeed().apply { current = listOf(note('1', alice, createdAt = 2000)) }
        val c = controller(feed, muted = mute.muted, mute = mute)
        c.start(); advanceUntilIdle()
        c.mute(note('1', alice)); advanceUntilIdle()
        assertEquals(alice, c.state.value.message?.undoMute)
        mute.outcome = ListOutcome.PublishFailed
        c.unmute(alice); advanceUntilIdle()
        assertEquals(uiText(R.string.error_send_failed), c.state.value.message?.text)
        assertNull(c.state.value.message?.undoMute)
    }

    @Test
    fun `a failed mute brings the account back with the usual message and no undo`() = runTest {
        val mute = FakeMute(outcome = ListOutcome.Rejected)
        val feed = FakeFeed().apply { current = listOf(note('1', alice, createdAt = 2000)) }
        val c = controller(feed, muted = mute.muted, mute = mute)
        c.start(); advanceUntilIdle()
        c.mute(note('1', alice)); advanceUntilIdle()
        assertEquals(1, c.state.value.notes.size)
        assertEquals(uiText(R.string.error_signing_rejected), c.state.value.message?.text)
        assertNull(c.state.value.message?.undoMute)
    }

    @Test
    fun `a mute whose publish fails brings the notes back`() = runTest {
        val mute = FakeMute(outcome = ListOutcome.PublishFailed).apply { gate = CompletableDeferred() }
        val feed = FakeFeed().apply { current = listOf(note('1', alice, createdAt = 2000), note('2', bob, createdAt = 1000)) }
        val c = controller(feed, muted = mute.muted, mute = mute)
        c.start(); advanceUntilIdle()
        c.mute(note('1', alice)); advanceUntilIdle()
        assertEquals(listOf(note('2', bob).id), c.state.value.notes.map { it.note.id }) // gone at once
        val refreshes = feed.refreshCalls
        mute.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(uiText(R.string.error_send_failed), c.state.value.message?.text)
        assertTrue(feed.refreshCalls > refreshes)
        assertEquals(listOf(note('1', alice).id, note('2', bob).id), c.state.value.notes.map { it.note.id })
    }

    @Test
    fun `a deleted note leaves the list, also when another screen deleted it, and stays out of later loads`() = runTest {
        val deletions = NoteDeletions()
        val feed = FakeFeed().apply { current = listOf(note('1', createdAt = 2000), note('2', createdAt = 1000)) }
        val c = controller(feed, deletions)
        c.start()
        advanceUntilIdle()
        assertEquals(2, c.state.value.notes.size)

        deletions.begin(note('1').id); deletions.end(note('1').id, deleted = true)
        advanceUntilIdle()
        assertEquals(listOf(note('2').id), c.state.value.notes.map { it.note.id })

        c.refresh() // the fake still serves it, as a search relay might
        advanceUntilIdle()
        assertEquals(listOf(note('2').id), c.state.value.notes.map { it.note.id })
    }

    @Test
    fun `a repost of a deleted note leaves the list too`() = runTest {
        val deletions = NoteDeletions()
        val original = note('1', createdAt = 1000)
        val feed = FakeFeed().apply { current = listOf(repostOf(original, by = "b".repeat(64), createdAt = 2000)) }
        val c = controller(feed, deletions)
        c.start()
        advanceUntilIdle()
        assertEquals(1, c.state.value.notes.size)
        deletions.begin(original.id); deletions.end(original.id, deleted = true)
        advanceUntilIdle()
        assertTrue(c.state.value.notes.isEmpty())
    }

    @Test
    fun `a note shows its deletion as pending while another screen's request runs, and is free again after a failure`() = runTest {
        val deletions = NoteDeletions()
        val feed = FakeFeed().apply { current = listOf(note('1', createdAt = 2000)) }
        val c = controller(feed, deletions)
        c.start()
        advanceUntilIdle()
        val publish = FakePublish(fail = true).apply { gate = CompletableDeferred() }
        val elsewhere = NoteActions(publish, eager(), deletions = deletions) // e.g. the thread
        elsewhere.delete(note('1'))
        advanceUntilIdle()
        assertTrue(c.state.value.notes.single().deleting)
        publish.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(c.state.value.notes.single().deleting) // the menu entry is free again
        assertEquals(1, c.state.value.notes.size)
    }
}
