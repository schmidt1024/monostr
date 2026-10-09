package com.monostr.nostr.model

import com.monostr.nostr.search.SearchQuery
import rust.nostr.sdk.Tag

/** Spec 4.2: every `nostr:npub`/`nprofile` in a text becomes one `p` tag (deduplicated, text order). */
object MentionTags {
    /** The single definition of a `nostr:npub1…`/`nostr:nprofile1…` token. */
    val TOKEN = Regex("(?i:nostr:(?:npub1|nprofile1))[0-9a-zA-Z]+") // regex

    /** The pubkey hex of one matched [TOKEN], or null when it does not decode. */
    fun pubkey(token: String): String? = (SearchQuery.parse(token) as? SearchQuery.Profile)?.pubkey

    fun pubkeys(text: String): List<String> = TOKEN.findAll(text).mapNotNull { pubkey(it.value) }.distinct().toList()

    fun from(text: String): List<Tag> = pubkeys(text).map { Tag.parse(listOf("p", it)) }
}
