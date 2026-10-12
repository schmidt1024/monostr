package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Timestamp
import java.nio.file.Path

/** Profile tabs like X (2026-10-12): "Posts" the author's own root notes, "Replies" their replies, "Reposts" what they reposted. */
class ProfileSectionsTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val alice = Keys.generate()
    private val bob = Keys.generate()

    private fun note(keys: Keys, text: String, at: Long) =
        EventBuilder.textNote(text).customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(keys)

    private fun reply(keys: Keys, text: String, root: Event, at: Long) =
        EventBuilder.textNoteReply(text, root, null, null).customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(keys)

    private fun repost(keys: Keys, of: Event, at: Long) =
        EventBuilder.repost(of, null).customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(keys)

    private suspend fun engine(relay: SilentWsRelay): NostrEngine {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(relay.url))
        e.connect()
        assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
        return e
    }

    @Test
    fun `posts hold only own root notes, replies only replies, reposts every repost`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val root = note(alice, "root", 1000).also { e.save(it) }
            val bobs = note(bob, "bob root", 1001).also { e.save(it) }
            val answer = reply(alice, "answer", bobs, 1002).also { e.save(it) }
            val bobsAnswer = reply(bob, "bob answer", root, 1003).also { e.save(it) }
            e.save(repost(alice, bobsAnswer, 1004))
            e.save(repost(alice, bobs, 1005))
            val repo = NostrFeedRepository(e)
            val hex = alice.publicKey().toHex()
            assertEquals(listOf("root"), repo.profileNotes(hex, ProfileSection.POSTS, fetch = false).map { it.content })
            assertEquals(listOf(answer.id().toHex()), repo.profileNotes(hex, ProfileSection.REPLIES, fetch = false).map { it.id })
            val reposts = repo.profileNotes(hex, ProfileSection.REPOSTS, fetch = false)
            assertEquals(listOf("bob root", "bob answer"), reposts.map { it.repostOf?.content })
            assertTrue(reposts.all { it.isRepost })
            e.close()
        }
    }

    @Test
    fun `a page of replies is skipped for posts, a page of posts for replies`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val roots = (0 until 3).map { note(alice, "root $it", 1000L + it).also { e.save(it) } }
            (0 until 60).forEach { e.save(reply(alice, "reply $it", roots[0], 2000L + it)) }
            (0 until 60).forEach { e.save(note(alice, "late $it", 3000L + it)) }
            val repo = NostrFeedRepository(e)
            val hex = alice.publicKey().toHex()
            val replies = repo.profileNotes(hex, ProfileSection.REPLIES, fetch = false)
            assertEquals(50, replies.size)
            assertEquals("reply 59", replies.first().content)
            assertEquals(listOf("reply 9", "reply 8"), repo.moreProfileNotes(hex, ProfileSection.REPLIES, before = 2009, limit = 2).map { it.content })
            assertEquals(listOf("root 2", "root 1", "root 0"), repo.moreProfileNotes(hex, ProfileSection.POSTS, before = 2999).map { it.content })
            e.close()
        }
    }

    @Test
    fun `more asks the relays for the author's page below the given time`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val hex = alice.publicKey().toHex()
            NostrFeedRepository(e).moreProfileNotes(hex, ProfileSection.POSTS, before = 5000)
            val pages = relay.requests.filter { "\"kinds\":[1]" in it }
            assertTrue(pages.any { "\"until\":5000" in it && hex in it }, "no author page below 5000: $pages")
            e.close()
        }
    }

    @Test
    fun `the reposts tab asks the relays for kind 6 alone, so many notes cannot crowd it out`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val bobs = note(bob, "bob root", 1000).also { e.save(it) }
            e.save(repost(alice, bobs, 1001))
            (0 until 60).forEach { e.save(note(alice, "late $it", 2000L + it)) }
            val repo = NostrFeedRepository(e)
            val hex = alice.publicKey().toHex()
            assertEquals(listOf("bob root"), repo.profileNotes(hex, ProfileSection.REPOSTS).map { it.repostOf?.content })
            assertTrue(relay.requests.any { "\"kinds\":[6]" in it && hex in it }, "no kind 6 page: ${relay.requests}")
            e.close()
        }
    }
}
