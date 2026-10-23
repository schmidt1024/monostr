package com.monostr.app.ui.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import com.monostr.app.ui.tips.LocalAmountUnit
import com.monostr.app.ui.media.NoteThumbnail
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monostr.app.R
import com.monostr.app.data.Presets
import com.monostr.app.ui.common.Avatar
import com.monostr.app.ui.common.MentionNames
import com.monostr.app.ui.common.asString
import com.monostr.app.ui.common.relativeTime
import com.monostr.app.ui.theme.MoneroGlyph
import com.monostr.nostr.model.NoteMedia
import com.monostr.nostr.repo.AboutNote
import com.monostr.nostr.repo.NotificationKind

/** One row of the notifications list (spec 11-C §5): a group of likes or reposts, a reply/quote/mention card, or a tip. */
@Composable
fun NotificationRowItem(row: NotificationRowUi, names: Map<String, String>, onOpenThread: (String) -> Unit, onOpenProfile: (String) -> Unit) {
    when (val r = row.row) {
        is NotificationRow.Group -> GroupRow(r, row, names, onOpenThread, onOpenProfile)
        is NotificationRow.Single -> when (r.item.kind) {
            NotificationKind.TIP -> TipRow(r, row, names, onOpenThread, onOpenProfile)
            else -> EventCard(r, row, names, onOpenThread, onOpenProfile)
        }
    }
}

/** The excerpt of the referenced note: its text, the media marker, or why there is none (spec 11-C §5.4). */
@Composable
private fun aboutText(about: AboutNote?, names: Map<String, String>): String = when (about) {
    null -> stringResource(R.string.notif_note_loading)
    AboutNote.Missing -> stringResource(R.string.notif_note_missing)
    AboutNote.Withdrawn -> stringResource(R.string.notif_note_withdrawn)
    // a like of my repost names the repost: its original carries the text
    is AboutNote.Found -> (about.note.repostOf ?: about.note).let { excerpt(it.displayContent, it.media.isNotEmpty(), names) }
}

/** Excerpts are one to three lines: the note's own line breaks would spend them on blank lines. */
object NotificationExcerpt {
    private val BLANKS = Regex("\\s+") // regex

    fun oneLine(text: String): String = text.replace(BLANKS, " ").trim()
}

@Composable
private fun excerpt(text: String, hasMedia: Boolean, names: Map<String, String>): String {
    val plain = NotificationExcerpt.oneLine(MentionNames.plain(text, names))
    return if (hasMedia) stringResource(R.string.notification_has_media, plain) else plain
}

/** A tap opens the note unless it is withdrawn (nothing to open) — a missing one is fetched by the thread itself. */
private fun opensThread(about: AboutNote?) = about != AboutNote.Withdrawn

@Composable
private fun GroupRow(group: NotificationRow.Group, row: NotificationRowUi, names: Map<String, String>, onOpenThread: (String) -> Unit, onOpenProfile: (String) -> Unit) {
    val (icon, tint) = when (group.kind) {
        NotificationKind.REPOST -> Icons.Outlined.Repeat to MaterialTheme.colorScheme.primary
        else -> Icons.Filled.Favorite to MaterialTheme.colorScheme.error
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = opensThread(row.about)) { onOpenThread(group.aboutId) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("notification-${group.key}"),
        verticalAlignment = Alignment.Top,
    ) {
        LeadingSlot {
            if (group.reactions.isNotEmpty()) {
                // emoji reactions show themselves instead of the heart
                Text(group.reactions.first(), fontSize = 26.sp, maxLines = 1, softWrap = false, modifier = Modifier.testTag("notification-emoji-${group.key}"))
            } else {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(LEADING_ICON))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // the newest faces fill one line, at most 7 (fewer on a narrow screen), like X; never a second line
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fit = ((maxWidth + FACE_GAP) / (FACE + FACE_GAP)).toInt()
                Row(horizontalArrangement = Arrangement.spacedBy(FACE_GAP)) {
                    for (actor in group.actors.take(minOf(MAX_FACES, fit))) {
                        Avatar(row.pictures[actor], size = FACE, modifier = Modifier.clickable { onOpenProfile(actor) }.testTag("notification-avatar-${actor.take(8)}"), contentDescription = stringResource(R.string.avatar_of, row.names[actor] ?: actor.take(8)))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(groupSentence(group, row.names), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(aboutText(row.about, names), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("notification-about-${group.key}"))
            Text(relativeTime(group.createdAt).asString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Thumbnail(row.about)
    }
}

/** "A liked", "A and B liked", "A, B and N others liked" (spec 11-C §5.1). */
@Composable
private fun groupSentence(group: NotificationRow.Group, names: Map<String, String>): String {
    val a = names[group.actors[0]] ?: group.actors[0].take(8)
    val b = group.actors.getOrNull(1)?.let { names[it] ?: it.take(8) }
    val others = group.count - 2
    val liked = group.kind != NotificationKind.REPOST
    // with an emoji among them the row says "reacted"; one person's emoji is named
    if (liked && group.reactions.isNotEmpty()) return when {
        b == null -> stringResource(R.string.notif_reacted_one, a, group.reactions.first())
        others <= 0 -> stringResource(R.string.notif_reacted_two, a, b)
        else -> pluralStringResource(R.plurals.notif_reacted_more, others, a, b, others)
    }
    return when {
        b == null -> stringResource(if (liked) R.string.notif_liked_one else R.string.notif_reposted_one, a)
        others <= 0 -> stringResource(if (liked) R.string.notif_liked_two else R.string.notif_reposted_two, a, b)
        else -> pluralStringResource(if (liked) R.plurals.notif_liked_more else R.plurals.notif_reposted_more, others, a, b, others)
    }
}

/** A reply, quote or mention as a compact card without actions (spec 11-C §5.2, variant A). */
@Composable
private fun EventCard(single: NotificationRow.Single, row: NotificationRowUi, names: Map<String, String>, onOpenThread: (String) -> Unit, onOpenProfile: (String) -> Unit) {
    val item = single.item
    val from = item.from
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = item.noteId != null) { item.noteId?.let(onOpenThread) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("notification-${item.id}"),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(from?.let { row.pictures[it] }, size = LEADING_WIDTH, modifier = Modifier.clickable(enabled = from != null) { from?.let(onOpenProfile) }.testTag("notification-avatar-${from.orEmpty().take(8)}"), contentDescription = from?.let { stringResource(R.string.avatar_of, row.names[it] ?: it.take(8)) })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(from?.let { row.names[it] } ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (item.kind == NotificationKind.MENTION) Text(" " + stringResource(R.string.notif_mentioned), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text(relativeTime(item.createdAt).asString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // spec §5.4: no about line until the note is known
            if (item.aboutId != null && row.about != null) {
                val label = if (item.kind == NotificationKind.QUOTE) R.string.notif_about_quote else R.string.notif_about_reply
                Text(stringResource(label, aboutText(row.about, names)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("notification-about-${item.id}"))
            }
            if (item.text.isNotBlank()) {
                val parsed = remember(item.text) { NoteMedia.parse(item.text, emptyList(), withQuote = false) }
                Text(excerpt(parsed.displayContent, parsed.media.isNotEmpty(), names), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A tip, as before, plus the note it went to or "to your profile" (spec 11-C §5.3). */
@Composable
private fun TipRow(single: NotificationRow.Single, row: NotificationRowUi, names: Map<String, String>, onOpenThread: (String) -> Unit, onOpenProfile: (String) -> Unit) {
    val item = single.item
    val target = item.target
    val enabled = target != NotificationTarget.None && opensThread(row.about)
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled) {
                when (target) {
                    is NotificationTarget.Thread -> onOpenThread(target.noteId)
                    is NotificationTarget.Profile -> onOpenProfile(target.pubkey)
                    NotificationTarget.None -> Unit
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("notification-${item.id}"),
        verticalAlignment = Alignment.Top,
    ) {
        LeadingSlot { Icon(MoneroGlyph, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(LEADING_ICON)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val name = item.from?.let { row.names[it] } ?: stringResource(R.string.tip_anonymous)
            Text("$name ${item.amount?.let { stringResource(R.string.notif_tipped, Presets.format(it, LocalAmountUnit.current), LocalAmountUnit.current.label) } ?: stringResource(R.string.notif_tipped_plain)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(if (item.aboutId == null) stringResource(R.string.notif_tip_profile) else aboutText(row.about, names), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("notification-about-${item.id}"))
            Text(relativeTime(item.createdAt).asString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Thumbnail(row.about)
    }
}

/** X: every row starts with a column as wide as a reply's avatar, so all texts begin on one line. */
private val LEADING_WIDTH = 40.dp
private val LEADING_ICON = 32.dp

@Composable
private fun LeadingSlot(content: @Composable () -> Unit) {
    Box(Modifier.width(LEADING_WIDTH), contentAlignment = Alignment.TopCenter) { content() }
}

/** X: a small picture at the right when the note the row is about has one. */
@Composable
private fun Thumbnail(about: AboutNote?) {
    val note = (about as? AboutNote.Found)?.note ?: return
    Spacer(Modifier.width(12.dp))
    NoteThumbnail(note.repostOf ?: note)
}

/** The faces of a grouped notification: up to [MAX_FACES] in one line. */
private val FACE = 28.dp
private val FACE_GAP = 6.dp
private const val MAX_FACES = 7
