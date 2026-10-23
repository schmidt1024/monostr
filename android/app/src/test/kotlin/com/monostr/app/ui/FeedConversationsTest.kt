package com.monostr.app.ui

import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.NoteDeletions
import com.monostr.app.ui.common.NoteVisibility
import com.monostr.app.ui.feed.ChainUi
import com.monostr.app.ui.feed.FeedController
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.Chain
import com.monostr.nostr.repo.Slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import com.monostr.nostr.model.NoteCounts
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedConversationsTest {
    private val alice = "a".repeat(64)
    private val profiles = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", "https://x/a.png", null, null)))
    private val root = note('r', author = "b".repeat(64), createdAt = 900)
    private val reply = note('x', createdAt = 1000, replyTo = root.id)

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.decorations(tips: FakeTips = FakeTips()) = TipDecorations(tips, FakePendingTips(), profiles, FakePublish(), eager()) { 5_000 }

    private fun TestScope.controller(
        feed: FakeFeed,
        conversations: FakeConversations,
        muted: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
        tips: FakeTips = FakeTips(),
        counts: FakeCounts = FakeCounts(),
    ) = FeedController(
        feed, profiles, FakePublish(), decorations(tips), BookmarkActions(FakeBookmarks(), eager()), counts, eager(),
        conversations = conversations, visibility = NoteVisibility(NoteDeletions(), muted),
    )

    @Test
    fun `a reply gets its chain and the decorated ancestors`() = runTest {
        val feed = FakeFeed().apply { current = listOf(reply) }
        val convs = FakeConversations(mapOf(reply.id to Chain(listOf(Slot.Shown(root)), cut = false)))
        val c = controller(feed, conversations = convs)
        c.start(); advanceUntilIdle()
        assertEquals(ChainUi(listOf(root.id), cut = false), c.state.value.chains[reply.id])
        assertEquals(root, c.state.value.ancestors[root.id]?.note)
    }

    @Test
    fun `a note shown as an ancestor is not shown again on its own`() = runTest {
        val feed = FakeFeed().apply { current = listOf(reply, root) } // newest first: the thread, then its first post
        val convs = FakeConversations(mapOf(reply.id to Chain(listOf(Slot.Shown(root)), cut = false)))
        val c = controller(feed, conversations = convs)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(reply.id), c.state.value.entries.map { it.note.id }, "the thread stands once, at its newest reply")
        assertEquals(listOf(reply.id, root.id), c.state.value.notes.map { it.note.id }, "the loaded page itself is unchanged")
    }

    @Test
    fun `a middle reply inside a longer chain is not shown as its own block`() = runTest {
        val middle = note('m', createdAt = 950, replyTo = root.id)
        val last = note('l', createdAt = 1200, replyTo = middle.id)
        val feed = FakeFeed().apply { current = listOf(last, middle) }
        val convs = FakeConversations(mapOf(
            last.id to Chain(listOf(Slot.Shown(root), Slot.Shown(middle)), cut = false),
            middle.id to Chain(listOf(Slot.Shown(root)), cut = false),
        ))
        val c = controller(feed, conversations = convs)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(last.id), c.state.value.entries.map { it.note.id })
    }

    @Test
    fun `when the reply goes away its first post is shown again`() = runTest {
        val muted = MutableStateFlow(emptySet<String>())
        val feed = FakeFeed().apply { current = listOf(reply, root) }
        val convs = FakeConversations(mapOf(reply.id to Chain(listOf(Slot.Shown(root)), cut = false)))
        val c = controller(feed, conversations = convs, muted = muted)
        c.start(); advanceUntilIdle()
        muted.value = setOf(reply.author)
        advanceUntilIdle()
        assertEquals(listOf(root.id), c.state.value.entries.map { it.note.id })
    }

    @Test
    fun `a repost of an ancestor stays, it says who reposted it`() = runTest {
        val rp = repostOf(root, by = alice, createdAt = 1100)
        val feed = FakeFeed().apply { current = listOf(rp, reply) }
        val convs = FakeConversations(mapOf(reply.id to Chain(listOf(Slot.Shown(root)), cut = false)))
        val c = controller(feed, conversations = convs)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(rp.id, reply.id), c.state.value.entries.map { it.note.id })
    }

    @Test
    fun `reply without chain stays single`() = runTest {
        val feed = FakeFeed().apply { current = listOf(reply) }
        val c = controller(feed, conversations = FakeConversations())
        c.start(); advanceUntilIdle()
        assertEquals(listOf(reply.id), c.state.value.notes.map { it.note.id })
        assertNull(c.state.value.chains[reply.id])
    }

    @Test
    fun `muted ancestor becomes unavailable, the reply stays`() = runTest {
        val muted = MutableStateFlow(setOf(root.author))
        val feed = FakeFeed().apply { current = listOf(reply) }
        val convs = FakeConversations(mapOf(reply.id to Chain(listOf(Slot.Shown(root)), cut = false)))
        val c = controller(feed, conversations = convs, muted = muted)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(reply.id), c.state.value.notes.map { it.note.id })
        assertEquals(listOf(root.id), c.state.value.chains[reply.id]!!.ancestors)
        assertNull(c.state.value.ancestors[root.id], "muted ancestor is not offered for display")
        // backlog 28: named as muted, one tap away
        assertEquals(root.id, c.state.value.mutedAncestors[root.id]?.note?.id)
    }

    @Test
    fun `an unmute moves the ancestor back from muted to shown`() = runTest {
        val muted = MutableStateFlow(setOf(root.author))
        val feed = FakeFeed().apply { current = listOf(reply) }
        val convs = FakeConversations(mapOf(reply.id to Chain(listOf(Slot.Shown(root)), cut = false)))
        val c = controller(feed, conversations = convs, muted = muted)
        c.start(); advanceUntilIdle()
        muted.value = emptySet(); advanceUntilIdle()
        assertEquals(root.id, c.state.value.ancestors[root.id]?.note?.id)
        assertNull(c.state.value.mutedAncestors[root.id])
    }

    @Test
    fun `unavailable slot keeps its id in the chain`() = runTest {
        val feed = FakeFeed().apply { current = listOf(reply) }
        val convs = FakeConversations(mapOf(reply.id to Chain(listOf(Slot.Unavailable(root.id)), cut = false)))
        val c = controller(feed, conversations = convs)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(root.id), c.state.value.chains[reply.id]!!.ancestors)
        assertNull(c.state.value.ancestors[root.id])
    }

    @Test
    fun `repost of a reply gets the chain of the reposted note`() = runTest {
        val rp = repostOf(reply, by = "a".repeat(64), createdAt = 1100)
        val feed = FakeFeed().apply { current = listOf(rp) }
        val convs = FakeConversations(mapOf(rp.id to Chain(listOf(Slot.Shown(root)), cut = false)))
        val c = controller(feed, conversations = convs)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(root.id), c.state.value.chains[rp.id]!!.ancestors)
    }

    @Test
    fun `loadMore resolves the chains of the older page`() = runTest {
        val older = note('y', createdAt = 500, replyTo = root.id)
        val feed = FakeFeed().apply { current = listOf(note('n', createdAt = 2000)); this.older = listOf(older) }
        val convs = FakeConversations(mapOf(older.id to Chain(listOf(Slot.Shown(root)), cut = false)))
        val c = controller(feed, conversations = convs)
        c.start(); advanceUntilIdle()
        c.loadMore(); advanceUntilIdle()
        assertEquals(listOf(root.id), c.state.value.chains[older.id]!!.ancestors)
    }

    @Test
    fun `ancestors stay tracked for receipts after a later list update`() = runTest {
        val feed = FakeFeed().apply { current = listOf(reply) }
        val convs = FakeConversations(mapOf(reply.id to Chain(listOf(Slot.Shown(root)), cut = false)))
        val tips = FakeTips()
        val c = controller(feed, conversations = convs, tips = tips)
        c.start(); advanceUntilIdle()
        feed.liveUpdates.emit(listOf(reply)); advanceUntilIdle()
        assertTrue(tips.observed.last().containsKey(root.id), "root still tracked")
    }

    @Test
    fun `a later chain keeps the counts of earlier ancestors`() = runTest {
        val older = note('y', createdAt = 500, replyTo = root.id)
        val feed = FakeFeed().apply { current = listOf(reply); this.older = listOf(older) }
        val convs = FakeConversations(mapOf(
            reply.id to Chain(listOf(Slot.Shown(root)), cut = false),
            older.id to Chain(listOf(Slot.Shown(root)), cut = false),
        ))
        val counts = FakeCounts()
        val c = controller(feed, conversations = convs, counts = counts)
        c.start(); advanceUntilIdle()
        counts.set(root.id, NoteCounts(3L, 0L, 0L))
        advanceUntilIdle()
        c.loadMore(); advanceUntilIdle()
        assertEquals(3L, c.state.value.ancestors[root.id]?.counts?.likes)
    }

    @Test
    fun `a visible reply requests counts for the notes above it too`() = runTest {
        val feed = FakeFeed().apply { current = listOf(reply) }
        val convs = FakeConversations(mapOf(reply.id to Chain(listOf(Slot.Shown(root)), cut = false)))
        val counts = FakeCounts()
        val c = controller(feed, conversations = convs, counts = counts)
        c.start(); advanceUntilIdle()
        counts.requested.clear()
        c.visible(listOf(reply.id))
        assertEquals(setOf(reply.id, root.id), counts.requested.flatten().toSet())
    }

    @Test
    fun `chain resolution holds up neither live updates nor paging, and walks a reply once while in flight`() = runTest {
        val other = note('n', createdAt = 2000)
        val older = note('y', createdAt = 500, replyTo = root.id)
        val feed = FakeFeed().apply { current = listOf(reply); this.older = listOf(older) }
        val convs = FakeConversations(mapOf(
            reply.id to Chain(listOf(Slot.Shown(root)), cut = false),
            older.id to Chain(listOf(Slot.Shown(root)), cut = false),
        )).apply { gate = CompletableDeferred() }
        val c = controller(feed, conversations = convs)
        c.start(); advanceUntilIdle()
        assertFalse(c.state.value.loading)
        feed.liveUpdates.emit(listOf(other, reply)); advanceUntilIdle()
        // the live note waits behind the "new notes" pill (newer than the newest shown); it came through all the same
        assertEquals(listOf(other.id), c.state.value.newAbove.map { it.note.id }, "live emission applied while the chain is pending")
        c.showNew(); advanceUntilIdle()
        assertEquals(listOf(other.id, reply.id), c.state.value.notes.map { it.note.id })
        assertEquals(1, convs.calls.count { reply.id in it }, "the reply is walked once while in flight: ${convs.calls}")
        c.loadMore(); advanceUntilIdle()
        assertFalse(c.state.value.loadingMore)
        assertTrue(c.state.value.notes.any { it.note.id == older.id })
        assertTrue(c.state.value.chains.isEmpty())
        convs.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(root.id), c.state.value.chains[reply.id]!!.ancestors)
        assertEquals(listOf(root.id), c.state.value.chains[older.id]!!.ancestors)
    }

    @Test
    fun `a refresh retries chains with unavailable notes`() = runTest {
        val feed = FakeFeed().apply { current = listOf(reply) }
        val convs = FakeConversations(mapOf(reply.id to Chain(listOf(Slot.Unavailable(root.id)), cut = false)))
        val c = controller(feed, conversations = convs)
        c.start(); advanceUntilIdle()
        assertNull(c.state.value.ancestors[root.id])
        convs.chains = mapOf(reply.id to Chain(listOf(Slot.Shown(root)), cut = false))
        c.refresh(); advanceUntilIdle()
        assertEquals(root, c.state.value.ancestors[root.id]?.note)
    }

    @Test
    fun `a refresh does not retry a muted ancestor`() = runTest {
        val feed = FakeFeed().apply { current = listOf(reply) }
        val convs = FakeConversations(mapOf(reply.id to Chain(listOf(Slot.Shown(root)), cut = false)))
        val c = controller(feed, conversations = convs, muted = MutableStateFlow(setOf(root.author)))
        c.start(); advanceUntilIdle()
        val before = convs.calls.size
        c.refresh(); advanceUntilIdle()
        assertEquals(before, convs.calls.size)
    }
}
