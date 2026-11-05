package com.monostr.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.monostr.nostr.model.AddressLinks
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.Article

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
    /** Spec 5: a text of more than [collapseAbove] lines shows [collapsedLines] and a "Show more" button. */
    collapsible: Boolean = false,
    collapseAbove: Int = COLLAPSE_ABOVE,
    collapsedLines: Int = COLLAPSED_LINES,
    /** What the expanded state hangs on (the note id): it survives scrolling and rotation, not leaving the screen. */
    stateKey: String = content,
    /** Spec 11.5 §3: the focused note of a thread reads larger. */
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    /** Spec 2026-10-16 §4: the opened note — long press selects (Copy in the system toolbar); a tap opens nothing, links still work. */
    selectable: Boolean = false,
) {
    val spans = remember(content) { NoteLinks.annotate(content) }
    val uriHandler = LocalUriHandler.current
    val openArticle = LocalOpenArticle.current
    val openList = LocalOpenList.current
    val primary = MaterialTheme.colorScheme.primary
    val noteLabel = stringResource(R.string.note_ref_label)
    val addressLabel = stringResource(R.string.address_ref_label)
    val invoiceLabel = stringResource(R.string.invoice_inline)
    val invoiceCopied = stringResource(R.string.invoice_copied)
    val context = androidx.compose.ui.platform.LocalContext.current
    // the links call the newest callbacks through these holders, so the text below is built once per content and names
    // (it was rebuilt, with fresh links that compare unequal, on every recomposition: every like, count or profile batch)
    val hashtag by rememberUpdatedState(onHashtag)
    val profile by rememberUpdatedState(onOpenProfile)
    val note by rememberUpdatedState(onOpenNote)
    val article by rememberUpdatedState(openArticle)
    val list by rememberUpdatedState(openList)
    val uris by rememberUpdatedState(uriHandler)
    // only the names this text shows: a profile batch for other notes leaves it alone
    val labels = spans.mapNotNull { (it as? NoteSpan.Mention)?.let { m -> MentionNames.label(m.pubkey, names) } }
    val text = remember(spans, labels, primary, noteLabel, addressLabel, invoiceLabel, invoiceCopied) {
        val styles = TextLinkStyles(style = SpanStyle(color = primary, textDecoration = TextDecoration.Underline))
        val nameStyles = TextLinkStyles(style = SpanStyle(color = primary))
        var mention = 0
        buildAnnotatedString {
            for (s in spans) {
                when (s) {
                    is NoteSpan.Plain -> append(s.text)
                    is NoteSpan.Hashtag -> withLink(LinkAnnotation.Clickable("tag", styles) { hashtag(s.tag) }) { append(s.text) }
                    is NoteSpan.Cashtag -> withLink(LinkAnnotation.Clickable("cashtag", styles) { hashtag(s.text) }) { append(s.text) }
                    is NoteSpan.Mention -> withLink(LinkAnnotation.Clickable("mention", nameStyles) { profile(s.pubkey) }) { append(labels[mention++]) }
                    is NoteSpan.NoteRef -> withLink(LinkAnnotation.Clickable("note", nameStyles) { note(s.noteId) }) { append(noteLabel) }
                    is NoteSpan.AddressRef -> withLink(LinkAnnotation.Clickable("address", nameStyles) {
                        val open = article
                        val openIn = list
                        val listAddress = ListAddress.of(s.ref)
                        if (s.ref.kind == Article.KIND && open != null) open(s.ref, true)
                        else if (listAddress != null && openIn != null) openIn(listAddress)
                        else runCatching { uris.openUri(AddressLinks.web(s.ref)) }
                    }) { append(addressLabel) }
                    is NoteSpan.Url -> withLink(LinkAnnotation.Clickable("url", styles) { runCatching { uris.openUri(s.url) } }) { append(s.text) }
                    is NoteSpan.Invoice -> withLink(LinkAnnotation.Clickable("invoice", nameStyles) { openInvoice(context, s.invoice.raw, invoiceCopied) }) { append("⚡ " + invoiceLabel) }
                }
            }
        }
    }
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    // measured with the text itself: laid out with [collapseAbove] lines first, an overflow there switches to [collapsedLines] plus the button
    var overflowing by remember(stateKey) { mutableStateOf(false) }
    val lines = when {
        !collapsible || expanded -> maxLines
        overflowing -> collapsedLines
        else -> collapseAbove
    }
    Column {
        val body: @Composable () -> Unit = {
            Text(
                text, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (collapsible && !expanded && !overflowing && it.hasVisualOverflow) overflowing = true },
                // nothing competes with the selection gestures in the opened note
                modifier = if (selectable) modifier else modifier.clickable(onClick = onOpen),
            )
        }
        if (selectable) SelectionContainer { body() } else body()
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
