package com.monostr.app.ui.feed

import com.monostr.app.ui.FakeBookmarks
import com.monostr.app.ui.FakeCounts
import com.monostr.app.ui.FakeFeed
import com.monostr.app.ui.FakePendingTips
import com.monostr.app.ui.FakeProfiles
import com.monostr.app.ui.FakePublish
import com.monostr.app.ui.FakeTips
import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.note
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.Chain
import com.monostr.nostr.repo.ConversationRepository
import com.monostr.nostr.repo.EventLocator
import com.monostr.nostr.repo.Slot
import com.monostr.nostr.repo.Wanted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-23 §5.1: the feed shows the pending row while stage 3 searches, then recomputes only the affected chains. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedPendingTest {
    private val root = note('r', author = "b".repeat(64), createdAt = 900)
    private val reply = note('x', createdAt = 1000, replyTo = root.id)

    /** [chains] answers per call from [script]; [resolve] returns [found] after [gate]. */
    private class Fake(val script: (List<Note>, Int) -> Map<String, Chain>) : ConversationRepository {
        val calls = ArrayList<List<String>>()
        val resolved = ArrayList<List<String>>()
        var found: Set<String> = emptySet()
        var resolveError: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override val retries = MutableSharedFlow<Unit>()
        override suspend fun chains(notes: List<Note>): Map<String, Chain> {
            calls += notes.map { it.id }
            return script(notes, calls.size)
        }
        override suspend fun ancestors(note: Note): List<Slot> = emptyList()
        override suspend fun resolve(pending: Collection<Slot.Pending>): Set<String> {
            resolved += pending.map { it.id }
            gate?.await()
            resolveError?.let { throw it }
            return found
        }
    }

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.controller(feed: FakeFeed, conv: ConversationRepository, forget: () -> Unit = {}): FeedController {
        val profiles = FakeProfiles()
        return FeedController(
            feed, profiles, FakePublish(), TipDecorations(FakeTips(), FakePendingTips(), profiles, FakePublish(), eager()) { 5_000 },
            BookmarkActions(FakeBookmarks(), eager()), FakeCounts(), eager(), conversations = conv, forgetMisses = forget,
        )
    }

    private fun pendingChain() = mapOf(reply.id to Chain(listOf(Slot.Pending(root.id, Wanted(root.id))), cut = false))

    @Test
    fun `pending shows as pending, then the note`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val conv = Fake { _, n -> if (n == 1) pendingChain() else mapOf(reply.id to Chain(listOf(Slot.Shown(root)), cut = false)) }
        conv.gate = gate; conv.found = setOf(root.id)
        val c = controller(FakeFeed().apply { current = listOf(reply) }, conv)
        c.start(); advanceUntilIdle()
        assertEquals(setOf(root.id), c.state.value.pending)
        assertNull(c.state.value.ancestors[root.id])
        gate.complete(Unit); advanceUntilIdle()
        assertNotNull(c.state.value.ancestors[root.id])
        assertTrue(c.state.value.pending.isEmpty())
    }

    @Test
    fun `not found becomes unavailable`() = runTest {
        val conv = Fake { _, n -> if (n == 1) pendingChain() else mapOf(reply.id to Chain(listOf(Slot.Unavailable(root.id)), cut = false)) }
        val c = controller(FakeFeed().apply { current = listOf(reply) }, conv)
        c.start(); advanceUntilIdle()
        assertTrue(c.state.value.pending.isEmpty())
        assertNull(c.state.value.ancestors[root.id])
        assertEquals(listOf(root.id), c.state.value.chains[reply.id]!!.ancestors)
    }

    @Test
    fun `only affected entries are walked again`() = runTest {
        val others = (0 until 9).map { note(('a' + it), createdAt = 1100L + it, replyTo = root.id) }
        val conv = Fake { notes, n ->
            if (n == 1) notes.associate { x ->
                x.id to Chain(listOf(if (x.id == reply.id) Slot.Pending(root.id, Wanted(root.id)) else Slot.Shown(root)), cut = false)
            } else notes.associate { it.id to Chain(listOf(Slot.Shown(root)), cut = false) }
        }
        val c = controller(FakeFeed().apply { current = listOf(reply) + others }, conv)
        c.start(); advanceUntilIdle()
        assertEquals(2, conv.calls.size)
        assertEquals(10, conv.calls[0].size)
        assertEquals(listOf(reply.id), conv.calls[1])
    }

    @Test
    fun `first chains are stored before resolve returns`() = runTest {
        val conv = Fake { _, _ -> pendingChain() }
        conv.gate = CompletableDeferred()
        val c = controller(FakeFeed().apply { current = listOf(reply) }, conv)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(root.id), c.state.value.chains[reply.id]!!.ancestors)
        assertEquals(setOf(root.id), c.state.value.pending)
    }

    @Test
    fun `pull to refresh forgets misses`() = runTest {
        var forgets = 0
        val c = controller(FakeFeed().apply { current = listOf(reply) }, Fake { _, _ -> emptyMap() }) { forgets++ }
        c.start(); advanceUntilIdle()
        c.refresh(); advanceUntilIdle()
        assertEquals(1, forgets)
    }

    /** The session's wiring: the feed's chains listen to the locator's retries, refresh forgets without a signal. */
    private class Locator : EventLocator {
        var emitted = 0
        var forgets = 0
        override val retries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override suspend fun searchable(id: String) = true
        override suspend fun find(wanted: Collection<Wanted>): Set<String> = emptySet()
        override suspend fun repliesFrom(author: String, tagIds: List<String>) = false
        override fun forget(notify: Boolean) {
            forgets++
            if (notify) { emitted++; retries.tryEmit(Unit) }
        }
    }

    @Test
    fun `one refresh walks the open chains once`() = runTest {
        val loc = Locator()
        val conv = object : ConversationRepository by Fake({ _, _ -> mapOf(reply.id to Chain(listOf(Slot.Unavailable(root.id)), cut = false)) }) {
            override val retries: Flow<Unit> = loc.retries
        }
        val walks = ArrayList<List<String>>()
        val counting = object : ConversationRepository by conv {
            override suspend fun chains(notes: List<Note>): Map<String, Chain> { walks += notes.map { it.id }; return conv.chains(notes) }
            // the feed walks with its job's asked set (final review M1)
            override suspend fun chains(notes: List<Note>, asked: MutableSet<String>): Map<String, Chain> = chains(notes)
        }
        val c = controller(FakeFeed().apply { current = listOf(reply) }, counting) { loc.forget(notify = false) }
        c.start(); advanceUntilIdle()
        assertEquals(1, walks.size)
        c.refresh(); advanceUntilIdle()
        assertEquals(1, loc.forgets)
        assertEquals(0, loc.emitted)
        assertEquals(listOf(listOf(reply.id)), walks.drop(1))
    }

    @Test
    fun `retries re-resolve open chains`() = runTest {
        val conv = Fake { _, _ -> mapOf(reply.id to Chain(listOf(Slot.Unavailable(root.id)), cut = false)) }
        val c = controller(FakeFeed().apply { current = listOf(reply) }, conv)
        c.start(); advanceUntilIdle()
        assertEquals(1, conv.calls.size)
        conv.retries.emit(Unit); advanceUntilIdle()
        assertEquals(2, conv.calls.size)
    }

    @Test
    fun `a failing resolve does not leave the pending row`() = runTest {
        val conv = Fake { _, _ -> pendingChain() }
        conv.resolveError = IllegalStateException("boom")
        val c = controller(FakeFeed().apply { current = listOf(reply) }, conv)
        c.start(); advanceUntilIdle()
        assertTrue(c.state.value.pending.isEmpty())
    }

    @Test
    fun `a retry during the search walks the entry again afterwards`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val conv = Fake { _, n -> if (n == 1) pendingChain() else mapOf(reply.id to Chain(listOf(Slot.Unavailable(root.id)), cut = false)) }
        conv.gate = gate
        val c = controller(FakeFeed().apply { current = listOf(reply) }, conv)
        c.start(); advanceUntilIdle()
        conv.retries.emit(Unit); advanceUntilIdle()
        assertEquals(1, conv.calls.size, "not walked while the search runs")
        gate.complete(Unit); advanceUntilIdle()
        // re-walk of the round plus the one the retry asked for
        assertEquals(3, conv.calls.size, conv.calls.toString())
        assertTrue(c.state.value.pending.isEmpty())
    }

    @Test
    fun `multi-hop pending is bounded`() = runTest {
        val conv = Fake { _, _ -> pendingChain() }
        val c = controller(FakeFeed().apply { current = listOf(reply) }, conv)
        c.start(); advanceUntilIdle()
        assertEquals(FeedController.PENDING_ROUNDS, conv.resolved.size)
        assertTrue(c.state.value.pending.isEmpty())
    }

    @Test
    fun `one resolve job walks with one asked set`() = runTest {
        // spec 2026-10-23 §10, final review M1: the re-walks of a job do not ask the own relays again for ids that missed there
        val sets = ArrayList<MutableSet<String>>()
        val conv = object : ConversationRepository {
            override val retries = MutableSharedFlow<Unit>()
            override suspend fun chains(notes: List<Note>): Map<String, Chain> = error("the feed walks with its asked set")
            override suspend fun chains(notes: List<Note>, asked: MutableSet<String>): Map<String, Chain> {
                sets += asked
                asked += root.id
                return pendingChain()
            }
            override suspend fun ancestors(note: Note): List<Slot> = emptyList()
        }
        val c = controller(FakeFeed().apply { current = listOf(reply) }, conv)
        c.start(); advanceUntilIdle()
        assertEquals(FeedController.PENDING_ROUNDS + 1, sets.size)
        assertTrue(sets.all { it === sets[0] }, "a new set per walk")
        assertTrue(c.state.value.pending.isEmpty())
    }
}
