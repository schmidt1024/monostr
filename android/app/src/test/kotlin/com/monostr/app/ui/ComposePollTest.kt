package com.monostr.app.ui

import com.monostr.app.ui.compose.ComposeController
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.PollRepository
import com.monostr.nostr.model.PollVote
import com.monostr.nostr.PublishResult
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

/** Polls task 6: the poll tool of the plain new-note composer. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposePollTest {
    private class FakePolls(val relays: List<String> = listOf("wss://r")) : PollRepository {
        val created = ArrayList<Triple<String, List<String>, Long>>()
        val noPreviews = ArrayList<Boolean>()
        override suspend fun votes(poll: Note): List<PollVote> = emptyList()
        override suspend fun vote(poll: Note, optionIds: List<String>) = error("not used")
        override suspend fun create(text: String, choices: List<String>, endsAt: Long, noPreview: Boolean): PublishResult {
            created += Triple(text, choices, endsAt)
            noPreviews += noPreview
            return PublishResult("p".repeat(64), 1068, relays, emptyMap())
        }
    }

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.controller(polls: FakePolls = FakePolls(), publish: FakePublish = FakePublish(), uploader: FakeMediaUploader? = FakeMediaUploader(), now: () -> Long = { 1_000L }) =
        ComposeController(null, FakeThreads(emptyMap()), publish, eager(), uploader = uploader, now = now, polls = polls)

    @Test
    fun `a poll disables media and media disables the poll`() = runTest {
        val c = controller()
        assertTrue(c.state.value.canAddPoll)
        c.addPoll()
        assertTrue(c.state.value.poll != null)
        assertFalse(c.state.value.canAttachPictures)
        assertFalse(c.state.value.canAttachVideo)
        assertFalse(c.state.value.canAddPoll)

        val d = controller()
        d.attach(listOf("content://p/1")); advanceUntilIdle()
        assertFalse(d.state.value.canAddPoll)
        d.addPoll()
        assertNull(d.state.value.poll)
    }

    @Test
    fun `send with a poll creates a 1068 with endsAt = now + seconds`() = runTest {
        val polls = FakePolls(); val publish = FakePublish()
        var clock = 1_000L
        val c = controller(polls, publish, now = { clock })
        c.addPoll()
        c.setChoice(0, "  Cake "); c.setChoice(1, "Feather")
        c.setLength(0, 1, 30)
        clock = 2_000L // the moment of sending counts, not the moment of editing
        c.send("Which wallet?"); advanceUntilIdle()
        assertEquals(listOf(Triple("Which wallet?", listOf("Cake", "Feather"), 2_000L + 5_400L)), polls.created)
        assertTrue(publish.calls.isEmpty())
        assertTrue(c.state.value.done)
    }

    @Test
    fun `a poll whose link card was put away says so`() = runTest {
        val polls = FakePolls()
        val c = controller(polls)
        c.addPoll(); c.setChoice(0, "a"); c.setChoice(1, "b")
        c.send("See https://example.com", noPreview = true); advanceUntilIdle()
        assertEquals(listOf(true), polls.noPreviews)
    }

    @Test
    fun `a stored draft with pictures does not land on a poll begun meanwhile`() = runTest {
        val me = "e".repeat(64)
        val pic = com.monostr.app.media.UploadedMedia("https://media.test", "https://media.test/1.jpg", "image/jpeg", "1".padStart(64, '0'), 1000, 400, 300, null, fresh = true)
        val drafts = FakeDraftStore().apply {
            put(me, com.monostr.app.data.DraftTargets.NOTE, com.monostr.app.data.Draft("old", attachments = listOf(pic), updatedAt = 900))
            loadGate = kotlinx.coroutines.CompletableDeferred()
        }
        val c = ComposeController(null, FakeThreads(emptyMap()), FakePublish(), eager(), uploader = FakeMediaUploader(), drafts = drafts, account = me, background = eager(), now = { 1_000L }, polls = FakePolls())
        c.start()
        c.addPoll()
        drafts.loadGate!!.complete(Unit); advanceUntilIdle()
        assertTrue(c.state.value.poll != null)
        assertTrue(c.state.value.attachments.isEmpty())
    }

    @Test
    fun `a poll stored for a resend can no longer be changed or removed`() = runTest {
        val polls = FakePolls(relays = emptyList())
        val c = controller(polls)
        c.addPoll(); c.setChoice(0, "a"); c.setChoice(1, "b")
        c.send("Q"); advanceUntilIdle()
        assertTrue(c.state.value.pendingResend)
        val before = c.state.value.poll
        c.setChoice(0, "changed"); c.addChoice(); c.setLength(0, 2, 0); c.removePoll()
        assertEquals(before, c.state.value.poll)
    }

    @Test
    fun `an invalid poll is not sent`() = runTest {
        val polls = FakePolls()
        val c = controller(polls)
        c.addPoll(); c.setChoice(0, "only one")
        c.send("Q"); advanceUntilIdle()
        assertTrue(polls.created.isEmpty())
        assertFalse(c.state.value.done)
        assertTrue(c.state.value.error != null)
    }

    @Test
    fun `choices are limited to four and 25 characters`() = runTest {
        val c = controller()
        c.addPoll(); c.addChoice(); c.addChoice(); c.addChoice()
        assertEquals(4, c.state.value.poll!!.choices.size)
        c.setChoice(0, "x".repeat(40))
        assertEquals(25, c.state.value.poll!!.choices[0].length)
        c.setLength(7, 3, 3)
        assertEquals(0, c.state.value.poll!!.hours)
    }

    @Test
    fun `remove poll restores the plain note`() = runTest {
        val publish = FakePublish(); val polls = FakePolls()
        val c = controller(polls, publish)
        c.addPoll(); c.removePoll()
        assertNull(c.state.value.poll)
        assertTrue(c.state.value.canAttachPictures && c.state.value.canAddPoll)
        c.send("hello"); advanceUntilIdle()
        assertEquals(listOf("post:hello"), publish.calls)
        assertTrue(polls.created.isEmpty())
    }

    @Test
    fun `reply, quote and article comment offer no poll`() = runTest {
        val r = ComposeController("a".repeat(64), FakeThreads(emptyMap()), FakePublish(), eager(), polls = FakePolls())
        assertFalse(r.state.value.canAddPoll); r.addPoll(); assertNull(r.state.value.poll)
        val q = ComposeController(null, FakeThreads(emptyMap()), FakePublish(), eager(), quoteId = "b".repeat(64), polls = FakePolls())
        q.addPoll(); assertNull(q.state.value.poll)
        val n = ComposeController(null, FakeThreads(emptyMap()), FakePublish(), eager())
        n.addPoll(); assertNull(n.state.value.poll) // no poll repository, no poll
    }
}
