package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path
import java.time.Duration

class SearchRepositoryTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.generate()
    private val bob = Keys.generate()

    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())

    private fun meta(keys: Keys, json: String) = EventBuilder.metadata(Metadata.fromJson(json)).signWithKeys(keys)
    private fun note(keys: Keys, text: String, at: Long, tags: List<String> = emptyList()) =
        EventBuilder.textNote(text).tags(tags.map { Tag.hashtag(it) }).customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(keys)

    @Test
    fun `local people match name, display name and nip05 case-insensitively`() = runTest {
        val e = engine()
        e.save(meta(alice, """{"name":"alice","display_name":"Alice Wonder","nip05":"alice@x.org"}"""))
        e.save(meta(bob, """{"name":"bobby","display_name":"Bob"}"""))
        val repo = NostrSearchRepository(e, Duration.ofMillis(200))
        assertEquals(listOf(alice.publicKey().toHex()), repo.profilesLocal("WONDER").map { it.pubkey })
        assertEquals(listOf(alice.publicKey().toHex()), repo.profilesLocal("x.org").map { it.pubkey })
        assertEquals(setOf(alice.publicKey().toHex(), bob.publicKey().toHex()), repo.profilesLocal("o").map { it.pubkey }.toSet())
        assertTrue(repo.profilesLocal("zzz").isEmpty())
    }

    @Test
    fun `profiles without search relays are the local ones and carry no error`() = runTest {
        val e = engine()
        e.save(meta(alice, """{"name":"alice"}"""))
        val repo = NostrSearchRepository(e, Duration.ofMillis(200))
        val out = repo.profiles("ali", emptyList())
        assertEquals(listOf(alice.publicKey().toHex()), out.items.map { it.pubkey })
        assertNull(out.error)
    }

    @Test
    fun `a dead search relay keeps the local people and yields no notes`() = runTest {
        val e = engine()
        e.save(meta(alice, """{"name":"alice"}"""))
        val repo = NostrSearchRepository(e, Duration.ofMillis(300))
        val people = repo.profiles("ali", listOf("ws://127.0.0.1:9"))
        assertEquals(listOf(alice.publicKey().toHex()), people.items.map { it.pubkey })
        assertTrue(people.error is SearchRelayUnreachableException)
        val notes = repo.notes("anything", listOf("ws://127.0.0.1:9"))
        assertTrue(notes.items.isEmpty())
        assertTrue(notes.error is SearchRelayUnreachableException)
        repo.detach(listOf("ws://127.0.0.1:9"))
        assertTrue(e.temporaryRelayUrls().isEmpty())
    }

    @Test
    fun `a search relay stays attached across two searches within one attach-detach session`() = runTest {
        val e = engine()
        SilentWsRelay().use { r ->
            val repo = NostrSearchRepository(e, Duration.ofMillis(300))
            repo.attach(listOf(r.url))
            repo.profiles("x", listOf(r.url))
            repo.notes("x", listOf(r.url))
            // profiles()/notes() each release their own fetchFrom-triggered attach again
            // (task-10 review round 1): the relay must still be held by the outer attach() alone.
            assertEquals(listOf(r.url), e.temporaryRelayUrls())
            repo.detach(listOf(r.url))
            assertTrue(e.temporaryRelayUrls().isEmpty())
        }
    }

    @Test
    fun `hashtag reads local notes newest first and pages with until`() = runTest {
        val e = engine()
        e.save(note(alice, "one #Monero", 1000, listOf("monero")))
        e.save(note(bob, "two", 2000, listOf("monero")))
        e.save(note(bob, "other", 3000, listOf("bitcoin")))
        val repo = NostrSearchRepository(e, Duration.ofMillis(200))
        assertEquals(listOf("two", "one #Monero"), repo.hashtag("monero").items.map { it.content })
        assertEquals(listOf("one #Monero"), repo.hashtag("monero", until = 1999).items.map { it.content })
        assertTrue(repo.hashtag("nothing").items.isEmpty())
    }

    @Test
    fun `hashtag matches mixed case tags`() = runTest {
        val e = engine()
        e.save(note(alice, "one #Monero", 1000, listOf("monero")))
        e.save(note(bob, "two #bitcoin", 2000, listOf("bitcoin")))
        val repo = NostrSearchRepository(e, Duration.ofMillis(200))
        assertEquals(listOf("one #Monero"), repo.hashtag("MONERO").items.map { it.content })
        assertEquals(listOf("two #bitcoin"), repo.hashtag("BITCOIN").items.map { it.content })
    }

    @Test
    fun `a note search drops a note the database knows as withdrawn`() = runTest {
        // a guard: rust-nostr's relay layer already skips an event the database marked deleted
        val withdrawn = note(alice, "monero withdrawn", 1000)
        val kept = note(alice, "monero kept", 2000)
        SilentWsRelay(serve = listOf(withdrawn.asJson(), kept.asJson())).use { relay ->
            val e = engine()
            e.save(withdrawn)
            e.save(EventBuilder(Kind(5u), "").tags(listOf(Tag.parse(listOf("e", withdrawn.id().toHex())), Tag.parse(listOf("k", "1")))).signWithKeys(alice))
            val repo = NostrSearchRepository(e, Duration.ofSeconds(3))
            assertEquals(listOf("monero kept"), repo.notes("monero", listOf(relay.url)).items.map { it.content })
            e.close()
        }
    }
}
