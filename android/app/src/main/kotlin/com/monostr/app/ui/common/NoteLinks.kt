package com.monostr.app.ui.common

import com.monostr.nostr.model.HashtagTags
import com.monostr.nostr.search.SearchQuery

/** One run of note text: plain, or a link the UI renders in primary colour with underline (spec 3.3). */
sealed class NoteSpan {
    abstract val text: String
    data class Plain(override val text: String) : NoteSpan()
    data class Hashtag(override val text: String, val tag: String) : NoteSpan()
    data class Mention(override val text: String, val pubkey: String) : NoteSpan()
    data class NoteRef(override val text: String, val noteId: String) : NoteSpan()
    data class Url(override val text: String, val url: String) : NoteSpan()
}

/** Pure text scanner; URLs win over hashtags so `#` in a URL is never a tag. */
object NoteLinks {
    // group 1: url, group 2: nostr link (scheme and prefix case-insensitive, bech32 may be all caps), group 3: hashtag (needs a letter somewhere)
    private val TOKEN = Regex(
        "(${HashtagTags.URL})" +
            "|((?i:nostr:(?:npub1|nprofile1|note1|nevent1))[0-9a-zA-Z]+)" + // regex
            "|${HashtagTags.HASHTAG}", // the hashtags a published note tags (`t`) are exactly the ones shown as links
    )

    fun annotate(content: String): List<NoteSpan> {
        if (content.isEmpty()) return emptyList()
        val out = ArrayList<NoteSpan>()
        var last = 0
        for (m in TOKEN.findAll(content)) {
            var end = m.range.last + 1
            val span: NoteSpan? = when {
                m.groups[1] != null -> {
                    val url = trimUrl(m.value)
                    end = m.range.first + url.length
                    NoteSpan.Url(url, url)
                }
                m.groups[2] != null -> when (val q = SearchQuery.parse(m.value)) {
                    is SearchQuery.Profile -> NoteSpan.Mention(m.value, q.pubkey)
                    is SearchQuery.Thread -> NoteSpan.NoteRef(m.value, q.noteId)
                    else -> null
                }
                else -> NoteSpan.Hashtag(m.value, m.groupValues[3].lowercase())
            }
            if (span == null) continue
            if (m.range.first > last) out += NoteSpan.Plain(content.substring(last, m.range.first))
            out += span
            last = end
        }
        if (last < content.length) out += NoteSpan.Plain(content.substring(last))
        return mergePlain(out)
    }

    /**
     * The URL char class has no notion of sentence punctuation, so it can swallow trailing
     * `.,;:!?` and an unmatched closing `)` that belong to the surrounding prose, not the link.
     */
    private fun trimUrl(raw: String): String {
        var end = raw.length
        while (end > 0 && raw[end - 1] in ".,;:!?") end--
        if (end > 0 && raw[end - 1] == ')' && '(' !in raw.substring(0, end)) end--
        return raw.substring(0, end)
    }

    private fun mergePlain(spans: List<NoteSpan>): List<NoteSpan> {
        val out = ArrayList<NoteSpan>()
        for (s in spans) {
            val prev = out.lastOrNull()
            if (s is NoteSpan.Plain && prev is NoteSpan.Plain) out[out.size - 1] = NoteSpan.Plain(prev.text + s.text) else out += s
        }
        return out
    }
}
