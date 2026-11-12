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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.monostr.app.ui.media.MediaRatios
import com.monostr.app.ui.media.MediaBoxes
import androidx.compose.foundation.layout.BoxWithConstraints
import com.monostr.app.R
import com.monostr.app.ui.media.rememberBlurhash
import com.monostr.app.ui.media.MediaCoverBadge
import com.monostr.app.ui.media.rememberMediaCover
import com.monostr.app.ui.media.MediaReveals
import com.monostr.app.ui.theme.PlainShapes
import com.monostr.app.ui.theme.Spacing
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteMedia
import com.monostr.nostr.model.Profile
import com.monostr.nostr.model.ZapQuote
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.QuoteRepository
import com.monostr.nostr.repo.QuoteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import rust.nostr.sdk.EventId
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap

sealed interface QuoteUi {
    data object Loading : QuoteUi
    data class Found(val note: Note, val author: Profile, val names: Map<String, String>) : QuoteUi
    data object Missing : QuoteUi
    /** A quoted zap receipt (owner 2026-11-12): amount, who zapped whom, the comment; [sender] null when the receipt names none. */
    data class Zap(val zap: ZapQuote, val sender: Profile?, val recipient: Profile) : QuoteUi
    /** The quoted note's author is muted (spec 9.3): the card shows a placeholder, the note opens on tap. */
    data object Muted : QuoteUi
    /**
     * A quoted zap receipt whose sender is muted: the same placeholder, but a tap opens the zapped note, if any;
     * the receipt itself never opens as a thread (owner 2026-11-12).
     */
    data class MutedZap(val zappedNote: String?) : QuoteUi
}

/** Where a tap on a quote card goes. */
sealed interface QuoteTap {
    data class Note(val id: String) : QuoteTap
    data class Profile(val pubkey: String) : QuoteTap
    data object None : QuoteTap

    companion object {
        /** A zap opens the zapped note, else the sender's (or recipient's) profile; never the receipt itself. */
        fun of(ui: QuoteUi, quotedId: String): QuoteTap = when (ui) {
            is QuoteUi.Zap -> ui.zap.zappedNote?.let(::Note) ?: Profile(ui.zap.sender ?: ui.zap.recipient)
            // muted: no profile of the muted sender, just the note if there is one
            is QuoteUi.MutedZap -> ui.zappedNote?.let(::Note) ?: None
            else -> Note(quotedId)
        }
    }
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
    /** Resolved cards: [QuoteUi.Found] and [QuoteUi.Zap] only. */
    private val found = ConcurrentHashMap<String, QuoteUi>()

    /** A kept card, or [QuoteUi.Muted] when its author (a zap's sender) is muted now. */
    private fun shown(ui: QuoteUi): QuoteUi = if (owner(ui)?.let { it in muted.value } == true) mutedOf(ui) else ui

    private fun mutedOf(ui: QuoteUi): QuoteUi = if (ui is QuoteUi.Zap) QuoteUi.MutedZap(ui.zap.zappedNote) else QuoteUi.Muted

    private fun owner(ui: QuoteUi): String? = when (ui) {
        is QuoteUi.Found -> ui.note.author
        is QuoteUi.Zap -> ui.zap.sender
        else -> null
    }

    fun cached(id: String, author: String? = null): QuoteUi? {
        found[id]?.let { return shown(it) }
        return when (val c = quotes.cached(id, author)) {
            is QuoteResult.Missing -> QuoteUi.Missing
            is QuoteResult.Found -> if (c.note.author in muted.value) QuoteUi.Muted else null
            is QuoteResult.Zap -> if (c.zap.sender?.let { it in muted.value } == true) QuoteUi.MutedZap(c.zap.zappedNote) else null
            null -> null
        }
    }

    /** The card's author as it is now: a profile the background lookup finds after the card loaded replaces it (spec perf 3.2). */
    fun author(found: QuoteUi.Found): Flow<Profile> =
        profiles.observe(found.note.author).map { if (it == Profile.empty(it.pubkey)) found.author else it }

    suspend fun load(id: String, hints: List<String>, author: String? = null, guesses: List<String> = emptyList()): QuoteUi {
        found[id]?.let { return shown(it) }
        val result = try {
            quotes.get(id, hints, author, guesses)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            QuoteResult.Missing
        }
        if (result is QuoteResult.Zap) return zap(id, result.zap)
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
        return keep(id, QuoteUi.Found(note, author, names))
    }

    /** Sender and recipient names through the same profile loading as a note's author; no request of its own. */
    private suspend fun zap(id: String, zap: ZapQuote): QuoteUi {
        if (zap.sender != null && zap.sender in muted.value) return QuoteUi.MutedZap(zap.zappedNote)
        val people = listOfNotNull(zap.sender, zap.recipient).distinct()
        val loaded = try {
            profiles.prefetch(people)
            people.associateWith { profiles.get(it, MentionNames.LOCAL_MAX_AGE) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
        fun profile(pubkey: String) = loaded[pubkey] ?: Profile.empty(pubkey)
        return keep(id, QuoteUi.Zap(zap, zap.sender?.let(::profile), profile(zap.recipient)))
    }

    private fun keep(id: String, ui: QuoteUi): QuoteUi {
        if (found.size >= MAX_KEPT) found.clear()
        found[id] = ui
        return ui
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
    author: String? = null,
    /** Without [author]: the quoting note's mentioned people, whose relays are asked too (see [QuoteRepository.get]). */
    guesses: List<String> = emptyList(),
) {
    val loader = LocalQuoteLoader.current
    // keyed, so a reused composition slot never shows the previous id's card
    var ui by remember(quotedId, loader) {
        mutableStateOf(loader?.cached(quotedId, author) ?: if (loader == null) QuoteUi.Missing else QuoteUi.Loading)
    }
    LaunchedEffect(quotedId, loader) {
        if (loader != null && ui == QuoteUi.Loading) ui = loader.load(quotedId, hints, author, guesses)
    }
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .fillMaxWidth()
            .clip(PlainShapes.large) // the content clip stays a fast rounded rect; the squircle border covers the sub-pixel difference
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable {
                when (val tap = QuoteTap.of(ui, quotedId)) {
                    is QuoteTap.Note -> onOpenNote(tap.id)
                    is QuoteTap.Profile -> onOpenProfile(tap.pubkey)
                    QuoteTap.None -> Unit
                }
            }
            .testTag("quote-${quotedId.take(8)}")
            .padding(Spacing.m),
    ) {
        when (val q = ui) {
            QuoteUi.Loading -> Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            QuoteUi.Muted, is QuoteUi.MutedZap -> Text(
                stringResource(R.string.mute_quote_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("quote-muted-${quotedId.take(8)}"),
            )
            QuoteUi.Missing -> {
                // the relays asked have nothing (old notes get pruned, user 2026-10-30): say so, not «not found»
                Text(stringResource(R.string.quote_gone), style = MaterialTheme.typography.bodyMedium)
                Text(QuoteLoader.shortRef(quotedId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is QuoteUi.Zap -> ZapQuoteContent(q)
            is QuoteUi.Found -> {
                val author by remember(q, loader) { loader?.author(q) ?: flowOf(q.author) }.collectAsState(q.author)
                Row(Modifier.clickable { onOpenProfile(q.note.author) }, verticalAlignment = Alignment.CenterVertically) {
                    Avatar(author.picture, Modifier.size(24.dp))
                    Spacer(Modifier.width(Spacing.s))
                    Text(author.shownName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(Spacing.s))
                    Text(relativeTime(q.note.createdAt).asString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (q.note.displayContent.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.xs))
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

/** Owner 2026-11-12: a quoted zap receipt, compact; read only, no paying or wallet action. */
@Composable
private fun ZapQuoteContent(q: QuoteUi.Zap) {
    Text(
        stringResource(R.string.quote_zap_title, NumberFormat.getIntegerInstance().format(q.zap.amountSat)),
        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("quote-zap-${q.zap.id.take(8)}"),
    )
    Spacer(Modifier.height(Spacing.xs))
    Text(
        if (q.sender != null) stringResource(R.string.quote_zap_from_to, q.sender.shownName, q.recipient.shownName)
        else stringResource(R.string.quote_zap_to, q.recipient.shownName),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    if (q.zap.comment.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.xs))
        Text(q.zap.comment, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * First picture of a quoted note in its own shape, like a note's single picture (2026-10-30, was always 16:9 and cut
 * screenshots): full width, the real ratio from `dim` or learned on load, only a picture taller than [QUOTE_MAX_HEIGHT_DP]
 * is cropped, from the top. A sensitive note is covered and "media on tap" waits for a tap, like NoteMediaRow.
 */
@Composable
private fun QuoteImage(note: Note, media: NoteMedia, onOpen: () -> Unit) {
    val (covered, waiting) = rememberMediaCover(note, hasImages = true)
    val placeholder = rememberBlurhash(media.blurhash)
    var learned by remember(media.url) { mutableStateOf(MediaRatios[media.url]) }
    BoxWithConstraints(Modifier.padding(top = Spacing.s).fillMaxWidth()) {
        // as a note's single picture: the loaded size wins when it contradicts the stated `dim` (EXIF-turned photo, review 2026-11-12)
        val stated = media.dim
        val box = MediaBoxes.single(learned?.takeIf { stated == null || MediaRatios.contradicts(stated, it) } ?: stated, widthDp = maxWidth.value, maxHeightDp = QUOTE_MAX_HEIGHT_DP)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(box.ratio)
                .clip(PlainShapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { if (covered || waiting) MediaReveals.reveal(note.id) else onOpen() }
                .testTag("quote-media-${note.id.take(8)}"),
            contentAlignment = Alignment.Center,
        ) {
            if (covered || waiting) {
                // covered or waiting media is never fetched: only the blurhash (or the plain box) shows
                placeholder?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                MediaCoverBadge(
                    if (covered) Icons.Filled.VisibilityOff else Icons.Outlined.Image,
                    title = stringResource(if (covered) R.string.media_sensitive else R.string.media_tap_to_load),
                )
            } else {
                AsyncImage(
                    model = media.url, contentDescription = media.alt,
                    // a tall screenshot keeps its top (where its text starts); everything else fits whole
                    contentScale = if (box.crop) ContentScale.Crop else ContentScale.Fit,
                    alignment = if (box.crop) Alignment.TopCenter else Alignment.Center,
                    onSuccess = { s ->
                        val w = s.result.image.width
                        val h = s.result.image.height
                        if (stated == null || MediaRatios.contradicts(stated, w to h)) { MediaRatios.put(media.url, w, h); learned = MediaRatios[media.url] }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** A quote sits inside another note: its picture stops lower than a note's own (400 dp). */
private const val QUOTE_MAX_HEIGHT_DP = 320f
