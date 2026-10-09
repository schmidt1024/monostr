package com.monostr.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.monostr.app.R
import com.monostr.app.ui.media.rememberBlurhash
import com.monostr.app.ui.media.rememberMediaCover
import com.monostr.app.ui.media.MediaReveals
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMedia
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.QuoteRepository
import com.monostr.nostr.repo.QuoteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rust.nostr.sdk.EventId
import java.util.concurrent.ConcurrentHashMap

sealed interface QuoteUi {
    data object Loading : QuoteUi
    data class Found(val note: Note, val author: Profile, val names: Map<String, String>) : QuoteUi
    data object Missing : QuoteUi
    /** The quoted note's author is muted (spec 9.3): the card shows a placeholder, the note opens on tap. */
    data object Muted : QuoteUi
}

/**
 * Spec 3: what a quote card shows. The note comes from [QuoteRepository] (at most one load per id),
 * the author and the names of the note's own mentions from [ProfileRepository]. Resolved cards are
 * kept, so scrolling back never shows the spinner again.
 */
class QuoteLoader(
    private val quotes: QuoteRepository,
    private val profiles: ProfileRepository,
    private val muted: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
) {
    private val found = ConcurrentHashMap<String, QuoteUi.Found>()

    fun cached(id: String): QuoteUi? {
        found[id]?.let { return if (it.note.author in muted.value) QuoteUi.Muted else it }
        return when (val c = quotes.cached(id)) {
            is QuoteResult.Missing -> QuoteUi.Missing
            is QuoteResult.Found -> if (c.note.author in muted.value) QuoteUi.Muted else null
            null -> null
        }
    }

    suspend fun load(id: String, hints: List<String>): QuoteUi {
        found[id]?.let { return if (it.note.author in muted.value) QuoteUi.Muted else it }
        val result = try {
            quotes.get(id, hints)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            QuoteResult.Missing
        }
        val note = (result as? QuoteResult.Found)?.note ?: return QuoteUi.Missing
        if (note.author in muted.value) return QuoteUi.Muted
        val author = try {
            profiles.prefetch(listOf(note.author))
            profiles.get(note.author, MentionNames.LOCAL_MAX_AGE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Profile.empty(note.author)
        }
        val names = try {
            MentionNames.resolve(profiles, MentionNames.collect(listOf(note)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
        if (found.size >= MAX_KEPT) found.clear()
        return QuoteUi.Found(note, author, names).also { found[id] = it }
    }

    companion object {
        private const val MAX_KEPT = 200
        fun shortRef(id: String): String {
            val bech32 = runCatching { EventId.parse(id).toBech32() }.getOrDefault(id)
            return bech32.take(10) + "…" + bech32.takeLast(4)
        }
    }
}

/** Provided once by the logged-in nav host; null outside of it (isolated UI tests), where cards show [QuoteUi.Missing]. */
val LocalQuoteLoader = staticCompositionLocalOf<QuoteLoader?> { null }

/**
 * Spec 3: the quoted note in an `outlineVariant` frame: 24 dp avatar, name, time, up to six lines of
 * text, the first picture as a 16:9 preview (same cover rules as note media). Depth 1: the text of a
 * card never gets a card of its own. Tap on the card opens the thread, tap on avatar/name the profile.
 */
@Composable
fun QuoteCard(
    quotedId: String,
    hints: List<String>,
    names: Map<String, String>,
    onOpenNote: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
    onHashtag: (String) -> Unit = {},
) {
    val loader = LocalQuoteLoader.current
    // keyed, so a reused composition slot never shows the previous id's card
    var ui by remember(quotedId, loader) {
        mutableStateOf(loader?.cached(quotedId) ?: if (loader == null) QuoteUi.Missing else QuoteUi.Loading)
    }
    LaunchedEffect(quotedId, loader) {
        if (loader != null && ui == QuoteUi.Loading) ui = loader.load(quotedId, hints)
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable { onOpenNote(quotedId) }
            .testTag("quote-${quotedId.take(8)}")
            .padding(10.dp),
    ) {
        when (val q = ui) {
            QuoteUi.Loading -> Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            QuoteUi.Muted -> Text(
                stringResource(R.string.mute_quote_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("quote-muted-${quotedId.take(8)}"),
            )
            QuoteUi.Missing -> {
                Text(stringResource(R.string.quote_missing), style = MaterialTheme.typography.bodyMedium)
                Text(QuoteLoader.shortRef(quotedId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is QuoteUi.Found -> {
                Row(Modifier.clickable { onOpenProfile(q.note.author) }, verticalAlignment = Alignment.CenterVertically) {
                    Avatar(q.author.picture, Modifier.size(24.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(q.author.shownName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Text(relativeTime(q.note.createdAt).asString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (q.note.displayContent.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    NoteText(
                        q.note.displayContent, onOpen = { onOpenNote(quotedId) }, onHashtag = onHashtag, onOpenProfile = onOpenProfile,
                        onOpenNote = onOpenNote, names = names + q.names, maxLines = 6,
                    )
                }
                q.note.media.firstOrNull { it.kind == NoteMedia.Kind.IMAGE }?.let { QuoteImage(q.note, it) { onOpenNote(quotedId) } }
            }
        }
    }
}

/** First picture of a quoted note, 16:9; a sensitive note is covered and "media on tap" waits for a tap, like NoteMediaRow. */
@Composable
private fun QuoteImage(note: Note, media: NoteMedia, onOpen: () -> Unit) {
    val (covered, waiting) = rememberMediaCover(note, hasImages = true)
    val placeholder = rememberBlurhash(media.blurhash)
    Box(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { if (covered || waiting) MediaReveals.reveal(note.id) else onOpen() }
            .testTag("quote-media-${note.id.take(8)}"),
        contentAlignment = Alignment.Center,
    ) {
        if (covered || waiting) {
            // covered or waiting media is never fetched: only the blurhash (or the plain box) shows
            placeholder?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Icon(
                if (covered) Icons.Filled.VisibilityOff else Icons.Filled.Download,
                contentDescription = stringResource(if (covered) R.string.media_sensitive else R.string.media_tap_to_load),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            AsyncImage(model = media.url, contentDescription = media.alt, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
