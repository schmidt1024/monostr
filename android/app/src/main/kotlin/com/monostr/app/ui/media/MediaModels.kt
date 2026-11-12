package com.monostr.app.ui.media

import com.monostr.app.media.VideoFrame
import com.monostr.nostr.model.NoteMedia

/** What a media cell asks Coil for; no Android types, so the JVM tests reach it. */
object MediaModels {
    /**
     * A picture its URL, a video its poster; a video without one the frame Monostr derives from the video itself (spec 2026-11-12).
     * A cell with a poster loads exactly what it did before.
     */
    fun cell(m: NoteMedia): Any = when {
        m.kind == NoteMedia.Kind.IMAGE -> m.url
        m.poster != null -> m.poster!!
        else -> VideoFrame(m.url)
    }

    /** Whether [model] is a derived frame: its failure stays quiet (the play button alone), there is no picture to open elsewhere. */
    fun derived(model: Any?): Boolean = model is VideoFrame
}
