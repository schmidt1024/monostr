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
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.FontWeight
import coil3.compose.AsyncImage
import com.monostr.app.R
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import com.monostr.app.data.linkpreview.LinkPreview
import com.monostr.app.data.linkpreview.LinkPreviews
import com.monostr.app.ui.common.NoteLinks
import com.monostr.app.ui.common.NoteSpan
import com.monostr.nostr.model.Note
import kotlinx.coroutines.delay
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
        return firstPreviewUrl(note.displayContent)
    }

    /** The first link of [text] that gets a card: no direct picture, video or sound file. */
    fun firstPreviewUrl(text: String): String? {
        val url = NoteLinks.annotate(text).firstNotNullOfOrNull { (it as? NoteSpan.Url)?.url } ?: return null
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

    /** The user's own draft: no mute check, the link is theirs. */
    fun cachedOwn(url: String): LinkPreview? = previews.cached(url)
    suspend fun loadOwn(url: String): LinkPreview? = previews.get(url)
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

/** How long the composer waits after the last keystroke before it loads a card, so a link typed by hand is not fetched letter by letter. */
private const val DRAFT_DEBOUNCE_MS = 600L

/**
 * The card the composer shows under the text (2026-10-30, like X's editor): the first qualifying link of [text], same
 * setting and rules as a note, null while [blocked] (pictures attached or a quote: the note will get no card either).
 */
@Composable
fun rememberDraftLink(text: String, blocked: Boolean): Pair<Note, NoteLink>? {
    if (!LocalMediaSettings.current.linkPreviews || blocked) return null
    val loader = LocalLinkPreviewLoader.current ?: return null
    val url = remember(text) { LinkPreviewRules.firstPreviewUrl(text) } ?: return null
    var preview by remember(url) { mutableStateOf(loader.cachedOwn(url)) }
    LaunchedEffect(url) { if (preview == null) { delay(DRAFT_DEBOUNCE_MS); preview = loader.loadOwn(url) } }
    // a stand-in note for the card: the user's own, never sensitive; its id keys the tap that loads a waiting picture
    val draft = remember { Note(DRAFT_ID, author = "", content = "", createdAt = 0, kind = 1, rootId = null, replyToId = null, repostOf = null, mentionedPubkeys = emptyList()) }
    return preview?.let { draft to NoteLink(url, it) }
}

const val DRAFT_ID = "draft-link"

/** The card under a note's text, for [rememberNoteLink]'s result. */
@Composable
fun NoteLinkPreview(note: Note, link: NoteLink?) {
    link?.let { LinkPreviewCard(note, it.url, it.preview) }
}

@Composable
fun LinkPreviewCard(note: Note, url: String, preview: LinkPreview, onDismiss: (() -> Unit)? = null) {
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
    if (preview.large && image != null) {
        // like X (2026-10-30): the title on one line in a small dark label on the picture, the domain under the card
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("link-preview-$id8")) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.91f).clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)) {
                PreviewImage(image, note, covered, waiting, open, onFailed = { imageFailed = true }, Modifier.fillMaxSize())
                onDismiss?.let { DismissButton(it, Modifier.align(Alignment.TopStart)) }
                Text(
                    preview.title, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.75f))
                        .clickable(onClickLabel = cd, onClick = open).semantics { contentDescription = cd }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("link-preview-overlay-$id8"),
                )
            }
            Text(
                // small and light: the domain is a quiet note under the picture, not a heading (user 2026-10-30)
                domain, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal, letterSpacing = 0.02.em), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).clickable(onClickLabel = cd, onClick = open),
            )
        }
    } else {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).testTag("link-preview-$id8"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            image?.let { PreviewImage(it, note, covered, waiting, open, onFailed = { imageFailed = true }, Modifier.size(72.dp)) }
            PreviewText(domain, preview.title, Modifier.weight(1f).then(textPart))
            onDismiss?.let { DismissButton(it) }
        }
    }
}

/** The composer's «no card» button (like X's editor): a small dark round X. */
@Composable
private fun DismissButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.padding(8.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.75f))
            .clickable(onClickLabel = stringResource(R.string.link_preview_remove), onClick = onClick)
            .testTag("link-preview-dismiss"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.link_preview_remove), tint = Color.White, modifier = Modifier.size(16.dp))
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
