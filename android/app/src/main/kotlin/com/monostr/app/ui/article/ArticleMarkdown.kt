package com.monostr.app.ui.article

import com.monostr.tips.RelayHosts
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code as CmCode
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.Heading as CmHeading
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link as CmLink
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph as CmParagraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

sealed interface MdBlock {
    data class Heading(val level: Int, val text: MdText) : MdBlock
    data class Paragraph(val text: MdText) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<MdBlock>>) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Picture(val url: String, val alt: String) : MdBlock
    data class Table(val header: List<MdText>, val rows: List<List<MdText>>) : MdBlock
    data object Rule : MdBlock
}

/** Plain text plus spans over its character ranges (end exclusive). */
data class MdText(val text: String, val spans: List<MdSpan>)

sealed interface MdSpan {
    val start: Int
    val end: Int
    data class Bold(override val start: Int, override val end: Int) : MdSpan
    data class Italic(override val start: Int, override val end: Int) : MdSpan
    data class Strike(override val start: Int, override val end: Int) : MdSpan
    data class InlineCode(override val start: Int, override val end: Int) : MdSpan
    data class Link(override val start: Int, override val end: Int, val url: String) : MdSpan
}

object ArticleMarkdown {
    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .build()

    private const val MAX_LIST_DEPTH = 2
    private val TAG_NAME = Regex("^<(/?)([A-Za-z][A-Za-z0-9-]*)")
    private val RAW_TAGS = setOf("script", "style", "iframe", "object")
    /** Tags that end a line in the stripped text of an HTML block (`<br>`, a closing paragraph, div, list item or heading). */
    private val BREAK_OPEN = setOf("br")
    private val BREAK_CLOSE = setOf("p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6")
    private val LINK_SCHEMES = setOf("https", "http", "nostr", "mailto")
    private const val MAX_DEPTH = 16

    fun parse(markdown: String): List<MdBlock> {
        if (markdown.isBlank()) return emptyList()
        return try {
            blocks(parser.parse(markdown), 0, 0)
        } catch (_: StackOverflowError) {
            listOf(MdBlock.Paragraph(MdText(markdown.trim(), emptyList())))
        }
    }

    fun readingMinutes(markdown: String): Int {
        val words = markdown.split(Regex("\\s+")).count { it.isNotEmpty() }
        return maxOf(1, (words + 229) / 230)
    }

    private fun blocks(parent: Node, listDepth: Int, depth: Int): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        if (depth > MAX_DEPTH) {
            val t = textOf(parent).trim()
            if (t.isNotEmpty()) out += MdBlock.Paragraph(MdText(t, emptyList()))
            return out
        }
        var n = parent.firstChild
        while (n != null) {
            when (n) {
                is CmHeading -> {
                    val (t, pics) = inline(n)
                    out += MdBlock.Heading(n.level.coerceIn(1, 3), t)
                    out += pics
                }
                is CmParagraph -> {
                    val (t, pics) = inline(n)
                    if (t.text.isNotBlank()) out += MdBlock.Paragraph(t)
                    out += pics
                }
                is BlockQuote -> out += MdBlock.Quote(blocks(n, listDepth, depth + 1))
                is BulletList -> list(n, false, 1, listDepth, depth, out)
                is OrderedList -> list(n, true, n.markerStartNumber ?: 1, listDepth, depth, out)
                is FencedCodeBlock -> out += MdBlock.Code(n.literal.trimEnd('\n'))
                is IndentedCodeBlock -> out += MdBlock.Code(n.literal.trimEnd('\n'))
                is ThematicBreak -> out += MdBlock.Rule
                is TableBlock -> table(n)?.let { out += it }
                is HtmlBlock -> {
                    val t = stripHtml(n.literal).trim()
                    if (t.isNotEmpty()) out += MdBlock.Paragraph(MdText(t, emptyList()))
                }
                else -> Unit // anything unknown: dropped
            }
            n = n.next
        }
        return out
    }

    private fun list(n: Node, ordered: Boolean, start: Int, depth: Int, bdepth: Int, out: MutableList<MdBlock>) {
        val items = ArrayList<List<MdBlock>>()
        var item = n.firstChild
        while (item != null) {
            if (item is ListItem) items += blocks(item, depth + 1, bdepth + 1)
            item = item.next
        }
        if (depth >= MAX_LIST_DEPTH) {
            // deeper than level 2: flatten the items into the surrounding item
            items.forEach { out += it }
        } else {
            out += MdBlock.ListBlock(ordered, start, items)
        }
    }

    private fun table(t: TableBlock): MdBlock.Table? {
        var header: List<MdText> = emptyList()
        val rows = ArrayList<List<MdText>>()
        var section = t.firstChild
        while (section != null) {
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    val cells = ArrayList<MdText>()
                    var c = row.firstChild
                    while (c != null) {
                        if (c is TableCell) cells += inline(c).first
                        c = c.next
                    }
                    if (section is TableHead) header = cells else if (section is TableBody) rows += cells
                }
                row = row.next
            }
            section = section.next
        }
        return if (header.isEmpty() && rows.isEmpty()) null else MdBlock.Table(header, rows)
    }

    private class Inline {
        val sb = StringBuilder()
        val spans = ArrayList<MdSpan>()
        val pics = ArrayList<MdBlock>()
    }

    private fun inline(container: Node): Pair<MdText, List<MdBlock>> {
        val acc = Inline()
        walk(container, acc, 0)
        return MdText(acc.sb.toString().trim(), fixSpans(acc)) to acc.pics
    }

    // trim() may drop leading whitespace: shift/clamp spans so they stay inside the final text
    private fun fixSpans(acc: Inline): List<MdSpan> {
        val raw = acc.sb.toString()
        val lead = raw.length - raw.trimStart().length
        val len = raw.trim().length
        return acc.spans.mapNotNull { s ->
            val a = (s.start - lead).coerceIn(0, len)
            val b = (s.end - lead).coerceIn(0, len)
            if (b <= a) null else when (s) {
                is MdSpan.Bold -> s.copy(start = a, end = b)
                is MdSpan.Italic -> s.copy(start = a, end = b)
                is MdSpan.Strike -> s.copy(start = a, end = b)
                is MdSpan.InlineCode -> s.copy(start = a, end = b)
                is MdSpan.Link -> s.copy(start = a, end = b)
            }
        }
    }

    private fun walk(parent: Node, acc: Inline, depth: Int) {
        if (depth > MAX_DEPTH) {
            acc.sb.append(textOf(parent))
            return
        }
        var skip: String? = null // inside script/style/iframe/object: drop up to the matching close
        var skipDepth = 0
        var n = parent.firstChild
        while (n != null) {
            if (skip != null) {
                if (n is HtmlInline) {
                    val m = TAG_NAME.find(n.literal)
                    if (m != null && m.groupValues[2].lowercase() == skip && !n.literal.trimEnd().endsWith("/>")) {
                        if (m.groupValues[1].isEmpty()) skipDepth++ else if (--skipDepth == 0) skip = null
                    }
                }
                n = n.next
                continue
            }
            when (n) {
                is Text -> acc.sb.append(n.literal)
                is SoftLineBreak -> acc.sb.append(' ')
                is HardLineBreak -> acc.sb.append('\n')
                is CmCode -> span(acc, { acc.sb.append(n.literal) }) { s, e -> MdSpan.InlineCode(s, e) }
                is Emphasis -> span(acc, { walk(n, acc, depth + 1) }) { s, e -> MdSpan.Italic(s, e) }
                is StrongEmphasis -> span(acc, { walk(n, acc, depth + 1) }) { s, e -> MdSpan.Bold(s, e) }
                is Strikethrough -> span(acc, { walk(n, acc, depth + 1) }) { s, e -> MdSpan.Strike(s, e) }
                is CmLink -> {
                    val url = n.destination.orEmpty().trim()
                    if (url.substringBefore(':', "").lowercase() in LINK_SCHEMES && ':' in url) {
                        span(acc, { walk(n, acc, depth + 1) }) { s, e -> MdSpan.Link(s, e, url) }
                    } else {
                        walk(n, acc, depth + 1)
                    }
                }
                is Image -> {
                    val url = n.destination.orEmpty().trim()
                    if (url.startsWith("https://", ignoreCase = true) && RelayHosts.isPublic(url)) {
                        acc.pics += MdBlock.Picture(url, textOf(n))
                    }
                }
                is HtmlInline -> {
                    val m = TAG_NAME.find(n.literal)
                    if (m != null && m.groupValues[1].isEmpty() && m.groupValues[2].lowercase() in RAW_TAGS &&
                        !n.literal.trimEnd().endsWith("/>")
                    ) {
                        skip = m.groupValues[2].lowercase()
                        skipDepth = 1
                    }
                }
                else -> walk(n, acc, depth + 1)
            }
            n = n.next
        }
    }

    /** Tags removed, text kept (a line break for `<br>` and the end of a block tag); script/style/iframe/object elements removed with their content. One linear pass. */
    private fun stripHtml(h: String): String {
        val sb = StringBuilder()
        var i = 0
        var noClose = false // no '>' left in the rest of the string
        while (i < h.length) {
            val c = h[i]
            if (c != '<' || noClose) { sb.append(c); i++; continue }
            val end = h.indexOf('>', i)
            if (end < 0) { noClose = true; sb.append(c); i++; continue }
            val m = TAG_NAME.find(h.substring(i, minOf(end + 1, i + 64)))
            val name = m?.groupValues?.get(2)?.lowercase()
            i = end + 1
            if (m != null && ((m.groupValues[1].isEmpty() && name in BREAK_OPEN) || (m.groupValues[1].isNotEmpty() && name in BREAK_CLOSE))) sb.append('\n')
            if (m != null && m.groupValues[1].isEmpty() && name in RAW_TAGS && h[end - 1] != '/') {
                val close = h.indexOf("</$name", i, ignoreCase = true)
                if (close < 0) return sb.toString()
                val ce = h.indexOf('>', close)
                if (ce < 0) return sb.toString()
                i = ce + 1
            }
        }
        return sb.toString()
    }

    /** Text of a subtree, without recursion. */
    private fun textOf(root: Node): String {
        val sb = StringBuilder()
        var c: Node? = root.firstChild
        while (c != null) {
            when (c) {
                is Text -> sb.append(c.literal)
                is CmCode -> sb.append(c.literal)
                is FencedCodeBlock -> sb.append(c.literal)
                is IndentedCodeBlock -> sb.append(c.literal)
                is SoftLineBreak -> sb.append(' ')
                is HardLineBreak -> sb.append('\n')
                else -> Unit
            }
            if (c.firstChild != null && c !is HtmlInline) {
                c = c.firstChild
            } else {
                while (c != null && c.next == null) {
                    c = c.parent
                    if (c === root) { c = null }
                }
                c = c?.next
            }
        }
        return sb.toString()
    }

    private inline fun span(acc: Inline, body: () -> Unit, make: (Int, Int) -> MdSpan) {
        val s = acc.sb.length
        body()
        val e = acc.sb.length
        if (e > s) acc.spans += make(s, e)
    }
}
