package com.monostr.nostr.model

/** NIP-23 long-form article (kind 30023); drafts (30024) are never an [Article]. Tags come from someone else: the image is https on a public host or none. */
data class Article(
    val event: AddressableEvent,
    val title: String,
    val summary: String?,
    val image: String?,
    val publishedAt: Long,
    val contentWarning: String?,
) {
    val ref: AddressRef get() = AddressRef(KIND, event.pubkey, event.identifier)
    val coordinate: String get() = ref.coordinate

    /** The article as a note for the shared action row, tips and counts; the text itself stays in [event] (displayContent is empty). */
    fun asNote(): Note = Note(
        id = event.id, author = event.pubkey, content = event.content, createdAt = event.createdAt, kind = KIND,
        rootId = null, replyToId = null, repostOf = null, mentionedPubkeys = emptyList(),
        contentWarning = contentWarning, displayContent = "", addressRef = ref,
    )

    companion object {
        const val KIND = 30023
        private val PUBKEY = Regex("[0-9a-f]{64}") // regex

        /** The ref of a `30023:<pubkey>:<d>` coordinate (the identifier may hold colons); null for any other string. */
        fun refOfCoordinate(coordinate: String): AddressRef? {
            val parts = coordinate.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != KIND.toString() || !PUBKEY.matches(parts[1])) return null
            return AddressRef(KIND, parts[1], parts[2])
        }

        fun parse(event: AddressableEvent): Article? {
            if (event.kind != KIND) return null
            return Article(
                event = event,
                title = event.tag("title") ?: "",
                summary = event.tag("summary"),
                image = LiveStream.pictureUrl(event.tag("image")),
                publishedAt = event.tag("published_at")?.toLongOrNull()?.takeIf { it > 0 } ?: event.createdAt,
                contentWarning = event.tags.firstOrNull { it.isNotEmpty() && it[0] == "content-warning" }?.let { it.getOrNull(1) ?: "" },
            )
        }
    }
}
