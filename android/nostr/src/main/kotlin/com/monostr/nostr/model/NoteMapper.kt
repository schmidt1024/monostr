package com.monostr.nostr.model

import com.monostr.tips.Kinds
import com.monostr.tips.event.EventJson
import rust.nostr.sdk.Event
import rust.nostr.sdk.Metadata
import com.monostr.tips.event.Event as JsonEvent

/** Maps rust-nostr events to the app's [Note] / [Profile] models. */
object NoteMapper {
    /**
     * The event crosses the FFI once, as JSON, and is read in Kotlin: reading kind, tags (one call per tag), content,
     * id and author one by one cost about 20 ms per note on a phone, a second per page.
     */
    fun note(event: Event, withQuote: Boolean = true, resolveRepost: (String) -> Event? = { null }): Note? =
        note(EventJson.decode(event.asJson()), withQuote, resolveRepost)

    internal fun note(event: JsonEvent, withQuote: Boolean = true, resolveRepost: (String) -> Event? = { null }): Note? {
        val tags = event.tags
        return when (event.kind) {
            // a NIP-22 comment reads like a note: its lowercase `e` is the parent; directly under an article that `e` (when there is one)
            // names the article's version, so [articleParent] marks the comment and the walks stop there instead of loading it
            1, Kinds.COMMENT -> {
                val refs = Nip10.parse(tags)
                val parsed = NoteMedia.parse(event.content, tags, withQuote)
                Note(
                    id = event.id, author = event.pubkey, content = event.content,
                    createdAt = event.createdAt, kind = event.kind,
                    rootId = refs.rootId, replyToId = refs.replyToId, repostOf = null,
                    mentionedPubkeys = tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] },
                    media = parsed.media,
                    contentWarning = tags.firstOrNull { it.isNotEmpty() && it[0] == "content-warning" }?.let { it.getOrNull(1) ?: "" },
                    hashtags = tags.filter { it.size >= 2 && it[0] == "t" }.map { it[1].lowercase() }.distinct(),
                    displayContent = parsed.displayContent,
                    quotedId = parsed.quote?.id,
                    quoteRelays = parsed.quote?.relays.orEmpty(),
                    client = client(tags),
                    rootHint = refs.rootHint, replyHint = refs.replyHint,
                    quoteAuthor = parsed.quote?.author,
                    addressRef = parsed.address,
                    articleParent = if (event.kind == Kinds.COMMENT) articleParent(tags) else null,
                )
            }
            6 -> {
                val referencedId = tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                val original = verifiedEmbedded(event.content, referencedId) ?: referencedId?.let(resolveRepost)
                Note(
                    id = event.id, author = event.pubkey, content = "",
                    createdAt = event.createdAt, kind = 6,
                    rootId = null, replyToId = null,
                    repostOf = original?.let { note(it) },
                    mentionedPubkeys = emptyList(),
                    client = client(tags),
                )
            }
            else -> null
        }
    }

    /**
     * The article a kind 1111 sits directly under: its root `A` tag is a `30023:<pubkey>:<d>` coordinate and the parent is that
     * same coordinate (lowercase `a`) or, from clients that only name the shown version, the parent kind `k` is 30023.
     * A comment below another comment names the article only as root, so it has none.
     */
    internal fun articleParent(tags: List<List<String>>): AddressRef? {
        val root = tags.firstOrNull { it.size >= 2 && it[0] == "A" }?.get(1) ?: return null
        val ref = Article.refOfCoordinate(root) ?: return null
        val parentCoordinate = tags.firstOrNull { it.size >= 2 && it[0] == "a" }?.get(1)
        val parentKind = tags.firstOrNull { it.size >= 2 && it[0] == "k" }?.get(1)
        return ref.takeIf { parentCoordinate == root || (parentCoordinate == null && parentKind == Article.KIND.toString()) }
    }

    /** The first `client` tag's name, trimmed and cut to [Note.CLIENT_MAX]; null for no tag, a bare tag or a blank name. */
    fun client(tags: List<List<String>>): String? =
        tags.firstOrNull { it.firstOrNull() == "client" }?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.take(Note.CLIENT_MAX)

    /**
     * The note embedded in a kind 6, only if its id and signature verify and, when the repost
     * has an `e` tag, the tag points at that same id. Anything else could be forged by the
     * reposter and is ignored.
     */
    fun verifiedEmbedded(repost: Event): Event? =
        verifiedEmbedded(repost.content(), repost.tags().toVec().map { it.asVec() }.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1))

    /** [verifiedEmbedded] for an event already read in Kotlin. */
    internal fun verifiedEmbedded(repost: JsonEvent): Event? = verifiedEmbedded(repost.content, repost.firstTagValue("e"))

    /** [event] read in Kotlin: one FFI call instead of one per field and tag. */
    fun decoded(event: Event): JsonEvent = EventJson.decode(event.asJson())

    /** [verifiedEmbedded] for a repost's [content] and the id its first `e` tag names ([referencedId]). */
    internal fun verifiedEmbedded(content: String, referencedId: String?): Event? {
        val embedded = content.takeIf { it.isNotBlank() }?.let { runCatching { Event.fromJson(it) }.getOrNull() } ?: return null
        if (!runCatching { embedded.verify() }.getOrDefault(false)) return null
        if (referencedId != null && referencedId != embedded.id().toHex()) return null
        return embedded
    }

    fun profile(pubkey: String, metadata: Metadata?): Profile {
        val r = metadata?.asRecord() ?: return Profile.empty(pubkey)
        return Profile(pubkey, r.name, r.displayName, r.picture, r.about, r.nip05, banner = r.banner, website = r.website)
    }
}
