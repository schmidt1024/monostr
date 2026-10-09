package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Timestamp
import java.nio.file.Path

/** Muted accounts are left out at the query, so they cannot fill a page of the home feed. */
class FeedExcludedTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val alice = Keys.generate()
    private val bob = Keys.generate()

    private fun note(keys: Keys, text: String, at: Long) =
        EventBuilder.textNote(text).customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(keys)

    @Test
    fun `an excluded author's notes do not take places in the page, nor in the relay requests`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(relay.url))
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
            e.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null), Contact(bob.publicKey(), null, null))).signWithKeys(me))
            // alice floods: her notes are the newest 50 of all follows
            (0 until 50).forEach { e.save(note(alice, "alice $it", 2000L + it)) }
            (0 until 3).forEach { e.save(note(bob, "bob $it", 1000L + it)) }
            val aliceHex = alice.publicKey().toHex()
            val repo = NostrFeedRepository(e, excluded = { setOf(aliceHex) })
            val bobs = listOf("bob 2", "bob 1", "bob 0")
            assertEquals(bobs, repo.notes().map { it.content })
            assertEquals(bobs, repo.loadMore(before = 3000).map { it.content })
            assertEquals(bobs, repo.live().first().map { it.content })
            repo.refresh()
            assertTrue(relay.requests.isNotEmpty())
            assertTrue(relay.requests.none { aliceHex in it }, "alice was asked for: ${relay.requests}")
            // the profile still shows her notes
            assertEquals(50, repo.notesBy(aliceHex, fetch = false).size)
            e.close()
        }
    }
}
