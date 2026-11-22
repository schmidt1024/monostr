package com.monostr.app.translate

/**
 * Plain text for the device translator plus the HTML of every piece that stays frozen: `frozen[i]` is the exact source
 * of the piece the marker `[offset + i]` in [text] stands for.
 */
data class Plain(val text: String, val frozen: List<String>, val offset: Int = 0)

/**
 * Bridge between the HTML pieces [TokenShield] and [MdHtml] produce and the plain text the on-device translator takes.
 *
 * [toPlain]: `<span translate="no">…</span>` and whole `<a …>…</a>` elements (the link text stays untranslated, the link
 * is kept) become `[k]` markers (k = [Plain.offset] + i; brackets proved to survive the Offline Translator unchanged)
 * whose exact source HTML is kept in [Plain.frozen]; `<br>` variants become `\n`; `<b>`, `<i>`, `<s>` and their closers
 * are dropped (flattened); the usual entities are decoded. Any other tag gives null (the caller must refuse to
 * translate). The offset is 0 unless the text itself has `[digits]` (footnotes): then it starts above the highest one,
 * so a source number never reads as a marker.
 *
 * [fromPlain]: every marker must come back exactly once (whitespace around it is tolerated), else null; a bracket number
 * outside the marker range (or with a leading zero) is plain text; text parts are escaped again, `\n` becomes `<br>`,
 * markers become their frozen HTML. The result goes through [TokenShield.restore] or [MdHtml.fromHtml] unchanged.
 */
object DevicePlain {
    private val FROZEN = Regex(
        """<span translate=(?:"no"|'no')>[^<]*</span>|<a(?:\s[^>]*)?>.*?</a>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val FORMAT = Regex("""</?(?:b|i|s)>""", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("""&(amp|lt|gt|quot|#39);""")
    private val BRACKET = Regex("""\[([0-9]+)]""")

    fun toPlain(html: String): Plain? {
        // the text runs between frozen pieces, decoded
        val runs = ArrayList<String>()
        val frozen = ArrayList<String>()
        var pos = 0
        for (m in FROZEN.findAll(html)) {
            runs += text(html.substring(pos, m.range.first)) ?: return null
            frozen += m.value
            pos = m.range.last + 1
        }
        runs += text(html.substring(pos)) ?: return null
        var offset = 0
        for (r in runs) for (m in BRACKET.findAll(r)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n >= offset && n < Int.MAX_VALUE - frozen.size) offset = n + 1
        }
        val out = StringBuilder()
        for ((i, r) in runs.withIndex()) {
            out.append(r)
            if (i < frozen.size) out.append('[').append(offset + i).append(']')
        }
        return Plain(out.toString(), frozen, offset)
    }

    /** A run between frozen pieces: formatting dropped, breaks and entities decoded; null for anything else with a `<`. */
    private fun text(s: String): String? {
        val flat = FORMAT.replace(BR.replace(s, "\n"), "")
        if (flat.indexOf('<') >= 0) return null
        return ENTITY.replace(flat) {
            when (it.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                else -> "'"
            }
        }
    }

    fun fromPlain(text: String, plain: Plain): String? {
        val n = plain.frozen.size
        val seen = BooleanArray(n)
        val sb = StringBuilder()
        var pos = 0
        for (m in BRACKET.findAll(text)) {
            val digits = m.groupValues[1]
            if (digits.length > 1 && digits[0] == '0') continue
            val k = digits.toIntOrNull() ?: continue
            val i = k - plain.offset
            if (i < 0 || i >= n) continue
            if (seen[i]) return null
            seen[i] = true
            escape(text.substring(pos, m.range.first), sb)
            sb.append(plain.frozen[i])
            pos = m.range.last + 1
        }
        escape(text.substring(pos), sb)
        return if (seen.all { it }) sb.toString() else null
    }

    private fun escape(s: String, sb: StringBuilder) {
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '\n' -> sb.append("<br>")
            else -> sb.append(c)
        }
    }
}
