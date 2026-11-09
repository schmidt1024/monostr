package com.monostr.nostr

import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.time.Duration
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind

class DetachedSenderTest {
    private fun sender() = DetachedSender(gate = NetworkGate().apply { set(NetworkMode.Direct) })
    private fun event() = EventBuilder.textNote("detached ${System.nanoTime()}").signWithKeys(Keys.generate())

    @Test
    fun `sends the finished event to the given relays and reports the relay's OK`() = runTest {
        SilentWsRelay(acceptEvents = true).use { relay ->
            val event = event()
            val result = sender().send(event, listOf("not a relay url", relay.url))
            assertTrue(result.sentToAny, "failed: ${result.failedRelays}")
            assertEquals(event.id().toHex(), result.eventId)
            assertEquals(1, result.kind)
            assertTrue(awaitTrue { relay.events.size == 1 })
            assertTrue(relay.events[0].contains(event.id().toHex()))
            assertTrue(relay.auths.isEmpty())
        }
    }

    @Test
    fun `never answers an AUTH challenge`() = runTest {
        SilentWsRelay(acceptEvents = true, authChallenge = "challenge-1").use { relay ->
            sender().send(event(), listOf(relay.url))
            assertTrue(awaitTrue { relay.events.size == 1 })
            assertTrue(relay.auths.isEmpty(), "a detached connection has no identity to authenticate with")
        }
    }

    @Test
    fun `without a usable relay nothing is sent, and an unreachable relay is a failed publish, not an exception`() = runTest {
        val none = sender().send(event(), listOf("not a relay url"))
        assertFalse(none.sentToAny)
        val dead = sender().send(event(), listOf("ws://127.0.0.1:9"))
        assertFalse(dead.sentToAny)
    }

    @Test
    fun `a subscription delivers stored and later events over its own connection and ends with its collector`() = runTest {
        val stored = event()
        val later = event()
        SilentWsRelay(serve = listOf(stored.asJson()), authChallenge = "challenge-1").use { relay ->
            val seen = CopyOnWriteArrayList<String>()
            val job = launch(Dispatchers.IO) {
                sender().subscribe(Filter().kind(Kind(1u)), listOf("not a relay url", relay.url)).collect { seen += it.id().toHex() }
            }
            assertTrue(awaitTrue { stored.id().toHex() in seen }, "what the relay had: $seen")
            relay.push(later.asJson())
            assertTrue(awaitTrue { later.id().toHex() in seen }, "what came in later: $seen")
            assertTrue(relay.auths.isEmpty(), "no identity to authenticate with")
            assertEquals(1, relay.connections)
            job.cancelAndJoin()
            assertTrue(awaitTrue { relay.open == 0 }, "the connection goes away with the collector")
        }
    }

    @Test
    fun `a slow collector loses none of a large first batch`() = runTest {
        // a relay may send a hundred stored events in one burst; the receipt waited for is the oldest, so it comes last
        val batch = (1..100).map { event() }
        SilentWsRelay(serve = batch.map { it.asJson() }).use { relay ->
            val seen = CopyOnWriteArrayList<String>()
            val job = launch(Dispatchers.IO) {
                sender().subscribe(Filter().kind(Kind(1u)), listOf(relay.url)).collect {
                    Thread.sleep(5) // the app parses and checks each event before it takes the next
                    seen += it.id().toHex()
                }
            }
            assertTrue(awaitTrue(15_000) { seen.size == batch.size }, "got ${seen.size} of ${batch.size}")
            assertEquals(batch.map { it.id().toHex() }.toSet(), seen.toSet())
            job.cancelAndJoin()
        }
    }

    @Test
    fun `under tor a relay whose connect never answers does not hold the send, which goes out once another relay is connected`() = runTest {
        // Pixel 2026-11-07: relay.damus.io and nos.lol took no tor circuit; tryConnect(5 s) returned after 125 s
        SilentWsRelay(acceptEvents = true).use { relay ->
            FakeSocks5(relayToPort = relay.port, hangs = { it == "hangs.example" }).use { socks ->
                val tor = DetachedSender(gate = NetworkGate().apply { set(NetworkMode.Tor(sessionPort = 1, isolatedPort = socks.port)) }, torReady = flowOf(true))
                val event = event()
                val started = System.nanoTime()
                val result = withContext(Dispatchers.IO) {
                    tor.send(event, listOf("ws://hangs.example", "ws://ok.example"), connectTimeout = Duration.ofSeconds(5), torOnly = true)
                }
                val tookMs = (System.nanoTime() - started) / 1_000_000
                assertEquals(listOf("ws://ok.example"), result.successRelays)
                assertTrue("ws://hangs.example" in result.failedRelays, "failed: ${result.failedRelays}")
                assertTrue(tookMs < 2_000, "sent once the first relay connected, not at the connect timeout: took $tookMs ms")
            }
        }
    }

    @Test
    fun `a subscription without a usable relay ends at once`() = runTest {
        assertTrue(sender().subscribe(Filter().kind(Kind(1u)), listOf("not a relay url")).toList().isEmpty())
    }
}
