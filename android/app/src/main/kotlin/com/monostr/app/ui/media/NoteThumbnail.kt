package com.monostr.app.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMedia

/** What a thumbnail of [note] shows: its first picture, or the poster of its first video; null without either. */
object Thumbnails {
    data class Source(val url: String, val blurhash: String?, val video: Boolean)

    fun source(note: Note): Source? {
        val m = note.media.firstOrNull { it.kind == NoteMedia.Kind.IMAGE || it.poster != null } ?: return null
        return if (m.kind == NoteMedia.Kind.IMAGE) Source(m.url, m.blurhash, video = false) else Source(m.poster!!, m.blurhash, video = true)
    }
}

/**
 * The small picture X shows at the right of a notification about a note with media (user 2026-10-11).
 * The note's cover rules hold: a sensitive note (with blurring on) or "media on tap" fetches nothing and
 * shows the blurhash or a plain box; nothing to reveal here, a tap on the row opens the note.
 */
@Composable
fun NoteThumbnail(note: Note, size: Dp = 48.dp) {
    val source = Thumbnails.source(note) ?: return
    val (covered, waiting) = rememberMediaCover(note, hasImages = true)
    val shape = RoundedCornerShape(8.dp)
    val placeholder = rememberBlurhash(source.blurhash)
    Box(
        Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant).testTag("note-thumbnail-${note.id.take(8)}"),
        contentAlignment = Alignment.Center,
    ) {
        if (covered || waiting) {
            placeholder?.let { Image(BitmapPainter(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (covered) Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        } else {
            AsyncImage(
                model = source.url, contentDescription = null, contentScale = ContentScale.Crop,
                placeholder = placeholder?.let { BitmapPainter(it) }, modifier = Modifier.fillMaxSize(),
            )
        }
        if (source.video && !covered) Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
    }
}
