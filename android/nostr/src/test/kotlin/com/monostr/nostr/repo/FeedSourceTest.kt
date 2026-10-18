package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.model.Note
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Timestamp
import java.nio.file.Path

/** Spec 2026-10-18 §4.1: a list's feed reads its members only; author filters go out in chunks of 200. */
class FeedSourceTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val alice = Keys.generate()
    private val bob = Keys.generate()

    private fun note(keys: Keys, text: String, at: Long) =
        EventBuilder.textNote(text).customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(keys)

    private suspend fun connected(relay: SilentWsRelay): NostrEngine {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(relay.url))
        e.connect()
        assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
        return e
    }

    @Test
    fun `a list feed shows only its members, an empty list shows nothing and asks nothing`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connected(relay)
            e.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
            e.save(note(alice, "alice", 1000)); e.save(note(bob, "bob", 1001)); e.save(note(me, "me", 1002))
            val repo = NostrFeedRepository(e)
            val bobHex = bob.publicKey().toHex()
            val list = FeedSource.Authors(listOf(bobHex, "not-a-key"))
            assertEquals(listOf("bob"), repo.notes(list).map { it.content })
            assertEquals(listOf("bob"), repo.live(list).first().map { it.content })
            assertEquals(listOf("me", "alice"), repo.notes().map { it.content }) // Following is unchanged
            // a muted member stays out of the list feed like out of the home feed
            assertEquals(emptyList<String>(), NostrFeedRepository(e, excluded = { setOf(bobHex) }).notes(list).map { it.content })
            val before = relay.requests.size
            val empty = FeedSource.Authors(emptyList())
            assertEquals(emptyList<Note>(), repo.notes(empty))
            assertEquals(emptyList<Note>(), repo.live(empty).first())
            repo.refresh(empty)
            assertEquals(emptyList<Note>(), repo.loadMore(empty, before = 5000))
            assertEquals(before, relay.requests.size) // an empty author set never becomes a filter for everyone
            e.close()
        }
    }

    @Test
    fun `more than 200 authors go out as several filters of at most 200`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connected(relay)
            val members = (1..450).map { Keys.generate().publicKey().toHex() }
            NostrFeedRepository(e).refresh(FeedSource.Authors(members))
            val filters: List<JsonObject> = relay.requests.flatMap { req -> Json.parseToJsonElement(req).jsonArray.drop(2).map { it.jsonObject } }
                .filter { it["authors"] != null }
            fun kinds(f: JsonObject) = f["kinds"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet()
            val noteFilters = filters.filter { kinds(it) == setOf(1, 6) }
            val deletionFilters = filters.filter { kinds(it) == setOf(5) }
            assertEquals(3, noteFilters.size)
            assertEquals(3, deletionFilters.size)
            assertTrue(filters.all { it["authors"]!!.jsonArray.size <= 200 })
            // the user follows nobody: the request names the members and the user (the home feed's authors)
            assertEquals(members.toSet() + me.publicKey().toHex(), noteFilters.flatMap { f -> f["authors"]!!.jsonArray.map { it.jsonPrimitive.content } }.toSet())
            e.close()
        }
    }

    @Test
    fun `a list's relay requests name the home feed's authors, never the members alone`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connected(relay)
            val carol = Keys.generate()
            val followed = listOf(alice, bob) + (1..8).map { Keys.generate() }
            e.save(EventBuilder.contactList(followed.map { Contact(it.publicKey(), null, null) }).signWithKeys(me))
            val repo = NostrFeedRepository(e)
            val home = (followed.map { it.publicKey().toHex() } + me.publicKey().toHex()).toSet()
            fun authorSets(from: Int): List<Set<String>> = relay.requests.drop(from)
                .flatMap { req -> Json.parseToJsonElement(req).jsonArray.drop(2).map { it.jsonObject } }
                .mapNotNull { f -> f["authors"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() }

            // members all followed: exactly the home feed's author set, on refresh, paging and live
            val followedOnly = FeedSource.Authors(listOf(bob.publicKey().toHex()))
            var from = relay.requests.size
            repo.refresh(followedOnly)
            repo.loadMore(followedOnly, before = 5000)
            repo.live(followedOnly).first()
            assertTrue(awaitTrue { authorSets(from).size >= 4 }, "list requests never reached the relay")
            assertTrue(authorSets(from).all { it == home }, "a request named a set other than the home feed's: ${authorSets(from)}")

            // a member the user does not follow is added to the home feed's authors, not asked for alone
            val mixed = FeedSource.Authors(listOf(bob.publicKey().toHex(), carol.publicKey().toHex()))
            from = relay.requests.size
            repo.refresh(mixed)
            assertTrue(authorSets(from).isNotEmpty())
            assertTrue(authorSets(from).all { it == home + carol.publicKey().toHex() })

            // the list feed still shows only its members
            e.save(note(alice, "alice", 1000)); e.save(note(bob, "bob", 1001))
            assertEquals(listOf("bob"), repo.notes(followedOnly).map { it.content })
            e.close()
        }
    }
}
