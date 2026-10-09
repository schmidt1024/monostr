package com.monostr.app.ui
import com.monostr.app.ui.common.uiText
import com.monostr.app.R

import com.monostr.app.ui.common.NoteActions
import com.monostr.app.ui.common.NoteDeletions
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.feed.NoteUi
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteActionsTest {
    private val alice = "a".repeat(64)
    private val n = note('1', alice)
    private val ui = NoteUi(n, Profile.empty(alice), Profile.empty(alice))

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `success sets the flag and reports the send`() = runTest {
        val actions = NoteActions(FakePublish(), eager())
        val messages = ArrayList<UiText>()
        actions.like(n) { messages += it }
        actions.repost(n) { messages += it }
        advanceUntilIdle()
        assertEquals(listOf(uiText(R.string.action_like_sent), uiText(R.string.action_repost_sent)), messages)
        assertTrue(actions.flags(ui).liked)
        assertTrue(actions.flags(ui).reposted)
    }

    @Test
    fun `no relay keeps the flag and says the event is held locally`() = runTest {
        val actions = NoteActions(FakePublish(relaysOk = false), eager())
        val messages = ArrayList<UiText>()
        actions.like(n) { messages += it }
        advanceUntilIdle()
        assertEquals(listOf(uiText(R.string.action_no_relay_local)), messages)
        assertTrue(actions.flags(ui).liked)
    }

    @Test
    fun `failure reverts the flag and reports a German message`() = runTest {
        val actions = NoteActions(FakePublish(fail = true), eager())
        val messages = ArrayList<UiText>()
        actions.repost(n) { messages += it }
        advanceUntilIdle()
        assertEquals(listOf(uiText(R.string.error_send_failed)), messages)
        assertFalse(actions.flags(ui).reposted)
    }

    @Test
    fun `like bumps the count at once and reverts on failure`() = runTest {
        val counts = FakeCounts()
        val ok = NoteActions(FakePublish(), eager(), counts)
        ok.like(note('a')) {}
        advanceUntilIdle()
        assertEquals(listOf(Triple("a".repeat(64), 7, 1L)), counts.bumps)
        val failing = NoteActions(FakePublish(fail = true), eager(), counts)
        failing.repost(note('b')) {}
        advanceUntilIdle()
        assertEquals(listOf(Triple("b".repeat(64), 6, 1L), Triple("b".repeat(64), 6, -1L)), counts.bumps.drop(1))
    }

    @Test
    fun `an accepted deletion request marks the note deleted and says so`() = runTest {
        val publish = FakePublish()
        val deletions = NoteDeletions()
        val actions = NoteActions(publish, eager(), deletions = deletions)
        actions.delete(n)
        advanceUntilIdle()
        assertEquals(listOf("delete:${n.id.take(4)}"), publish.calls)
        assertEquals(setOf(n.id), deletions.deleted.value)
        assertTrue(deletions.pending.value.isEmpty())
        assertEquals(listOf(uiText(R.string.note_deleted)), deletions.messages.value)
    }

    @Test
    fun `a refused or unsent request changes nothing`() = runTest {
        for (publish in listOf(FakePublish(relaysOk = false), FakePublish(fail = true))) {
            val deletions = NoteDeletions()
            val actions = NoteActions(publish, eager(), deletions = deletions)
            actions.delete(n)
            advanceUntilIdle()
            assertTrue(deletions.deleted.value.isEmpty())
            assertTrue(deletions.pending.value.isEmpty()) // the menu entry is free again
            assertEquals(listOf(uiText(R.string.error_send_failed)), deletions.messages.value)
        }
    }

    @Test
    fun `while a request runs the note is flagged and a second request is ignored`() = runTest {
        val publish = FakePublish().apply { gate = CompletableDeferred() }
        val actions = NoteActions(publish, eager())
        actions.delete(n)
        actions.delete(n)
        assertTrue(actions.flags(ui).deleting)
        publish.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, publish.calls.size)
        assertFalse(actions.flags(ui).deleting)
    }

    @Test
    fun `a deletion request outlives the screen that started it`() = runTest {
        val publish = FakePublish().apply { gate = CompletableDeferred() }
        val screen = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val background = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val deletions = NoteDeletions(background = background)
        val ran = ArrayList<String>()
        val actions = NoteActions(publish, screen, deletions = deletions, afterDelete = { ran += it.id; null })
        actions.delete(n)
        screen.cancel() // the user leaves the screen
        publish.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(n.id), deletions.deleted.value)
        assertTrue(deletions.pending.value.isEmpty())
        assertEquals(listOf(n.id), ran)
        background.cancel()
    }

    @Test
    fun `the message of a deletion reaches the session also when the screen that started it is gone`() = runTest {
        val publish = FakePublish(relaysOk = false).apply { gate = CompletableDeferred() }
        val screen = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val background = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val deletions = NoteDeletions(background = background)
        NoteActions(publish, screen, deletions = deletions).delete(n)
        screen.cancel()
        publish.gate!!.complete(Unit)
        advanceUntilIdle()
        // the refusal waits in the session for whatever screen is shown next
        assertEquals(listOf(uiText(R.string.error_send_failed)), deletions.messages.value)
        background.cancel()
    }

    @Test
    fun `consume removes one message and leaves an equal later one`() {
        val deletions = NoteDeletions()
        val first = uiText(R.string.note_deleted)
        deletions.post(first)
        deletions.post(uiText(R.string.error_send_failed))
        deletions.post(uiText(R.string.note_deleted))
        deletions.consume(first)
        assertEquals(listOf(uiText(R.string.error_send_failed), uiText(R.string.note_deleted)), deletions.messages.value)
        deletions.consume(uiText(R.string.note_deleted))
        assertEquals(listOf(uiText(R.string.error_send_failed)), deletions.messages.value)
    }

    @Test
    fun `a failing follow-up neither crashes nor hides the success message`() = runTest {
        val deletions = NoteDeletions()
        val actions = NoteActions(FakePublish(), eager(), deletions = deletions, afterDelete = { throw IllegalStateException("x") })
        actions.delete(n)
        advanceUntilIdle()
        assertEquals(listOf(uiText(R.string.note_deleted)), deletions.messages.value)
    }

    @Test
    fun `what follows the deletion runs only after success and its message comes second`() = runTest {
        val ran = ArrayList<String>()
        val deletions = NoteDeletions()
        val ok = NoteActions(FakePublish(), eager(), deletions = deletions, afterDelete = { ran += it.id; uiText(R.string.error_send_failed) })
        ok.delete(n)
        advanceUntilIdle()
        assertEquals(listOf(n.id), ran)
        assertEquals(listOf(uiText(R.string.note_deleted), uiText(R.string.error_send_failed)), deletions.messages.value)

        val failed = NoteActions(FakePublish(relaysOk = false), eager(), afterDelete = { ran += "never"; null })
        failed.delete(n)
        advanceUntilIdle()
        assertEquals(listOf(n.id), ran)
    }
}
