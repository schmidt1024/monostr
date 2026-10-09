package com.monostr.nostr.model

import rust.nostr.sdk.Tag

/** Every `#hashtag` of a note's text becomes one `t` tag (lowercase, deduplicated, text order), so relays find the note by `#t`. */
object HashtagTags {
    /** A URL; scanned first so that a `#` inside a URL is never a tag. */
    const val URL = "https?://[^\\s<>\"']+" // regex
    /** A hashtag not glued to a word, with a letter somewhere; group 1 is the name. */
    const val HASHTAG = "(?<![\\p{L}\\p{N}_])#((?=[\\p{N}_]*\\p{L})[\\p{L}\\p{N}_]+)" // regex

    private val TOKEN = Regex("$URL|$HASHTAG")

    fun names(text: String): List<String> =
        TOKEN.findAll(text).mapNotNull { m -> m.groups[1]?.value?.lowercase() }.distinct().toList()

    fun from(text: String): List<Tag> = names(text).map { Tag.parse(listOf("t", it)) }
}
