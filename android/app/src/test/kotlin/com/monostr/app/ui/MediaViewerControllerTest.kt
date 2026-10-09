package com.monostr.app.ui

import com.monostr.app.ui.media.MediaViewerController
import com.monostr.nostr.model.NoteMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaViewerControllerTest {
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `loads the note's pictures and clamps the start page`() = runTest {
        val n = note('a').copy(media = listOf(NoteMedia("https://x/1.jpg", NoteMedia.Kind.IMAGE), NoteMedia("https://v/c.mp4", NoteMedia.Kind.VIDEO), NoteMedia("https://x/2.jpg", NoteMedia.Kind.IMAGE)))
        val c = MediaViewerController(n.id, index = 2, threads = FakeThreads(emptyMap(), notes = mapOf(n.id to n)), scope = eager())
        c.start(); advanceUntilIdle()
        assertEquals(listOf("https://x/1.jpg", "https://x/2.jpg"), c.state.value.media.map { it.url }) // videos are not paged
        assertEquals(1, c.state.value.page) // index 2 in note.media is the second picture
    }

    @Test
    fun `missing note is reported`() = runTest {
        val c = MediaViewerController("b".repeat(64), 0, FakeThreads(emptyMap()), eager())
        c.start(); advanceUntilIdle()
        assertTrue(c.state.value.missing)
    }

    @Test
    fun `a url list is paged without a note lookup`() = runTest {
        val c = MediaViewerController(listOf("https://x/1.jpg", "https://x/2.jpg"), index = 1, scope = eager())
        c.start(); advanceUntilIdle()
        assertEquals(listOf("https://x/1.jpg", "https://x/2.jpg"), c.state.value.media.map { it.url })
        assertTrue(c.state.value.media.all { it.kind == NoteMedia.Kind.IMAGE })
        assertEquals(1, c.state.value.page)
        assertFalse(c.state.value.missing)
    }
}
