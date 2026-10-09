package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.ui.bookmarks.BookmarksController
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.NoteDeletions
import com.monostr.app.ui.common.NoteVisibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.BookmarkOutcome
import com.monostr.nostr.repo.ListOutcome
import com.monostr.nostr.repo.MuteRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksControllerTest {
    private val alice = "a".repeat(64)
    private val profiles = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", null, null, null)))
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.controller(repo: FakeBookmarks, counts: FakeCounts = FakeCounts(), muted: StateFlow<Set<String>> = MutableStateFlow(emptySet()), mute: MuteRepository? = null): BookmarksController {
        val scope = eager()
        val publish = FakePublish()
        val deletions = NoteDeletions()
        return BookmarksController(
            repo, BookmarkActions(repo, scope), profiles, publish, TipDecorations(FakeTips(), FakePendingTips(), profiles, publish, scope) { 5_000 }, counts, scope,
            deletions = deletions, visibility = NoteVisibility(deletions, muted), mute = mute,
        )
    }

    @Test
    fun `a muted account's bookmarked note shows as not found, the entry itself stays`() = runTest {
        val bob = "b".repeat(64)
        val n1 = note('1', alice, 1000); val n2 = note('2', bob, 900)
        val repo = FakeBookmarks(initial = listOf(n1.id, n2.id)).apply { notes = mapOf(n1.id to n1, n2.id to n2) }
        val muted = MutableStateFlow(setOf(alice))
        val c = controller(repo, muted = muted)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(n1.id, n2.id), c.state.value.entries.map { it.id })
        assertNull(c.state.value.entries[0].note)
        assertEquals(n2.id, c.state.value.entries[1].note?.note?.id)
        muted.value = setOf(alice, bob); advanceUntilIdle()
        assertEquals(listOf(n1.id, n2.id), c.state.value.entries.map { it.id })
        assertNull(c.state.value.entries[1].note)
    }

    @Test
    fun `a mute whose publish fails resolves the rows again, so the note comes back`() = runTest {
        val n1 = note('1', alice, 1000)
        val repo = FakeBookmarks(initial = listOf(n1.id)).apply { notes = mapOf(n1.id to n1) }
        val mute = FakeMute(outcome = ListOutcome.PublishFailed).apply { gate = CompletableDeferred() }
        val c = controller(repo, muted = mute.muted, mute = mute)
        c.start(); advanceUntilIdle()
        assertEquals(n1.id, c.state.value.entries.single().note?.note?.id)
        c.mute(n1); advanceUntilIdle()
        assertNull(c.state.value.entries.single().note) // hidden at once
        mute.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(uiText(R.string.error_send_failed), c.state.value.message?.text)
        assertEquals(n1.id, c.state.value.entries.single().note?.note?.id)
    }

    @Test
    fun `entries resolve in list order, unknown ids are placeholders, remove drops the row after success`() = runTest {
        val n1 = note('1', alice, 1000)
        val repo = FakeBookmarks(initial = listOf(n1.id, "9".repeat(64))).apply { notes = mapOf(n1.id to n1) }
        val c = controller(repo)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(true), repo.loadCalls, "the bookmarks screen may prompt the signer")
        assertFalse(c.state.value.loading)
        assertEquals(listOf(n1.id, "9".repeat(64)), c.state.value.entries.map { it.id })
        assertEquals("Alice", c.state.value.entries[0].note?.author?.shownName)
        assertNull(c.state.value.entries[1].note)
        assertTrue(c.state.value.entries[0].note!!.bookmarked)
        c.remove(n1); advanceUntilIdle()
        assertEquals(listOf("9".repeat(64)), c.state.value.entries.map { it.id })
        repo.outcome = BookmarkOutcome.PublishFailed
        c.remove(note('9', alice)); advanceUntilIdle()
        assertEquals(listOf("9".repeat(64)), c.state.value.entries.map { it.id })
        assertEquals(uiText(R.string.bookmark_failed), c.state.value.message?.text)
    }

    @Test
    fun `a removed row stays while the publish is in flight and after it failed`() = runTest {
        val n1 = note('1', alice, 1000); val n2 = note('2', alice, 900)
        val repo = FakeBookmarks(initial = listOf(n1.id, n2.id), outcome = BookmarkOutcome.PublishFailed).apply { notes = mapOf(n1.id to n1, n2.id to n2) }
        val c = controller(repo)
        c.start(); advanceUntilIdle()
        repo.gate = kotlinx.coroutines.CompletableDeferred()
        c.remove(n1); advanceUntilIdle()
        assertEquals(listOf(n2.id), repo.state.value.ids, "the repository removed optimistically")
        assertEquals(listOf(n1.id, n2.id), c.state.value.entries.map { it.id }, "the row stays until the outcome")
        assertFalse(c.state.value.entries[0].note!!.bookmarked, "the kept row shows the pending removal at once")
        assertTrue(c.state.value.entries[1].note!!.bookmarked)
        repo.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(n1.id, n2.id), c.state.value.entries.map { it.id })
        assertTrue(c.state.value.entries[0].note!!.bookmarked, "after the failed publish the row is bookmarked again")
        assertEquals(uiText(R.string.bookmark_failed), c.state.value.message?.text)
    }

    @Test
    fun `a removed row disappears once the publish succeeded`() = runTest {
        val n1 = note('1', alice, 1000); val n2 = note('2', alice, 900)
        val repo = FakeBookmarks(initial = listOf(n1.id, n2.id)).apply { notes = mapOf(n1.id to n1, n2.id to n2) }
        val c = controller(repo)
        c.start(); advanceUntilIdle()
        repo.gate = kotlinx.coroutines.CompletableDeferred()
        c.remove(n2); advanceUntilIdle()
        assertEquals(listOf(n1.id, n2.id), c.state.value.entries.map { it.id }, "the row stays until the outcome")
        repo.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(n1.id), c.state.value.entries.map { it.id })
        assertNull(c.state.value.message)
    }

    @Test
    fun `visible() requests resolved notes only`() = runTest {
        val n1 = note('1', alice, 1000)
        val unresolved = "9".repeat(64)
        val repo = FakeBookmarks(initial = listOf(n1.id, unresolved)).apply { notes = mapOf(n1.id to n1) }
        val counts = FakeCounts()
        val c = controller(repo, counts)
        c.start(); advanceUntilIdle()
        c.visible(listOf(n1.id, unresolved))
        assertEquals(listOf(listOf(n1.id)), counts.requested)
    }
}
