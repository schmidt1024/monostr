package com.monostr.nostr

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Keys
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A relay of the list that never connected in this session and failed its attempts is left alone until the next start
 * (0.24.3): rust-nostr would retry it every 10 to 60 s for as long as the app runs. The settings show it as resting.
 */
class EngineRelayRestTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()

    /** Accepts every TCP connection and closes it at once: a relay host that drops every attempt, which [connections] counts. */
    private class DroppingRelay(port: Int = 0) : AutoCloseable {
        private val server: ServerSocket = bindLoopback(port)
        val connections = AtomicInteger(0)
        val url = "ws://127.0.0.1:${server.localPort}"
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    runCatching { s.close() }
                }
            }
        }
        override fun close() = server.close()
    }

    @Test
    fun `a relay that never connected rests after its failed attempts, a good one stays`() = runBlocking {
        SilentWsRelay(answerEose = true).use { good ->
            DroppingRelay().use { bad ->
                val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(good.url, bad.url))
                try {
                    e.restAttempts = 1
                    e.restCheckMs = 200
                    e.connect()
                    assertTrue(
                        awaitTrue(15_000) { e.relayStates().first().any { it.url.trimEnd('/') == bad.url && it.resting } },
                        "the dropping relay never rested: ${e.relayStates().first()}",
                    )
                    val states = e.relayStates().first()
                    val goodInfo = states.single { it.url.trimEnd('/') == good.url }
                    assertTrue(goodInfo.everConnected)
                    assertFalse(goodInfo.resting)
                    assertFalse(states.single { it.url.trimEnd('/') == bad.url }.everConnected)
                    val attempts = bad.connections.get()
                    delay(12_000) // longer than rust-nostr's first retry interval (10 s)
                    assertEquals(attempts, bad.connections.get(), "the resting relay was tried again")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `nothing rests while no relay has connected, so rust keeps retrying when the device was offline`() = runBlocking {
        DroppingRelay().use { a ->
            DroppingRelay().use { b ->
                val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(a.url, b.url))
                try {
                    e.restAttempts = 1
                    e.restCheckMs = 200
                    e.connect()
                    assertTrue(awaitTrue(5_000) { a.connections.get() >= 1 && b.connections.get() >= 1 }, "no attempt was made")
                    delay(1_500)
                    assertTrue(e.relayStates().first().none { it.resting }, "a relay rested without proof the network works: ${e.relayStates().first()}")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a resting relay is tried again after its rest and rests again when it still fails`() = runBlocking {
        SilentWsRelay(answerEose = true).use { good ->
            DroppingRelay().use { bad ->
                val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(good.url, bad.url))
                try {
                    e.restAttempts = 1
                    e.restCheckMs = 200
                    e.restPeriodMs = 1_500
                    e.connect()
                    assertTrue(awaitTrue(15_000) { e.relayStates().first().any { it.url.trimEnd('/') == bad.url && it.resting } }, "never rested")
                    val before = bad.connections.get()
                    assertTrue(awaitTrue(10_000) { bad.connections.get() > before }, "the resting relay was never tried again")
                    assertTrue(awaitTrue(10_000) { e.relayStates().first().any { it.url.trimEnd('/') == bad.url && it.resting } }, "it did not rest again")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a relay renewed by reconnectAll keeps counting as connected once and does not rest`() = runBlocking {
        // spec 2026-10-23 §7 + 0.24.3: a relay that connected once in the session never rests, also after a network switch
        SilentWsRelay(answerEose = true).use { good ->
            val flaky = SilentWsRelay(answerEose = true)
            DroppingRelay().use { twin ->
                // the twin never connected in this session: with the same failures it rests (proof the checks run)
                val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(good.url, flaky.url, twin.url))
                try {
                    e.connect()
                    assertTrue(awaitTrue { e.connectedNormalRelayUrls() == setOf(good.url, flaky.url) }, "connected: ${e.connectedNormalRelayUrls()}")
                    e.restAttempts = 1
                    e.restCheckMs = 200
                    flaky.close() // the new network does not reach it yet
                    // every new connection to it is accepted and dropped at once: its failed attempts add up
                    DroppingRelay(flaky.port).use { dropping ->
                        e.reconnectAll()
                        assertTrue(awaitTrue { e.connectedRelayUrls() == setOf(good.url) }, "connected: ${e.connectedRelayUrls()}")
                        assertTrue(awaitTrue(15_000) { e.relayStates().first().any { it.url.trimEnd('/') == twin.url && it.resting } }, "the twin never rested")
                        assertTrue(awaitTrue(15_000) { dropping.connections.get() >= 1 }, "the renewed relay made no attempt")
                        delay(3_000) // many rest checks, with the good relay online and the renewed one failing
                        assertTrue(dropping.connections.get() >= 1)
                        val info = e.relayStates().first().single { it.url.trimEnd('/') == flaky.url }
                        assertFalse(info.resting, "a relay that connected before the switch rested: $info (attempts ${dropping.connections.get()})")
                        assertTrue(info.everConnected, "shown as never connected: $info")
                    }
                } finally {
                    e.close()
                    flaky.shutdown()
                }
            }
        }
    }
}
