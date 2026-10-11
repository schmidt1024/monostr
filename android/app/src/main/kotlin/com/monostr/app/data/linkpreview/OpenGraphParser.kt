package com.monostr.app.data.linkpreview

import java.net.URI

/** What a link preview card shows (spec 2.2); [url] is the page after redirects. */
data class LinkPreview(val url: String, val title: String, val imageUrl: String?, val large: Boolean)

/**
 * Spec 3.2: Open Graph / Twitter Card tags of a page head (no HTML library). The tags are found
 * with plain index scans, so a hostile page cannot make the parse slower than linear; only a
 * single, length-capped tag goes through a regex.
 */
object OpenGraphParser {
    const val MAX_TITLE = 300
    /** A longer `<meta …>` tag is skipped: no real one comes near it. */
    private const val MAX_TAG = 4096

    private val ATTR = Regex("([a-zA-Z_:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))") // regex
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]{1,8}|#[0-9]{1,8}|[a-zA-Z]{2,8});") // regex
    private val SPACE = Regex("\\s+") // regex
    private val IMAGE_KEYS = listOf("og:image", "og:image:url", "og:image:secure_url", "twitter:image", "twitter:image:src")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "laquo" to "«", "raquo" to "»", "middot" to "·", "bull" to "•", "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€",
        "eacute" to "é", "egrave" to "è", "agrave" to "à", "aacute" to "á", "ccedil" to "ç", "ntilde" to "ñ",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
    )

    fun parse(page: String, baseUrl: String): LinkPreview? {
        // only the head counts; a <title> in an inline SVG of the body is not the page title
        val headEnd = page.indexOf("</head", ignoreCase = true)
        val html = if (headEnd >= 0) page.substring(0, headEnd) else page
        val meta = HashMap<String, String>()
        var from = 0
        while (true) {
            val start = html.indexOf("<meta", from, ignoreCase = true)
            if (start < 0) break
            val end = html.indexOf('>', start)
            if (end < 0) break // an unclosed tag ends the scan
            from = end + 1
            val next = html.getOrNull(start + 5)
            if (next != null && !next.isWhitespace() && next != '/' && next != '>') continue // <metadata> and the like
            if (end - start > MAX_TAG) continue
            val attrs = HashMap<String, String>()
            for (a in ATTR.findAll(html.substring(start, end + 1))) {
                val v = a.groups[2]?.value ?: a.groups[3]?.value ?: a.groupValues[4]
                attrs.putIfAbsent(a.groupValues[1].lowercase(), v)
            }
            val key = (attrs["property"] ?: attrs["name"])?.trim()?.lowercase() ?: continue
            val content = attrs["content"] ?: continue
            meta.putIfAbsent(key, content)
        }
        val rawTitle = meta["og:title"] ?: meta["twitter:title"] ?: titleElement(html)
        val title = rawTitle?.let { clean(it) }?.takeIf { it.isNotEmpty() } ?: return null
        val image = IMAGE_KEYS.firstNotNullOfOrNull { k -> meta[k]?.let { resolve(baseUrl, decodeEntities(it.trim())) } }
        val card = meta["twitter:card"]?.trim()?.lowercase()
        val w = meta["og:image:width"]?.trim()?.toIntOrNull()
        val h = meta["og:image:height"]?.trim()?.toIntOrNull()
        val large = image != null && when {
            card != null -> card == "summary_large_image"
            w != null && h != null && h > 0 -> w >= 400 && w.toDouble() / h >= 1.3
            else -> false
        }
        return LinkPreview(baseUrl, title, image, large)
    }

    /** The text of the first `<title>`, found with two index scans. */
    private fun titleElement(html: String): String? {
        val open = html.indexOf("<title", ignoreCase = true)
        if (open < 0) return null
        val gt = html.indexOf('>', open)
        if (gt < 0) return null
        val close = html.indexOf("</title", gt, ignoreCase = true)
        return if (close < 0) null else html.substring(gt + 1, close)
    }

    /** The common named entities and numeric references; anything else stays as written. */
    fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> codePoint(e.substring(2).toLongOrNull(16))
            e.startsWith("#") -> codePoint(e.substring(1).toLongOrNull())
            else -> NAMED[e] ?: m.value
        }
    }

    private fun codePoint(c: Long?): String =
        if (c != null && c in 1..0x10FFFF && Character.isValidCodePoint(c.toInt())) String(Character.toChars(c.toInt())) else "\uFFFD"

    private fun clean(raw: String): String =
        decodeEntities(raw).replace(SPACE, " ").filterNot { it.isISOControl() }.trim().take(MAX_TITLE)

    /** [ref] against the page address; only http(s) results are kept. */
    private fun resolve(base: String, ref: String): String? {
        if (ref.isEmpty()) return null
        val abs = runCatching { URI(base).resolve(ref.replace(" ", "%20")).toString() }.getOrNull() ?: return null
        return abs.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
    }
}
