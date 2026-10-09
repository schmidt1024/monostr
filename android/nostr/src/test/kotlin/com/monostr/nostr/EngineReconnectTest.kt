package com.monostr.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * What happens to a publish when the relay connection was lost and the relay is reachable again:
 * the situation after the app spent a while in the background (v0.8.1: a Monero setup failed four
 * times with "no relay" right after the user came back from copying the seed).
 */
class EngineReconnectTest {
    @TempDir lateinit var dir: Path

    private val sk = "0000000000000000000000000000000000000000000000000000000000000001"

    @Test
    fun `a publish right after the relay came back reaches it instead of failing while the pool waits to retry`() = runTest {
        val first = SilentWsRelay(acceptEvents = true)
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), listOf(first.url))
        try {
            e.connect()
            assertTrue(awaitTrue { e.connectedRelayUrls().isNotEmpty() }, "connected at first")
            first.shutdown()
            assertTrue(awaitTrue(15_000) { e.connectedRelayUrls().isEmpty() }, "the engine noticed the loss")
            SilentWsRelay(acceptEvents = true, port = first.port).use { back ->
                val started = System.currentTimeMillis()
                val result = e.signAndPublish(EventBuilder.textNote("after the gap"))
                val took = System.currentTimeMillis() - started
                assertTrue(result.sentToAny, "failed after $took ms: ${result.failedRelays}")
                assertTrue(awaitTrue { back.events.size == 1 })
            }
        } finally {
            e.close()
        }
    }

    @Test
    fun `live subscriptions are asked again on the reconnect a publish forced`() = runTest {
        val first = SilentWsRelay(acceptEvents = true, answerEose = true)
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), listOf(first.url))
        try {
            e.connect()
            assertTrue(awaitTrue { e.connectedRelayUrls().isNotEmpty() })
            e.subscribe(Filter().kind(Kind(1u)).limit(5u))
            assertTrue(awaitTrue { first.requests.size == 1 }, "subscribed: ${first.requests}")
            first.shutdown()
            assertTrue(awaitTrue(15_000) { e.connectedRelayUrls().isEmpty() })
            SilentWsRelay(acceptEvents = true, answerEose = true, port = first.port).use { back ->
                assertTrue(e.signAndPublish(EventBuilder.textNote("after the gap")).sentToAny)
                assertTrue(awaitTrue { back.requests.any { it.contains("\"kinds\":[1]") } }, "the feed keeps updating: ${back.requests}")
            }
        } finally {
            e.close()
        }
    }

    @Test
    fun `a relay that is still down after a forced reconnect keeps being retried`() = runTest {
        val first = SilentWsRelay(acceptEvents = true)
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), listOf(first.url))
        try {
            e.connect()
            assertTrue(awaitTrue { e.connectedRelayUrls().isNotEmpty() })
            first.shutdown()
            assertTrue(awaitTrue(15_000) { e.connectedRelayUrls().isEmpty() })
            assertFalse(e.signAndPublish(EventBuilder.textNote("into the void")).sentToAny)
            SilentWsRelay(acceptEvents = true, port = first.port).use {
                assertTrue(awaitTrue(60_000) { e.connectedRelayUrls().isNotEmpty() }, "the pool's own retry is still running")
            }
        } finally {
            e.close()
        }
    }

    @Test
    fun `a connected relay is left alone when another relay is dead`() = runTest {
        SilentWsRelay(acceptEvents = true).use { good ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), listOf(good.url, "ws://127.0.0.1:9"))
            try {
                e.connect()
                assertTrue(awaitTrue { e.connectedRelayUrls().isNotEmpty() })
                val started = System.currentTimeMillis()
                val result = e.signAndPublish(EventBuilder.textNote("the good relay is left alone"))
                val took = System.currentTimeMillis() - started
                assertTrue(result.sentToAny)
                assertTrue(took < 3_000, "a relay that is not connected must not hold the publish up: $took ms")
                assertTrue(result.failedRelays.keys.any { it.contains("127.0.0.1:9") }, "the skipped relay is reported: ${result.failedRelays}")
                e.reconnectIfLost()
                assertEquals(1, good.connections, "a working connection is not torn down for the sake of a dead relay")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a relay that is still connecting is waited for, not skipped`() = runTest {
        // back from the background one relay is often there already while the others are in their handshake:
        // a replaceable event (follow list, profile, payment info) must not end up on that one relay only
        SilentWsRelay(acceptEvents = true).use { good ->
            SilentWsRelay(acceptEvents = true, handshakeDelayMs = 600).use { slow ->
                val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), listOf(good.url, slow.url))
                try {
                    e.connect()
                    assertTrue(awaitTrue { e.connectedRelayUrls().isNotEmpty() })
                    val result = e.signAndPublish(EventBuilder.textNote("both relays get it"))
                    assertTrue(result.successRelays.any { it.contains(":${slow.port}") }, "sent to ${result.successRelays}, failed ${result.failedRelays}")
                    assertTrue(result.successRelays.any { it.contains(":${good.port}") })
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a relay that never finishes connecting holds a publish up for a short while only`() = runTest {
        SilentWsRelay(acceptEvents = true).use { good ->
            SilentWsRelay(handshakeDelayMs = 60_000).use { stuck ->
                val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), listOf(good.url, stuck.url))
                try {
                    e.connect()
                    assertTrue(awaitTrue { e.connectedRelayUrls().isNotEmpty() })
                    val started = System.currentTimeMillis()
                    val result = e.signAndPublish(EventBuilder.textNote("the stuck relay is given up on"))
                    val took = System.currentTimeMillis() - started
                    assertTrue(result.sentToAny)
                    assertTrue(took < 4_000, "took $took ms")
                    assertEquals(NOT_CONNECTED, result.failedRelays.entries.firstOrNull { it.key.contains(":${stuck.port}") }?.value, "${result.failedRelays}")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a reconnect cancelled half way leaves the relays with their retry`() = runTest {
        val first = SilentWsRelay(acceptEvents = true)
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), listOf(first.url))
        try {
            e.connect()
            assertTrue(awaitTrue { e.connectedRelayUrls().isNotEmpty() })
            first.shutdown()
            assertTrue(awaitTrue(15_000) { e.connectedRelayUrls().isEmpty() })
            // something answers on the port but never completes the websocket handshake: the forced connect hangs
            val stalling = ServerSocket(first.port, 50, InetAddress.getLoopbackAddress())
            val held = CopyOnWriteArrayList<Socket>()
            thread(isDaemon = true) { while (!stalling.isClosed) held += runCatching { stalling.accept() }.getOrNull() ?: break }
            val job = launch { e.reconnectIfLost() }
            withContext(Dispatchers.IO) { Thread.sleep(1_000) } // real time: the retry wait was ended, the connect is hanging
            job.cancelAndJoin()                                  // the user left the screen
            stalling.close()
            held.forEach { runCatching { it.close() } }
            SilentWsRelay(acceptEvents = true, port = first.port).use {
                assertTrue(awaitTrue(45_000) { e.connectedRelayUrls().isNotEmpty() }, "the pool still retries on its own")
            }
        } finally {
            e.close()
        }
    }

    @Test
    fun `reconnectIfLost brings the relays back without a publish`() = runTest {
        val first = SilentWsRelay(acceptEvents = true)
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), listOf(first.url))
        try {
            e.connect()
            assertTrue(awaitTrue { e.connectedRelayUrls().isNotEmpty() })
            first.shutdown()
            assertTrue(awaitTrue(15_000) { e.connectedRelayUrls().isEmpty() })
            SilentWsRelay(acceptEvents = true, port = first.port).use {
                e.reconnectIfLost()
                assertTrue(e.connectedRelayUrls().isNotEmpty(), "connected again when the call returns")
            }
        } finally {
            e.close()
        }
    }
}
