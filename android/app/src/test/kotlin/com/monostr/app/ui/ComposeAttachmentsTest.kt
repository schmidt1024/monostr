package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.media.UploadException
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.compose.ComposeController
import com.monostr.nostr.repo.ThreadView
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
class ComposeAttachmentsTest {
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.controller(publish: FakePublish, uploader: FakeMediaUploader, replyTo: String? = null, quote: String? = null, threads: FakeThreads = FakeThreads(emptyMap())) =
        ComposeController(replyTo, threads, publish, eager(), quoteId = quote, uploader = uploader)

    @Test
    fun `picked pictures upload at once and go out with the note, in order`() = runTest {
        val publish = FakePublish()
        val uploader = FakeMediaUploader()
        val c = controller(publish, uploader)
        c.attach(listOf("content://p/1", "content://p/2"))
        advanceUntilIdle()
        assertEquals(listOf("NOTE:content://p/1", "NOTE:content://p/2"), uploader.uploads)
        val shown = c.state.value.attachments
        assertEquals(listOf("content://p/1", "content://p/2"), shown.map { it.source })
        assertTrue(shown.all { it.uploaded != null && !it.uploading && it.error == null })
        assertTrue(c.state.value.attachmentsReady)

        c.setSensitive(true)
        c.send("look")
        advanceUntilIdle()
        assertTrue(c.state.value.done)
        assertEquals("post:look|2img|cw", publish.calls.single())
        assertEquals(shown.map { it.uploaded!!.url }, publish.lastAttachments.map { it.url })
        assertTrue(publish.lastSensitive)
    }

    @Test
    fun `a note of pictures only is allowed, a note of nothing is not`() = runTest {
        val publish = FakePublish()
        val c = controller(publish, FakeMediaUploader())
        c.send("  ")
        assertEquals(uiText(R.string.compose_error_empty), c.state.value.error)
        c.attach(listOf("content://p/1"))
        advanceUntilIdle()
        assertNull(c.state.value.error) // picking a picture clears the complaint
        c.send("  ")
        advanceUntilIdle()
        assertEquals("post:  |1img", publish.calls.single())
    }

    @Test
    fun `at most four pictures, and none without an uploader`() = runTest {
        val uploader = FakeMediaUploader()
        val c = controller(FakePublish(), uploader)
        c.attach(listOf("a", "b", "c"))
        c.attach(listOf("d", "e", "f"))
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c", "d"), c.state.value.attachments.map { it.source })
        assertFalse(c.state.value.canAttach)
        assertEquals(4, uploader.uploads.size)
        c.removeAttachment(c.state.value.attachments[0].id)
        assertTrue(c.state.value.canAttach)

        val bare = ComposeController(null, FakeThreads(emptyMap()), FakePublish(), eager())
        bare.attach(listOf("a"))
        assertTrue(bare.state.value.attachments.isEmpty())
    }

    @Test
    fun `send waits for running uploads and refuses failed ones`() = runTest {
        val publish = FakePublish()
        val uploader = FakeMediaUploader().apply { gate = CompletableDeferred() }
        val c = controller(publish, uploader)
        c.attach(listOf("content://p/1"))
        advanceUntilIdle()
        assertTrue(c.state.value.attachments.single().uploading)
        assertFalse(c.state.value.attachmentsReady)
        c.send("hi")
        advanceUntilIdle()
        assertEquals(uiText(R.string.compose_error_uploads), c.state.value.error)
        assertTrue(publish.calls.isEmpty())

        uploader.gate!!.complete(Unit)
        advanceUntilIdle()
        c.send("hi")
        advanceUntilIdle()
        assertEquals("post:hi|1img", publish.calls.single())

        // a failed upload blocks as well, until it is retried or removed
        val failing = FakeMediaUploader().apply { failure = UploadException.Nsfw() }
        val publish2 = FakePublish()
        val c2 = controller(publish2, failing)
        c2.attach(listOf("content://p/9"))
        advanceUntilIdle()
        assertEquals(uiText(R.string.upload_error_nsfw), c2.state.value.attachments.single().error)
        assertFalse(c2.state.value.attachments.single().uploading)
        c2.send("hi")
        advanceUntilIdle()
        assertEquals(uiText(R.string.compose_error_uploads), c2.state.value.error)
        assertTrue(publish2.calls.isEmpty())
    }

    @Test
    fun `a failed upload can be retried or removed`() = runTest {
        val uploader = FakeMediaUploader().apply { failure = UploadException.Network(java.io.IOException("down")) }
        val publish = FakePublish()
        val c = controller(publish, uploader)
        c.attach(listOf("content://p/1", "content://p/2"))
        advanceUntilIdle()
        val (first, second) = c.state.value.attachments
        assertEquals(uiText(R.string.upload_error_network), first.error)

        uploader.failure = null
        c.retry(first.id)
        advanceUntilIdle()
        assertNull(c.state.value.attachments[0].error)
        assertTrue(c.state.value.attachments[0].uploaded != null)
        assertEquals(3, uploader.uploads.size) // two attempts for the first, one for the second
        // retrying a picture that is not in the error state does nothing
        c.retry(first.id)
        assertEquals(3, uploader.uploads.size)

        c.removeAttachment(second.id)
        advanceUntilIdle()
        assertEquals(listOf(first.id), c.state.value.attachments.map { it.id })
        assertTrue(uploader.discarded.isEmpty()) // the second never reached the server
        c.send("ok")
        advanceUntilIdle()
        assertEquals("post:ok|1img", publish.calls.single())
    }

    @Test
    fun `removing an uploaded picture deletes it at the server, and the last one takes the switch with it`() = runTest {
        val uploader = FakeMediaUploader()
        val c = controller(FakePublish(), uploader)
        c.attach(listOf("content://p/1"))
        advanceUntilIdle()
        c.setSensitive(true)
        val a = c.state.value.attachments.single()
        c.removeAttachment(a.id)
        assertTrue(c.state.value.attachments.isEmpty())
        assertEquals(listOf(a.uploaded!!.sha256), uploader.discarded)
        assertFalse(c.state.value.sensitive)
        // removing it again does nothing
        c.removeAttachment(a.id)
        assertEquals(1, uploader.discarded.size)
    }

    @Test
    fun `removing a picture while it uploads stops the upload`() = runTest {
        val uploader = FakeMediaUploader().apply { gate = CompletableDeferred() }
        val c = controller(FakePublish(), uploader)
        c.attach(listOf("content://p/1"))
        advanceUntilIdle()
        c.removeAttachment(c.state.value.attachments.single().id)
        uploader.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(c.state.value.attachments.isEmpty())
        assertTrue(uploader.completed.isEmpty())
        assertTrue(uploader.discarded.isEmpty())
    }

    @Test
    fun `leaving without sending discards the uploads, leaving after a send does not`() = runTest {
        val uploader = FakeMediaUploader()
        val c = controller(FakePublish(), uploader)
        c.attach(listOf("content://p/1", "content://p/2"))
        advanceUntilIdle()
        c.discardUnsent()
        assertEquals(c.state.value.attachments.map { it.uploaded!!.sha256 }, uploader.discarded)

        val sentUploader = FakeMediaUploader()
        val sent = controller(FakePublish(), sentUploader)
        sent.attach(listOf("content://p/1"))
        advanceUntilIdle()
        sent.send("hi")
        advanceUntilIdle()
        sent.discardUnsent()
        assertTrue(sentUploader.discarded.isEmpty())

        // a note stored for a later resend (no relay reachable) names the pictures too: they stay
        val offlineUploader = FakeMediaUploader()
        val offline = controller(FakePublish(relaysOk = false), offlineUploader)
        offline.attach(listOf("content://p/1"))
        advanceUntilIdle()
        offline.send("hi")
        advanceUntilIdle()
        assertTrue(offline.state.value.pendingResend)
        assertFalse(offline.state.value.canAttach) // the stored event is frozen
        offline.discardUnsent()
        assertTrue(offlineUploader.discarded.isEmpty())
    }

    @Test
    fun `replies and quotes carry the pictures too`() = runTest {
        val root = note('1')
        val threads = FakeThreads(mapOf(root.id to ThreadView(root, root, emptyList())), mapOf(root.id to root))
        val publish = FakePublish()
        val reply = controller(publish, FakeMediaUploader(), replyTo = root.id, threads = threads)
        reply.start()
        advanceUntilIdle()
        reply.attach(listOf("content://p/1"))
        advanceUntilIdle()
        reply.send("re")
        advanceUntilIdle()
        assertEquals("reply:1111:re|1img", publish.calls.last())

        val quote = controller(publish, FakeMediaUploader(), quote = root.id, threads = threads)
        quote.start()
        advanceUntilIdle()
        quote.attach(listOf("content://p/1"))
        advanceUntilIdle()
        quote.setSensitive(true)
        quote.send("")
        advanceUntilIdle()
        assertEquals("quote:1111:|1img|cw", publish.calls.last())
    }

    @Test
    fun `leaving while a send is in flight keeps the pictures, leaving after a failed send discards them`() = runTest {
        val uploader = FakeMediaUploader()
        val publish = FakePublish().apply { gate = CompletableDeferred() }
        val c = controller(publish, uploader)
        c.attach(listOf("content://p/1"))
        advanceUntilIdle()
        c.send("hi")
        c.discardUnsent()
        assertTrue(uploader.discarded.isEmpty())

        val failedUploader = FakeMediaUploader()
        val failed = controller(FakePublish(fail = true), failedUploader)
        failed.attach(listOf("content://p/1", "content://p/2"))
        advanceUntilIdle()
        failed.send("hi")
        advanceUntilIdle()
        assertFalse(failed.state.value.sending)
        assertTrue(failed.state.value.error != null)
        failed.discardUnsent()
        assertEquals(failed.state.value.attachments.map { it.uploaded!!.sha256 }, failedUploader.discarded)
    }

    @Test
    fun `while a send is in flight the pictures and the switch are frozen`() = runTest {
        val uploader = FakeMediaUploader()
        val publish = FakePublish().apply { gate = CompletableDeferred() }
        val c = controller(publish, uploader)
        c.attach(listOf("content://p/1", "content://p/2"))
        advanceUntilIdle()
        c.setSensitive(true)
        val before = c.state.value.attachments
        c.send("hi")
        assertTrue(c.state.value.sending)

        c.attach(listOf("content://p/3"))
        c.removeAttachment(before[0].id)
        c.retry(before[1].id)
        c.setSensitive(false)
        advanceUntilIdle()
        assertEquals(before, c.state.value.attachments)
        assertTrue(c.state.value.sensitive)
        assertEquals(2, uploader.uploads.size)
        assertTrue(uploader.discarded.isEmpty())

        publish.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("post:hi|2img|cw", publish.calls.single())
        assertEquals(before.map { it.uploaded!!.url }, publish.lastAttachments.map { it.url })
        assertTrue(publish.lastSensitive)
    }

    @Test
    fun `the same picture is not attached twice`() = runTest {
        val uploader = FakeMediaUploader()
        val c = controller(FakePublish(), uploader)
        c.attach(listOf("a", "a"))
        advanceUntilIdle()
        assertEquals(listOf("a"), c.state.value.attachments.map { it.source })
        c.attach(listOf("a"))
        advanceUntilIdle()
        assertEquals(1, c.state.value.attachments.size)
        assertEquals(1, uploader.uploads.size)
        // duplicates do not eat room: three more still fit
        c.attach(listOf("b", "b", "c", "d", "e"))
        assertEquals(listOf("a", "b", "c", "d"), c.state.value.attachments.map { it.source })
    }

    @Test
    fun `a blob that two tiles share is deleted once, with the last of them`() = runTest {
        val uploader = FakeMediaUploader().apply { sameHash = "9".repeat(64) }
        val c = controller(FakePublish(), uploader)
        c.attach(listOf("content://p/1", "content://p/2"))
        advanceUntilIdle()
        val (first, second) = c.state.value.attachments
        c.removeAttachment(first.id)
        assertTrue(uploader.discarded.isEmpty())
        c.removeAttachment(second.id)
        assertEquals(listOf("9".repeat(64)), uploader.discarded)

        val leaving = FakeMediaUploader().apply { sameHash = "8".repeat(64) }
        val l = controller(FakePublish(), leaving)
        l.attach(listOf("content://p/1", "content://p/2"))
        advanceUntilIdle()
        l.discardUnsent()
        assertEquals(listOf("8".repeat(64)), leaving.discarded)
    }

    @Test
    fun `a second tap on send while one is running publishes once`() = runTest {
        val publish = FakePublish().apply { gate = CompletableDeferred() }
        val c = controller(publish, FakeMediaUploader())
        c.send("hi")
        c.send("hi")
        publish.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("post:hi"), publish.calls)
    }

    @Test
    fun `a resend that throws keeps the pictures of the stored note`() = runTest {
        val uploader = FakeMediaUploader()
        val publish = FakePublish(relaysOk = false)
        val c = controller(publish, uploader)
        c.attach(listOf("content://p/1"))
        advanceUntilIdle()
        c.send("hi")
        advanceUntilIdle()
        assertTrue(c.state.value.pendingResend)
        publish.failResend = true
        c.send("hi")
        advanceUntilIdle()
        assertFalse(c.state.value.sending)
        assertTrue(c.state.value.error != null)
        c.discardUnsent()
        assertTrue(uploader.discarded.isEmpty())
    }
}
