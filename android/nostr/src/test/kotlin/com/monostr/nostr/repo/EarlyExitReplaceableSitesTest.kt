package com.monostr.nostr.repo

import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.connectedEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

/** Spec wave 2 B4, call sites 7, 8 and 10 (9 is in ListsFetchTest): newest replaceable + grace, and existence at the first event. */
class EarlyExitReplaceableSitesTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.generate()
    private val aliceHex = alice.publicKey().toHex()
    private fun profile() = EventBuilder(Kind(0u), """{"name":"alice"}""").signWithKeys(alice)

    private suspend fun <T> within3s(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(3_000) { block() } }

    @Test
    fun `7a raw metadata of an unknown author comes from the fastest relay`() = runTest {
        SilentWsRelay(serve = listOf(profile().asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent), me.secretKey().toHex())
                try {
                    val raw = within3s { NostrProfileRepository(e).rawMetadata(aliceHex, Duration.ofSeconds(8)) }
                    assertEquals("""{"name":"alice"}""", raw)
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `7a the own raw metadata waits for every relay, a deleted picture depends on it`() = runTest {
        val own = EventBuilder(Kind(0u), """{"name":"me"}""").signWithKeys(me)
        SilentWsRelay(serve = listOf(own.asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent), me.secretKey().toHex())
                try {
                    val started = System.nanoTime()
                    val raw = withContext(Dispatchers.Default) { NostrProfileRepository(e).rawMetadata(me.publicKey().toHex(), Duration.ofSeconds(2)) }
                    assertEquals("""{"name":"me"}""", raw)
                    assertTrue((System.nanoTime() - started) / 1_000_000 >= 1_500, "the own profile was read with an early exit")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `7b a queued profile refresh lands from the fastest relay`() = runTest {
        SilentWsRelay(serve = listOf(profile().asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent), me.secretKey().toHex())
                try {
                    val repo = NostrProfileRepository(e)
                    repo.prefetch(listOf(aliceHex))
                    assertTrue(awaitTrue(3_000) { repo.observe(aliceHex).first().name == "alice" }, "the profile waited for the silent relay")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `7c a miss on the session relays asks the index right after their EOSE (regression guard)`() = runTest {
        SilentWsRelay(answerEose = true).use { session ->
            SilentWsRelay(serve = listOf(profile().asJson())).use { index ->
                val e = connectedEngine(dir, listOf(session), me.secretKey().toHex())
                try {
                    val repo = NostrProfileRepository(e, indexRelays = listOf(index.url))
                    repo.prefetch(listOf(aliceHex))
                    assertTrue(awaitTrue(3_000) { repo.observe(aliceHex).first().name == "alice" }, "the index was asked late")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `8 an author's write relays come from the fastest relay`() = runTest {
        val list = EventBuilder(Kind(10002u), "").tags(listOf(Tag.parse(listOf("r", "wss://nos.lol")))).signWithKeys(alice)
        SilentWsRelay(serve = listOf(list.asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent), me.secretKey().toHex())
                try {
                    val repo = NostrAuthorRelaysRepository(e, indexers = emptyList(), fetchTimeout = Duration.ofSeconds(8))
                    assertEquals(listOf("wss://nos.lol"), within3s { repo.writeRelays(aliceHex) })
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `8 the exact write relays wait for every relay`() = runTest {
        val list = EventBuilder(Kind(10002u), "").tags(listOf(Tag.parse(listOf("r", "wss://nos.lol")))).signWithKeys(alice)
        SilentWsRelay(serve = listOf(list.asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent), me.secretKey().toHex())
                try {
                    val repo = NostrAuthorRelaysRepository(e, indexers = emptyList(), fetchTimeout = Duration.ofSeconds(2))
                    val started = System.nanoTime()
                    assertEquals(listOf("wss://nos.lol"), withContext(Dispatchers.Default) { repo.writeRelaysExact(aliceHex) })
                    assertTrue((System.nanoTime() - started) / 1_000_000 >= 1_500, "the exact lookup stopped early")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `10 existence is decided at the first event, locally without a request, and false when nobody has one`() = runTest {
        val note = EventBuilder.textNote("exists").signWithKeys(me)
        SilentWsRelay(serve = listOf(note.asJson()), recordRequests = true).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent), me.secretKey().toHex())
                try {
                    val filter = Filter().kind(Kind(1u)).author(me.publicKey()).limit(1u)
                    assertTrue(within3s { storedOrFetched(e, filter, Duration.ofSeconds(5)) })
                    val before = fast.requests.size
                    assertTrue(storedOrFetched(e, filter, Duration.ofSeconds(5)), "now stored")
                    assertEquals(before, fast.requests.size, "a stored event needs no request")
                } finally {
                    e.close()
                }
            }
        }
        SilentWsRelay(answerEose = true).use { empty ->
            val e = connectedEngine(dir.resolve("b"), listOf(empty), me.secretKey().toHex())
            try {
                assertFalse(storedOrFetched(e, Filter().kind(Kind(10050u)).author(me.publicKey()).limit(1u), Duration.ofSeconds(5)))
            } finally {
                e.close()
            }
        }
    }
}
