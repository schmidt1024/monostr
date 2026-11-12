package com.monostr.app.ui

import com.monostr.app.ui.common.QuoteLoader
import com.monostr.app.ui.common.QuoteTap
import com.monostr.app.ui.common.QuoteUi
import com.monostr.nostr.model.Profile
import com.monostr.nostr.model.ZapQuote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys

class QuoteLoaderTest {
    private val alice = "a".repeat(64)
    private val carolKeys = Keys.parse("0000000000000000000000000000000000000000000000000000000000000003")
    private val carol = carolKeys.publicKey().toHex()

    @Test
    fun `found quotes carry the author and the names of their mentions, missing ones stay missing`() = runTest {
        val quoted = note('1', alice, 100, content = "hey nostr:${carolKeys.publicKey().toBech32()}")
        val profiles = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", null, null, null), carol to Profile(carol, "carol", null, null, null, null)))
        val quotes = FakeQuotes(mapOf(quoted.id to quoted))
        val loader = QuoteLoader(quotes, profiles)
        assertNull(loader.cached(quoted.id))
        val found = loader.load(quoted.id, listOf("wss://hint.example")) as QuoteUi.Found
        assertEquals("Alice", found.author.shownName)
        assertEquals(mapOf(carol to "carol"), found.names)
        assertEquals(listOf(quoted.id to listOf("wss://hint.example")), quotes.calls)
        assertEquals(found, loader.cached(quoted.id))
        loader.load(quoted.id, emptyList())
        assertEquals(1, quotes.calls.size) // served from the loader's memory
        assertEquals(QuoteUi.Missing, loader.load("9".repeat(64), emptyList()))
    }

    @Test
    fun `a quote by a muted account shows the placeholder, not the content`() = runTest {
        val quoted = note('1', alice, 100)
        val muted = MutableStateFlow(emptySet<String>())
        val loader = QuoteLoader(FakeQuotes(mapOf(quoted.id to quoted)), FakeProfiles(), muted)
        assertTrue(loader.load(quoted.id, emptyList()) is QuoteUi.Found)
        muted.value = setOf(alice)
        assertEquals(QuoteUi.Muted, loader.cached(quoted.id)) // a kept card is hidden too
        assertEquals(QuoteUi.Muted, loader.load(quoted.id, emptyList()))
        muted.value = emptySet()
        assertTrue(loader.load(quoted.id, emptyList()) is QuoteUi.Found)
        val fresh = QuoteLoader(FakeQuotes(mapOf(quoted.id to quoted)), FakeProfiles(), MutableStateFlow(setOf(alice)))
        assertEquals(QuoteUi.Muted, fresh.load(quoted.id, emptyList()))
    }

    @Test
    fun `a quoted zap carries sender and recipient profiles and hides when the sender is muted`() = runTest {
        val bob = "b".repeat(64)
        val zap = ZapQuote("9".repeat(64), 1_000, sender = alice, comment = "nice", recipient = bob, zappedNote = null, createdAt = 100)
        val muted = MutableStateFlow(emptySet<String>())
        val profiles = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", null, null, null)))
        val loader = QuoteLoader(FakeQuotes(zaps = mapOf(zap.id to zap)), profiles, muted)
        val ui = loader.load(zap.id, emptyList()) as QuoteUi.Zap
        assertEquals(zap, ui.zap)
        assertEquals("Alice", ui.sender!!.shownName)
        assertEquals(Profile.shortPubkey(bob), ui.recipient.shownName)
        assertEquals(ui, loader.cached(zap.id))
        muted.value = setOf(alice)
        assertEquals(QuoteUi.MutedZap(null), loader.cached(zap.id))
        val anonymous = zap.copy(id = "8".repeat(64), sender = null)
        val other = QuoteLoader(FakeQuotes(zaps = mapOf(anonymous.id to anonymous)), FakeProfiles(), muted).load(anonymous.id, emptyList()) as QuoteUi.Zap
        assertNull(other.sender)
    }

    @Test
    fun `a muted zap opens the zapped note or nothing, never the receipt`() = runTest {
        val bob = "b".repeat(64)
        val zapped = "7".repeat(64)
        val zap = ZapQuote("9".repeat(64), 1_000, sender = alice, comment = "", recipient = bob, zappedNote = zapped, createdAt = 100)
        val loader = QuoteLoader(FakeQuotes(zaps = mapOf(zap.id to zap)), FakeProfiles(), MutableStateFlow(setOf(alice)))
        val ui = loader.load(zap.id, emptyList())
        assertEquals(QuoteUi.MutedZap(zapped), ui)
        assertEquals(QuoteTap.Note(zapped), QuoteTap.of(ui, zap.id))
        assertEquals(QuoteTap.None, QuoteTap.of(QuoteUi.MutedZap(null), zap.id))
        // muted notes keep opening their thread; an unmuted zap without a note opens the sender
        assertEquals(QuoteTap.Note("1".repeat(64)), QuoteTap.of(QuoteUi.Muted, "1".repeat(64)))
        val open = QuoteUi.Zap(zap.copy(zappedNote = null), null, Profile.empty(bob))
        assertEquals(QuoteTap.Profile(alice), QuoteTap.of(open, zap.id))
    }

    @Test
    fun `short reference is a shortened note1`() {
        val r = QuoteLoader.shortRef("ab".repeat(32))
        assertTrue(r.startsWith("note1"))
        assertTrue(r.contains("…"))
    }

    @Test
    fun `an author whose profile lands after the card loaded shows on the card`() = runTest {
        val quoted = note('1', alice, 100)
        val profiles = FakeProfiles()
        val loader = QuoteLoader(FakeQuotes(mapOf(quoted.id to quoted)), profiles)
        val found = loader.load(quoted.id, emptyList()) as QuoteUi.Found
        assertEquals(Profile.shortPubkey(alice), found.author.shownName)
        assertEquals(Profile.shortPubkey(alice), loader.author(found).first().shownName)
        profiles.land(Profile(alice, "alice", "Alice", null, null, null))
        assertEquals("Alice", loader.author(found).first().shownName)
    }
}
