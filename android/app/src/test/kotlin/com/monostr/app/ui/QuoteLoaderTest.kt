package com.monostr.app.ui

import com.monostr.app.ui.common.QuoteLoader
import com.monostr.app.ui.common.QuoteUi
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun `short reference is a shortened note1`() {
        val r = QuoteLoader.shortRef("ab".repeat(32))
        assertTrue(r.startsWith("note1"))
        assertTrue(r.contains("…"))
    }
}
