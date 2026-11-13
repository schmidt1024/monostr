package com.monostr.app.ui.feed

/**
 * Backlog 80: one person reposting the same note twice (two kind 6, seconds apart) showed two identical rows. A list
 * keeps the first repost per person and note — the newest, since feeds are newest first — like X.
 */
object Reposts {
    /** [notes] without later reposts of a note the same person reposted above; the same instance when nothing repeats. */
    fun collapsed(notes: List<NoteUi>): List<NoteUi> {
        var seen: HashSet<Pair<String, String>>? = null
        var out: ArrayList<NoteUi>? = null
        notes.forEachIndexed { i, ui ->
            val n = ui.note
            val target = if (n.isRepost) n.repostedId ?: n.repostOf?.id else null
            if (target == null) {
                out?.add(ui)
                return@forEachIndexed
            }
            val s = seen ?: HashSet<Pair<String, String>>().also { seen = it }
            if (s.add(n.author to target)) {
                out?.add(ui)
            } else if (out == null) {
                out = ArrayList<NoteUi>(notes.size).apply { addAll(notes.subList(0, i)) }
            }
        }
        return out ?: notes
    }
}
