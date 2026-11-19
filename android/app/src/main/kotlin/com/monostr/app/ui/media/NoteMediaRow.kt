package com.monostr.app.ui.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.ui.graphics.Color
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
import coil3.compose.AsyncImage
import com.monostr.app.R
import com.monostr.app.ui.common.LocalTorActive
import com.monostr.app.ui.theme.PlainShapes
import com.monostr.app.ui.theme.Spacing
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

/** Decoded blurhashes by hash: a cell scrolled back in (its `remember` gone) does not decode on the main thread again. */
private val blurhashes = android.util.LruCache<String, ImageBitmap>(200)

/** The decoded 32x32 blurhash of a picture, or null. */
@Composable
fun rememberBlurhash(hash: String?): ImageBitmap? =
    remember(hash) {
        hash?.let { h ->
            blurhashes.get(h) ?: Blurhash.decode(h, 32, 32)?.let { Bitmap.createBitmap(it, 32, 32, Bitmap.Config.ARGB_8888).asImageBitmap() }?.also { blurhashes.put(h, it) }
        }
    }

/**
 * Spec 3.2: one picture full width; from two on one horizontally scrolling row with dots (backlog 93, [GalleryRow]); videos as a poster with a
 * play icon. A sensitive note is covered until tapped; with "media on tap" every picture waits
 * for a tap. Tapping a picture opens the viewer, tapping a video the player.
 */
@Composable
fun NoteMediaRow(
    note: Note, onOpenMedia: (index: Int) -> Unit, onOpenVideo: (url: String) -> Unit,
    /** The opened note (thread focus): a single picture may take [MediaBoxes.DETAIL_SCREEN_SHARE] of the screen, else [MediaBoxes.FEED_SCREEN_SHARE]. */
    detail: Boolean = false,
) {
    if (note.media.isEmpty()) return
    val tag = note.id.take(8)
    // A video poster is an image download too, so it waits like a picture; a video without one downloads its first frame
    // (spec 2026-11-12), so every cell here downloads something.
    val (covered, waiting) = rememberMediaCover(note, hasImages = true)
    Box(Modifier.fillMaxWidth().padding(top = Spacing.s)) {
        // Covered or waiting media is never fetched: only the blurhash placeholder (or a grey box) shows.
        // backlog 62: a single picture as tall as the screen allows (header, actions and bars still in view), never below the old cap
        val screenShare = if (detail) MediaBoxes.DETAIL_SCREEN_SHARE else MediaBoxes.FEED_SCREEN_SHARE
        val maxHeightDp = maxOf(MediaBoxes.MAX_HEIGHT_DP, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * screenShare)
        MediaGrid(note.media, tag, blurred = covered, placeholderOnly = covered || waiting, onOpenMedia = onOpenMedia, onOpenVideo = onOpenVideo, enabled = !covered && !waiting, maxHeightDp = maxHeightDp)
        if (covered) {
            Cover(tag = "note-media-cover-$tag", icon = Icons.Filled.VisibilityOff, title = stringResource(R.string.media_sensitive),
                subtitle = note.contentWarning?.takeIf { it.isNotBlank() } ?: stringResource(R.string.media_tap_to_show)) { MediaReveals.reveal(note.id) }
        } else if (waiting) {
            Cover(tag = "note-media-load-$tag", icon = Icons.Outlined.Image, title = stringResource(R.string.media_tap_to_load), subtitle = null) { MediaReveals.reveal(note.id) }
        }
    }
}

@Composable
private fun BoxScope.Cover(tag: String, icon: ImageVector, title: String, subtitle: String?, onTap: () -> Unit) {
    Box(
        Modifier.matchParentSize().clip(PlainShapes.medium).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)).clickable(onClick = onTap, onClickLabel = title).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        MediaCoverBadge(icon, title, subtitle, Modifier.testTag("media-cover-badge-${tag.substringAfterLast('-')}"))
    }
}

/**
 * Icon and text over a covered or waiting picture: light on a dark translucent pill, readable in
 * both themes and over any blurhash (the theme's onPrimary is black in the dark theme).
 */
@Composable
fun MediaCoverBadge(icon: ImageVector, title: String?, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(MaterialTheme.shapes.large).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = Spacing.l, vertical = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Color.White) }
        subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f)) }
    }
}

@Composable
private fun MediaGrid(media: List<NoteMedia>, tag: String, blurred: Boolean, placeholderOnly: Boolean, enabled: Boolean, onOpenMedia: (Int) -> Unit, onOpenVideo: (String) -> Unit, maxHeightDp: Float = MediaBoxes.MAX_HEIGHT_DP) {
    val shape = PlainShapes.medium
    if (media.size == 1) {
        val m = media[0]
        val video = m.kind == NoteMedia.Kind.VIDEO
        // spec 2026-10-16 §5/§6: without dim, the size learned when the picture (or the video's poster) loaded;
        // remembered per URL, so scrolling back shows the right box at once
        var learned by remember(m.url) { mutableStateOf(MediaRatios[m.url]) }
        // Full width, height from the real ratio up to a cap (see MediaBoxes): only a picture taller
        // than the cap is cropped, everything else is fully visible.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // owner 2026-11-12 (Pegah, via Primal): `dim 4000x3000` for a portrait photo turned by EXIF; the loaded picture's own
            // size wins when it contradicts the stated one, otherwise the stated size stays and nothing is relaid out
            val stated = m.dim
            val box = MediaBoxes.single(learned?.takeIf { stated == null || MediaRatios.contradicts(stated, it) } ?: stated, widthDp = maxWidth.value, maxHeightDp = maxHeightDp, video = video)
            MediaCell(
                m, 0, tag, Modifier.fillMaxWidth().aspectRatio(box.ratio).clip(shape), blurred, placeholderOnly, enabled, onOpenMedia, onOpenVideo,
                contentScale = if (box.crop) ContentScale.Crop else ContentScale.Fit,
                alignment = if (box.crop && !video) Alignment.TopCenter else Alignment.Center,
                onSize = { w, h ->
                    if (stated == null || MediaRatios.contradicts(stated, w to h)) { MediaRatios.put(m.url, w, h); learned = MediaRatios[m.url] }
                },
            )
        }
        return
    }
    // backlog 93: from two items one horizontal row; every card opens the viewer at its own index (the viewer holds all)
    GalleryRow(media, tag, blurred, placeholderOnly, enabled, onOpenMedia, onOpenVideo, maxHeightDp)
}

@Composable
internal fun MediaCell(
    m: NoteMedia, index: Int, tag: String, modifier: Modifier, blurred: Boolean, placeholderOnly: Boolean, enabled: Boolean, onOpenMedia: (Int) -> Unit, onOpenVideo: (String) -> Unit,
    contentScale: ContentScale = ContentScale.Crop,
    /** Where a cropped picture is anchored; a single too-tall picture keeps its top (user 2026-10-30, screenshots start there). */
    alignment: Alignment = Alignment.Center,
    /** Called with the loaded picture's (or poster's) size: the single item and the gallery cards learn their ratio from it. */
    onSize: ((Int, Int) -> Unit)? = null,
) {
    val placeholder = rememberBlurhash(m.blurhash)
    val videoLabel = stringResource(R.string.media_video)
    val uriHandler = LocalUriHandler.current
    // Spec 3.2: a tap on a failed picture opens it externally instead of the (unusable) viewer.
    var failure by remember(m.url) { mutableStateOf<MediaFailure?>(null) }
    val failed = failure != null
    val torActive = LocalTorActive.current
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
        val source: Any? = MediaModels.cell(m)
        // owner 2026-11-12: the 32x32 blurhash went through Coil as the placeholder and was drawn with the picture's own scale
        // (Fit for most), a square with bars in a box of another shape. It lies under the picture now, always cropped to the
        // whole cell, until the picture is there.
        var loaded by remember(source) { mutableStateOf(false) }
        if (placeholder != null && !placeholderOnly && source != null && !loaded) {
            Image(placeholder, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        when {
            placeholder != null && (placeholderOnly || source == null) -> Image(placeholder, contentDescription = m.alt, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            placeholderOnly || source == null -> Unit
            m.kind == NoteMedia.Kind.IMAGE && GifPlayback.isGif(m.url, m.mime) -> GifImage(
                m.url, key = "$tag-$index", contentDescription = m.alt, contentScale = contentScale, alignment = alignment,
                placeholder = null,
                onSize = { w, h -> loaded = true; onSize?.invoke(w, h) }, onError = { e -> failure = MediaFailures.of(e, torActive) },
            )
            // AsyncImage, not SubcomposeAsyncImage: no subcomposition per loading state in a scrolling list (Coil's advice)
            else -> AsyncImage(
                model = source, contentDescription = m.alt, contentScale = contentScale, alignment = alignment, modifier = Modifier.fillMaxSize(),
                onSuccess = { s -> loaded = true; onSize?.invoke(s.result.image.width, s.result.image.height) },
                onError = { e -> failure = MediaFailures.of(e.result.throwable, torActive) },
            )
        }
        // a derived video frame that did not come gives up quietly: the play button stays alone
        val shown = failure
        if (shown != null && !placeholderOnly && source != null && !MediaModels.derived(source)) MediaFailedMark(shown)
        if (m.kind == NoteMedia.Kind.VIDEO) Icon(Icons.Filled.PlayArrow, contentDescription = videoLabel, tint = Color.White, modifier = Modifier.background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(Spacing.m))
        // owner 2026-11-19: the length from `imeta duration`, bottom left like a player; nothing is fetched for it
        m.durationSeconds?.takeIf { m.kind == NoteMedia.Kind.VIDEO }?.let { secs ->
            Text(
                VideoDuration.label(secs), style = MaterialTheme.typography.labelSmall, color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.s)
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall).padding(horizontal = Spacing.xs, vertical = Spacing.xxs)
                    .testTag("note-media-duration-$tag-$index"),
            )
        }
    }
}
