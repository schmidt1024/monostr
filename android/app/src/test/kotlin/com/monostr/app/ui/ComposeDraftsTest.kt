package com.monostr.app.ui

import com.monostr.app.data.Draft
import com.monostr.app.data.DraftTargets
import com.monostr.app.media.UploadedMedia
import com.monostr.app.ui.compose.ComposeController
import com.monostr.nostr.repo.ThreadView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-16 §8.2: silent drafts per target — restore, debounced save, save on leaving, removed on send. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposeDraftsTest {
    private val me = "e".repeat(64)
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun media(n: Int) = UploadedMedia("https://media.test", "https://media.test/$n.jpg", "image/jpeg", n.toString().padStart(64, '0'), 1000, 400, 300, null, fresh = true)
    private fun TestScope.controller(
        drafts: FakeDraftStore,
        uploader: FakeMediaUploader = FakeMediaUploader(),
        publish: FakePublish = FakePublish(),
        replyTo: String? = null,
        threads: FakeThreads = FakeThreads(emptyMap()),
        localNames: suspend (List<String>) -> Map<String, String> = { emptyMap() },
    ) = ComposeController(replyTo, threads, publish, eager(), uploader = uploader, drafts = drafts, account = me, background = eager(), now = { 5_000L }, localNames = localNames)

    @Test
    fun `opening the composer restores the draft of its target, pictures without a new upload`() = runTest {
        val drafts = FakeDraftStore().apply { put(me, DraftTargets.NOTE, Draft("half a thought", sensitive = true, attachments = listOf(media(1)), updatedAt = 4_000)) }
        val uploader = FakeMediaUploader()
        val c = controller(drafts, uploader)
        c.start()
        advanceUntilIdle()
        val s = c.state.value
        assertEquals("half a thought", s.restoredText)
        assertEquals(listOf(media(1)), s.attachments.map { it.uploaded })
        assertTrue(s.attachmentsReady)
        assertTrue(s.sensitive)
        assertTrue(uploader.uploads.isEmpty())
        c.restoreApplied()
        assertNull(c.state.value.restoredText)
    }

    @Test
    fun `a prefilled compose never touches the new-note draft`() = runTest {
        val drafts = FakeDraftStore().apply { put(me, DraftTargets.NOTE, Draft("my own draft", sensitive = false, attachments = emptyList(), updatedAt = 4_000)) }
        val c = ComposeController(null, FakeThreads(emptyMap()), FakePublish(), eager(), drafts = drafts, account = me, background = eager(), now = { 5_000L }, prefill = "\n\nnostr:naddr1x")
        c.start()
        advanceUntilIdle()
        assertEquals("\n\nnostr:naddr1x", c.state.value.restoredText)
        c.onTextChange("my words\n\nnostr:naddr1x", 8)
        c.saveDraftNow()
        advanceUntilIdle()
        assertEquals("my own draft", drafts.stored(me, DraftTargets.NOTE)!!.text)
    }

    @Test
    fun `typing saves once, a second after the last change`() = runTest {
        val drafts = FakeDraftStore()
        val c = controller(drafts)
        c.start()
        advanceUntilIdle()
        c.onTextChange("h", 1)
        advanceTimeBy(500)
        c.onTextChange("hi", 2)
        advanceTimeBy(900)
        runCurrent()
        assertTrue(drafts.saves.isEmpty())
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("hi"), drafts.saves.map { it.text })
        assertEquals(5_000L, drafts.stored(me, DraftTargets.NOTE)!!.updatedAt)
    }

    @Test
    fun `a finished upload and the switch are saved, a running upload is not`() = runTest {
        val drafts = FakeDraftStore()
        val uploader = FakeMediaUploader()
        val c = controller(drafts, uploader)
        c.start()
        advanceUntilIdle()
        c.attach(listOf("content://p/1"))
        advanceUntilIdle()
        c.setSensitive(true)
        advanceUntilIdle()
        val saved = drafts.stored(me, DraftTargets.NOTE)!!
        assertEquals(listOf(c.state.value.attachments.single().uploaded), saved.attachments)
        assertTrue(saved.sensitive)
        uploader.gate = CompletableDeferred()
        c.attach(listOf("content://p/2"))
        c.onTextChange("x", 1)
        advanceUntilIdle()
        assertEquals(1, drafts.stored(me, DraftTargets.NOTE)!!.attachments.size)
    }

    @Test
    fun `a sent note takes its draft along and leaves its pictures on the server`() = runTest {
        val drafts = FakeDraftStore()
        val uploader = FakeMediaUploader()
        val c = controller(drafts, uploader)
        c.start()
        advanceUntilIdle()
        c.attach(listOf("content://p/1"))
        c.onTextChange("hi", 2)
        advanceUntilIdle()
        assertNotNull(drafts.stored(me, DraftTargets.NOTE))
        c.send("hi")
        advanceUntilIdle()
        assertTrue(c.state.value.done)
        assertNull(drafts.stored(me, DraftTargets.NOTE))
        c.discardUnsent()
        advanceUntilIdle()
        assertNull(drafts.stored(me, DraftTargets.NOTE))
        assertTrue(uploader.discarded.isEmpty())
    }

    @Test
    fun `an offline send removes the draft too, the stored note keeps the pictures`() = runTest {
        val drafts = FakeDraftStore()
        val uploader = FakeMediaUploader()
        val c = controller(drafts, uploader, FakePublish(relaysOk = false))
        c.start()
        advanceUntilIdle()
        c.attach(listOf("content://p/1"))
        c.onTextChange("hi", 2)
        advanceUntilIdle()
        c.send("hi")
        advanceUntilIdle()
        assertTrue(c.state.value.pendingResend)
        assertNull(drafts.stored(me, DraftTargets.NOTE))
        c.onTextChange("hi!", 3)
        c.discardUnsent()
        advanceUntilIdle()
        assertNull(drafts.stored(me, DraftTargets.NOTE))
        assertTrue(uploader.discarded.isEmpty())
    }

    @Test
    fun `leaving keeps text, switch and pictures as the draft and deletes nothing`() = runTest {
        val drafts = FakeDraftStore()
        val uploader = FakeMediaUploader()
        val c = controller(drafts, uploader)
        c.start()
        advanceUntilIdle()
        c.attach(listOf("content://p/1"))
        advanceUntilIdle()
        c.setSensitive(true)
        c.onTextChange("later", 5) // left before the debounce ran
        c.discardUnsent()
        advanceUntilIdle()
        val d = drafts.stored(me, DraftTargets.NOTE)!!
        assertEquals("later", d.text)
        assertTrue(d.sensitive)
        assertEquals(1, d.attachments.size)
        assertTrue(uploader.discarded.isEmpty())
    }

    @Test
    fun `a composer emptied by the user removes its draft`() = runTest {
        val drafts = FakeDraftStore().apply { put(me, DraftTargets.NOTE, Draft("old", updatedAt = 4_000)) }
        val c = controller(drafts)
        c.start()
        advanceUntilIdle()
        c.restoreApplied()
        c.onTextChange("old", 3)
        c.onTextChange("", 0)
        c.discardUnsent()
        advanceUntilIdle()
        assertNull(drafts.stored(me, DraftTargets.NOTE))
    }

    @Test
    fun `removing a restored picture deletes it at the server and from the draft`() = runTest {
        val drafts = FakeDraftStore().apply { put(me, DraftTargets.NOTE, Draft("keep", attachments = listOf(media(1)), updatedAt = 4_000)) }
        val uploader = FakeMediaUploader()
        val c = controller(drafts, uploader)
        c.start()
        advanceUntilIdle()
        c.removeAttachment(c.state.value.attachments.single().id)
        advanceUntilIdle()
        assertEquals(listOf(media(1).sha256), uploader.discarded)
        assertTrue(drafts.stored(me, DraftTargets.NOTE)!!.attachments.isEmpty())
    }

    @Test
    fun `a failed leave-save deletes only the pictures the stored draft does not name`() = runTest {
        val drafts = FakeDraftStore().apply { put(me, DraftTargets.NOTE, Draft("keep", attachments = listOf(media(9)), updatedAt = 4_000)) }
        val uploader = FakeMediaUploader()
        val c = controller(drafts, uploader)
        c.start()
        advanceUntilIdle()
        drafts.fail = true // read fine, from now on every write fails
        c.attach(listOf("content://p/1"))
        advanceUntilIdle()
        val added = c.state.value.attachments.last().uploaded!!.sha256
        c.discardUnsent()
        advanceUntilIdle()
        assertEquals(listOf(added), uploader.discarded, "the restored picture stays: the old draft still shows it")
        assertEquals(listOf(media(9)), drafts.stored(me, DraftTargets.NOTE)!!.attachments)
    }

    @Test
    fun `closing before the draft has loaded leaves the stored draft alone`() = runTest {
        val drafts = FakeDraftStore().apply {
            put(me, DraftTargets.NOTE, Draft("precious", updatedAt = 4_000))
            loadGate = CompletableDeferred()
        }
        val c = controller(drafts)
        c.start()
        advanceUntilIdle()
        c.onTextChange("", 0)
        c.discardUnsent()
        advanceUntilIdle()
        assertEquals("precious", drafts.stored(me, DraftTargets.NOTE)!!.text)
    }

    @Test
    fun `a store that fails leaves a working composer, and leaving discards the pictures as before`() = runTest {
        val drafts = FakeDraftStore().apply { fail = true }
        val uploader = FakeMediaUploader()
        val c = controller(drafts, uploader)
        c.start()
        advanceUntilIdle()
        c.attach(listOf("content://p/1"))
        c.onTextChange("hi", 2)
        advanceUntilIdle()
        assertTrue(c.state.value.attachmentsReady)
        assertNull(c.state.value.error)
        c.discardUnsent()
        advanceUntilIdle()
        assertEquals(listOf(c.state.value.attachments.single().uploaded!!.sha256), uploader.discarded)
    }

    @Test
    fun `a reply keeps its own draft, apart from the new note's`() = runTest {
        val root = note('1')
        val threads = FakeThreads(mapOf(root.id to ThreadView(root, root, emptyList())), mapOf(root.id to root))
        val drafts = FakeDraftStore().apply {
            put(me, DraftTargets.NOTE, Draft("new note", updatedAt = 4_000))
            put(me, "reply:${root.id}", Draft("my reply", updatedAt = 4_000))
        }
        val reply = controller(drafts, replyTo = root.id, threads = threads)
        reply.start()
        advanceUntilIdle()
        assertEquals("my reply", reply.state.value.restoredText)
        reply.send("my reply")
        advanceUntilIdle()
        assertNull(drafts.stored(me, "reply:${root.id}"))
        assertEquals("new note", drafts.stored(me, DraftTargets.NOTE)!!.text)
    }

    private val known = "a".repeat(64)
    private val unknown = "b".repeat(64)
    private fun npub(hex: String) = com.monostr.nostr.Npub.encode(hex)

    @Test
    fun `a restored draft names its known mentions and keeps the text raw`() = runTest {
        val text = "hi nostr:${npub(known)} and nostr:${npub(unknown)}"
        val drafts = FakeDraftStore().apply { put(me, DraftTargets.NOTE, Draft(text, sensitive = false, attachments = emptyList(), updatedAt = 4_000)) }
        val c = controller(drafts, localNames = { keys -> keys.filter { it == known }.associateWith { "Alice" } })
        c.start()
        advanceUntilIdle()
        assertEquals(text, c.state.value.restoredText)
        assertEquals(mapOf(known to "Alice"), c.state.value.mentionNames)
    }

    @Test
    fun `a failing name lookup still restores the text, without names`() = runTest {
        val text = "hi nostr:${npub(known)}"
        val drafts = FakeDraftStore().apply { put(me, DraftTargets.NOTE, Draft(text, sensitive = false, attachments = emptyList(), updatedAt = 4_000)) }
        val c = controller(drafts, localNames = { error("db down") })
        c.start()
        advanceUntilIdle()
        assertEquals(text, c.state.value.restoredText)
        assertTrue(c.state.value.mentionNames.isEmpty())
    }
}
