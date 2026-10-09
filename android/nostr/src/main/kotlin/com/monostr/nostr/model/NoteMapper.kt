package com.monostr.nostr.model

import rust.nostr.sdk.Event
import rust.nostr.sdk.Metadata

/** Maps rust-nostr events to the app's [Note] / [Profile] models. */
object NoteMapper {
    fun note(event: Event, withQuote: Boolean = true, resolveRepost: (String) -> Event? = { null }): Note? {
        val kind = event.kind().asU16().toInt()
        val tags = event.tags().toVec().map { it.asVec() }
        return when (kind) {
            1 -> {
                val refs = Nip10.parse(tags)
                val parsed = NoteMedia.parse(event.content(), tags, withQuote)
                Note(
                    id = event.id().toHex(), author = event.author().toHex(), content = event.content(),
                    createdAt = event.createdAt().asSecs().toLong(), kind = 1,
                    rootId = refs.rootId, replyToId = refs.replyToId, repostOf = null,
                    mentionedPubkeys = tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] },
                    media = parsed.media,
                    contentWarning = tags.firstOrNull { it.isNotEmpty() && it[0] == "content-warning" }?.let { it.getOrNull(1) ?: "" },
                    hashtags = tags.filter { it.size >= 2 && it[0] == "t" }.map { it[1].lowercase() }.distinct(),
                    displayContent = parsed.displayContent,
                    quotedId = parsed.quote?.id,
                    quoteRelays = parsed.quote?.relays.orEmpty(),
                    client = client(tags),
                )
            }
            6 -> {
                val referencedId = tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                val original = verifiedEmbedded(event) ?: referencedId?.let(resolveRepost)
                Note(
                    id = event.id().toHex(), author = event.author().toHex(), content = "",
                    createdAt = event.createdAt().asSecs().toLong(), kind = 6,
                    rootId = null, replyToId = null,
                    repostOf = original?.let { note(it) },
                    mentionedPubkeys = emptyList(),
                    client = client(tags),
                )
            }
            else -> null
        }
    }

    /** The first `client` tag's name, trimmed and cut to [Note.CLIENT_MAX]; null for no tag, a bare tag or a blank name. */
    fun client(tags: List<List<String>>): String? =
        tags.firstOrNull { it.firstOrNull() == "client" }?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.take(Note.CLIENT_MAX)

    /**
     * The note embedded in a kind 6, only if its id and signature verify and, when the repost
     * has an `e` tag, the tag points at that same id. Anything else could be forged by the
     * reposter and is ignored.
     */
    fun verifiedEmbedded(repost: Event): Event? {
        val embedded = repost.content().takeIf { it.isNotBlank() }?.let { runCatching { Event.fromJson(it) }.getOrNull() } ?: return null
        if (!runCatching { embedded.verify() }.getOrDefault(false)) return null
        val referencedId = repost.tags().toVec().map { it.asVec() }.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        if (referencedId != null && referencedId != embedded.id().toHex()) return null
        return embedded
    }

    fun profile(pubkey: String, metadata: Metadata?): Profile {
        val r = metadata?.asRecord() ?: return Profile.empty(pubkey)
        return Profile(pubkey, r.name, r.displayName, r.picture, r.about, r.nip05, banner = r.banner)
    }
}
