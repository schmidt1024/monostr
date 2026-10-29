package com.monostr.app.ui.common

import android.os.SystemClock
import android.text.format.DateUtils
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
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.monostr.app.R
import com.monostr.app.ui.media.LocalMediaSettings
import com.monostr.app.ui.media.MediaCoverBadge
import com.monostr.app.ui.media.MediaReveals
import com.monostr.nostr.PerfTrace
import com.monostr.nostr.model.AddressLinks
import com.monostr.nostr.model.AddressRef
import com.monostr.nostr.model.AddressableEvent
import com.monostr.nostr.model.Article
import com.monostr.nostr.model.LiveStream
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.AddressResult
import com.monostr.nostr.repo.AddressableRepository
import com.monostr.nostr.repo.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

sealed interface AddressUi {
    data object Loading : AddressUi
    data class Found(val event: AddressableEvent) : AddressUi
    data object Missing : AddressUi
    data object Deleted : AddressUi
    /** The author is muted: a placeholder, like [QuoteUi.Muted]. */
    data object Muted : AddressUi
}

/** Spec 2026-10-29 naddr: the card's data. Results are kept by [AddressableRepository]; this only adds the mute rule and profiles. */
class AddressLoader(
    private val addresses: AddressableRepository,
    private val profiles: ProfileRepository,
    private val muted: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
) {
    fun cached(ref: AddressRef, allowForeign: Boolean = true): AddressUi? = addresses.cached(ref, allowForeign)?.let { map(it) }

    /**
     * A live event is asked again when the remembered one is old, so callers load it even when [cached] answered.
     * [allowForeign] false (DM cards): only the user's own relays are asked.
     */
    suspend fun load(ref: AddressRef, allowForeign: Boolean = true): AddressUi {
        val result = try {
            addresses.get(ref, allowForeign)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AddressResult.Missing
        }
        return map(result)
    }

    fun profile(pubkey: String): Flow<Profile> = profiles.observe(pubkey)

    suspend fun prefetch(pubkey: String) {
        try {
            profiles.prefetch(listOf(pubkey))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the row falls back to the short key
        }
    }

    private fun map(r: AddressResult): AddressUi = when (r) {
        is AddressResult.Found -> if (r.event.pubkey in muted.value || LiveStream.parse(r.event)?.host in muted.value) AddressUi.Muted else AddressUi.Found(r.event)
        AddressResult.Missing -> AddressUi.Missing
        AddressResult.Deleted -> AddressUi.Deleted
    }
}

/** Spec 8: a DM sender must not pick relays the app contacts, so a card in a chat bubble ignores the naddr's hints (and loads with `allowForeign = false`). */
fun dmAddressRef(ref: AddressRef): AddressRef = ref.copy(relays = emptyList())

/**
 * Spec 8 (final review I1): false inside a screen opened from a direct message (the article reader with `foreign = false`, the chat):
 * the note cards in it fetch nothing a sender could point elsewhere: quotes without hints or author relays, address cards on the
 * user's own relays only, no link preview. True everywhere else.
 */
val LocalAllowForeign = staticCompositionLocalOf { true }

/** What a note card in a [LocalAllowForeign] scope may use to fetch what it embeds. */
object ForeignRule {
    /** Hints of a quoted note; none without foreign relays. */
    fun quoteHints(relays: List<String>, allowForeign: Boolean): List<String> = if (allowForeign) relays else emptyList()

    /** The quoted note's author (its relays are asked); none without foreign relays. */
    fun quoteAuthor(author: String?, allowForeign: Boolean): String? = author.takeIf { allowForeign }

    /** An embedded address: as it is, or without its hints and on the own relays only. */
    fun address(ref: AddressRef, allowForeign: Boolean): Pair<AddressRef, Boolean> = if (allowForeign) ref to true else dmAddressRef(ref) to false

    /** Whether a link preview may be fetched (the page's host is the sender's pick). */
    fun linkPreview(allowForeign: Boolean): Boolean = allowForeign
}

/** Provided once by the logged-in nav host; null outside of it (isolated UI tests), where cards show [AddressUi.Missing]. */
val LocalAddressLoader = staticCompositionLocalOf<AddressLoader?> { null }

/**
 * Opens an article (kind 30023) in the in-app reader with (ref, allowForeign); provided by the logged-in nav host. Null (isolated UI tests): the
 * browser, like every other kind.
 */
val LocalOpenArticle = staticCompositionLocalOf<((AddressRef, Boolean) -> Unit)?> { null }

/**
 * Spec 2026-10-29 naddr: an `naddr` as a card in the `outlineVariant` frame of the quote card: type
 * label and status badge, optional 16:9 picture (same cover rules as note pictures), title, summary and
 * the person (host or author). The whole card opens the web link (njump.me, zap.stream for streams),
 * the person row the profile. Never blocks the list: cached first, the rest loads in a LaunchedEffect.
 * [sensitive]: the hosting note is sensitive, so the picture is covered like the note's own. [allowForeign] false
 * (DM cards): neither hints nor the author's relays are contacted, only the user's own relays.
 */
@Composable
fun AddressCard(
    ref: AddressRef,
    names: Map<String, String>,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
    sensitive: Boolean = false,
    allowForeign: Boolean = true,
) {
    val loader = LocalAddressLoader.current
    var ui by remember(ref.coordinate, ref.relays, loader) {
        mutableStateOf(loader?.cached(ref, allowForeign) ?: if (loader == null) AddressUi.Missing else AddressUi.Loading)
    }
    LaunchedEffect(ref.coordinate, ref.relays, loader) {
        if (loader == null) return@LaunchedEffect
        val shown = ui
        // a remembered live event may be old: ask again, the repository answers at once when it is fresh
        if (shown == AddressUi.Loading || (shown is AddressUi.Found && shown.event.kind == 30311)) ui = loader.load(ref, allowForeign)
    }
    val resolved = ui
    val started = remember(ref.coordinate) { SystemClock.elapsedRealtime() }
    LaunchedEffect(resolved is AddressUi.Found) {
        // the delta tells a cached card (a few ms) from a cold one
        if (resolved is AddressUi.Found) PerfTrace.mark("address card shown ${ref.kind} ${SystemClock.elapsedRealtime() - started}ms")
    }
    val uriHandler = LocalUriHandler.current
    val openArticle = LocalOpenArticle.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable {
                // an article opens in the reader; a card that did not resolve (or any other kind) goes to the web
                if (ref.kind == Article.KIND && openArticle != null && resolved !is AddressUi.Missing) openArticle(ref, allowForeign)
                else runCatching { uriHandler.openUri(AddressLinks.web(ref)) }
            }
            .testTag("address-card-${ref.identifier.take(8)}"),
    ) {
        when (val q = resolved) {
            AddressUi.Loading -> Box(Modifier.fillMaxWidth().height(48.dp).padding(10.dp).testTag("address-card-loading"), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            AddressUi.Muted -> Text(
                stringResource(R.string.mute_quote_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp).testTag("address-card-muted"),
            )
            AddressUi.Missing, AddressUi.Deleted -> Text(
                stringResource(if (q == AddressUi.Deleted) R.string.address_deleted else R.string.address_missing),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(10.dp).testTag("address-card-missing"),
            )
            is AddressUi.Found -> FoundBody(q.event, loader, names, onOpenProfile, sensitive)
        }
    }
}

@Composable
private fun FoundBody(event: AddressableEvent, loader: AddressLoader?, names: Map<String, String>, onOpenProfile: (String) -> Unit, sensitive: Boolean) {
    val now = remember(event.id) { System.currentTimeMillis() / 1000 }
    val model = remember(event.id) { AddressCardModel.from(event, now) }
    Column(Modifier.testTag(if (model.live) "address-card-live" else "address-card-generic")) {
        model.image?.let { PictureBox(it, event.id, model.covered || sensitive) }
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(model.typeLabel), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                model.badge?.let { badge ->
                    Spacer(Modifier.width(8.dp))
                    StatusBadge(badge, model.startsAt)
                }
            }
            Text(model.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            model.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            val fallback = remember(model.personPubkey) { Profile.empty(model.personPubkey) }
            val person by remember(model.personPubkey, loader) { loader?.profile(model.personPubkey) ?: flowOf(fallback) }.collectAsState(fallback)
            LaunchedEffect(model.personPubkey, loader) { loader?.prefetch(model.personPubkey) }
            Row(Modifier.padding(top = 6.dp).clickable { onOpenProfile(model.personPubkey) }, verticalAlignment = Alignment.CenterVertically) {
                Avatar(person.picture, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    names[model.personPubkey] ?: person.shownName, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                model.participants?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(pluralStringResource(R.plurals.address_watching, it, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(badge: AddressCardModel.Badge, startsAt: Long?) {
    val context = LocalContext.current
    val (text, bg, fg) = when (badge) {
        AddressCardModel.Badge.LIVE -> Triple(stringResource(R.string.address_live), Color(0xFFD32F2F), Color.White)
        AddressCardModel.Badge.ENDED -> Triple(stringResource(R.string.address_ended), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        AddressCardModel.Badge.PLANNED -> {
            val starts = startsAt?.let { DateUtils.formatDateTime(context, it * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH) }
            // without a start time the badge is the plain planned state: "Starts" alone would read wrong
            Triple(if (starts != null) stringResource(R.string.address_planned, starts) else stringResource(R.string.address_planned_unknown), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
    Text(
        text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp).testTag("address-card-status"),
    )
}

/** The card's picture, 16:9; covered (sensitive) or waiting ("media on tap") it is not fetched and a tap reveals it, like [QuoteImage]. */
@Composable
private fun PictureBox(url: String, id: String, sensitive: Boolean) {
    val settings = LocalMediaSettings.current
    val revealed by MediaReveals.revealed.collectAsStateWithLifecycle()
    val covered = settings.blurSensitive && sensitive && id !in revealed
    val waiting = !covered && settings.mediaOnTap && id !in revealed
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (covered || waiting) Modifier.clickable { MediaReveals.reveal(id) } else Modifier)
            .testTag("address-card-media"),
        contentAlignment = Alignment.Center,
    ) {
        if (covered || waiting) {
            MediaCoverBadge(
                if (covered) Icons.Filled.VisibilityOff else Icons.Outlined.Image,
                title = stringResource(if (covered) R.string.media_sensitive else R.string.media_tap_to_load),
            )
        } else {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
