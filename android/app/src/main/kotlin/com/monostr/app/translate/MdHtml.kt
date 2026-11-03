package com.monostr.app.translate

import com.monostr.app.ui.article.MdSpan
import com.monostr.app.ui.article.MdText
import com.monostr.app.ui.common.NoteLinks
import com.monostr.app.ui.common.NoteSpan

/**
 * An article block's [MdText] as the HTML LibreTranslate translates (`format: "html"`) and back (spec 2026-11-03
 * «Articles»). Bold, italic and strikethrough become `<b>`, `<i>`, `<s>`; a link `<a data-i="n">` without its URL
 * (restored from the original by index, never taken from the reply); inline code and the tokens the reader turns into
 * links on its own (URLs, `nostr:` refs, hashtags, cashtags, invoices) `<span translate="no">…</span>`, which must come
 * back unchanged and in order.
 *
 * [fromHtml] accepts `<strong>`/`<em>`/`<strike>`/`<del>`, any letter case, quote style and extra attributes, `<br>`
 * variants and the usual entities; any other tag, a crossed or unclosed tag, a changed frozen piece, a missing, extra or
 * unknown link index or a different number of formatting tags gives null, and the block keeps its original.
 *
 * A link whose display text is itself a URL or token (`<https://x>`, `[https://x](https://x)`) is not prose: that text
 * is frozen too and may come back only inside its own `<a>`. And since the reader finds links in the text outside link
 * and code spans again ([readerTokens]), a decoded block must give exactly the original's tokens there, in order: a URL
 * the translator glued to the next word would otherwise link somewhere else.
 */
object MdHtml {
    private const val BOLD = 0
    private const val ITALIC = 1
    private const val STRIKE = 2
    private const val LINK = 3

    /** [link]: the index of the link whose whole display text this is, else -1. */
    internal class Frozen(val text: String, val code: Boolean, val link: Int = -1)

    /** [tags]: the number of `<b>`, `<i>`, `<s>` emitted; [linkTags]: the `<a>` emitted per link index. */
    internal class Encoded(
        val html: String,
        val frozen: List<Frozen>,
        val links: List<MdSpan.Link>,
        val tags: IntArray,
        val linkTags: IntArray,
        /** Some letter outside the frozen pieces: something to translate at all. */
        val prose: Boolean,
        /** [readerTokens] of the original. */
        val readerTokens: List<String>,
    )

    fun toHtml(t: MdText): String = encode(t).html

    fun fromHtml(html: String, original: MdText): MdText? = decode(html, encode(original))

    internal fun encode(t: MdText): Encoded {
        val text = t.text
        val n = text.length
        val spans = t.spans.filter { it.start in 0..n && it.end in 0..n && it.start < it.end }
        val links = spans.filterIsInstance<MdSpan.Link>()
        val formatting = spans.filter { it !is MdSpan.InlineCode }
        // which frozen piece each character belongs to (-1: translated text)
        val frozenId = IntArray(n) { -1 }
        val code = BooleanArray(n)
        val inLink = BooleanArray(n)
        val linkOf = IntArray(n) { -1 } // the link whose display text (a URL or token) a frozen character is
        var nextId = 0
        for (s in spans) when (s) {
            is MdSpan.InlineCode -> { val id = nextId++; for (i in s.start until s.end) { frozenId[i] = id; code[i] = true } }
            is MdSpan.Link -> for (i in s.start until s.end) inLink[i] = true
            else -> Unit
        }
        for (s in links) {
            if ((s.start until s.end).any { code[it] }) continue
            val shown = text.substring(s.start, s.end)
            val only = NoteLinks.annotate(shown).singleOrNull() ?: continue
            if (only is NoteSpan.Plain || !TokenShield.isLossless(listOf(only), shown)) continue
            val id = nextId++
            val k = links.indexOf(s)
            for (i in s.start until s.end) { frozenId[i] = id; linkOf[i] = k }
        }
        // the reader finds links in the text outside link and code spans (ArticleText.articleText): those stay as they are
        var i = 0
        while (i < n) {
            if (code[i] || inLink[i]) { i++; continue }
            var j = i
            while (j < n && !code[j] && !inLink[j]) j++
            val region = text.substring(i, j)
            val found = NoteLinks.annotate(region)
            if (!TokenShield.isLossless(found, region)) {
                val id = nextId++
                for (k in i until j) frozenId[k] = id
            } else {
                var p = i
                for (sp in found) {
                    val len = if (sp is NoteSpan.Url) sp.url.length else sp.text.length
                    if (sp !is NoteSpan.Plain) { val id = nextId++; for (k in p until p + len) frozenId[k] = id }
                    p += len
                }
            }
            i = j
        }

        val startingAt = formatting.groupBy { it.start }
        val points = java.util.TreeSet<Int>().apply {
            add(0); add(n)
            for (s in formatting) { add(s.start); add(s.end) }
            for (k in 1 until n) if (frozenId[k] != frozenId[k - 1]) add(k)
        }.toIntArray()
        val sb = StringBuilder(n + 16)
        val frozen = ArrayList<Frozen>()
        val tags = IntArray(3)
        val linkTags = IntArray(links.size)
        val stack = ArrayList<MdSpan>()
        var prose = false
        fun open(s: MdSpan) {
            stack += s
            when (s) {
                is MdSpan.Bold -> { sb.append("<b>"); tags[BOLD]++ }
                is MdSpan.Italic -> { sb.append("<i>"); tags[ITALIC]++ }
                is MdSpan.Strike -> { sb.append("<s>"); tags[STRIKE]++ }
                is MdSpan.Link -> { val k = links.indexOf(s); sb.append("<a data-i=\"").append(k).append("\">"); linkTags[k]++ }
                is MdSpan.InlineCode -> Unit
            }
        }
        fun close(s: MdSpan) {
            sb.append(
                when (s) {
                    is MdSpan.Bold -> "</b>"
                    is MdSpan.Italic -> "</i>"
                    is MdSpan.Strike -> "</s>"
                    is MdSpan.Link -> "</a>"
                    is MdSpan.InlineCode -> ""
                },
            )
        }
        for (pi in 0 until points.size - 1) {
            val a = points[pi]
            val b = points[pi + 1]
            // close what ends here; spans opened inside one of them that go on are closed and opened again (they cross)
            if (stack.any { it.end <= a }) {
                val reopen = ArrayList<MdSpan>()
                while (stack.any { it.end <= a }) {
                    val top = stack.removeAt(stack.size - 1)
                    close(top)
                    if (top.end > a) reopen.add(0, top)
                }
                reopen.forEach(::open)
            }
            startingAt[a]?.sortedByDescending { it.end }?.forEach(::open)
            val piece = text.substring(a, b)
            if (frozenId[a] >= 0) {
                sb.append("<span translate=\"no\">")
                escape(piece, sb, breaks = false) // a <br> would end the piece's plain-text scan
                sb.append("</span>")
                frozen += Frozen(piece, code[a], linkOf[a])
            } else {
                if (piece.any { it.isLetter() }) prose = true
                escape(piece, sb)
            }
        }
        while (stack.isNotEmpty()) close(stack.removeAt(stack.size - 1))
        return Encoded(sb.toString(), frozen, links, tags, linkTags, prose, readerTokens(t))
    }

    /**
     * The tokens the reader turns into links on its own in [t] (ArticleText.articleText): those [NoteLinks] finds in each
     * run of text outside link and code spans, as their source texts, in order.
     */
    internal fun readerTokens(t: MdText): List<String> {
        val text = t.text
        val n = text.length
        val cut = BooleanArray(n)
        for (s in t.spans) if (s is MdSpan.Link || s is MdSpan.InlineCode) {
            for (i in s.start.coerceIn(0, n) until s.end.coerceIn(0, n)) cut[i] = true
        }
        val out = ArrayList<String>()
        var i = 0
        while (i < n) {
            if (cut[i]) { i++; continue }
            var j = i
            while (j < n && !cut[j]) j++
            out += TokenShield.linkTokens(text.substring(i, j))
            i = j
        }
        return out
    }

    private fun escape(s: String, sb: StringBuilder, breaks: Boolean = true) {
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '\n' -> if (breaks) sb.append("<br>") else sb.append('\n')
            else -> sb.append(c)
        }
    }

    private val CLOSING = Regex("""^/\s*([A-Za-z]+)\s*$""")
    private val OPENING = Regex("""^([A-Za-z]+)((?:\s+[A-Za-z_:][-A-Za-z0-9_:.]*(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'=<>`]+))?)*)\s*(/?)$""")
    private val ATTR = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?""")
    private val ENTITY = Regex("""&(?:(amp|lt|gt|quot|apos|nbsp)|#([0-9]{1,7})|#[xX]([0-9a-fA-F]{1,6}));""")

    private class Open(val kind: Int, val start: Int, val link: Int)

    internal fun decode(html: String, enc: Encoded): MdText? {
        val sb = StringBuilder(html.length)
        val spans = ArrayList<MdSpan>()
        val stack = ArrayList<Open>()
        val tags = IntArray(3)
        val linkTags = IntArray(enc.links.size)
        var frozenAt = 0
        var i = 0
        while (i < html.length) {
            val c = html[i]
            when (c) {
                '<' -> {
                    val end = html.indexOf('>', i)
                    if (end < 0) return null
                    val tag = html.substring(i + 1, end)
                    i = end + 1
                    CLOSING.matchEntire(tag)?.let { m ->
                        val kind = kindOf(m.groupValues[1]) ?: return null
                        val top = stack.removeLastOrNull() ?: return null
                        if (top.kind != kind) return null
                        if (top.start < sb.length) spans += when (kind) {
                            BOLD -> MdSpan.Bold(top.start, sb.length)
                            ITALIC -> MdSpan.Italic(top.start, sb.length)
                            STRIKE -> MdSpan.Strike(top.start, sb.length)
                            else -> MdSpan.Link(top.start, sb.length, enc.links[top.link].url)
                        }
                        return@let
                    } ?: run {
                        val m = OPENING.matchEntire(tag) ?: return null
                        val name = m.groupValues[1].lowercase()
                        val attrs = ATTR.findAll(m.groupValues[2]).associate { a ->
                            a.groupValues[1].lowercase() to (a.groups[2]?.value ?: a.groups[3]?.value ?: a.groups[4]?.value ?: "")
                        }
                        val selfClosing = m.groupValues[3] == "/"
                        when {
                            name == "br" -> sb.append('\n')
                            selfClosing -> return null
                            name == "span" -> {
                                if (attrs["translate"]?.lowercase() != "no") return null
                                val close = html.indexOf("</span>", i, ignoreCase = true)
                                if (close < 0) return null
                                // the server may send it back escaped or not, with <br> for a line break: compared unescaped
                                val inner = BR.replace(html.substring(i, close), "\n")
                                if (inner.indexOf('<') >= 0) return null
                                val want = enc.frozen.getOrNull(frozenAt++) ?: return null
                                if (normal(entities(inner)) != normal(want.text)) return null
                                // a URL or token moved into a link would show one address and open another; a link's own
                                // URL text stays in that link
                                val inLinkNow = stack.lastOrNull { it.kind == LINK }?.link
                                if (want.link >= 0) { if (inLinkNow != want.link) return null }
                                else if (!want.code && inLinkNow != null) return null
                                val s = sb.length
                                sb.append(want.text)
                                if (want.code) spans += MdSpan.InlineCode(s, sb.length)
                                i = close + 7
                            }
                            else -> {
                                val kind = kindOf(name) ?: return null
                                var link = -1
                                if (kind == LINK) {
                                    link = attrs["data-i"]?.toIntOrNull() ?: return null
                                    if (link !in enc.links.indices || stack.any { it.kind == LINK }) return null
                                    linkTags[link]++
                                } else {
                                    tags[kind]++
                                }
                                stack += Open(kind, sb.length, link)
                            }
                        }
                    }
                }
                '&' -> {
                    val m = ENTITY.matchAt(html, i)
                    if (m == null) { sb.append('&'); i++ } else { sb.append(entity(m) ?: return null); i = m.range.last + 1 }
                }
                else -> { sb.append(c); i++ }
            }
        }
        if (stack.isNotEmpty() || frozenAt != enc.frozen.size) return null
        if (!tags.contentEquals(enc.tags) || !linkTags.contentEquals(enc.linkTags)) return null
        val out = MdText(sb.toString(), spans.sortedWith(compareBy({ it.start }, { -it.end })))
        // the reader must find the same links in the translation as in the original
        return out.takeIf { readerTokens(it) == enc.readerTokens }
    }

    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val SPACE = Regex("""\s+""")

    /** Whitespace as the server may reflow it; the original piece is what is kept. */
    private fun normal(s: String) = SPACE.replace(s, " ").trim()

    private fun kindOf(name: String): Int? = when (name.lowercase()) {
        "b", "strong" -> BOLD
        "i", "em" -> ITALIC
        "s", "strike", "del" -> STRIKE
        "a" -> LINK
        else -> null
    }

    private fun entities(s: String): String = ENTITY.replace(s) { entity(it) ?: it.value }

    private fun entity(m: MatchResult): String? {
        m.groups[1]?.value?.let {
            return when (it) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> " " // nbsp: the text had a plain space
            }
        }
        val cp = m.groups[2]?.value?.toIntOrNull() ?: m.groups[3]?.value?.toIntOrNull(16) ?: return null
        if (cp == 0 || cp > Character.MAX_CODE_POINT || cp in 0xD800..0xDFFF) return null
        return String(Character.toChars(cp))
    }
}
