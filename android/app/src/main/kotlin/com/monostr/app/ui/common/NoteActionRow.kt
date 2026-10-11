package com.monostr.app.ui.common

import com.monostr.app.ui.tips.LocalAmountUnit
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.sp
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
    /** Launch feedback: a long press on the heart picks an emoji reaction (NIP-25). */
    onReact: (String) -> Unit = {},
) {
    var repostMenu by remember { mutableStateOf(false) }
    var reactMenu by remember { mutableStateOf(false) }
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
            // the own reaction shows in place of the heart when it is an emoji; "+" is the filled heart
            val emoji = if (note.liked) Reactions.shown(note.reaction) else null
            ActionIcon(
                icon = if (note.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, description = stringResource(R.string.note_like), selected = note.liked,
                tint = if (note.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onLike, tag = "note-like-${shown.id.take(8)}", count = note.counts?.likes?.takeIf { showCounts },
                onLongClick = { if (!note.liked) reactMenu = true }, longClickLabel = stringResource(R.string.note_react), emoji = emoji,
                stateDescription = emoji?.let { stringResource(R.string.note_reacted_with, it) },
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
                    text = if (compact) t.count.toString() else stringResource(R.string.note_tips_chip, Presets.withUnit(t.total, LocalAmountUnit.current), t.count),
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
        if (reactMenu) {
            ReactionSheet(onPick = { e -> reactMenu = false; onReact(e) }, onDismiss = { reactMenu = false })
        }
        if (repostMenu) {
            RepostSheet(
                reposted = note.reposted,
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
private fun ActionIcon(
    icon: ImageVector, description: String, selected: Boolean, tint: Color, onClick: () -> Unit, tag: String, stateDescription: String? = null, enabled: Boolean = true, count: Long? = null,
    onLongClick: (() -> Unit)? = null, longClickLabel: String? = null,
    /** Shown in place of [icon] (an own emoji reaction). */
    emoji: String? = null,
) {
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
            .combinedClickable(enabled = enabled, onClick = onClick, onClickLabel = description, onLongClick = onLongClick, onLongClickLabel = longClickLabel)
            .semantics {
                this.selected = selected
                if (stateDescription != null) this.stateDescription = stateDescription
            }
            .testTag(tag)
            .padding(start = GLYPH_START, end = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (emoji != null) {
                Box(Modifier.size(22.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                    Text(emoji, fontSize = 17.sp, maxLines = 1, softWrap = false, modifier = Modifier.testTag("$tag-emoji"))
                }
            } else {
                Icon(icon, contentDescription = description, tint = if (!enabled) MaterialTheme.colorScheme.outline else tint, modifier = Modifier.size(22.dp))
            }
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
private fun RepostSheet(reposted: Boolean, onRepost: () -> Unit, onQuote: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("repost-sheet")) {
        ListItem(
            headlineContent = { Text(stringResource(if (reposted) R.string.action_undo_repost else R.string.note_repost)) },
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

/** The user's quick reactions, in their order (Settings → Reactions); provided at the app root. */
val LocalQuickReactions = staticCompositionLocalOf { Reactions.QUICK }

/** NIP-25 contents: which one is a plain like, and the quick choices of the reaction sheet. */
object Reactions {
    /** The default quick choices, five (user 2026-10-11); the user adds, removes and reorders them in Settings. */
    val QUICK = listOf("❤️", "🔥", "😂", "💯", "🙏")

    /** At most this many quick choices: two rows of the sheet. */
    const val MAX_QUICK = 12

    fun encode(list: List<String>): String = Json.encodeToString(ListSerializer(String.serializer()), list)

    /** The stored list; null, unreadable or empty is the default, unusable entries are dropped. */
    fun decode(stored: String?): List<String> {
        val raw = stored?.let { runCatching { Json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: return QUICK
        return raw.fold(emptyList<String>()) { acc, e -> add(acc, e) }.ifEmpty { QUICK }
    }

    /** [list] with [input] appended when it is a usable emoji, not there yet, and the list is not full. */
    fun add(list: List<String>, input: String): List<String> {
        val e = clean(input) ?: return list
        return if (e in list || list.size >= MAX_QUICK) list else list + e
    }

    /** [list] with the entry at [index] swapped with its neighbour [delta] (-1 up, +1 down); unchanged at the ends. */
    fun move(list: List<String>, index: Int, delta: Int): List<String> {
        val to = index + delta
        if (index !in list.indices || to !in list.indices) return list
        return list.toMutableList().also { it[index] = list[to]; it[to] = list[index] }
    }

    fun remove(list: List<String>, index: Int): List<String> = list.filterIndexed { i, _ -> i != index }

    /** "+" and an empty content are the like (the heart). */
    fun isLike(content: String): Boolean = content.isBlank() || content.trim() == "+"

    /**
     * An emoji, and only that: at most [MAX_CODE_POINTS] code points, every one from the emoji ranges in [EMOJI]
     * plus the joiners and modifiers that build emoji sequences, and at least one pictograph. Reactions are relay
     * input that the app shows as a symbol and in "X reacted with …"; an allow-list keeps letters, digits and their
     * look-alikes (superscript or circled digits, Roman numerals, enclosed letters, regional indicators that render
     * as letters) out, so a reaction cannot read as "sent you 10 XMR" (security review 2026-10-11). Flags are lost
     * with the regional indicators. Null for anything else.
     */
    fun clean(input: String): String? {
        val t = input.trim()
        if (t.isEmpty() || t.codePointCount(0, t.length) > MAX_CODE_POINTS) return null
        val cps = t.codePoints().toArray()
        if (!cps.all { cp -> EMOJI.any { cp in it } || JOINERS.any { cp in it } }) return null
        return t.takeIf { cps.any { cp -> EMOJI.any { cp in it } } }
    }

    /** Pictographic ranges (Unicode emoji blocks), without enclosed alphanumerics, dingbat digits and regional indicators. */
    private val EMOJI = listOf(
        0x1F300..0x1FAFF, // pictographs, emoticons, transport, supplemental symbols, symbols and pictographs ext-A (skin tones inside)
        0x1F004..0x1F004, 0x1F0CF..0x1F0CF, // mahjong tile, playing card
        0x2600..0x2775, 0x2794..0x27BF, // miscellaneous symbols and dingbats, without the dingbat circled digits 2776–2793
        0x2300..0x23FF, // miscellaneous technical (⌚ ⏰ ⏳)
        0x2B00..0x2BFF, // arrows and stars (⬆ ⭐ ⭕)
        0x2190..0x21FF, // arrows
        0x25A0..0x25FF, // geometric shapes
        0x2700..0x2700,
    )

    /** Parts of emoji sequences: zero-width joiner, variation selectors, the tag characters of subdivision flags. */
    private val JOINERS = listOf(0x200D..0x200D, 0xFE0E..0xFE0F, 0xE0020..0xE007F)

    /** A reaction's content as shown: the emoji, or null for a plain like and for anything that is no emoji (shown as the heart). */
    fun shown(content: String?): String? = content?.takeUnless(::isLike)?.let(::clean)

    const val MAX_CODE_POINTS = 8
}

/** A long press on the heart: quick emojis and a field for any other. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReactionSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("reaction-sheet")) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.note_react), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LocalQuickReactions.current.forEachIndexed { i, e ->
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = e) { onPick(e) }.testTag("reaction-quick-$i"),
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 26.sp) }
                }
            }
            Spacer(Modifier.height(12.dp))
            var other by remember { mutableStateOf("") }
            val cleaned = Reactions.clean(other)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = other, onValueChange = { other = it }, singleLine = true,
                    label = { Text(stringResource(R.string.note_react_other)) },
                    modifier = Modifier.weight(1f).testTag("reaction-other"),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { cleaned?.let(onPick) }, enabled = cleaned != null, modifier = Modifier.testTag("reaction-other-send")) {
                    Text(stringResource(R.string.note_react_send))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
