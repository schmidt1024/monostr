package com.monostr.app.ui
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.jupiter.api.io.TempDir
import java.io.File
import com.monostr.app.data.FeedSnapshot
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
import kotlinx.coroutines.cancel
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
import com.monostr.nostr.repo.FeedSource
import com.monostr.nostr.repo.Chain
import com.monostr.nostr.repo.Slot
import com.monostr.nostr.repo.MuteRepository

@OptIn(ExperimentalCoroutinesApi::class)
class FeedControllerTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    @TempDir lateinit var snapshotDir: File
    private val profiles = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", "https://x/a.png", null, null)))

    /** Controllers get an eager scope on the test scheduler; long-lived collectors stay suspended and are dropped with the scope. */
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.decorations() = TipDecorations(FakeTips(), FakePendingTips(), profiles, FakePublish(), eager()) { 5_000 }

    @Test
    fun `the home feed opens the startup gate once its first refresh is in, not for the snapshot or the local page`() = runTest {
        var opened = 0
        val feed = FakeFeed().apply { current = listOf(note('1', alice, 1000)); refreshGate = CompletableDeferred() }
        val snapshot = FeedSnapshot(File(snapshotDir, "feed.json"), UnconfinedTestDispatcher(testScheduler))
        snapshot.write(listOf(FeedSnapshot.Item(note('2', alice, 900), Profile.empty(alice), Profile.empty(alice))))
        val c = FeedController(feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(), snapshot = snapshot, onFirstFresh = { opened++ })
        c.start(); advanceUntilIdle()
        assertTrue(c.state.value.notes.isNotEmpty(), "snapshot and local page are shown")
        assertEquals(0, opened)
        feed.refreshGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, opened)
        c.refresh(); advanceUntilIdle()
        assertEquals(1, opened, "a later refresh is no first page")
    }

    @Test
    fun `a failed or cancelled first refresh opens the gate too, a list tab never does`() = runTest {
        var failed = 0
        FeedController(
            FakeFeed().apply { refreshError = IllegalStateException("offline") }, profiles, FakePublish(), decorations(),
            BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(), onFirstFresh = { failed++ },
        ).start()
        advanceUntilIdle()
        assertEquals(1, failed)
        var cancelled = 0
        val vm = eager()
        FeedController(
            FakeFeed().apply { refreshGate = CompletableDeferred() }, profiles, FakePublish(), decorations(),
            BookmarkActions(FakeBookmarks(), vm), FakeCounts(), vm, onFirstFresh = { cancelled++ },
        ).start()
        advanceUntilIdle()
        assertEquals(0, cancelled)
        vm.cancel(); advanceUntilIdle()
        assertEquals(1, cancelled, "the view model went away while the relays were asked")
        var list = 0
        FeedController(
            FakeFeed(), profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(),
            source = FeedSource.Authors(listOf(alice)), onFirstFresh = { list++ },
        ).start()
        advanceUntilIdle()
        assertEquals(0, list)
    }

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
    fun `a profile landing after the live list keeps a pending tip set meanwhile`() = runTest {
        val feed = FakeFeed()
        val n1 = note('1', alice, 2000)
        val n2 = note('2', bob, 1500)
        feed.current = listOf(n1)
        val pending = FakePendingTips()
        val c = FeedController(feed, profiles, FakePublish(), TipDecorations(FakeTips(), pending, profiles, FakePublish(), eager()) { 5_000 }, BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        // a live list arrives; n1 is decorated, bob's profile is still to come from the background lookup
        feed.liveUpdates.emit(listOf(n1, n2))
        advanceUntilIdle()
        // the user tips n1: the marker shows at once
        pending.add(PendingTip("e".repeat(64), n1.id, 5, 4_000))
        advanceUntilIdle()
        assertTrue(c.state.value.notes.first { it.note.id == n1.id }.pendingTip)
        // bob's profile lands from the background lookup and must not take the marker away
        profiles.land(Profile(bob, "bob", "Bob", null, null, null))
        advanceUntilIdle()
        assertEquals(listOf(n1.id, n2.id), c.state.value.notes.map { it.note.id })
        assertTrue(c.state.value.notes.first { it.note.id == n1.id }.pendingTip, "the landed profile overwrote the pending marker")
        assertEquals("Bob", c.state.value.notes.first { it.note.id == n2.id }.author.displayName)
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
    fun `the first page shows with stored profiles and takes over a profile the background lookup found`() = runTest {
        // cold start (2026-10-19): the page waited for profiles fetched from the relays, seconds on the Pixel
        val known = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", null, null, null)))
        val feed = FakeFeed().apply { current = listOf(note('1', alice, 1000)) }
        val c = FeedController(feed, known, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        assertEquals(listOf(note('1', alice).id), c.state.value.notes.map { it.note.id })
        assertFalse(c.state.value.loading)
        assertEquals("Alice", c.state.value.notes.single().author.displayName)
        // the lookup answers with a newer profile: the shown note takes it over
        known.land(Profile(alice, "alice", "Alice B.", null, null, null))
        advanceUntilIdle()
        assertEquals("Alice B.", c.state.value.notes.single().author.displayName)
    }

    @Test
    fun `a list feed's snapshot leaves out accounts no longer on the list`() = runTest {
        val snap = FeedSnapshot(File(snapshotDir, "list.json"), UnconfinedTestDispatcher(testScheduler))
        val kept = note('8', alice, 600)
        val gone = note('9', bob, 500)
        snap.write(listOf(kept, gone).map { FeedSnapshot.Item(it, Profile.empty(it.author), Profile.empty(it.author)) })
        val feed = FakeFeed().apply { current = listOf(note('1', alice, 1000)); liveGate = CompletableDeferred(); refreshGate = CompletableDeferred() }
        val c = FeedController(feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(), source = FeedSource.Authors(listOf(alice)), snapshot = snap)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(kept.id), c.state.value.notes.map { it.note.id })
    }

    @Test
    fun `a snapshot read after an empty first page is not shown`() = runTest {
        // the file read is slower than the live list, which is empty (nothing followed, or all muted)
        val snap = FeedSnapshot(File(snapshotDir, "feed.json"), StandardTestDispatcher(testScheduler))
        snap.write(listOf(FeedSnapshot.Item(note('9', alice, 500), profiles.current.value.getValue(alice), profiles.current.value.getValue(alice))))
        val feed = FakeFeed().apply { current = emptyList() }
        val c = FeedController(feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(), snapshot = snap)
        c.start()
        advanceUntilIdle()
        assertTrue(c.state.value.notes.isEmpty())
    }

    @Test
    fun `a mute while only the snapshot shows waits for the first fresh page`() = runTest {
        val snap = FeedSnapshot(File(snapshotDir, "feed.json"), UnconfinedTestDispatcher(testScheduler))
        snap.write(listOf(FeedSnapshot.Item(note('9', alice, 500), profiles.current.value.getValue(alice), profiles.current.value.getValue(alice))))
        val muted = MutableStateFlow<Set<String>>(emptySet())
        // neither the database nor the relays have answered yet
        val feed = FakeFeed().apply { current = listOf(note('1', alice, 1000)); liveGate = CompletableDeferred(); refreshGate = CompletableDeferred() }
        val c = FeedController(
            feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(),
            visibility = NoteVisibility(NoteDeletions(), muted), snapshot = snap,
        )
        c.start(); advanceUntilIdle()
        muted.value = setOf(bob); advanceUntilIdle()
        assertEquals(1, feed.liveCalls, "the first database read is not cancelled")
        feed.liveGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(2, feed.liveCalls, "the live list is read again after the first fresh page")
    }

    @Test
    fun `the last shown page appears at once and the first fresh page replaces it`() = runTest {
        // file work on the test scheduler: the delayed write is done when the virtual time has passed
        val snap = FeedSnapshot(File(snapshotDir, "feed.json"), UnconfinedTestDispatcher(testScheduler))
        val old = note('9', alice, 500)
        snap.write(listOf(FeedSnapshot.Item(old, profiles.current.value.getValue(alice), profiles.current.value.getValue(alice))))
        val fresh = note('1', alice, 1000)
        val feed = FakeFeed().apply { current = listOf(fresh); liveGate = CompletableDeferred(); refreshGate = CompletableDeferred() }
        val c = FeedController(feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(), snapshot = snap)
        c.start()
        advanceUntilIdle()
        assertEquals(listOf(old.id), c.state.value.notes.map { it.note.id })
        assertFalse(c.state.value.loading)
        assertEquals("Alice", c.state.value.notes.single().author.displayName)
        feed.liveGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(fresh.id), c.state.value.notes.map { it.note.id }, "a note only the snapshot had leaves with the first fresh page")
        feed.refreshGate!!.complete(Unit)
        advanceTimeBy(FeedController.SNAPSHOT_DELAY_MS + 100)
        advanceUntilIdle()
        assertEquals(listOf(fresh.id), snap.read().map { it.note.id }, "the fresh page is kept for the next start")
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
    fun `a newly muted account is only hidden without a relay round, an unmute restarts and refreshes`() = runTest {
        // the mute list arrives seconds after the start (cold start): restarting then threw the first page away
        val muted = MutableStateFlow<Set<String>>(emptySet())
        val feed = FakeFeed().apply { current = listOf(note('1', alice, 1000), note('2', bob, 900)) }
        val c = controller(feed, muted = muted)
        c.start(); advanceUntilIdle()
        assertEquals(1, feed.liveCalls)
        assertEquals(1, feed.refreshCalls)
        muted.value = setOf(alice); advanceUntilIdle()
        assertEquals(1, feed.refreshCalls, "muting more accounts asks the relays nothing new")
        assertEquals(listOf(note('2', bob).id), c.state.value.notes.map { it.note.id })
        val livesBefore = feed.liveCalls
        muted.value = emptySet(); advanceUntilIdle()
        assertEquals(livesBefore + 1, feed.liveCalls, "an unmute restarts, so the account's notes are asked for again")
        assertEquals(2, feed.refreshCalls)
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

    @Test
    fun `an ancestor keeps the profile the background lookup found, also after the list is rebuilt`() = runTest {
        val carol = "c".repeat(64)
        val root = note('r', carol, 900)
        val reply = note('s', alice, 1000, replyTo = root.id, root = root.id)
        val known = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", null, null, null)))
        val feed = FakeFeed().apply { current = listOf(reply) }
        val conversations = FakeConversations(mapOf(reply.id to Chain(listOf(Slot.Shown(root)), cut = false)))
        val muted = MutableStateFlow<Set<String>>(emptySet())
        val c = FeedController(
            feed, known, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(),
            conversations = conversations, visibility = NoteVisibility(NoteDeletions(), muted),
        )
        c.start()
        advanceUntilIdle()
        assertEquals(root.id, c.state.value.ancestors[root.id]?.note?.id)
        // carol's profile arrives from the relays
        known.land(Profile(carol, "carol", "Carol", null, null, null))
        advanceUntilIdle()
        assertEquals("Carol", c.state.value.ancestors[root.id]?.author?.displayName)
        // a visibility change rebuilds the ancestors from what the controller keeps
        muted.value = setOf("d".repeat(64))
        advanceUntilIdle()
        assertEquals("Carol", c.state.value.ancestors[root.id]?.author?.displayName)
    }

    @Test
    fun `a newly muted account restarts only the live list, so it does not take the newest places`() = runTest {
        val muted = MutableStateFlow<Set<String>>(emptySet())
        val feed = FakeFeed().apply { current = listOf(note('1', alice, 1000), note('2', bob, 900)) }
        val c = controller(feed, muted = muted)
        c.start(); advanceUntilIdle()
        muted.value = setOf(alice); advanceUntilIdle()
        assertEquals(2, feed.liveCalls, "the live query is read again without the muted account")
        assertEquals(1, feed.refreshCalls, "no new relay round")
    }

    @Test
    fun `a list feed reads its members only and builds conversation blocks like the home feed`() = runTest {
        val root = note('r', alice, 900)
        val reply = note('s', bob, 1000, replyTo = root.id, root = root.id)
        val feed = FakeFeed().apply { current = listOf(reply, note('t', "c".repeat(64), 1100)) }
        val conversations = FakeConversations(mapOf(reply.id to Chain(listOf(Slot.Shown(root)), cut = false)))
        val source = FeedSource.Authors(listOf(bob))
        val c = FeedController(feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(), conversations = conversations, source = source)
        c.start()
        advanceUntilIdle()
        assertEquals(listOf(reply.id), c.state.value.notes.map { it.note.id })
        assertEquals(listOf(root.id), c.state.value.chains[reply.id]?.ancestors)
        assertTrue(feed.seenSources.isNotEmpty() && feed.seenSources.all { it == source })
    }

    @Test
    fun `a paused feed ignores live updates, a resumed one restarts live and refreshes`() = runTest {
        val first = note('1', alice, 1000)
        val feed = FakeFeed().apply { current = listOf(first) }
        val c = FeedController(feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start()
        advanceUntilIdle()
        val lives = feed.liveCalls
        val refreshes = feed.refreshCalls
        c.setLive(false)
        feed.liveUpdates.emit(listOf(note('2', alice, 2000), first))
        advanceUntilIdle()
        assertEquals(listOf(first.id), c.state.value.notes.map { it.note.id })
        c.setLive(true)
        advanceUntilIdle()
        assertEquals(lives + 1, feed.liveCalls)
        assertEquals(refreshes + 1, feed.refreshCalls)
        c.setLive(true)
        advanceUntilIdle()
        assertEquals(lives + 1, feed.liveCalls) // already live: nothing restarts
    }
}
