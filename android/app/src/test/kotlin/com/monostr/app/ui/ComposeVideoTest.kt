package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.compose.ComposeController
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

/** Spec 2026-10-27 video §3: exactly one video per note, never with pictures, at most two minutes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposeVideoTest {
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.controller(publish: FakePublish = FakePublish(), uploader: FakeMediaUploader = FakeMediaUploader()) =
        ComposeController(null, FakeThreads(emptyMap()), publish, eager(), uploader = uploader)

    @Test
    fun `a video blocks pictures and pictures block a video`() = runTest {
        val c = controller()
        assertTrue(c.state.value.canAttachVideo && c.state.value.canAttachPictures)
        c.attachVideo("content://v/1")
        advanceUntilIdle()
        assertFalse(c.state.value.canAttachPictures)
        assertFalse(c.state.value.canAttachVideo)
        c.attach(listOf("content://p/1"))
        assertEquals(1, c.state.value.attachments.size)

        val d = controller()
        d.attach(listOf("content://p/1"))
        advanceUntilIdle()
        assertFalse(d.state.value.canAttachVideo)
        d.attachVideo("content://v/2")
        advanceUntilIdle()
        assertTrue(d.state.value.attachments.none { it.video })
    }

    @Test
    fun `a source over 121 s is refused with the message and no tile`() = runTest {
        val up = FakeMediaUploader().apply { videoDurationMs = 121_001 }
        val c = controller(uploader = up)
        c.attachVideo("content://v/1")
        advanceUntilIdle()
        assertTrue(c.state.value.attachments.isEmpty())
        assertEquals(uiText(R.string.compose_video_too_long), c.state.value.error)
        assertTrue(up.videoUploads.isEmpty())
    }

    @Test
    fun `video tile progress combines encode and upload`() = runTest {
        val up = FakeMediaUploader().apply { videoGate = CompletableDeferred() }
        val c = controller(uploader = up)
        c.attachVideo("content://v/1")
        advanceUntilIdle()
        val tile = c.state.value.attachments.single()
        assertTrue(tile.video)
        assertEquals(0.5f, tile.preparing)
        assertEquals(0.25f, tile.progress) // half the encode is a quarter of the whole
        up.videoGate!!.complete(Unit)
        advanceUntilIdle()
        val done = c.state.value.attachments.single()
        assertNull(done.preparing)
        assertEquals(1f, done.progress)
        assertTrue(done.uploaded!!.isVideo)
    }

    @Test
    fun `send waits for the video and the note names it`() = runTest {
        val publish = FakePublish()
        val up = FakeMediaUploader().apply { videoGate = CompletableDeferred() }
        val c = controller(publish, up)
        c.attachVideo("content://v/1")
        advanceUntilIdle()
        assertFalse(c.state.value.attachmentsReady)
        up.videoGate!!.complete(Unit)
        advanceUntilIdle()
        c.send("watch")
        advanceUntilIdle()
        val a = publish.lastAttachments.single()
        assertEquals("video/mp4", a.mime)
        assertTrue(a.image!!.endsWith(".jpg"))
        assertEquals(30.0, a.durationSeconds)
    }

    @Test
    fun `remove of the video tile discards it`() = runTest {
        val up = FakeMediaUploader()
        val c = controller(uploader = up)
        c.attachVideo("content://v/1")
        advanceUntilIdle()
        val tile = c.state.value.attachments.single()
        c.removeAttachment(tile.id)
        assertEquals(listOf(tile.uploaded!!.sha256), up.discarded)
        assertTrue(c.state.value.canAttachPictures && c.state.value.canAttachVideo)
    }

    @Test
    fun `a failed video can be retried as a video`() = runTest {
        val up = FakeMediaUploader().apply { failure = com.monostr.app.media.UploadException.Unavailable() }
        val c = controller(uploader = up)
        c.attachVideo("content://v/1")
        advanceUntilIdle()
        val tile = c.state.value.attachments.single()
        assertTrue(tile.error != null && tile.video)
        up.failure = null
        c.retry(tile.id)
        advanceUntilIdle()
        assertEquals(listOf("content://v/1", "content://v/1"), up.videoUploads)
        assertTrue(c.state.value.attachments.single().uploaded!!.isVideo)
    }

    @Test
    fun `closing the composer while encoding cancels and discards nothing that is not up`() = runTest {
        val up = FakeMediaUploader().apply { videoGate = CompletableDeferred() }
        val c = controller(uploader = up)
        c.attachVideo("content://v/1")
        advanceUntilIdle()
        c.discardUnsent()
        up.videoGate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(up.discarded.isEmpty())
        assertNull(c.state.value.attachments.single().uploaded)
    }
}
