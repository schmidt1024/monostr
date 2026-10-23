package com.monostr.app.ui

import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.thread.ThreadController
import com.monostr.app.ui.thread.ThreadRow
import com.monostr.app.ui.thread.visibleRows
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.repo.Slot
import com.monostr.nostr.repo.ThreadRepository
import com.monostr.nostr.repo.ThreadView
import com.monostr.nostr.repo.Wanted
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

/** The thread page's rows (spec 2026-10-16 §4.2): ancestors, the focused note, reply strands. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadStrandsTest {
    private val root = note('r', author = "b".repeat(64), createdAt = 900)
    private val focused = note('f', createdAt = 1000, replyTo = root.id, root = root.id)
    private val r1 = note('1', author = "c".repeat(64), createdAt = 1001, replyTo = focused.id, root = root.id)
    private val c1 = note('2', author = "c".repeat(64), createdAt = 1002, replyTo = r1.id, root = root.id)
    private val c2 = note('3', author = "d".repeat(64), createdAt = 1003, replyTo = r1.id, root = root.id)

    private fun view() = ThreadView(root, focused, listOf(focused, r1, c1, c2), ancestors = listOf(Slot.Shown(root)))

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.threadController(noteId: String, threads: ThreadRepository) = ThreadController(
        noteId, threads, FakeProfiles(), FakePublish(),
        TipDecorations(FakeTips(), FakePendingTips(), FakeProfiles(), FakePublish(), eager()) { 5_000 },
        BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(),
    )

    @Test
    fun `rows are ancestors, focused, strand with one child and more`() = runTest {
        val c = threadController(focused.id, FakeThreads(mapOf(focused.id to view())))
        c.start(); advanceUntilIdle()
        assertEquals(
            listOf(
                ThreadRow.Ancestor(root.id, lineAbove = false),
                ThreadRow.Focused(focused.id, lineAbove = true),
                ThreadRow.Reply(r1.id, lineBelow = true, keepWhenHidden = true, orphan = false),
                ThreadRow.Child(c1.id),
                ThreadRow.More("more-${r1.id}", open = r1.id),
            ),
            c.state.value.rows,
        )
        assertEquals(setOf(root.id, focused.id, r1.id, c1.id), c.state.value.items.map { it.note.id }.toSet())
    }

    @Test
    fun `unavailable ancestor keeps its row`() = runTest {
        val v = view().copy(ancestors = listOf(Slot.Unavailable(root.id)), root = null)
        val c = threadController(focused.id, FakeThreads(mapOf(focused.id to v)))
        c.start(); advanceUntilIdle()
        assertEquals(ThreadRow.Ancestor(root.id, lineAbove = false), c.state.value.rows.first())
        assertTrue(c.state.value.items.none { it.note.id == root.id })
    }

    @Test
    fun `pending ancestor is a pending row`() = runTest {
        val v = view().copy(ancestors = listOf(Slot.Pending(root.id, Wanted(root.id))), root = null)
        val c = threadController(focused.id, FakeThreads(mapOf(focused.id to v)))
        c.start(); advanceUntilIdle()
        assertEquals(ThreadRow.Ancestor(root.id, lineAbove = false, pending = true), c.state.value.rows.first())
        assertTrue(c.state.value.items.none { it.note.id == root.id })
    }

    @Test
    fun `the root stays on top when the ancestor walk came back empty`() = runTest {
        val v = view().copy(ancestors = emptyList())
        val c = threadController(focused.id, FakeThreads(mapOf(focused.id to v)))
        c.start(); advanceUntilIdle()
        assertEquals(
            listOf(ThreadRow.Ancestor(root.id, lineAbove = false), ThreadRow.Focused(focused.id, lineAbove = true)),
            c.state.value.rows.take(2),
        )
        assertTrue(c.state.value.items.any { it.note.id == root.id })
    }

    @Test
    fun `a reply whose parent is unknown keeps its replying-to line`() = runTest {
        val orphan = note('o', author = "c".repeat(64), createdAt = 1001, replyTo = "9".repeat(64), root = root.id)
        val v = ThreadView(root, root, listOf(root, orphan))
        val c = threadController(root.id, FakeThreads(mapOf(root.id to v)))
        c.start(); advanceUntilIdle()
        assertEquals(
            listOf(ThreadRow.Focused(root.id, lineAbove = false), ThreadRow.Reply(orphan.id, lineBelow = false, keepWhenHidden = false, orphan = true)),
            c.state.value.rows,
        )
    }

    private val strandRows = listOf(
        ThreadRow.Focused("f", lineAbove = false),
        ThreadRow.Reply("r", lineBelow = true, keepWhenHidden = true, orphan = false),
        ThreadRow.Child("c"),
        ThreadRow.More("more-r", open = "r"),
        ThreadRow.Reply("s", lineBelow = false, keepWhenHidden = false, orphan = false),
        ThreadRow.More("more-s", open = "s"),
    )

    @Test
    fun `a hidden reply with a shown child stays as unavailable`() {
        assertEquals(strandRows, visibleRows(strandRows, setOf("f", "c", "s")))
    }

    @Test
    fun `a hidden child leaves, its reply stays`() {
        assertEquals(strandRows - ThreadRow.Child("c"), visibleRows(strandRows, setOf("f", "r", "s")))
    }

    @Test
    fun `a hidden reply with a hidden child leaves with its show replies row`() {
        assertEquals(
            listOf(strandRows[0], strandRows[4], strandRows[5]),
            visibleRows(strandRows, setOf("f", "s")),
        )
    }

    @Test
    fun `a hidden reply without a child leaves with its show replies row`() {
        assertEquals(strandRows.take(4), visibleRows(strandRows, setOf("f", "r", "c")))
    }

    @Test
    fun `a hidden ancestor keeps its row`() {
        val rows = listOf(ThreadRow.Ancestor("a", lineAbove = false), ThreadRow.Focused("f", lineAbove = true))
        assertEquals(rows, visibleRows(rows, setOf("f")))
    }
}
