package com.monostr.nostr

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Backlog 118: an engine closed while rust-nostr was dialling a relay left that relay «connecting» for good, and it went on
 * dialling our SOCKS port every 10 to 30 s after the close (a squatter on the port heard relay.monostr.com and nos.lol).
 */
class EngineCloseStragglerTest {
    @TempDir lateinit var dir: Path

    private val relays = listOf("ws://one.example", "ws://two.example", "ws://three.example", "ws://four.example")

    @Test
    fun `an engine closed in the middle of its dials dials nothing afterwards`() = runBlocking<Unit> {
        FakeSocks5().use { socks ->
            repeat(40) { i ->
                val e = NostrEngine.create(dir.resolve("lmdb$i").toString(), null, relays, socksPort = socks.port)
                e.connect()
                delay(i % 8L) // the close lands at another point of the dial each time
                e.close()
            }
            delay(3_000)
            val after = socks.asks.size
            delay(25_000) // rust-nostr retries a relay it thinks lost after 10 s, later more slowly
            assertEquals(emptyList<FakeSocks5.Ask>(), socks.asks.drop(after), "dialled after every engine was closed")
        }
    }

    /** The same loss with a hold instead of a close: the engine lives on (Tor left Connected) and must stay silent. */
    @Test
    fun `an engine held in the middle of its dials dials nothing while held`() = runBlocking<Unit> {
        FakeSocks5().use { socks ->
            val engines = (0 until 40).map { i ->
                NostrEngine.create(dir.resolve("held$i").toString(), null, relays, socksPort = socks.port).also { e ->
                    e.connect()
                    delay(i % 8L)
                    e.holdConnections()
                }
            }
            try {
                delay(3_000)
                val after = socks.asks.size
                delay(25_000)
                assertEquals(emptyList<FakeSocks5.Ask>(), socks.asks.drop(after), "dialled while held")
            } finally {
                engines.forEach { it.close() }
            }
        }
    }

    /** Pixel 2026-11-27: relays whose dial failed sleep until their retry; closed meanwhile, each dialled once more when it was due. */
    @Test
    fun `a relay waiting for its retry at the close does not dial when the retry is due`() = runBlocking<Unit> {
        FakeSocks5().use { socks ->
            val e = NostrEngine.create(dir.resolve("retry").toString(), null, relays, socksPort = socks.port)
            e.connect()
            delay(2_000) // every dial was refused by now
            e.close()
            val after = socks.asks.size
            delay(20_000)
            assertEquals(emptyList<FakeSocks5.Ask>(), socks.asks.drop(after), "dialled after the close ($after dials before it)")
        }
    }

    /** Pixel 2026-11-27: purplepag.es, attached for one lookup and removed again, went on dialling for good. */
    @Test
    fun `a temporary relay removed while it waits for its retry does not dial again`() = runBlocking<Unit> {
        FakeSocks5().use { socks ->
            val e = NostrEngine.create(dir.resolve("temp").toString(), null, emptyList(), socksPort = socks.port)
            try {
                e.connect()
                val held = e.attachTemporary(listOf("ws://indexer.example"))
                delay(2_000)
                e.detachTemporary(held)
                val after = socks.asks.size
                delay(20_000)
                assertEquals(emptyList<FakeSocks5.Ask>(), socks.asks.drop(after), "dialled after the detach ($after dials before it)")
            } finally {
                e.close()
            }
        }
    }

    /** As above, but nothing listens on the port before the close (tor not started yet), and a squatter does afterwards. */
    @Test
    fun `closed with no proxy listening, nothing reaches a later listener on the port`() = runBlocking<Unit> {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val e = NostrEngine.create(dir.resolve("nolistener").toString(), null, relays, socksPort = port)
        e.connect()
        e.attachTemporary(listOf("ws://indexer.example"))
        delay(2_000)
        e.close()
        val heard = java.util.concurrent.atomic.AtomicInteger()
        java.net.ServerSocket(port, 50, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { listener ->
            kotlin.concurrent.thread(isDaemon = true) {
                while (!listener.isClosed) { val s = runCatching { listener.accept() }.getOrNull() ?: break; heard.incrementAndGet(); runCatching { s.close() } }
            }
            delay(25_000)
            assertEquals(0, heard.get(), "dials after the close")
        }
    }
}
