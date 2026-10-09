package com.monostr.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import com.monostr.app.R
import com.monostr.nostr.model.Note

/** X's "Replying to @name" line on a reply in a list: whom the reply answers, from its `p` tags. */
object ReplyContext {
    /** Names shown in the line; the rest is a number. */
    const val NAMES = 2

    data class Shown(val names: List<String>, val more: Int)

    /** The accounts a reply answers: its `p` tags without its author and without repeats, in tag order; empty for a root note. */
    fun targets(note: Note): List<String> =
        if (!note.isReply) emptyList() else note.mentionedPubkeys.filter { it != note.author }.distinct()

    fun shown(targets: List<String>): Shown = Shown(targets.take(NAMES), (targets.size - NAMES).coerceAtLeast(0))

    // stand-ins for the names in the translated text, replaced by the links afterwards
    internal const val FIRST = '\u0001'
    internal const val SECOND = '\u0002'
}

/** The line itself; nothing when the reply names nobody but its author. Each name opens its profile. */
@Composable
fun ReplyContextLine(note: Note, names: Map<String, String>, onOpenProfile: (String) -> Unit, modifier: Modifier = Modifier) {
    val targets = ReplyContext.targets(note)
    if (targets.isEmpty()) return
    val shown = ReplyContext.shown(targets)
    val first = ReplyContext.FIRST.toString()
    val second = ReplyContext.SECOND.toString()
    val template = when {
        shown.more > 0 -> pluralStringResource(R.plurals.note_replying_more, shown.more, first, second, shown.more)
        shown.names.size == 2 -> stringResource(R.string.note_replying_two, first, second)
        else -> stringResource(R.string.note_replying_one, first)
    }
    val nameStyles = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary))
    val text = buildAnnotatedString {
        for (c in template) {
            val pubkey = when (c) {
                ReplyContext.FIRST -> shown.names[0]
                ReplyContext.SECOND -> shown.names.getOrNull(1)
                else -> null
            }
            if (pubkey == null) append(c) else withLink(LinkAnnotation.Clickable("reply-to", nameStyles) { onOpenProfile(pubkey) }) { append(MentionNames.label(pubkey, names)) }
        }
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.testTag("note-reply-to-${note.id.take(8)}"))
}
