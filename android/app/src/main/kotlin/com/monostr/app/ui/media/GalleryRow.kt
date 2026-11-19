package com.monostr.app.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.monostr.app.R
import com.monostr.app.ui.theme.PlainShapes
import com.monostr.app.ui.theme.Spacing
import com.monostr.nostr.model.NoteMedia

/**
 * Backlog 93 (spec 2026-11-19 gallery carousel): 2-20 pictures and videos as one horizontally scrolling row of
 * equal-height, aspect-true cards, X-like dots above it when the row overflows. The row height and the overflow are
 * fixed from the first layout ([GalleryGeometry.layout]); a size learned later only changes that card's width, so the
 * note never jumps and the dots never appear or vanish. Covered or waiting rows show no dots and do not scroll.
 */
@Composable
internal fun GalleryRow(
    media: List<NoteMedia>, tag: String, blurred: Boolean, placeholderOnly: Boolean, enabled: Boolean,
    onOpenMedia: (Int) -> Unit, onOpenVideo: (String) -> Unit, maxHeightDp: Float,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val rowWidth = maxWidth.value
        // learned sizes (MediaRatios) per card; a card's picture reporting its size updates only its own width
        val learned = remember(media) { mutableStateListOf(*media.map { MediaRatios[it.url] }.toTypedArray()) }
        val video = { m: NoteMedia -> m.kind == NoteMedia.Kind.VIDEO }
        val first = remember(media, rowWidth, maxHeightDp) {
            GalleryGeometry.layout(media.mapIndexed { i, m -> GalleryGeometry.aspect(m.dim, learned[i], video(m)) }, rowWidth, maxHeightDp)
        }
        val rowHeight = first.rowHeightDp
        // recomputed only when a learned size arrives, not on every scroll frame
        val learnedNow = learned.toList()
        val widths = remember(media, learnedNow, rowHeight, rowWidth) {
            media.mapIndexed { i, m -> GalleryGeometry.cardWidth(GalleryGeometry.aspect(m.dim, learnedNow[i], video(m)), rowHeight, rowWidth).first }
        }
        val state = rememberLazyListState()
        val n = media.size
        val description = pluralStringResource(R.plurals.media_gallery, n, n)
        Column(Modifier.fillMaxWidth()) {
            if (first.overflows && !placeholderOnly) {
                // fractional position of the current card; blends to the last dot while the last card is visible
                // read only inside GalleryDotsRow: a swipe recomposes the dots, not the row
                val position = remember(state, n) {
                    derivedStateOf {
                        // before the row's first layout there is no item info: plain first index (no last-dot flash)
                        val info = state.layoutInfo
                        if (info.visibleItemsInfo.isEmpty()) state.firstVisibleItemIndex.toFloat()
                        else {
                            val firstSize = info.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }?.size?.takeIf { it > 0 }
                            val raw = state.firstVisibleItemIndex + (if (firstSize != null) state.firstVisibleItemScrollOffset.toFloat() / firstSize else 0f)
                            val last = info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == n - 1 }
                            val remaining = last?.let { (it.offset + it.size - (info.viewportEndOffset - info.afterContentPadding)).toFloat().coerceAtLeast(0f) }
                            val scrolled = if (state.firstVisibleItemIndex == 0) state.firstVisibleItemScrollOffset.toFloat() else null
                            GalleryDots.position(n, raw, remaining, last?.size?.toFloat() ?: 0f, scrolled)
                        }
                    }
                }
                // 6 dp of dots, then 6 dp of space above the row
                Box(Modifier.height(12.dp)) { GalleryDotsRow(n, { position.value }, tag) }
            }
            LazyRow(
                state = state,
                userScrollEnabled = enabled,
                horizontalArrangement = Arrangement.spacedBy(GalleryGeometry.GAP_DP.dp),
                modifier = Modifier.fillMaxWidth().height(rowHeight.dp).testTag("note-media-row-$tag").semantics { contentDescription = description },
            ) {
                itemsIndexed(media, key = { i, m -> "$i:${m.url}" }) { i, m ->
                    val stated = m.dim
                    MediaCell(
                        m, i, tag, Modifier.width(widths[i].dp).fillMaxHeight().clip(PlainShapes.medium), blurred, placeholderOnly, enabled, onOpenMedia, onOpenVideo,
                        contentScale = ContentScale.Crop,
                        // same rule as the single picture: the loaded size wins only without dim or when it contradicts dim (turned photo)
                        onSize = { w, h ->
                            if (stated == null || MediaRatios.contradicts(stated, w to h)) {
                                MediaRatios.put(m.url, w, h)
                                if (i < learned.size) learned[i] = MediaRatios[m.url]
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The dot indicator ([GalleryDots]): small edge dots, the active one a longer and brighter pill. Decorative only. */
@Composable
internal fun GalleryDotsRow(count: Int, position: () -> Float, tag: String) {
    val color = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.height(6.dp).testTag("note-media-dots-$tag").clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GalleryDots.dots(count, position()).forEach { d ->
            val alpha = 0.25f + (0.6f - 0.25f) * d.active
            if (d.small) {
                Box(Modifier.size(4.dp).background(color.copy(alpha = alpha), CircleShape))
            } else {
                Box(Modifier.width(lerp(10.dp, 20.dp, d.active)).height(4.dp).background(color.copy(alpha = alpha), RoundedCornerShape(50)))
            }
        }
    }
}
