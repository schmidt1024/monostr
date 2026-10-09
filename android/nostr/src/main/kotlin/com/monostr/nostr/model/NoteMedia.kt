package com.monostr.nostr.model

/** One picture or video attached to a note, from the text or a NIP-92 `imeta` tag (spec 3.1). */
data class NoteMedia(
    val url: String,
    val kind: Kind,
    val poster: String? = null,
    val blurhash: String? = null,
    val alt: String? = null,
    /** width x height from `imeta dim`, when given. */
    val dim: Pair<Int, Int>? = null,
) {
    enum class Kind { IMAGE, VIDEO }

    /** Media in display order, the note text with those URLs (and a freestanding quote link) removed, and the quote. */
    data class Parsed(val media: List<NoteMedia>, val displayContent: String, val quote: QuoteRef? = null)

    companion object {
        const val MAX = 4
        /** `#nsfw` as a whole hashtag, anywhere in the content (spec 3.1). */
        val NSFW = Regex("(?<![\\p{L}\\p{N}_])#nsfw(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE) // regex
        private val URL = Regex("https?://[^\\s<>\"']+") // regex
        private val HTTP = Regex("^https?://", RegexOption.IGNORE_CASE) // regex
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp")
        private val VIDEO_EXT = setOf("mp4", "webm", "mov", "m4v")

        /** [withQuote] false: no quote detection (inside a quote card, spec 3: depth 1). */
        fun parse(content: String, tags: List<List<String>>, withQuote: Boolean = true): Parsed {
            val quote = if (withQuote) NoteQuote.find(content, tags) else null
            val text = if (quote != null) NoteQuote.strip(content, quote.id) else content
            return parseMedia(text, tags).copy(quote = quote)
        }

        private fun parseMedia(content: String, tags: List<List<String>>): Parsed {
            val meta = imeta(tags)
            val ordered = LinkedHashMap<String, NoteMedia>()
            for (m in URL.findAll(content)) {
                val url = trimUrl(m.value)
                if (url in ordered) continue
                val attrs = meta[url]
                val kind = kindOf(url, attrs?.get("m")) ?: continue
                ordered[url] = build(url, kind, attrs)
            }
            for ((url, attrs) in meta) {
                if (url in ordered) continue
                val kind = kindOf(url, attrs["m"]) ?: continue
                ordered[url] = build(url, kind, attrs)
            }
            val media = ordered.values.take(MAX)
            if (media.isEmpty()) return Parsed(emptyList(), content)
            // A URL is removed only where no further URL character follows, so `.../a.png` leaves `.../a.png.html` intact.
            val remove = media.map { it.url }.distinct().map { Regex(Regex.escape(it) + "(?=[.,;:!?)]*(?:[\\s<>\"']|$))") } // regex
            val lines = content.split('\n').mapNotNull { line ->
                var out = line
                var touched = false
                for (pattern in remove) {
                    val replaced = pattern.replace(out, "")
                    if (replaced != out) { out = replaced; touched = true }
                }
                if (touched && out.isBlank()) null else out.trimEnd()
            }
            return Parsed(media, lines.joinToString("\n").trim())
        }

        /** IMAGE/VIDEO from the MIME type when given, else from the extension before `?`/`#`; null for anything else. */
        fun kindOf(url: String, mime: String?): Kind? {
            when {
                mime?.startsWith("image/") == true -> return Kind.IMAGE
                mime?.startsWith("video/") == true -> return Kind.VIDEO
            }
            val path = url.substringBefore('?').substringBefore('#')
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                in IMAGE_EXT -> Kind.IMAGE
                in VIDEO_EXT -> Kind.VIDEO
                else -> null
            }
        }

        private fun build(url: String, kind: Kind, attrs: Map<String, String>?): NoteMedia {
            val dim = attrs?.get("dim")?.let { d ->
                val w = d.substringBefore('x').toIntOrNull(); val h = d.substringAfter('x', "").toIntOrNull()
                if (w != null && h != null && w > 0 && h > 0) w to h else null
            }
            val poster = attrs?.get("image")?.takeIf { HTTP.containsMatchIn(it) }
            return NoteMedia(url, kind, poster = poster, blurhash = attrs?.get("blurhash"), alt = attrs?.get("alt"), dim = dim)
        }

        /** `imeta` tags as url → attributes (`m`, `image`, `blurhash`, `alt`, `dim`); entries without an http(s) `url` are skipped. */
        private fun imeta(tags: List<List<String>>): Map<String, Map<String, String>> {
            val out = LinkedHashMap<String, Map<String, String>>()
            for (t in tags) {
                if (t.isEmpty() || t[0] != "imeta") continue
                val attrs = t.drop(1).mapNotNull { e ->
                    val i = e.indexOf(' ')
                    if (i <= 0) null else e.substring(0, i) to e.substring(i + 1).trim()
                }.toMap()
                val url = attrs["url"]?.let(::trimUrl)?.takeIf { HTTP.containsMatchIn(it) } ?: continue
                out[url] = attrs
            }
            return out
        }

        /** Same rule as NoteLinks.trimUrl: sentence punctuation and an unmatched `)` belong to the prose. */
        private fun trimUrl(raw: String): String {
            var end = raw.length
            while (end > 0 && raw[end - 1] in ".,;:!?") end--
            if (end > 0 && raw[end - 1] == ')' && '(' !in raw.substring(0, end)) end--
            return raw.substring(0, end)
        }
    }
}
