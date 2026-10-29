package com.monostr.app.ui

import com.monostr.app.data.DraftTargets
import com.monostr.nostr.model.AddressRef
import com.monostr.nostr.model.AddressableEvent
import com.monostr.nostr.model.Note
import com.monostr.nostr.repo.AddressResult
import com.monostr.nostr.repo.AddressableRepository
import com.monostr.app.ui.compose.ComposeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-29 article reader: the composer's article-comment target. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposeArticleCommentTest {
    private val author = "a".repeat(64)
    private val ref = AddressRef(30023, author, "my-post")
    private val event = AddressableEvent("e".repeat(64), 30023, author, "my-post", 1000, listOf(listOf("d", "my-post"), listOf("title", "My post")), "body")
    private val parent = Note(id = "c".repeat(64), author = "b".repeat(64), content = "nice", createdAt = 2000, kind = 1111, rootId = null, replyToId = event.id, repostOf = null, mentionedPubkeys = emptyList())

    private class Addresses(val result: AddressResult) : AddressableRepository {
        val foreign = ArrayList<Boolean>()
        override fun cached(ref: AddressRef, allowForeign: Boolean): AddressResult? { foreign += allowForeign; return result }
        override suspend fun get(ref: AddressRef, allowForeign: Boolean): AddressResult { foreign += allowForeign; return result }
    }

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.controller(publish: FakePublish, addresses: Addresses, parentId: String? = null, drafts: FakeDraftStore? = null) =
        ComposeController(
            null, FakeThreads(emptyMap(), notes = mapOf(parent.id to parent)), publish, eager(), articleRef = ref, commentParentId = parentId, addresses = addresses,
            drafts = drafts, account = "f".repeat(64), background = eager(), now = { 5_000L },
        )

    @Test
    fun `a top level comment publishes through comment under the article, asking only the own relays`() = runTest {
        val publish = FakePublish()
        val addresses = Addresses(AddressResult.Found(event))
        val c = controller(publish, addresses)
        c.start(); advanceUntilIdle()
        assertEquals("My post", c.state.value.articleTitle)
        c.send("hello"); advanceUntilIdle()
        assertEquals(listOf("comment:eeee:-:hello"), publish.calls)
        assertTrue(c.state.value.done)
        assertTrue(addresses.foreign.none { it })
    }

    @Test
    fun `answering a reply passes it as the parent`() = runTest {
        val publish = FakePublish()
        val c = controller(publish, Addresses(AddressResult.Found(event)), parentId = parent.id)
        c.start(); advanceUntilIdle()
        assertEquals(parent, c.state.value.replyTo)
        c.send("thanks"); advanceUntilIdle()
        assertEquals(listOf("comment:eeee:cccc:thanks"), publish.calls)
    }

    @Test
    fun `an article that does not load blocks the send`() = runTest {
        val publish = FakePublish()
        val c = controller(publish, Addresses(AddressResult.Missing))
        c.start(); advanceUntilIdle()
        assertTrue(c.state.value.articleUnresolved)
        c.send("hello"); advanceUntilIdle()
        assertTrue(publish.calls.isEmpty())
    }

    @Test
    fun `the comment has its own draft slot per article and parent`() = runTest {
        val top = DraftTargets.of(null, null, null, ref.coordinate, null)
        val nested = DraftTargets.of(null, null, null, ref.coordinate, parent.id)
        assertNotEquals(top, nested)
        assertNotEquals(DraftTargets.NOTE, top)
        assertNotEquals(DraftTargets.of(parent.id, null), nested)
        val drafts = FakeDraftStore()
        val c = controller(FakePublish(), Addresses(AddressResult.Found(event)), drafts = drafts)
        c.start(); advanceUntilIdle()
        c.onTextChange("half a thought", 5)
        c.saveDraftNow(); advanceUntilIdle()
        assertEquals("half a thought", drafts.stored("f".repeat(64), top)!!.text)
        assertEquals(null, drafts.stored("f".repeat(64), DraftTargets.NOTE))
    }
}
