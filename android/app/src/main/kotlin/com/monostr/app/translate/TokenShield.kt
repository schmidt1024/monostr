package com.monostr.app.translate

import com.monostr.app.ui.common.NoteLinks
import com.monostr.app.ui.common.NoteSpan

/**
 * HTML to send to the translator plus the verbatim token texts the markers stand for; [before] and [after] hold the
 * whitespace the original had right before and after each token ("" when none), for [TokenShield.restore] to put back
 * where the translator glued a token to its neighbour.
 */
data class Shielded(
    val html: String,
    val tokens: List<String>,
    val before: List<String> = tokens.map { "" },
    val after: List<String> = tokens.map { "" },
)

/**
 * Keeps URLs, nostr: refs, hashtags, cashtags and invoices byte-identical through translation.
 * Every non-plain span becomes `<span translate="no">⟦i⟧</span>`; on [restore] any deviation inside
 * a marker (or a missing, duplicated or foreign marker) rejects the whole translation (null).
 *
 * Tolerated translator changes: whitespace around markers, entity re-encoding in plain text,
 * `<br>` / `<br/>` / `<br />`, and `translate='no'` (single quotes). Anything else with a `<` is rejected.
 *
 * The note renders the restored text through [NoteLinks] again, so the restored tokens must also read as the same
 * tokens there: a URL glued to the next word (`https://a.b` + `und`) would link `https://a.bund`. When they do not, the
 * original's whitespace next to each token is put back once; still different: rejected.
 */
object TokenShield {
    private const val OPEN = '⟦'
    private const val CLOSE = '⟧'

    private val MARKER = Regex("""<span translate=(?:"no"|'no')>⟦(0|[1-9][0-9]*)⟧</span>""")
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("""&(amp|lt|gt|quot|apos|#39|#x27|#34|#x22);""", RegexOption.IGNORE_CASE)

    /** Null when the tokenizer is not lossless for [content]: the caller must refuse to translate, never send it unshielded. */
    fun protect(content: String): Shielded? {
        val spans = NoteLinks.annotate(content)
        if (!isLossless(spans, content)) return null
        val tokens = ArrayList<String>()
        val before = ArrayList<String>()
        val after = ArrayList<String>()
        val sb = StringBuilder()
        var at = 0 // offset in [content] of the text being emitted
        fun token(text: String) {
            sb.append("<span translate=\"no\">").append(OPEN).append(tokens.size).append(CLOSE).append("</span>")
            tokens += text
            before += content.getOrNull(at - 1)?.takeIf { it.isWhitespace() }?.toString().orEmpty()
            after += content.getOrNull(at + text.length)?.takeIf { it.isWhitespace() }?.toString().orEmpty()
        }
        fun plain(text: String) {
            for (ch in text) {
                when (ch) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '\n' -> sb.append("<br>")
                    OPEN, CLOSE -> token(ch.toString()) // would collide with our markers
                    else -> sb.append(ch)
                }
                at++
            }
        }
        for (s in spans) if (s is NoteSpan.Plain) plain(s.text) else { val t = source(s); token(t); at += t.length }
        return Shielded(sb.toString(), tokens, before, after)
    }

    /**
     * The tokens [NoteLinks] finds in [text], as their source texts in order: what the note renders as links. The
     * marker chars are tokens of [protect] but plain text to the renderer, so callers compare without them.
     */
    internal fun linkTokens(text: String): List<String> =
        NoteLinks.annotate(text).filter { it !is NoteSpan.Plain }.map(::source)

    private fun isMarkerChar(token: String) = token.length == 1 && (token[0] == OPEN || token[0] == CLOSE)

    internal fun isLossless(spans: List<NoteSpan>, content: String): Boolean =
        spans.joinToString("") { source(it) } == content

    /** The exact source text of a span: a Url's `text` is the shortened display form, its `url` the source. */
    private fun source(span: NoteSpan): String = if (span is NoteSpan.Url) span.url else span.text

    fun restore(html: String, shielded: Shielded): String? =
        restore(html, shielded.tokens, shielded.before, shielded.after)

    /** [before]/[after]: see [Shielded]; empty lists put no whitespace back. */
    fun restore(
        html: String,
        tokens: List<String>,
        before: List<String> = emptyList(),
        after: List<String> = emptyList(),
    ): String? {
        val seen = BooleanArray(tokens.size)
        // the translation as plain runs (String) and token indexes (Int), in order
        val parts = ArrayList<Any>()
        var pos = 0
        for (m in MARKER.findAll(html)) {
            parts += plain(html.substring(pos, m.range.first)) ?: return null
            val i = m.groupValues[1].toIntOrNull() ?: return null
            if (i !in tokens.indices || seen[i]) return null
            seen[i] = true
            parts += i
            pos = m.range.last + 1
        }
        parts += plain(html.substring(pos)) ?: return null
        if (seen.any { !it }) return null

        val want = tokens.filterNot(::isMarkerChar)
        val plainJoin = assemble(parts, tokens, before, after, pad = false)
        if (linkTokens(plainJoin) == want) return plainJoin
        val padded = assemble(parts, tokens, before, after, pad = true)
        return padded.takeIf { it != plainJoin && linkTokens(it) == want }
    }

    /** With [pad], a token whose neighbour in the translation is not whitespace gets the original's whitespace there. */
    private fun assemble(parts: List<Any>, tokens: List<String>, before: List<String>, after: List<String>, pad: Boolean): String {
        val out = StringBuilder()
        for ((k, p) in parts.withIndex()) {
            if (p is String) { out.append(p); continue }
            val i = p as Int
            val t = tokens[i]
            val fix = pad && !isMarkerChar(t)
            if (fix && out.isNotEmpty() && !out.last().isWhitespace()) out.append(before.getOrNull(i).orEmpty())
            out.append(t)
            if (fix) {
                val next = parts.drop(k + 1).firstNotNullOfOrNull { n -> if (n is Int) tokens[n].firstOrNull() else (n as String).firstOrNull() }
                if (next != null && !next.isWhitespace()) out.append(after.getOrNull(i).orEmpty())
            }
        }
        return out.toString()
    }

    private fun plain(s: String): String? {
        if (s.indexOf(OPEN) >= 0 || s.indexOf(CLOSE) >= 0) return null
        val noBr = BR.replace(s, "\n")
        if (noBr.indexOf('<') >= 0) return null
        return ENTITY.replace(noBr) {
            when (it.groupValues[1].lowercase()) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot", "#34", "#x22" -> "\""
                else -> "'"
            }
        }
    }
}
