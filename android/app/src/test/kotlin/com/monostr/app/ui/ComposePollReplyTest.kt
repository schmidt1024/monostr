package com.monostr.app.ui

import com.monostr.app.ui.compose.ComposeController
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Poll
import com.monostr.nostr.model.PollOption
import com.monostr.nostr.repo.ThreadRepository
import kotlinx.coroutines.CompletableDeferred
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

/** Polls task 4: a reply to a poll, or to a comment under one, is a NIP-22 comment. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposePollReplyTest {
    private val poll = Note(
        id = "a".repeat(64), author = "b".repeat(64), content = "Lunch?", createdAt = 1000, kind = 1068, rootId = null, replyToId = null,
        repostOf = null, mentionedPubkeys = emptyList(), poll = Poll(listOf(PollOption("a", "Yes"), PollOption("b", "No")), false, null, emptyList()),
    )
    private val comment = Note(
        id = "c".repeat(64), author = "d".repeat(64), content = "hm", createdAt = 2000, kind = 1111, rootId = null, replyToId = poll.id,
        repostOf = null, mentionedPubkeys = emptyList(), pollRootId = poll.id,
    )
    private val note = Note(id = "e".repeat(64), author = "d".repeat(64), content = "hi", createdAt = 2000, kind = 1, rootId = null, replyToId = null, repostOf = null, mentionedPubkeys = emptyList())

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.controller(replyTo: Note, vararg known: Note, publish: FakePublish) =
        ComposeController(replyTo.id, FakeThreads(emptyMap(), notes = (known.toList() + replyTo).associateBy { it.id }), publish, eager(), now = { 5_000L })

    @Test
    fun `a reply to a poll is a comment with the poll as root`() = runTest {
        val publish = FakePublish()
        val c = controller(poll, publish = publish)
        c.start(); advanceUntilIdle()
        c.send("my take"); advanceUntilIdle()
        assertEquals(listOf("comment:aaaa:-:my take"), publish.calls)
    }

    @Test
    fun `a reply to a comment under a poll is a comment with the poll as root and the comment as parent`() = runTest {
        val publish = FakePublish()
        val c = controller(comment, poll, publish = publish)
        c.start(); advanceUntilIdle()
        c.send("thanks"); advanceUntilIdle()
        assertEquals(listOf("comment:aaaa:cccc:thanks"), publish.calls)
    }

    @Test
    fun `a send while the poll of the answered comment still loads waits for it, never a kind 1 reply`() = runTest {
        val publish = FakePublish()
        val gate = CompletableDeferred<Unit>()
        val threads = object : ThreadRepository by FakeThreads(emptyMap(), notes = listOf(comment, poll).associateBy { it.id }) {
            override suspend fun note(id: String): Note? {
                if (id == poll.id) gate.await()
                return if (id == poll.id) poll else comment
            }
        }
        val c = ComposeController(comment.id, threads, publish, eager(), now = { 5_000L })
        c.start(); advanceUntilIdle()
        c.send("too early"); advanceUntilIdle()
        assertTrue(publish.calls.isEmpty(), publish.calls.toString())
        gate.complete(Unit); advanceUntilIdle()
        c.send("thanks"); advanceUntilIdle()
        assertEquals(listOf("comment:aaaa:cccc:thanks"), publish.calls)
    }

    @Test
    fun `a reply to a comment whose poll does not load stays an ordinary reply`() = runTest {
        val publish = FakePublish()
        val c = controller(comment, publish = publish)
        c.start(); advanceUntilIdle()
        c.send("thanks"); advanceUntilIdle()
        assertEquals(1, publish.calls.size)
        assert(publish.calls.single().startsWith("reply")) { publish.calls.toString() }
    }

    @Test
    fun `a reply to a kind 1 note is unchanged`() = runTest {
        val publish = FakePublish()
        val c = controller(note, publish = publish)
        c.start(); advanceUntilIdle()
        c.send("yo"); advanceUntilIdle()
        assert(publish.calls.single().startsWith("reply")) { publish.calls.toString() }
    }
}
