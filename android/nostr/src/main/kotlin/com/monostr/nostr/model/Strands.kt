package com.monostr.nostr.model

/** A direct reply to the focused note with at most one of its replies; [hidden] counts the rest below it (spec 2026-10-16 §4.1). */
data class Strand(val reply: Note, val child: Note?, val hidden: Int)

object Strands {
    fun build(focused: Note, replies: List<Note>): List<Strand> {
        val all = replies.filter { it.id != focused.id }
        val ids = all.map { it.id }.toSet() + focused.id
        val byParent = all.groupBy { it.replyToId }
        val focusedIsRoot = focused.rootId == null
        val direct = all.filter { it.replyToId == focused.id || (focusedIsRoot && it.replyToId !in ids) }
            .sortedWith(compareBy<Note>({ it.author != focused.author }, { it.createdAt }))
        return direct.map { r ->
            val kids = (byParent[r.id] ?: emptyList()).sortedBy { it.createdAt }
            val child = kids.firstOrNull { it.author == r.author } ?: kids.firstOrNull { it.author == focused.author } ?: kids.firstOrNull()
            Strand(r, child, descendants(r.id, byParent) - (if (child != null) 1 else 0))
        }
    }

    private fun descendants(id: String, byParent: Map<String?, List<Note>>, seen: MutableSet<String> = HashSet()): Int =
        (byParent[id] ?: emptyList()).sumOf { if (seen.add(it.id)) 1 + descendants(it.id, byParent, seen) else 0 }
}
