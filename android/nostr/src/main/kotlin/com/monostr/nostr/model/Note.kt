package com.monostr.nostr.model

import kotlinx.serialization.Serializable

/** A kind 1 note or a kind 6 repost as shown in feeds and threads. */
@Serializable
data class Note(
    val id: String,
    val author: String,
    val content: String,
    val createdAt: Long,
    val kind: Int,
    /** NIP-10 root of the thread this note belongs to, null for roots. */
    val rootId: String?,
    /** NIP-10 direct parent, null for roots. */
    val replyToId: String?,
    /** For kind 6: the reposted note (embedded or referenced). */
    val repostOf: Note?,
    val mentionedPubkeys: List<String>,
    /** Pictures/videos from the text and `imeta` tags (spec 3.1); at most [NoteMedia.MAX]. */
    val media: List<NoteMedia> = emptyList(),
    /** NIP-36 `content-warning` reason ("" for a bare tag), null without the tag. */
    val contentWarning: String? = null,
    /** `t` tags, lowercased. */
    val hashtags: List<String> = emptyList(),
    /** [content] without the media URLs, for display. */
    val displayContent: String = content,
    /** Spec 3: the note this one quotes (NIP-18 `q` tag, else the first `nostr:note`/`nevent` in the text); one card per note. */
    val quotedId: String? = null,
    /** Relay hints for [quotedId] from the `q` tags and the `nevent`; may be empty. */
    val quoteRelays: List<String> = emptyList(),
    /** NIP-89: the name in the first `client` tag (trimmed, at most [CLIENT_MAX] characters), null without one. */
    val client: String? = null,
    /** Relay/author hint of the [rootId] `e` tag (spec 2026-10-23 §4.1), null without one. */
    val rootHint: RefHint? = null,
    /** Relay/author hint of the [replyToId] `e` tag, null without one. */
    val replyHint: RefHint? = null,
    /** Author pubkey of the quoted note from the `q` tag or `nevent`, null if unknown. */
    val quoteAuthor: String? = null,
) {
    val isReply: Boolean get() = replyToId != null
    val isRepost: Boolean get() = kind == 6
    /** Spec 3.1: NIP-36 tag, `t` tag nsfw, or `#nsfw` in the text. */
    val isSensitive: Boolean get() = contentWarning != null || "nsfw" in hashtags || NoteMedia.NSFW.containsMatchIn(content)

    companion object {
        const val CLIENT_MAX = 32
    }
}

/** Where to look for a referenced note: a relay (ws/wss, no trailing slash) and/or its author's hex pubkey. */
@Serializable
data class RefHint(val relay: String? = null, val author: String? = null)

/**
 * Pubkeys whose relays may hold the parent: the `e` tag's author hint, else the first three `p` tags
 * (hex only, lower-cased), spec 2026-10-23 §4.1.
 */
fun Note.parentCandidates(): List<String> =
    replyHint?.author?.let(::listOf)
        ?: mentionedPubkeys.map { it.lowercase() }.filter { PUBKEY_HEX.matches(it) }.take(3)

private val PUBKEY_HEX = Regex("^[0-9a-f]{64}$") // regex

@Serializable
data class Profile(
    val pubkey: String,
    val name: String?,
    val displayName: String?,
    val picture: String?,
    val about: String?,
    val nip05: String?,
    /** kind 0 `banner` URL (spec 5), shown 16:9 above the avatar. */
    val banner: String? = null,
    /** kind 0 `website`, shown as a link under the bio. */
    val website: String? = null,
) {
    /** Display name, then name, then a shortened npub-like fallback. */
    val shownName: String get() = displayName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: shortPubkey(pubkey)

    companion object {
        fun empty(pubkey: String) = Profile(pubkey, null, null, null, null, null)
        fun shortPubkey(pubkey: String): String = if (pubkey.length >= 16) pubkey.take(8) + "…" + pubkey.takeLast(4) else pubkey
    }
}

enum class RelayState { CONNECTING, CONNECTED, DISCONNECTED }

/**
 * A relay of the list as the settings show it. [everConnected]: it connected at least once in this session; [resting]: it
 * never did and failed its attempts, so the engine tries it only once every ten minutes (0.24.3).
 */
data class RelayInfo(val url: String, val state: RelayState, val everConnected: Boolean = false, val resting: Boolean = false)
