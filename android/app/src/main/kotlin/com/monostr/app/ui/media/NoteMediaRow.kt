package com.monostr.app.ui.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import com.monostr.app.R
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMedia

/** Whether the pictures of a note are hidden ([covered]: sensitive) or wait for a tap ([waiting]: "media on tap"). */
data class MediaCover(val covered: Boolean, val waiting: Boolean)

/** The cover rules shared by [NoteMediaRow] and the quote card; [hasImages] says whether anything would be downloaded. */
@Composable
fun rememberMediaCover(note: Note, hasImages: Boolean): MediaCover {
    val settings = LocalMediaSettings.current
    val revealed by MediaReveals.revealed.collectAsStateWithLifecycle()
    val covered = settings.blurSensitive && note.isSensitive && note.id !in revealed
    val waiting = !covered && settings.mediaOnTap && note.id !in revealed && hasImages
    return MediaCover(covered, waiting)
}

/** The decoded 32x32 blurhash of a picture, or null. */
@Composable
fun rememberBlurhash(hash: String?): ImageBitmap? =
    remember(hash) { hash?.let { Blurhash.decode(it, 32, 32) }?.let { Bitmap.createBitmap(it, 32, 32, Bitmap.Config.ARGB_8888).asImageBitmap() } }

/**
 * Spec 3.2: one picture full width, two to four in a two-column grid; videos as a poster with a
 * play icon. A sensitive note is covered until tapped; with "media on tap" every picture waits
 * for a tap. Tapping a picture opens the viewer, tapping a video the player.
 */
@Composable
fun NoteMediaRow(note: Note, onOpenMedia: (index: Int) -> Unit, onOpenVideo: (url: String) -> Unit) {
    if (note.media.isEmpty()) return
    val tag = note.id.take(8)
    // A video poster is an image download too, so it waits like a picture.
    val (covered, waiting) = rememberMediaCover(note, hasImages = note.media.any { it.kind == NoteMedia.Kind.IMAGE || it.poster != null })
    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        // Covered or waiting media is never fetched: only the blurhash placeholder (or a grey box) shows.
        MediaGrid(note.media, tag, blurred = covered, placeholderOnly = covered || waiting, onOpenMedia = onOpenMedia, onOpenVideo = onOpenVideo, enabled = !covered && !waiting)
        if (covered) {
            Cover(tag = "note-media-cover-$tag", icon = Icons.Filled.VisibilityOff, title = stringResource(R.string.media_sensitive),
                subtitle = note.contentWarning?.takeIf { it.isNotBlank() } ?: stringResource(R.string.media_tap_to_show)) { MediaReveals.reveal(note.id) }
        } else if (waiting) {
            Cover(tag = "note-media-load-$tag", icon = Icons.Filled.Download, title = stringResource(R.string.media_tap_to_load), subtitle = null) { MediaReveals.reveal(note.id) }
        }
    }
}

@Composable
private fun BoxScope.Cover(tag: String, icon: ImageVector, title: String, subtitle: String?, onTap: () -> Unit) {
    Column(
        Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)).clickable(onClick = onTap, onClickLabel = title).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
        subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary) }
    }
}

@Composable
private fun MediaGrid(media: List<NoteMedia>, tag: String, blurred: Boolean, placeholderOnly: Boolean, enabled: Boolean, onOpenMedia: (Int) -> Unit, onOpenVideo: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    if (media.size == 1) {
        val m = media[0]
        // Full width, height from the real ratio up to a cap (see MediaBoxes): only a picture taller
        // than the cap is cropped, everything else is fully visible.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val box = MediaBoxes.single(m.dim, widthDp = maxWidth.value)
            MediaCell(
                m, 0, tag, Modifier.fillMaxWidth().aspectRatio(box.ratio).clip(shape), blurred, placeholderOnly, enabled, onOpenMedia, onOpenVideo,
                contentScale = if (box.crop) ContentScale.Crop else ContentScale.Fit,
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        media.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pair.forEachIndexed { col, m ->
                    val index = row * 2 + col
                    MediaCell(m, index, tag, Modifier.weight(1f).aspectRatio(1f).clip(shape), blurred, placeholderOnly, enabled, onOpenMedia, onOpenVideo)
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MediaCell(
    m: NoteMedia, index: Int, tag: String, modifier: Modifier, blurred: Boolean, placeholderOnly: Boolean, enabled: Boolean, onOpenMedia: (Int) -> Unit, onOpenVideo: (String) -> Unit,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val placeholder = rememberBlurhash(m.blurhash)
    val videoLabel = stringResource(R.string.media_video)
    val uriHandler = LocalUriHandler.current
    // Spec 3.2: a tap on a failed picture opens it externally instead of the (unusable) viewer.
    var failed by remember(m.url) { mutableStateOf(false) }
    val base = modifier
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .then(if (blurred) Modifier.blur(24.dp) else Modifier)
        .clickable(enabled = enabled, onClickLabel = if (m.kind == NoteMedia.Kind.VIDEO) videoLabel else m.alt) {
            when {
                m.kind == NoteMedia.Kind.VIDEO -> onOpenVideo(m.url)
                failed -> runCatching { uriHandler.openUri(m.url) }
                else -> onOpenMedia(index)
            }
        }
        .testTag("note-media-$tag-$index")
    Box(base, contentAlignment = Alignment.Center) {
        val source = if (m.kind == NoteMedia.Kind.VIDEO) m.poster else m.url
        when {
            placeholder != null && (placeholderOnly || source == null) -> Image(placeholder, contentDescription = m.alt, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            placeholderOnly || source == null -> Unit
            else -> SubcomposeAsyncImage(
                model = source, contentDescription = m.alt, contentScale = contentScale, modifier = Modifier.fillMaxSize(),
                loading = { if (placeholder != null) Image(placeholder, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) },
                error = { Icon(Icons.Filled.BrokenImage, contentDescription = stringResource(R.string.media_failed), tint = MaterialTheme.colorScheme.outline) },
                onError = { failed = true },
            )
        }
        if (m.kind == NoteMedia.Kind.VIDEO) Icon(Icons.Filled.PlayArrow, contentDescription = videoLabel, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(12.dp))
    }
}
