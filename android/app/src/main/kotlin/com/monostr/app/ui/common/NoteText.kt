package com.monostr.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import com.monostr.app.R

/**
 * Note body with tappable hashtags, `nostr:` mentions/notes and URLs (spec 3.3). URLs and hashtags
 * are primary colour and underlined (theme spec §4); mentions read `@name` and a note reference
 * that stayed in the text reads "note", both primary without underline (Plan 10c spec 2/3).
 * The rest of the text opens the thread.
 */
@Composable
fun NoteText(
    content: String,
    onOpen: () -> Unit,
    onHashtag: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
    names: Map<String, String> = emptyMap(),
    maxLines: Int = Int.MAX_VALUE,
    /** Spec 5: a text of more than [COLLAPSE_ABOVE] lines shows [COLLAPSED_LINES] and a "Show more" button. */
    collapsible: Boolean = false,
    /** What the expanded state hangs on (the note id): it survives scrolling and rotation, not leaving the screen. */
    stateKey: String = content,
    /** Spec 11.5 §3: the focused note of a thread reads larger. */
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val spans = remember(content) { NoteLinks.annotate(content) }
    val uriHandler = LocalUriHandler.current
    val primary = MaterialTheme.colorScheme.primary
    val styles = TextLinkStyles(style = SpanStyle(color = primary, textDecoration = TextDecoration.Underline))
    val nameStyles = TextLinkStyles(style = SpanStyle(color = primary))
    val noteLabel = stringResource(R.string.note_ref_label)
    val text = buildAnnotatedString {
        for (s in spans) {
            when (s) {
                is NoteSpan.Plain -> append(s.text)
                is NoteSpan.Hashtag -> withLink(LinkAnnotation.Clickable("tag", styles) { onHashtag(s.tag) }) { append(s.text) }
                is NoteSpan.Mention -> withLink(LinkAnnotation.Clickable("mention", nameStyles) { onOpenProfile(s.pubkey) }) { append(MentionNames.label(s.pubkey, names)) }
                is NoteSpan.NoteRef -> withLink(LinkAnnotation.Clickable("note", nameStyles) { onOpenNote(s.noteId) }) { append(noteLabel) }
                is NoteSpan.Url -> withLink(LinkAnnotation.Clickable("url", styles) { runCatching { uriHandler.openUri(s.url) } }) { append(s.text) }
            }
        }
    }
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    // measured with the text itself: laid out with 12 lines first, an overflow there switches to 10 lines plus the button
    var overflowing by remember(stateKey) { mutableStateOf(false) }
    val lines = when {
        !collapsible || expanded -> maxLines
        overflowing -> COLLAPSED_LINES
        else -> COLLAPSE_ABOVE
    }
    Column {
        Text(
            text, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (collapsible && !expanded && !overflowing && it.hasVisualOverflow) overflowing = true },
            modifier = modifier.clickable(onClick = onOpen),
        )
        if (collapsible && overflowing && !expanded) {
            // its own node with the role Button; only it expands, the text still opens the thread
            // no inner padding: the label starts flush with the text above it (user 2026-10-08)
            TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("note-more-${stateKey.take(8)}")) {
                Text(stringResource(R.string.note_show_more), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Spec 5: 11 and 12 lines stay whole; a button that reveals two lines is not worth it. */
private const val COLLAPSE_ABOVE = 12
private const val COLLAPSED_LINES = 10
