package com.monostr.app.ui

import com.monostr.app.ui.common.MentionNames
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys

class MentionNamesTest {
    private val carolKeys = Keys.parse("0000000000000000000000000000000000000000000000000000000000000003")
    private val daveKeys = Keys.parse("0000000000000000000000000000000000000000000000000000000000000004")
    private val carol = carolKeys.publicKey().toHex()
    private val dave = daveKeys.publicKey().toHex()
    private val bob = "b".repeat(64)

    @Test
    fun `collect joins p tags and text mentions, reposts included`() {
        val n = note('1', content = "hi nostr:${carolKeys.publicKey().toBech32()}").copy(mentionedPubkeys = listOf(bob))
        val r = repostOf(note('2', content = "yo nostr:${daveKeys.publicKey().toBech32()}"), by = bob, createdAt = 5)
        assertEquals(setOf(carol, bob, dave), MentionNames.collect(listOf(n, r)))
        assertEquals(setOf(carol), MentionNames.collectTexts(listOf("x nostr:${carolKeys.publicKey().toBech32()}", "none")))
    }

    @Test
    fun `resolve prefetches once and keeps only real names`() = runTest {
        val profiles = FakeProfiles(
            mapOf(carol to Profile(carol, "carol", "Carol", null, null, null), dave to Profile(dave, " ", null, null, null, null)),
        )
        val names = MentionNames.resolve(profiles, listOf(carol, dave, bob))
        assertEquals(mapOf(carol to "Carol"), names)
        assertEquals(listOf(listOf(carol, dave, bob)), profiles.prefetched.map { it.toList() })
        assertEquals(emptyMap<String, String>(), MentionNames.resolve(profiles, emptyList()))
        assertEquals(1, profiles.prefetched.size) // nothing to resolve, no prefetch
    }

    @Test
    fun `labels fall back to the short pubkey and plain text replaces mentions`() {
        assertEquals("@Carol", MentionNames.label(carol, mapOf(carol to "Carol")))
        assertEquals("@" + Profile.shortPubkey(dave), MentionNames.label(dave, emptyMap()))
        assertEquals("hey @Carol!", MentionNames.plain("hey nostr:${carolKeys.publicKey().toBech32()}!", mapOf(carol to "Carol")))
    }
}
