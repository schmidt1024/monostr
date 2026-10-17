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
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Timestamp
import java.nio.file.Path

/** Spec 2026-10-16 §2.1: the home feed is one list — notes, reposts and replies of the followed accounts. */
class FeedWithRepliesTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val alice = Keys.generate()
    private val stranger = Keys.generate()

    private fun note(keys: Keys, text: String, at: Long) =
        EventBuilder.textNote(text).customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(keys)

    private fun reply(keys: Keys, text: String, to: Event, at: Long) =
        EventBuilder.textNoteReply(text, to, null, null).customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(keys)

    private suspend fun engine(relay: SilentWsRelay): NostrEngine {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(relay.url))
        e.connect()
        assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
        e.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
        return e
    }

    @Test
    fun `replies of followed accounts are in the feed, also to accounts nobody follows`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val foreign = note(stranger, "stranger root", 1000).also { e.save(it) }
            e.save(note(alice, "alice root", 1001))
            e.save(reply(alice, "alice replies to stranger", foreign, 1002))
            e.save(reply(stranger, "stranger replies", foreign, 1003)) // not followed: not in the feed
            val repo = NostrFeedRepository(e)
            val expected = listOf("alice replies to stranger", "alice root")
            assertEquals(expected, repo.notes().map { it.content })
            assertEquals(expected, repo.live().first().map { it.content })
            assertEquals(listOf("alice root"), repo.loadMore(before = 1001).map { it.content })
            e.close()
        }
    }
}
