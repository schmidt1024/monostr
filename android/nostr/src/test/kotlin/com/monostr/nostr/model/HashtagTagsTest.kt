package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** NIP-01/NIP-24: every #hashtag of a note becomes a `t` tag, so relays find the note by `#t`. */
class HashtagTagsTest {
    @Test
    fun `hashtags become lowercase t tags once, in text order`() {
        assertEquals(listOf("monero", "nostr", "xmr2026"), HashtagTags.names("Tips in #Monero on #nostr! #XMR2026 and #monero again"))
    }

    @Test
    fun `a hash inside a URL, a number alone and a hash inside a word are no tags`() {
        assertEquals(listOf("ok"), HashtagTags.names("see https://x.example/page#section and #123 or a#b and #ok"))
    }

    @Test
    fun `the tags are t tags`() {
        assertEquals(listOf(listOf("t", "monero")), HashtagTags.from("#Monero").map { it.asVec() })
    }
}
