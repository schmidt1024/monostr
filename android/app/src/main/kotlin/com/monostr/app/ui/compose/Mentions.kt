package com.monostr.app.ui.compose

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange
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

    /**
     * Spec 8, for the state-based field: the keyboard sees the shown `@name`, never the raw token, so it may
     * rewrite it (composing text "Alin" after a backspace, autocorrect). [new] is [old] after one edit. Deletions
     * go through [cutToken]; typing at a shown token's outer edges is left alone. An edit that replaces text
     * reaching into a shown token, or inserts inside it, is judged by what took the token's place: the shown
     * name again, maybe followed by a space or punctuation, keeps the token (and what follows); a shortening or
     * a near-copy of the name ("Alin", "Aline", "Alinas") removes the whole token and leaves nothing; other text
     * stays as typed. Null when [new] is left as it is.
     */
    fun editToken(old: String, new: String, names: Map<String, String>): Edit? {
        if (names.isEmpty() || old == new) return null
        val min = minOf(old.length, new.length)
        var start = 0
        while (start < min && old[start] == new[start]) start++
        var tail = 0
        while (tail < min - start && old[old.length - 1 - tail] == new[new.length - 1 - tail]) tail++
        val end = old.length - tail // [start, end) of old became [start, new.length - tail) of new
        if (new.length < old.length && old.removeRange(start, end) == new) return cutToken(old, new, names)
        val tokens = MentionTags.TOKEN.findAll(old)
            .filter { m -> MentionTags.pubkey(m.value)?.let { it in names } == true }
            .map { it.range }.toList()
        if (start == end) {
            // a pure insertion can sit anywhere in a run of equal characters; one place outside every token is enough
            val k = new.length - old.length
            var p = start
            while (p >= 0 && old.regionMatches(p, new, p + k, old.length - p)) {
                if (tokens.none { it.first < p && p <= it.last }) return null
                p--
            }
            val t = tokens.first { it.first < start && start <= it.last }
            val typed = new.substring(start, start + k)
            return Edit(old.replaceRange(t, typed), t.first + typed.length)
        }
        val hit = tokens.filter { it.first < end && start <= it.last }
        if (hit.isEmpty()) return null
        val from = minOf(start, hit.first().first)
        val to = maxOf(end, hit.last().last + 1)
        // what took the place of [from, to): the field maps an edit of a shown @name to the whole raw token
        val typed = new.substring(from, new.length - (old.length - to))
        val shown = shown(old.substring(from, to), names)
        val bare = shown.removePrefix("@")
        // the name committed again, maybe with a space or punctuation behind it ("Alina ", "@Alina!"): the token
        // stays and the rest follows it; a glued letter or digit would run into the token, so that is not kept
        val rest = when {
            typed.startsWith(shown) -> typed.substring(shown.length)
            typed.startsWith(bare) -> typed.substring(bare.length)
            else -> null
        }
        return when {
            rest != null && (rest.isEmpty() || !rest[0].isLetterOrDigit()) ->
                Edit(old.replaceRange(from, to, old.substring(from, to) + rest), to + rest.length)
            // a shortening ("Alin") or a near-copy ("Aline", "Alinas" from autocorrect): the whole token goes, nothing stays
            shown.startsWith(typed) || typed.removePrefix("@").commonPrefixWith(bare).isNotEmpty() ->
                Edit(old.removeRange(from, to), from)
            else -> null // the token is already gone, replaced by what was typed
        }
    }

    /** The `nostr:npub…` tokens in [raw] whose person has a name in [names], each with the `@name` it shows as, in text order. */
    fun shownTokens(raw: String, names: Map<String, String>): List<Pair<IntRange, String>> =
        if (names.isEmpty()) emptyList()
        else MentionTags.TOKEN.findAll(raw).mapNotNull { m -> MentionTags.pubkey(m.value)?.let(names::get)?.let { m.range to "@$it" } }.toList()

    /** [raw] as the composer field shows it. */
    fun shown(raw: String, names: Map<String, String>): String =
        shownTokens(raw, names).asReversed().fold(raw) { text, (range, label) -> text.replaceRange(range, label) }

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
 * The field keeps the cursor out of a replaced token (it lands at one of its ends).
 */
class MentionOutput(private val names: Map<String, String>) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        // from the back, so the ranges in front stay where they are
        for ((range, label) in MentionQuery.shownTokens(asCharSequence().toString(), names).asReversed()) {
            replace(range.first, range.last + 1, label)
        }
    }
}

/** Spec 8 (10c backlog): a backspace, cut or keyboard rewrite of a shown `@name` removes the whole token ([MentionQuery.editToken]). */
class MentionInput(private val names: Map<String, String>) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val cut = MentionQuery.editToken(originalText.toString(), asCharSequence().toString(), names) ?: return
        replace(0, length, cut.text)
        selection = TextRange(cut.cursor)
    }
}
