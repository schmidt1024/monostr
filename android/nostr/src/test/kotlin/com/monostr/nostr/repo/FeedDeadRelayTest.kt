package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Timestamp
import java.net.ServerSocket
import java.nio.file.Path

/**
 * The first fresh page on a cold start (Pixel 2026-10-21): a relay in the list that refuses every connection held the
 * page to the fetch timeout (8 s) in half the starts, because rust-nostr 0.44 keeps such a relay in a fetch for the
 * first 60 s. The page asks the relays connected once the start settled; one that connects later is asked afterwards.
 */
class FeedDeadRelayTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val alice = Keys.generate()

    private fun note(text: String, at: Long) =
        EventBuilder.textNote(text).customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(alice)

    /** A loopback address nobody listens on: every connection is refused at once, as with a relay that went away. */
    private fun refusingUrl(): String = ServerSocket(0).use { "ws://127.0.0.1:${it.localPort}" }

    private suspend fun engine(relays: List<String>): NostrEngine =
        NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), relays)

    @Test
    fun `a relay that refuses every connection does not hold the first page to the timeout`() = runBlocking {
        val fresh = note("fresh", 2000)
        SilentWsRelay(serve = listOf(fresh.asJson())).use { good ->
            val e = engine(listOf(good.url, refusingUrl()))
            try {
                e.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
                e.connect()
                val started = System.nanoTime()
                NostrFeedRepository(e).refresh(FeedSource.Following)
                val tookMs = (System.nanoTime() - started) / 1_000_000
                assertEquals(listOf("fresh"), NostrFeedRepository(e).notes().map { it.content })
                assertTrue(tookMs < 4_000, "the first page took $tookMs ms")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a relay that connects after the start settled is asked for the page afterwards`() = runBlocking {
        val early = note("early", 2000)
        val late = note("late", 2001)
        SilentWsRelay(serve = listOf(early.asJson())).use { fast ->
            SilentWsRelay(serve = listOf(late.asJson()), handshakeDelayMs = 3_500).use { slow ->
                val e = engine(listOf(fast.url, slow.url))
                try {
                    e.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
                    e.connect()
                    val started = System.nanoTime()
                    NostrFeedRepository(e).refresh(FeedSource.Following)
                    val tookMs = (System.nanoTime() - started) / 1_000_000
                    assertTrue(tookMs < 3_400, "the first page waited for the slow relay: $tookMs ms")
                    assertTrue(
                        awaitTrue(timeoutMs = 15_000) { NostrFeedRepository(e).notes().map { it.content } == listOf("late", "early") },
                        "the slow relay's note never arrived: ${NostrFeedRepository(e).notes().map { it.content }}",
                    )
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `with no relay connected yet the page asks the ones still connecting, never waits for a refusing one`() = runBlocking {
        val late = note("late", 2001)
        SilentWsRelay(serve = listOf(late.asJson()), handshakeDelayMs = 3_000).use { slow ->
            val e = engine(listOf(slow.url, refusingUrl()))
            try {
                e.save(EventBuilder.contactList(listOf(Contact(alice.publicKey(), null, null))).signWithKeys(me))
                e.connect()
                val started = System.nanoTime()
                NostrFeedRepository(e).refresh(FeedSource.Following)
                val tookMs = (System.nanoTime() - started) / 1_000_000
                assertEquals(listOf("late"), NostrFeedRepository(e).notes().map { it.content })
                assertTrue(tookMs < 6_000, "the page waited for the refusing relay: $tookMs ms")
            } finally {
                e.close()
            }
        }
    }
}
