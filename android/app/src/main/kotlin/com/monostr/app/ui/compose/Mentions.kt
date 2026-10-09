package com.monostr.app.ui.compose

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.monostr.nostr.Npub
import com.monostr.nostr.model.MentionTags
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.FeedRepository
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.SearchRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Spec 4.2: the `@partial` before the cursor, the ranking of candidates and the replacement. Pure. */
object MentionQuery {
    const val MAX = 8

    /** [start] is the index of the `@`; [query] has at least one name character. */
    data class Active(val start: Int, val query: String)
    data class Edit(val text: String, val cursor: Int)

    fun active(text: String, cursor: Int): Active? {
        if (cursor <= 0 || cursor > text.length) return null
        var i = cursor
        while (i > 0 && isNameChar(text[i - 1])) i--
        if (i == cursor || i == 0 || text[i - 1] != '@') return null
        val at = i - 1
        if (at > 0 && !text[at - 1].isWhitespace()) return null // an at-sign inside a word is part of an address, not a mention
        return Active(at, text.substring(i, cursor))
    }

    fun rank(query: String, follows: List<Profile>, local: List<Profile>, max: Int = MAX): List<Profile> {
        val q = query.lowercase()
        val followed = follows.map { it.pubkey }.toSet()
        return (follows + local).distinctBy { it.pubkey }
            .filter { p -> fields(p).any { it.contains(q) } }
            .sortedWith(
                compareBy<Profile>(
                    { p -> if (fields(p).any { it.startsWith(q) }) 0 else 1 },
                    { if (it.pubkey in followed) 0 else 1 },
                    { it.shownName.lowercase() },
                ),
            )
            .take(max)
    }

    /**
     * Replaces `@partial` (from [active] to [cursor]) by `nostr:npub…` and one space; an existing space
     * after the cursor is reused. Before a line break a space is inserted too, so the cursor stays on
     * the line (spec 8 of Plan 10d).
     */
    fun replace(text: String, active: Active, cursor: Int, pubkey: String): Edit {
        val token = "nostr:" + Npub.encode(pubkey)
        val rest = text.substring(cursor)
        val spaced = if (rest.startsWith(" ")) rest else " $rest"
        return Edit(text.substring(0, active.start) + token + spaced, active.start + token.length + 1)
    }

    /**
     * Spec 8 (10c backlog): [new] is [old] with one range deleted that cuts into a `nostr:npub…` token
     * shown as `@name` (its pubkey is in [names]): the whole token goes and the cursor lands where the
     * deletion began. Null for every other edit (typing, plain text, whole tokens, tokens shown raw).
     */
    fun cutToken(old: String, new: String, names: Map<String, String>): Edit? {
        if (names.isEmpty() || new.length >= old.length) return null
        var start = 0
        while (start < new.length && old[start] == new[start]) start++
        var tail = 0
        while (tail < new.length - start && old[old.length - 1 - tail] == new[new.length - 1 - tail]) tail++
        val end = old.length - tail // [start, end) of old was deleted
        if (old.removeRange(start, end) != new) return null
        val cut = MentionTags.TOKEN.findAll(old)
            .filter { m -> MentionTags.pubkey(m.value)?.let { it in names } == true }
            .filter { m -> m.range.first < end && start <= m.range.last }
            .toList()
        if (cut.isEmpty() || cut.all { start <= it.range.first && it.range.last < end }) return null
        val from = minOf(start, cut.first().range.first)
        val to = maxOf(end, cut.last().range.last + 1)
        return Edit(old.removeRange(from, to), from)
    }

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.' || c == '-'
    private fun fields(p: Profile) = listOfNotNull(p.name, p.displayName, p.nip05).map { it.lowercase() }
}

/** Spec 4.2: candidates from the follows (names from the profile database) and the local name search; never the network. */
class MentionSuggester(
    private val feed: FeedRepository,
    private val profiles: ProfileRepository,
    private val search: SearchRepository,
    private val self: String,
) {
    private val mutex = Mutex()
    private var follows: List<Profile>? = null

    /** Follows with a stored kind 0, read from the database only (empty until a contact list is stored); later calls reuse the list. */
    suspend fun preload(): List<Profile> = mutex.withLock {
        follows ?: profiles.local(feed.followsLocal().filter { it != self }).also { follows = it }
    }

    suspend fun suggest(query: String): List<Profile> =
        MentionQuery.rank(query, preload(), search.profilesLocal(query, 20).filter { it.pubkey != self })
}

/**
 * Spec 4.2: `nostr:npub…` tokens whose name is known show as `@name`; the stored text never changes.
 * A token is one unit for the cursor: an offset inside it maps to its end.
 */
class MentionDisplay(raw: String, names: Map<String, String>) {
    private data class Seg(val rawStart: Int, val rawEnd: Int, val shownStart: Int, val shownEnd: Int, val token: Boolean)

    val shown: String
    private val segs: List<Seg>

    init {
        val out = StringBuilder()
        val list = ArrayList<Seg>()
        var last = 0
        for (m in MentionTags.TOKEN.findAll(raw)) {
            val pubkey = MentionTags.pubkey(m.value) ?: continue
            val name = names[pubkey] ?: continue
            if (m.range.first > last) {
                list += Seg(last, m.range.first, out.length, out.length + (m.range.first - last), token = false)
                out.append(raw, last, m.range.first)
            }
            val label = "@$name"
            list += Seg(m.range.first, m.range.last + 1, out.length, out.length + label.length, token = true)
            out.append(label)
            last = m.range.last + 1
        }
        if (last < raw.length) {
            list += Seg(last, raw.length, out.length, out.length + (raw.length - last), token = false)
            out.append(raw, last, raw.length)
        }
        shown = out.toString()
        segs = list
    }

    fun toShown(offset: Int): Int {
        val s = segs.firstOrNull { offset < it.rawEnd } ?: return shown.length
        return when {
            !s.token -> s.shownStart + (offset - s.rawStart)
            offset <= s.rawStart -> s.shownStart
            else -> s.shownEnd
        }
    }

    fun toRaw(offset: Int): Int {
        val s = segs.firstOrNull { offset < it.shownEnd } ?: return segs.lastOrNull()?.rawEnd ?: offset
        return when {
            !s.token -> s.rawStart + (offset - s.shownStart)
            offset <= s.shownStart -> s.rawStart
            else -> s.rawEnd
        }
    }
}

class MentionTransformation(private val names: Map<String, String>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (names.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val display = MentionDisplay(text.text, names)
        return TransformedText(
            AnnotatedString(display.shown),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = display.toShown(offset)
                override fun transformedToOriginal(offset: Int) = display.toRaw(offset)
            },
        )
    }
}
