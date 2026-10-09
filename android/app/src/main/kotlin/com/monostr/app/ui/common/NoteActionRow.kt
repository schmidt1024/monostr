package com.monostr.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.app.data.Presets
import com.monostr.app.ui.feed.NoteUi
import com.monostr.app.ui.theme.MoneroGlyph
import com.monostr.app.ui.theme.MonostrFonts
import com.monostr.nostr.model.Note
import androidx.compose.ui.unit.Dp

/**
 * Reply · repost · like · bookmark · tip (+ tipper chip) of a note card (spec 4.2); shared by the list card
 * and the detail card (spec 11.5). [spread] distributes the icons over the width (the detail card, like X's
 * detail view); below [countsMinWidth] of inner width the counts are dropped, never the icons.
 */
@Composable
fun NoteActionRow(
    note: NoteUi,
    shown: Note,
    onReply: () -> Unit,
    onRepost: () -> Unit,
    onQuote: () -> Unit,
    onLike: () -> Unit,
    onBookmark: () -> Unit,
    onTip: () -> Unit,
    onShowTippers: () -> Unit,
    spread: Boolean = false,
    countsMinWidth: Dp = 300.dp,
) {
    var repostMenu by remember { mutableStateOf(false) }
    // one node for the callers: the row, the pending-tip line below it, and the sheet
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        // spec 4.2: numbers are dropped, never the icons, when the row would overflow (narrow cards)
        val showCounts = maxWidth >= countsMinWidth // three four-character counts at font 1.3 need ~290 dp beside two plain icons
        // in a list the first icon's glyph lines up with the text edge: the 48 dp target is shifted left by the glyph's inset
        // (the detail card spreads the icons, there the targets stay where they are)
        Row(Modifier.fillMaxWidth().then(if (spread) Modifier else Modifier.offset(x = -ICON_INSET)), horizontalArrangement = if (spread) Arrangement.SpaceBetween else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(
                icon = Icons.Outlined.ChatBubbleOutline, description = stringResource(R.string.note_reply), selected = false,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onReply, tag = "note-reply-${shown.id.take(8)}", count = note.counts?.replies?.takeIf { showCounts },
            )
            ActionIcon(
                icon = if (note.reposted) Icons.Filled.RepeatOn else Icons.Outlined.Repeat, description = stringResource(R.string.note_repost), selected = note.reposted,
                tint = if (note.reposted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { repostMenu = true }, tag = "note-repost-${shown.id.take(8)}", count = note.counts?.reposts?.takeIf { showCounts },
            )
            ActionIcon(
                icon = if (note.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, description = stringResource(R.string.note_like), selected = note.liked,
                tint = if (note.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onLike, tag = "note-like-${shown.id.take(8)}", count = note.counts?.likes?.takeIf { showCounts },
            )
            val bookmarkedLabel = stringResource(R.string.note_bookmarked)
            val notBookmarkedLabel = stringResource(R.string.note_not_bookmarked)
            ActionIcon(
                icon = if (note.bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, description = stringResource(R.string.note_bookmark),
                selected = note.bookmarked, stateDescription = if (note.bookmarked) bookmarkedLabel else notBookmarkedLabel,
                tint = if (note.bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onBookmark, tag = "note-bookmark-${shown.id.take(8)}", enabled = note.bookmarkEnabled,
            )
            // spec 5.5: the tip is its own action; the pending state also shows as text below
            val pendingLabel = stringResource(R.string.note_tip_pending)
            ActionIcon(
                icon = MoneroGlyph, description = stringResource(R.string.note_tip), selected = false,
                stateDescription = if (note.pendingTip) pendingLabel else null,
                tint = if (note.pendingTip) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onTip, tag = "note-tip-${shown.id.take(8)}", enabled = note.canTip,
            )
            note.tips?.takeIf { it.count > 0 }?.let { t ->
                val openTippers = stringResource(R.string.note_tips_open)
                var compact by remember(t) { mutableStateOf(false) }
                Text(
                    text = if (compact) t.count.toString() else stringResource(R.string.note_tips_chip, Presets.format(t.total), t.count),
                    style = MaterialTheme.typography.labelSmall, fontFamily = MonostrFonts.mono, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (it.hasVisualOverflow && !compact) compact = true },
                    modifier = Modifier
                        .then(if (spread) Modifier else Modifier.weight(1f, fill = false))
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .clickable(onClick = onShowTippers, onClickLabel = openTippers)
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .testTag("note-tips-${shown.id.take(8)}"),
                )
            }
        }
        }
        if (note.pendingTip) {
            Text(stringResource(R.string.note_tip_pending), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("note-pending-${shown.id.take(8)}"))
        }
    }
        if (repostMenu) {
            RepostSheet(
                onRepost = { repostMenu = false; onRepost() },
                onQuote = { repostMenu = false; onQuote() },
                onDismiss = { repostMenu = false },
            )
        }
}

/** Where the glyph starts inside its 48 dp target: (48 − 22) / 2. */
private val GLYPH_START = 13.dp

/** [GLYPH_START] less the ~1 dp the reply glyph leaves free inside its frame (checked on the Pixel: 13 dp sat 2–3 px left of the text). */
private val ICON_INSET = 12.dp

/** Icon button with `selected` semantics and a test tag on the click target; a count (spec 4.2) sits under a shrunk icon, the 48 dp target unchanged. */
@Composable
private fun ActionIcon(icon: ImageVector, description: String, selected: Boolean, tint: Color, onClick: () -> Unit, tag: String, stateDescription: String? = null, enabled: Boolean = true, count: Long? = null) {
    val locale = LocalLocale.current.platformLocale
    Box(
        // the whole target is clickable and at least 48 dp: minimumInteractiveComponentSize() around a
        // 40 dp box only enlarged the layout, the clickable node stayed 40 dp and edge taps were lost (0.2.0).
        // The count sits right of the icon (0.4.2, X-style) and widens the target to the right only: the
        // glyph keeps its place 13 dp from the start, whether a count shows or not (0.11.8; centred content
        // moved it left by 9 dp when the count arrived). The rounded clip covers the whole box, so nothing is cut.
        Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick, onClickLabel = description)
            .semantics {
                this.selected = selected
                if (stateDescription != null) this.stateDescription = stateDescription
            }
            .testTag(tag)
            .padding(start = GLYPH_START, end = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = description, tint = if (!enabled) MaterialTheme.colorScheme.outline else tint, modifier = Modifier.size(22.dp))
            if (count != null && count > 0) {
                Spacer(Modifier.width(4.dp))
                Text(formatCount(count, locale), style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, softWrap = false, modifier = Modifier.testTag("$tag-count"))
            }
        }
    }
}

/** Spec 4.1: the repost icon offers "Repost" (at once) and "Quote" (opens the composer). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepostSheet(onRepost: () -> Unit, onQuote: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("repost-sheet")) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.note_repost)) },
            leadingContent = { Icon(Icons.Outlined.Repeat, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onRepost).testTag("action-repost"),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.action_quote)) },
            leadingContent = { Icon(Icons.Outlined.FormatQuote, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onQuote).testTag("action-quote"),
        )
        Spacer(Modifier.height(24.dp))
    }
}
