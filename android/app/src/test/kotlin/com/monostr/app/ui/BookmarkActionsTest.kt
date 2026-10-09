package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.feed.NoteUi
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.BookmarkOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.update
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
class BookmarkActionsTest {
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `flags follow the repository, ok is silent, failures carry a message`() = runTest {
        val repo = FakeBookmarks(initial = listOf("1".repeat(64)))
        val actions = BookmarkActions(repo, eager())
        val n1 = NoteUi(note('1'), Profile.empty("a".repeat(64)), Profile.empty("a".repeat(64)))
        val n2 = NoteUi(note('2'), Profile.empty("a".repeat(64)), Profile.empty("a".repeat(64)))
        assertTrue(actions.flags(n1).bookmarked); assertFalse(actions.flags(n2).bookmarked)
        val results = ArrayList<UiText?>()
        actions.toggle(n2.note) { results += it }; advanceUntilIdle()
        assertEquals(listOf<UiText?>(null), results)
        assertTrue(actions.flags(n2).bookmarked)
        repo.outcome = BookmarkOutcome.PublishFailed
        actions.toggle(n1.note) { results += it }; advanceUntilIdle()
        assertEquals(uiText(R.string.bookmark_failed), results.last())
        repo.outcome = BookmarkOutcome.Unsupported
        actions.toggle(n1.note) { results += it }; advanceUntilIdle()
        assertEquals(uiText(R.string.bookmark_nip44_unsupported), results.last())
        repo.outcome = BookmarkOutcome.Rejected
        actions.toggle(n1.note) { results += it }; advanceUntilIdle()
        assertNull(results.last())
        assertTrue(actions.flags(n2).bookmarkEnabled)
        repo._state.update { it.copy(writable = false) }
        assertFalse(actions.flags(n2).bookmarkEnabled)
    }

    @Test
    fun `a toggle survives the screen that started it`() = runTest {
        val repo = FakeBookmarks().apply { gate = CompletableDeferred() }
        val screen = eager()
        val actions = BookmarkActions(repo, screen, background = eager())
        var delivered = false
        var result: UiText? = uiText(R.string.bookmark_failed)
        actions.toggle(note('1')) { delivered = true; result = it }
        advanceUntilIdle()
        screen.cancel() // the user left before the relays answered
        repo.gate!!.complete(Unit); advanceUntilIdle()
        assertTrue(delivered)
        assertNull(result) // Ok is silent
        assertEquals(listOf(note('1').id), repo.state.value.ids)
    }
}
