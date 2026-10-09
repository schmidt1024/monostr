package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Nip19Profile

class MentionTagsTest {
    private val alice = Keys.parse("0000000000000000000000000000000000000000000000000000000000000002")
    private val carol = Keys.parse("0000000000000000000000000000000000000000000000000000000000000003")

    @Test
    fun `npub and nprofile mentions become one p tag per person in text order`() {
        val text = "hi nostr:${carol.publicKey().toBech32()} and nostr:${Nip19Profile(alice.publicKey(), listOf()).toBech32()}, " +
            "again NOSTR:${carol.publicKey().toBech32().uppercase()} and nostr:npub1broken"
        assertEquals(listOf(carol.publicKey().toHex(), alice.publicKey().toHex()), MentionTags.pubkeys(text))
        assertEquals(listOf(listOf("p", carol.publicKey().toHex()), listOf("p", alice.publicKey().toHex())), MentionTags.from(text).map { it.asVec() })
        assertEquals(emptyList<String>(), MentionTags.pubkeys("no mentions"))
    }
}
