package com.monostr.app.ui

import com.monostr.app.data.dm.DmMessage
import com.monostr.app.data.dm.DmStatus
import com.monostr.app.data.dm.InMemoryDmStore
import com.monostr.app.dm.DmSyncState
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.dm.ChatController
import com.monostr.app.ui.dm.ConversationsController
import com.monostr.app.ui.feed.FeedController
import com.monostr.app.ui.thread.ThreadController
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.NoteCounts
import com.monostr.nostr.repo.FeedSource
import com.monostr.nostr.repo.Slot
import com.monostr.nostr.repo.ThreadView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Spec perf2 §7.1: the wave 2 marks on the 0.23.0 behaviour, each once per screen (the feed: once per start). */
@OptIn(ExperimentalCoroutinesApi::class)
class Wave2MarksTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val profiles = FakeProfiles()

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.decorations() = TipDecorations(FakeTips(), FakePendingTips(), profiles, FakePublish(), eager()) { 5_000 }
    private fun List<String>.marks(name: String) = count { it.endsWith(" $name") }

    @Test
    fun `the home feed marks its first fresh page and the first count of a visible note`() = runTest {
        val lines = withPerfLines {
            val n1 = note('1', alice, 1000)
            val feed = FakeFeed().apply { current = listOf(n1); refreshGate = CompletableDeferred() }
            val counts = FakeCounts()
            val c = FeedController(feed, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), counts, eager())
            c.start(); advanceUntilIdle()
            assertEquals(0, marks("feed fresh page"), "the local page is no fresh page")
            feed.refreshGate!!.complete(Unit); advanceUntilIdle()
            assertEquals(1, marks("feed fresh page"))
            counts.set("9".repeat(64), NoteCounts(1, 0, 0)); advanceUntilIdle()
            assertEquals(0, marks("feed first counts"), "a count of a note nobody saw")
            c.visible(listOf(n1.id))
            counts.set(n1.id, NoteCounts(2, 0, 0)); advanceUntilIdle()
            counts.set(n1.id, NoteCounts(3, 0, 0)); advanceUntilIdle()
            c.refresh(); advanceUntilIdle()
        }
        assertEquals(1, lines.marks("feed first counts"))
        assertEquals(1, lines.marks("feed fresh page"), "a pull-to-refresh is no first fresh page")
    }

    @Test
    fun `a list tab marks nothing of the home feed`() = runTest {
        val lines = withPerfLines {
            val n1 = note('1', alice, 1000)
            val counts = FakeCounts()
            val c = FeedController(
                FakeFeed().apply { current = listOf(n1) }, profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), counts, eager(),
                source = FeedSource.Authors(listOf(alice)),
            )
            c.start(); advanceUntilIdle()
            c.visible(listOf(n1.id))
            counts.set(n1.id, NoteCounts(2, 0, 0)); advanceUntilIdle()
        }
        assertEquals(0, lines.marks("feed fresh page"))
        assertEquals(0, lines.marks("feed first counts"))
    }

    @Test
    fun `a thread marks its ancestors, its relay replies and the focused note's counts once each`() = runTest {
        val root = note('1', alice, 100)
        val reply = note('2', bob, 200, replyTo = root.id)
        val views = MutableSharedFlow<ThreadView>(extraBufferCapacity = 8)
        val counts = FakeCounts()
        val lines = withPerfLines {
            val c = ThreadController(reply.id, FakeThreads(emptyMap(), flows = mapOf(reply.id to views)), profiles, FakePublish(), decorations(), BookmarkActions(FakeBookmarks(), eager()), counts, eager())
            c.start()
            views.tryEmit(ThreadView(root, reply, emptyList())); advanceUntilIdle()
            assertEquals(0, marks("thread ancestors shown") + marks("thread relay replies shown") + marks("thread focused counts"))
            views.tryEmit(ThreadView(root, reply, emptyList(), ancestors = listOf(Slot.Shown(root)))); advanceUntilIdle()
            assertEquals(1, marks("thread ancestors shown"))
            assertEquals(0, marks("thread relay replies shown"))
            views.tryEmit(ThreadView(root, reply, emptyList(), ancestors = listOf(Slot.Shown(root)), repliesFetched = true)); advanceUntilIdle()
            counts.set(root.id, NoteCounts(4, 0, 0)); advanceUntilIdle()
            assertEquals(0, marks("thread focused counts"), "the root is not the focused note")
            counts.set(reply.id, NoteCounts(1, 0, 0)); advanceUntilIdle()
            views.tryEmit(ThreadView(root, reply, listOf(note('3', alice, 300, replyTo = reply.id, root = root.id)), ancestors = listOf(Slot.Shown(root)), repliesFetched = true)); advanceUntilIdle()
            counts.set(reply.id, NoteCounts(2, 0, 0)); advanceUntilIdle()
        }
        assertEquals(1, lines.marks("thread ancestors shown"))
        assertEquals(1, lines.marks("thread relay replies shown"))
        assertEquals(1, lines.marks("thread focused counts"))
    }

    @Test
    fun `the messages tab and a chat mark their first loaded state once`() = runTest {
        val store = InMemoryDmStore()
        store.upsert(DmMessage("1", bob, false, "hi", 100, 100, DmStatus.RECEIVED, "w1"))
        val lines = withPerfLines {
            val list = ConversationsController(store, profiles, FakeFeed(), MutableStateFlow(DmSyncState()), store.pendingCount(), {}, { 0 }, eager())
            list.start(); advanceUntilIdle()
            val chat = ChatController(bob, alice, store, profiles, { _, _ -> DmStatus.SENT }, { DmStatus.SENT }, eager(), { 1_000 }, work = UnconfinedTestDispatcher(testScheduler))
            chat.start(); advanceUntilIdle()
            chat.send("again"); advanceUntilIdle()
        }
        assertEquals(1, lines.marks("dm list shown"))
        assertEquals(1, lines.marks("chat first messages"))
    }
}
