package com.monostr.app.ui.media

import coil3.ImageLoader
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.monostr.app.R
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import com.monostr.app.data.linkpreview.LinkPreview
import com.monostr.app.data.linkpreview.LinkPreviews
import com.monostr.app.ui.common.NoteLinks
import com.monostr.app.ui.common.NoteSpan
import com.monostr.nostr.model.Note
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import com.monostr.nostr.repo.ListState
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Spec link previews 2.1: which link of a note gets a card, apart from the setting and the mute check. */
object LinkPreviewRules {
    private val MEDIA_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "svg", "mp4", "webm", "mov", "m4v", "mp3", "ogg", "wav")

    /** The domain the card names: the one a tap opens, never the page the fetch ended on after redirects. */
    fun shownDomain(url: String): String = url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: NoteLinks.shownUrl(url)

    /**
     * The note's text while [url]'s card shows: a link that ends the note leaves it, the card opens it (like X);
     * a link inside a sentence stays, so the sentence stays whole.
     */
    fun textWithCard(text: String, url: String): String {
        val trimmed = text.trimEnd()
        if (!trimmed.endsWith(url)) return text
        val before = trimmed.dropLast(url.length)
        // only the whole link: "…/x" is not the end of "…/x/more"
        if (before.isNotEmpty() && !before.last().isWhitespace()) return text
        return before.trimEnd()
    }

    fun firstPreviewUrl(note: Note): String? {
        if (note.media.isNotEmpty() || note.quotedId != null) return null
        val url = NoteLinks.annotate(note.displayContent).firstNotNullOfOrNull { (it as? NoteSpan.Url)?.url } ?: return null
        val path = url.toHttpUrlOrNull()?.encodedPath?.lowercase() ?: return null
        if (path.substringAfterLast('/').substringAfterLast('.', "") in MEDIA_EXT) return null
        return url
    }
}

/**
 * One per logged-in account, like the quote loader: muted authors (spec 9.3) never cause a request,
 * and nothing loads before the mute list itself has (its set is empty until then, review I6).
 */
class LinkPreviewLoader(private val previews: LinkPreviews, private val muted: StateFlow<Set<String>>, private val mute: StateFlow<ListState>) {
    fun cached(note: Note, url: String): LinkPreview? = if (!mute.value.loaded || note.author in muted.value) null else previews.cached(url)
    suspend fun load(note: Note, url: String): LinkPreview? {
        mute.first { it.loaded }
        if (note.author in muted.value) return null
        // checked again afterwards: the author may have been muted while the page loaded
        return previews.get(url)?.takeIf { note.author !in muted.value }
    }
}

val LocalLinkPreviewLoader = staticCompositionLocalOf<LinkPreviewLoader?> { null }

/** The guarded image loader for preview pictures (security review); without one no picture loads. */
val LocalPreviewImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

/** A note's previewed link and its card data, once loaded. */
class NoteLink(val url: String, val preview: LinkPreview)

/**
 * The card of a note (spec 2), or null: setting off, no loader, no qualifying link, no data (yet). The note's text and
 * its card read the same one, so the text leaves out the link only while the card shows it.
 */
@Composable
fun rememberNoteLink(note: Note): NoteLink? {
    if (!LocalMediaSettings.current.linkPreviews) return null
    // spec 8: never inside a screen opened from a direct message (the page's host is the sender's pick)
    if (!com.monostr.app.ui.common.ForeignRule.linkPreview(com.monostr.app.ui.common.LocalAllowForeign.current)) return null
    val loader = LocalLinkPreviewLoader.current ?: return null
    val url = remember(note.id, note.displayContent) { LinkPreviewRules.firstPreviewUrl(note) } ?: return null
    var preview by remember(url) { mutableStateOf(loader.cached(note, url)) }
    LaunchedEffect(url) { if (preview == null) preview = loader.load(note, url) }
    return preview?.let { NoteLink(url, it) }
}

/** The card under a note's text, for [rememberNoteLink]'s result. */
@Composable
fun NoteLinkPreview(note: Note, link: NoteLink?) {
    link?.let { LinkPreviewCard(note, it.url, it.preview) }
}

@Composable
fun LinkPreviewCard(note: Note, url: String, preview: LinkPreview) {
    val uriHandler = LocalUriHandler.current
    val id8 = note.id.take(8)
    val domain = remember(url) { LinkPreviewRules.shownDomain(url) }
    var imageFailed by remember(preview.imageUrl) { mutableStateOf(false) }
    val image = preview.imageUrl?.takeIf { !imageFailed }
    val (covered, waiting) = rememberMediaCover(note, hasImages = image != null)
    val cd = stringResource(R.string.link_preview_cd, preview.title, domain)
    val open: () -> Unit = { runCatching { uriHandler.openUri(url) } }
    val shape = RoundedCornerShape(12.dp)
    val textPart = Modifier.clickable(onClickLabel = cd, onClick = open).semantics { contentDescription = cd }.padding(12.dp)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).testTag("link-preview-$id8")) {
        if (preview.large && image != null) {
            // the domain and the title on a dark gradient over the picture's lower edge: one block, nothing under it
            Box(Modifier.fillMaxWidth().aspectRatio(1.91f)) {
                PreviewImage(image, note, covered, waiting, open, onFailed = { imageFailed = true }, Modifier.fillMaxSize())
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        // dark well before the text starts: the title stays readable on bright pictures full of their own text
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.62f), 1f to Color.Black.copy(alpha = 0.88f)))
                        .clickable(onClickLabel = cd, onClick = open).semantics { contentDescription = cd }
                        .padding(start = 12.dp, end = 12.dp, top = 36.dp, bottom = 10.dp)
                        .testTag("link-preview-overlay-$id8"),
                ) {
                    Text(domain, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(preview.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                image?.let { PreviewImage(it, note, covered, waiting, open, onFailed = { imageFailed = true }, Modifier.size(72.dp)) }
                PreviewText(domain, preview.title, Modifier.weight(1f).then(textPart))
            }
        }
    }
}

@Composable
private fun PreviewText(domain: String, title: String, modifier: Modifier) {
    Column(modifier) {
        Text(domain, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Covered (sensitive) or waiting ("media on tap") pictures are not downloaded; a tap reveals them, like note media (spec 2.3). */
@Composable
private fun PreviewImage(url: String, note: Note, covered: Boolean, waiting: Boolean, onOpen: () -> Unit, onFailed: () -> Unit, modifier: Modifier) {
    val id8 = note.id.take(8)
    val hidden = covered || waiting
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { if (hidden) MediaReveals.reveal(note.id) else onOpen() }
            .testTag(if (hidden) "link-preview-load-$id8" else "link-preview-image-$id8"),
    ) {
        if (hidden) {
            Icon(
                if (covered) Icons.Filled.VisibilityOff else Icons.Outlined.Image,
                contentDescription = stringResource(if (covered) R.string.media_sensitive else R.string.media_tap_to_load),
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center),
            )
        } else {
            // never the app's shared loader: that one has no address check
            LocalPreviewImageLoader.current?.let { loader ->
                AsyncImage(model = url, contentDescription = null, imageLoader = loader, contentScale = ContentScale.Crop, onError = { onFailed() }, modifier = Modifier.matchParentSize())
            }
        }
    }
}
