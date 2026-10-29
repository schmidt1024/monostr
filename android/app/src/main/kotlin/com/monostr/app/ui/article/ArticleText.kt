package com.monostr.app.ui.article

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.monostr.app.ui.common.MentionNames
import com.monostr.app.ui.common.ForeignRule
import com.monostr.app.ui.common.NoteLinks
import com.monostr.app.ui.common.NoteSpan
import com.monostr.nostr.model.AddressRef

/**
 * Spec 8: an article opened from a direct message (`allowForeign` false) must not make the app contact relays its
 * author chose: an embed or a nested link inside it drops the naddr's hints and stays on the user's own relays.
 * With `allowForeign` true nothing changes.
 */
fun embedTarget(ref: AddressRef, allowForeign: Boolean): Pair<AddressRef, Boolean> = ForeignRule.address(ref, allowForeign)

/** What a tap on a link inside an article does; the screen decides (profile, thread, reader, browser). */
class ArticleLinkActions(
    val onUrl: (String) -> Unit,
    val onHashtag: (String) -> Unit,
    val onProfile: (String) -> Unit,
    val onNote: (String) -> Unit,
    val onAddress: (AddressRef) -> Unit,
)

class ArticleTextStyle(
    val link: Color,
    val noteLabel: String,
    val addressLabel: String,
    val names: Map<String, String>,
    val codeBackground: Color,
)

/** Spans styled per paragraph; a hostile paragraph's text beyond the first [MAX_SPANS] spans stays plain (final review I3). */
internal const val MAX_SPANS = 2_000

/**
 * An [MdText] as styled text: bold, italic, strikethrough and inline code from the spans; a Markdown link is a
 * link; `nostr:` tokens, URLs and hashtags in the rest are found by [NoteLinks] like in a note (spec 3.3).
 *
 * Linear in the text and the spans (final review I3): the text is cut only at link and code spans, bold, italic and
 * strikethrough are laid over the result as ranges, mapped through the pieces [NoteLinks] replaced (mention labels,
 * shortened URLs).
 */
fun articleText(text: MdText, style: ArticleTextStyle, actions: ArticleLinkActions): AnnotatedString {
    val full = text.text
    var limit = full.length
    var spans = text.spans
    if (spans.size > MAX_SPANS) {
        val sorted = spans.sortedBy { it.start }
        limit = sorted[MAX_SPANS].start.coerceIn(0, full.length)
        spans = sorted.subList(0, MAX_SPANS)
    }
    val urlStyle = TextLinkStyles(style = SpanStyle(color = style.link, textDecoration = TextDecoration.Underline))
    val nameStyle = TextLinkStyles(style = SpanStyle(color = style.link))
    // link and code spans cut the text; their boundaries, sorted
    val cutting = spans.filter { (it is MdSpan.Link || it is MdSpan.InlineCode) && it.start.coerceIn(0, limit) < it.end.coerceIn(0, limit) }.sortedBy { it.start }
    val points = java.util.TreeSet<Int>().apply {
        add(0); add(limit)
        for (c in cutting) { add(c.start.coerceIn(0, limit)); add(c.end.coerceIn(0, limit)) }
    }.toIntArray()
    val map = OffsetMap()
    val out = AnnotatedString.Builder(full.length)
    val active = ArrayList<MdSpan>()
    var next = 0
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        // spans nest (the parser's tree): the active set stays as deep as the nesting
        active.removeAll { it.end.coerceIn(0, limit) <= a }
        while (next < cutting.size && cutting[next].start.coerceIn(0, limit) <= a) active += cutting[next++]
        val piece = full.substring(a, b)
        val link = active.lastOrNull { it is MdSpan.Link } as MdSpan.Link?
        val code = active.any { it is MdSpan.InlineCode }
        when {
            code -> {
                out.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = style.codeBackground)) { append(piece) }
                map.add(a, piece.length, piece.length, linear = true)
            }
            link != null -> {
                out.withLink(LinkAnnotation.Clickable("md-link", urlStyle) { open(link.url, actions) }) { append(piece) }
                map.add(a, piece.length, piece.length, linear = true)
            }
            else -> {
                var src = a
                for (sp in NoteLinks.annotate(piece)) {
                    val before = out.length
                    val srcLen = when (sp) {
                        is NoteSpan.Plain -> { out.append(sp.text); sp.text.length }
                        is NoteSpan.Hashtag -> { out.withLink(LinkAnnotation.Clickable("tag", urlStyle) { actions.onHashtag(sp.tag) }) { append(sp.text) }; sp.text.length }
                        is NoteSpan.Mention -> { out.withLink(LinkAnnotation.Clickable("mention", nameStyle) { actions.onProfile(sp.pubkey) }) { append(MentionNames.label(sp.pubkey, style.names)) }; sp.text.length }
                        is NoteSpan.NoteRef -> { out.withLink(LinkAnnotation.Clickable("note", nameStyle) { actions.onNote(sp.noteId) }) { append(style.noteLabel) }; sp.text.length }
                        is NoteSpan.AddressRef -> { out.withLink(LinkAnnotation.Clickable("address", nameStyle) { actions.onAddress(sp.ref) }) { append(style.addressLabel) }; sp.text.length }
                        is NoteSpan.Url -> { out.withLink(LinkAnnotation.Clickable("url", urlStyle) { actions.onUrl(sp.url) }) { append(sp.text) }; sp.url.length }
                    }
                    map.add(src, srcLen, out.length - before, linear = sp is NoteSpan.Plain || sp is NoteSpan.Hashtag)
                    src += srcLen
                }
            }
        }
    }
    if (limit < full.length) {
        map.add(limit, full.length - limit, full.length - limit, linear = true)
        out.append(full, limit, full.length)
    }
    for (sp in spans) {
        val st = when (sp) {
            is MdSpan.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
            is MdSpan.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
            is MdSpan.Strike -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            else -> continue
        }
        val s = sp.start.coerceIn(0, limit)
        val e = sp.end.coerceIn(0, limit)
        if (s >= e) continue
        val os = map.start(s)
        val oe = map.end(e)
        if (os < oe) out.addStyle(st, os, oe)
    }
    return out.toAnnotatedString()
}

/** Source offsets of [articleText] to output offsets, piece by piece in source order; a replaced piece maps as a whole. */
private class OffsetMap {
    private var src = IntArray(16)
    private var srcLen = IntArray(16)
    private var out = IntArray(16)
    private var outLen = IntArray(16)
    private var linear = BooleanArray(16)
    private var size = 0
    private var outEnd = 0

    fun add(srcStart: Int, sl: Int, ol: Int, linear: Boolean) {
        if (sl <= 0 && ol <= 0) return
        if (size == src.size) {
            val n = size * 2
            src = src.copyOf(n); srcLen = srcLen.copyOf(n); out = out.copyOf(n); outLen = outLen.copyOf(n); this.linear = this.linear.copyOf(n)
        }
        src[size] = srcStart; srcLen[size] = sl; out[size] = outEnd; outLen[size] = ol; this.linear[size] = linear && sl == ol
        outEnd += ol
        size++
    }

    /** The last piece starting at or before [p]; -1 for none. */
    private fun find(p: Int): Int {
        var lo = 0
        var hi = size - 1
        var r = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (src[mid] <= p) { r = mid; lo = mid + 1 } else hi = mid - 1
        }
        return r
    }

    fun start(p: Int): Int {
        val i = find(p)
        if (i < 0) return 0
        if (p >= src[i] + srcLen[i]) return out[i] + outLen[i]
        return if (linear[i]) out[i] + (p - src[i]) else out[i]
    }

    fun end(p: Int): Int {
        val i = find(p - 1)
        if (i < 0) return 0
        if (p - 1 >= src[i] + srcLen[i]) return out[i] + outLen[i]
        return if (linear[i]) out[i] + (p - src[i]) else out[i] + outLen[i]
    }
}

/** A Markdown link target: a `nostr:` reference goes where the same token goes in a note, anything else through the link path. */
private fun open(url: String, actions: ArticleLinkActions) {
    if (url.startsWith("nostr:", ignoreCase = true)) {
        when (val s = NoteLinks.annotate(url).singleOrNull()) {
            is NoteSpan.Mention -> return actions.onProfile(s.pubkey)
            is NoteSpan.NoteRef -> return actions.onNote(s.noteId)
            is NoteSpan.AddressRef -> return actions.onAddress(s.ref)
            else -> return
        }
    }
    actions.onUrl(url)
}
