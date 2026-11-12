package com.monostr.app.ui

import com.monostr.app.media.VideoFrame
import com.monostr.app.ui.media.Thumbnails
import com.monostr.nostr.model.NoteMedia
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The notification thumbnail (2026-10-11): the first picture, else a video's poster, else a bare video's derived frame (2026-11-12). */
class ThumbnailsTest {
    private fun with(vararg media: NoteMedia) = note('x').copy(media = media.toList())

    @Test
    fun `the first picture, a video's poster, a bare video's derived frame last, nothing without media`() {
        val video = NoteMedia("https://m.example/v.mp4", NoteMedia.Kind.VIDEO, poster = "https://m.example/p.jpg")
        val picture = NoteMedia("https://m.example/a.jpg", NoteMedia.Kind.IMAGE, blurhash = "LEHV6nWB2yk8")
        assertEquals(Thumbnails.Source("https://m.example/p.jpg", null, video = true), Thumbnails.source(with(video, picture)))
        assertEquals(Thumbnails.Source("https://m.example/a.jpg", "LEHV6nWB2yk8", video = false), Thumbnails.source(with(NoteMedia("https://m.example/v.mp4", NoteMedia.Kind.VIDEO), picture)))
        assertEquals(Thumbnails.Source(VideoFrame("https://m.example/v.mp4"), null, video = true), Thumbnails.source(with(NoteMedia("https://m.example/v.mp4", NoteMedia.Kind.VIDEO))))
        assertNull(Thumbnails.source(note('x')))
    }
}
