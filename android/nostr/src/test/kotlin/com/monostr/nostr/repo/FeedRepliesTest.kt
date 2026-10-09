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

/** Spec "feed without replies" (2026-10-08): the home feed shows root notes only; replies live in the thread, the profile keeps them. */
class FeedRepliesTest {
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
        e.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null), Contact(bob.publicKey(), null, null))).signWithKeys(me))
        return e
    }

    @Test
    fun `a page of replies is skipped, the root notes behind it fill the feed`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val roots = (0 until 3).map { note(alice, "root $it", 1000L + it).also { e.save(it) } }
            // 60 replies newer than every root: more than one page of 50
            (0 until 60).forEach { e.save(reply(alice, "reply $it", roots[0], 2000L + it)) }
            val repo = NostrFeedRepository(e)
            val expected = listOf("root 2", "root 1", "root 0")
            assertEquals(expected, repo.notes().map { it.content })
            assertEquals(expected, repo.live().first().map { it.content })
            assertEquals(expected, repo.loadMore(before = 2061).map { it.content })
            // the profile keeps the replies
            assertTrue(repo.notesBy(alice.publicKey().toHex(), fetch = false).all { it.isReply })
            e.close()
        }
    }

    @Test
    fun `paging goes on below a stretch of replies longer than the local rounds cover`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val roots = (0 until 3).map { note(alice, "root $it", 1000L + it).also { e.save(it) } }
            // 520 replies: more than the 10 local pages of 50 one read covers, so the first relay round finds nothing
            (0 until 520).forEach { e.save(reply(alice, "reply $it", roots[0], 2000L + it)) }
            val repo = NostrFeedRepository(e)
            assertEquals(emptyList<String>(), repo.notes().map { it.content }, "the local rounds are bounded")
            assertEquals(listOf("root 2", "root 1", "root 0"), repo.loadMore(before = 2521).map { it.content })
            val pages = relay.requests.filter { "\"kinds\":[1,6]" in it }
            assertTrue(pages.size > 1, "one relay page only: $pages")
            // the first page's oldest event is reply 470 (2470): the next page starts one second below it
            assertTrue(pages.last().contains("\"until\":2469"), "the second page starts below the first: ${pages.last()}")
            e.close()
        }
    }

    @Test
    fun `a repost of a reply stays out, a repost of a root note stays in`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = engine(relay)
            val root = note(alice, "root", 1000).also { e.save(it) }
            val answer = reply(alice, "answer", root, 1001).also { e.save(it) }
            e.save(repost(bob, answer, 1002))
            e.save(repost(bob, root, 1003))
            val repo = NostrFeedRepository(e)
            val feed = repo.notes()
            assertEquals(listOf("root", "root"), feed.map { it.repostOf?.content ?: it.content })
            assertTrue(feed.first().isRepost && feed.last().id == root.id().toHex())
            e.close()
        }
    }
}
