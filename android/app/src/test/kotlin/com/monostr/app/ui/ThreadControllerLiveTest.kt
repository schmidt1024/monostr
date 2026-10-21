package com.monostr.app.ui

import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.compose.ComposeController
import com.monostr.app.ui.thread.ThreadController
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.Slot
import com.monostr.nostr.repo.ThreadRepository
import com.monostr.nostr.repo.ThreadView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec perf2 C6: a busy thread rebuilds the screen per state, not per emission, and watches receipts per slice. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadControllerLiveTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val root = note('1', alice, 100)
    private val focused = note('2', bob, 200, replyTo = root.id)

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun reply(i: Int) = Note(i.toString().padStart(64, '0'), alice, "r$i", 300L + i, 1, root.id, focused.id, null, emptyList())
    private fun view(ids: Iterable<Int>) = ThreadView(root, focused, ids.map(::reply), ancestors = listOf(Slot.Shown(root)))

    @Test
    fun `fifty quick views end on the last one with far fewer rebuilds`() = runTest {
        val views = flow { for (n in 1..50) { emit(view(1..n)); delay(1) } }
        val slow = FakeProfiles().apply { getAllDelayMs = 10 }
        val decorations = TipDecorations(FakeTips(), FakePendingTips(), FakeProfiles(), FakePublish(), eager()) { 5_000 }
        val c = ThreadController(focused.id, FakeThreads(emptyMap(), flows = mapOf(focused.id to views)), slow, FakePublish(), decorations, BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start(); advanceUntilIdle()
        assertEquals(50, c.state.value.items.count { it.note.content.startsWith("r") })
        // every rebuild reads the authors once; the focused note's author is in each read
        val rebuilds = slow.getAllCalls.count { bob in it }
        assertTrue(rebuilds <= 10, "rebuilds: $rebuilds")
    }

    @Test
    fun `receipts are watched per slice of new notes, a view that drops a reply restarts nothing`() = runTest {
        val tips = FakeTips()
        val decorations = TipDecorations(tips, FakePendingTips(), FakeProfiles(), FakePublish(), eager()) { 5_000 }
        val views = MutableSharedFlow<ThreadView>(extraBufferCapacity = 8)
        val c = ThreadController(focused.id, FakeThreads(emptyMap(), flows = mapOf(focused.id to views)), FakeProfiles(), FakePublish(), decorations, BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager())
        c.start(); advanceUntilIdle()
        fun slices() = tips.observed.filter { it.isNotEmpty() }.map { it.size }
        // root + focused + 150 replies
        views.tryEmit(view(1..150)); advanceUntilIdle()
        assertEquals(listOf(100, 52), slices())
        views.tryEmit(view((1..150) - 7)); advanceUntilIdle()
        assertEquals(listOf(100, 52), slices(), "a withdrawn reply restarts no receipt fetch")
        views.tryEmit(view(1..151)); advanceUntilIdle()
        assertEquals(listOf(100, 52, 53), slices(), "a new reply restarts only the last slice")
    }

    @Test
    fun `the reply target comes from the note lookup, not from the thread`() = runTest {
        val threads = object : ThreadRepository {
            override fun observe(noteId: String): Flow<ThreadView> = flow { awaitCancellation() }
            override suspend fun note(id: String) = root.takeIf { it.id == id }
        }
        val c = ComposeController(root.id, threads, FakePublish(), eager())
        c.start(); advanceUntilIdle()
        assertEquals(root.id, c.state.value.replyTo?.id)
        assertFalse(c.state.value.replyUnresolved)
    }
}
