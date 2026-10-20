package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.connectedEngine
import com.monostr.nostr.model.ListAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import java.nio.file.Path
import java.time.Duration

/** Spec perf 3.7: list lookups ask all their relays side by side, read the database first and do not ask again within ten minutes. */
class ListsFetchTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val signer = LocalSigner(me.secretKey().toHex())
    private val bob = Keys.generate()
    private val c = Keys.generate().publicKey().toHex()
    private var clock = 2_000L

    /** Every author's write relays: [relays], after [delayMs] of real time (an indexer lookup). */
    private class SlowAuthorRelays(private val relays: List<String>, private val delayMs: Long = 0) : AuthorRelaysRepository {
        override suspend fun writeRelays(pubkey: String): List<String> {
            if (delayMs > 0) withContext(Dispatchers.IO) { Thread.sleep(delayMs) }
            return relays
        }
        override suspend fun attach(relays: List<String>) {}
        override suspend fun detach(relays: List<String>) {}
        override suspend fun fetchNotes(relays: List<String>, pubkey: String, limit: Int): Set<String> = emptySet()
    }

    private fun picks() = EventBuilder(Kind(30000u), "")
        .tags(listOf(Tag.parse(listOf("d", "picks")), Tag.parse(listOf("title", "Bob picks")), Tag.parse(listOf("p", c))))
        .signWithKeys(bob)

    private fun listRequests(relay: SilentWsRelay) = relay.requests.filter { "30000" in it }

    @Test
    fun `the session relays and the extra relays are asked side by side`() = runTest {
        SilentWsRelay(recordRequests = true).use { own ->
            SilentWsRelay(recordRequests = true).use { extra ->
                val e = connectedEngine(dir, own, me.secretKey().toHex())
                try {
                    val filter = Filter().kind(Kind(30000u)).author(bob.publicKey())
                    val started = System.currentTimeMillis()
                    val done = async(Dispatchers.Default) { fetchWide(e, filter, Duration.ofSeconds(3)) { listOf(extra.url) } }
                    // before: the extra relays were asked only after the session relays' 3 s had run out
                    assertTrue(awaitTrue(2_000) { listRequests(extra).isNotEmpty() }, "the extra relay waited for the session relay")
                    done.await()
                    val took = System.currentTimeMillis() - started
                    assertTrue(took < 5_000, "both legs one after another: $took ms")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a slow write relay lookup does not hold back the session relays`() = runTest {
        SilentWsRelay(recordRequests = true).use { own ->
            val e = connectedEngine(dir, own, me.secretKey().toHex())
            try {
                val repo = NostrListsRepository(e, signer, SlowAuthorRelays(emptyList(), delayMs = 2_000), now = { clock }, fetchTimeout = Duration.ofSeconds(1))
                val lookup = async(Dispatchers.Default) { repo.of(bob.publicKey().toHex()) }
                assertTrue(awaitTrue(1_500) { listRequests(own).isNotEmpty() }, "the session relay waited for the write relay lookup")
                lookup.await()
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a foreign account's stored lists are read without a relay`() = runTest {
        SilentWsRelay(recordRequests = true).use { own ->
            val e = connectedEngine(dir, own, me.secretKey().toHex())
            try {
                e.save(picks())
                val repo = NostrListsRepository(e, signer, SlowAuthorRelays(emptyList()), now = { clock })
                val started = System.currentTimeMillis()
                assertEquals(listOf("Bob picks"), repo.localOf(bob.publicKey().toHex()).map { it.title })
                assertTrue(System.currentTimeMillis() - started < 1_000)
                withContext(Dispatchers.IO) { Thread.sleep(300) }
                assertTrue(listRequests(own).isEmpty(), "asked: ${own.requests}")
                // `of` does ask: the recording works, so the empty list above is not the silent relay's doing
                repo.of(bob.publicKey().toHex())
                assertTrue(listRequests(own).isNotEmpty(), "of() never asked the relay")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a foreign list fetched once is not fetched again for ten minutes`() = runTest {
        SilentWsRelay(answerEose = true).use { own ->
            val e = connectedEngine(dir, own, me.secretKey().toHex())
            try {
                e.save(picks())
                val repo = NostrListsRepository(e, signer, SlowAuthorRelays(emptyList()), now = { clock })
                val address = ListAddress(30000, bob.publicKey().toHex(), "picks")
                assertEquals("Bob picks", repo.byAddress(address)?.title)
                assertEquals("Bob picks", repo.byAddress(address)?.title)
                assertEquals(1, listRequests(own).size, "requests: ${own.requests}")
                clock += 601
                repo.byAddress(address)
                assertTrue(awaitTrue { listRequests(own).size == 2 })
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a foreign fetch no relay replied to is asked again on the next call`() = runTest {
        // a silent relay: the first lookup runs into its timeout without any reply
        SilentWsRelay(recordRequests = true).use { own ->
            val e = connectedEngine(dir, own, me.secretKey().toHex())
            try {
                val repo = NostrListsRepository(e, signer, SlowAuthorRelays(emptyList()), now = { clock }, fetchTimeout = Duration.ofSeconds(1))
                val address = ListAddress(30000, bob.publicKey().toHex(), "picks")
                repo.byAddress(address)
                assertEquals(1, listRequests(own).size, "requests: ${own.requests}")
                repo.byAddress(address)
                assertTrue(awaitTrue { listRequests(own).size == 2 }, "not asked again: ${own.requests}")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `an extra relay written with a trailing slash is not asked twice`() = runTest {
        SilentWsRelay(answerEose = true).use { own ->
            val e = connectedEngine(dir, own, me.secretKey().toHex())
            try {
                fetchWide(e, Filter().kind(Kind(30000u)).author(bob.publicKey()), Duration.ofSeconds(2)) { listOf(own.url + "/") }
                assertEquals(1, listRequests(own).size, "requests: ${own.requests}")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a lookup while the relay is unreachable is asked again once a relay answers`() = runTest {
        val revived = SilentWsRelay(answerEose = true)
        revived.use {
            val own = SilentWsRelay(answerEose = true)
            val e = connectedEngine(dir, own, me.secretKey().toHex())
            try {
                // the second call finds a working write relay for the author; the session relay stays dead
                // the first call also names a write relay on a closed port: refused at once, not silent
                var writeRelays = listOf("ws://127.0.0.1:1")
                val authorRelays = object : AuthorRelaysRepository by SlowAuthorRelays(emptyList()) {
                    override suspend fun writeRelays(pubkey: String) = writeRelays
                }
                val repo = NostrListsRepository(e, signer, authorRelays, now = { clock }, fetchTimeout = Duration.ofSeconds(3))
                val address = ListAddress(30000, bob.publicKey().toHex(), "picks")
                own.shutdown() // connection refused from here on, not a silent relay
                assertTrue(awaitTrue { e.connectedNormalRelayUrls().isEmpty() }, "the closed relay still counts as connected")
                repo.byAddress(address)
                writeRelays = listOf(revived.url)
                repo.byAddress(address)
                assertTrue(awaitTrue { listRequests(revived).isNotEmpty() }, "an unreachable relay counted as a reply: ${revived.requests}")
            } finally {
                e.close()
            }
        }
    }
}
