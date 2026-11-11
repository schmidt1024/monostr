package com.monostr.app.ui.settings

import java.text.Normalizer

/** An index entry with its texts in the current app language. [pageTitle] is the path shown next to the title. */
data class ResolvedEntry(val entry: SettingEntry, val title: String, val hint: String, val pageTitle: String)

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** Lowercase, decomposed, without accents: «Über» becomes «uber», Turkish dotless «ı» becomes «i». */
fun normalizeForSearch(s: String): String = COMBINING_MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "").replace('ı', 'i')

/** The entries whose control is on its page in the state [a]; the rest would open a page without it. */
fun availableEntries(entries: List<ResolvedEntry>, a: SettingsAvailability): List<ResolvedEntry> = entries.filter { it.entry.available(a) }

/**
 * Case- and accent-insensitive substring search over title, synonyms, hint and page title. UX P2-6: ranked title hits >
 * synonym hits > hint hits > hits only in the page title (the path), each group in index order; path-only hits are left
 * out as soon as anything matched directly, so «tor» does not list every row of Tor & privacy. A blank query finds nothing.
 */
fun searchSettings(query: String, entries: List<ResolvedEntry>): List<ResolvedEntry> {
    val q = normalizeForSearch(query.trim())
    if (q.isEmpty()) return emptyList()
    val ranked = entries.mapNotNull { e ->
        val rank = when {
            normalizeForSearch(e.title).contains(q) -> 0
            e.entry.synonyms.any { normalizeForSearch(it).contains(q) } -> 1
            normalizeForSearch(e.hint).contains(q) -> 2
            normalizeForSearch(e.pageTitle).contains(q) -> 3
            else -> return@mapNotNull null
        }
        rank to e
    }
    val direct = ranked.any { it.first < 3 }
    return ranked.filter { !direct || it.first < 3 }.sortedBy { it.first }.map { it.second }
}
