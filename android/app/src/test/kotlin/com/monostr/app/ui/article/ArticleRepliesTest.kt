package com.monostr.app.ui.article

import com.monostr.app.ui.FakeCounts
import com.monostr.app.ui.FakePendingTips
import com.monostr.app.ui.FakeProfiles
import com.monostr.app.ui.FakePublish
import com.monostr.app.ui.FakeTips
import com.monostr.app.ui.thread.ThreadRow
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.AddressRef
import com.monostr.nostr.model.AddressableEvent
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.AddressResult
import com.monostr.nostr.repo.AddressableRepository
import com.monostr.nostr.repo.ArticleRepliesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-29 article reader: the replies under the article, built like the thread screen's strands. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArticleRepliesTest {
    private val author = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val ref = AddressRef(30023, author, "my-post")
    private val event = AddressableEvent("e".repeat(64), 30023, author, "my-post", 1000, listOf(listOf("d", "my-post"), listOf("title", "T")), "# Hello")

    private fun note(c: Char, by: String, at: Long, parent: String = event.id, kind: Int = 1111) =
        Note(id = c.toString().repeat(64), author = by, content = "n$c", createdAt = at, kind = kind, rootId = parent, replyToId = parent, repostOf = null, mentionedPubkeys = emptyList())

    private class Addresses(val result: AddressResult) : AddressableRepository {
        override fun cached(ref: AddressRef, allowForeign: Boolean): AddressResult? = result
        override suspend fun get(ref: AddressRef, allowForeign: Boolean): AddressResult = result
    }

    private class Replies(val flow: Flow<List<Note>>) : ArticleRepliesRepository {
        var observed = 0
        val foreign = ArrayList<Boolean>()
        override fun observe(ref: AddressRef, allowForeign: Boolean): Flow<List<Note>> { observed++; foreign += allowForeign; return flow }
    }

    private fun TestScope.controller(addresses: AddressableRepository, replies: Replies, muted: Set<String> = emptySet(), allowForeign: Boolean = true): ArticleController {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val profiles = FakeProfiles()
        val publish = FakePublish()
        return ArticleController(
            ref, addresses, profiles, publish, TipDecorations(FakeTips(), FakePendingTips(), profiles, publish, scope), FakeCounts(), scope,
            muted = MutableStateFlow(muted), allowForeign = allowForeign, replies = replies, parseContext = kotlin.coroutines.EmptyCoroutineContext, cache = ArticleBlockCache(),
        )
    }

    @Test
    fun `replies are asked only once the article is shown, with the foreign rule`() = runTest {
        val missing = Replies(MutableStateFlow(emptyList()))
        controller(Addresses(AddressResult.Missing), missing).also { it.start(); advanceUntilIdle() }
        assertEquals(0, missing.observed)
        val replies = Replies(MutableStateFlow(emptyList()))
        controller(Addresses(AddressResult.Found(event)), replies, allowForeign = false).also { it.start(); advanceUntilIdle() }
        assertEquals(listOf(false), replies.foreign)
    }

    @Test
    fun `comments and old notes become the thread screen's rows, the author first, one child per strand`() = runTest {
        val first = note('1', bob, 10)
        val child = note('2', bob, 11, parent = first.id)
        val authors = note('3', author, 20)
        val old = note('4', carol, 5, kind = 1)
        val c = controller(Addresses(AddressResult.Found(event)), Replies(MutableStateFlow(listOf(old, first, child, authors))))
        c.start(); advanceUntilIdle()
        val rows = c.repliesState.value.rows
        // the article's author first, then by time; bob's strand carries its child
        assertEquals(listOf(authors.id, old.id, first.id, child.id), rows.map { it.id })
        assertTrue(rows[3] is ThreadRow.Child)
        assertEquals(setOf(authors.id, old.id, first.id, child.id), c.repliesState.value.items.map { it.note.id }.toSet())
    }

    @Test
    fun `a muted account's reply is hidden, a muted child leaves its strand`() = runTest {
        val first = note('1', bob, 10)
        val child = note('2', carol, 11, parent = first.id)
        val hidden = note('3', carol, 12)
        val c = controller(Addresses(AddressResult.Found(event)), Replies(MutableStateFlow(listOf(first, child, hidden))), muted = setOf(carol))
        c.start(); advanceUntilIdle()
        assertEquals(listOf(first.id), c.repliesState.value.rows.map { it.id })
    }

    @Test
    fun `a muted strand head stays as a muted row while its child shows`() = runTest {
        val head = note('1', carol, 10)
        val child = note('2', bob, 11, parent = head.id)
        val c = controller(Addresses(AddressResult.Found(event)), Replies(MutableStateFlow(listOf(head, child))), muted = setOf(carol))
        c.start(); advanceUntilIdle()
        val r = c.repliesState.value
        assertEquals(listOf(head.id, child.id), r.rows.map { it.id })
        assertTrue((r.rows[0] as ThreadRow.Reply).keepWhenHidden)
        assertEquals(setOf(head.id), r.muted.keys)
        assertEquals(listOf(child.id), r.items.map { it.note.id })
    }

    @Test
    fun `replies count as loaded only once the list came`() = runTest {
        val flow = kotlinx.coroutines.flow.MutableSharedFlow<List<Note>>()
        val c = controller(Addresses(AddressResult.Found(event)), Replies(flow))
        c.start(); advanceUntilIdle()
        assertEquals(false, c.repliesState.value.loaded)
        flow.emit(emptyList()); advanceUntilIdle()
        assertEquals(true, c.repliesState.value.loaded)
        assertEquals(emptyList<ThreadRow>(), c.repliesState.value.rows)
    }

    @Test
    fun `a new reply from the relays joins the list`() = runTest {
        val flow = MutableStateFlow(listOf(note('1', bob, 10)))
        val c = controller(Addresses(AddressResult.Found(event)), Replies(flow))
        c.start(); advanceUntilIdle()
        assertEquals(1, c.repliesState.value.rows.size)
        flow.value = flow.value + note('2', carol, 30)
        advanceUntilIdle()
        assertEquals(2, c.repliesState.value.rows.size)
    }
}
