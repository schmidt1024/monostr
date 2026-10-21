package com.monostr.nostr

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import java.net.ServerSocket
import java.nio.file.Path
import java.time.Duration

/**
 * A relay whose connection failed holds no fetch to its timeout (Pixel 2026-10-21, after 0.24.1 fixed the feed page):
 * rust-nostr 0.44 keeps such a relay in a fetch for the first 60 s after the start, and later too when it was a good
 * relay before it dropped. Every fetch leaves the relays out whose connection failed.
 */
class EngineSkipFailedTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val alice = Keys.generate()
    private val note = EventBuilder.textNote("hello").signWithKeys(alice)
    private val filter get() = Filter().kind(Kind(1u)).author(alice.publicKey())

    private fun refusingUrl(): String = ServerSocket(0).use { "ws://127.0.0.1:${it.localPort}" }

    private suspend fun engine(relays: List<String>): NostrEngine =
        NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), relays)

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val started = System.nanoTime()
        val out = block()
        return out to (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun `a fetch right after the start does not wait for a relay that refuses every connection`() = runBlocking {
        SilentWsRelay(serve = listOf(note.asJson())).use { good ->
            val e = engine(listOf(good.url, refusingUrl()))
            try {
                e.connect()
                val (events, ms) = timed { e.fetch(filter) }
                assertEquals(1, events.size)
                assertTrue(ms < 4_000, "the fetch took $ms ms")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `an early-exit fetch whose condition never holds does not wait for a refusing relay`() = runBlocking {
        SilentWsRelay(answerEose = true).use { empty ->
            val e = engine(listOf(empty.url, refusingUrl()))
            try {
                e.connect()
                val (_, ms) = timed { e.fetch(filter, exit = Exit.AnyEvent) }
                assertTrue(ms < 3_500, "the fetch took $ms ms")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a fetch from named relays does not wait for one of them that refuses`() = runBlocking {
        SilentWsRelay(serve = listOf(note.asJson())).use { good ->
            SilentWsRelay(answerEose = true).use { home ->
                val e = connectedEngine(dir, home, me.secretKey().toHex())
                try {
                    val (events, ms) = timed { e.fetchFrom(listOf(good.url, refusingUrl()), filter) }
                    assertEquals(1, events.size)
                    assertTrue(ms < 4_000, "the fetch took $ms ms")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a good relay that dropped later in the session holds no fetch`() = runBlocking {
        SilentWsRelay(serve = listOf(note.asJson())).use { good ->
            val gone = SilentWsRelay(answerEose = true)
            val e = connectedEngine(dir, listOf(good, gone), me.secretKey().toHex())
            try {
                e.startWindow = Duration.ZERO // later in the session: no grace wait
                gone.shutdown()
                assertTrue(awaitTrue(10_000) { e.connectedNormalRelayUrls().size == 1 }, "the shut relay still counts as connected")
                val (events, ms) = timed { e.fetch(filter) }
                assertEquals(1, events.size)
                assertTrue(ms < 3_000, "the fetch took $ms ms")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `an early exit right after the start is not held back for a relay that is slow to connect`() = runBlocking {
        SilentWsRelay(serve = listOf(note.asJson())).use { good ->
            SilentWsRelay(answerEose = true, handshakeDelayMs = 3_000).use { slow ->
                val e = engine(listOf(good.url, slow.url))
                try {
                    e.connect()
                    val (events, ms) = timed { e.fetch(filter, exit = Exit.AnyEvent) }
                    assertEquals(1, events.size)
                    assertTrue(ms < 1_500, "the early exit took $ms ms")
                } finally {
                    e.close()
                }
            }
        }
    }
}
