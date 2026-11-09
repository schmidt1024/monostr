package com.monostr.nostr

import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import java.nio.file.Path
import java.time.Duration

/**
 * Tor plan Task 6: outside Connected our SOCKS port may belong to another local app, so a held engine reaches for no socket at all;
 * the database stays readable. rust-nostr's own reconnects cannot ask, so holding ends the pool's connections.
 */
class EngineHeldTest {
    @TempDir lateinit var dir: Path

    private val sk = "0000000000000000000000000000000000000000000000000000000000000001"
    private fun note() = EventBuilder.textNote("held ${System.nanoTime()}")

    private suspend fun held(socks: Int, relays: List<String> = listOf("ws://relay.example")) =
        NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), relays, socksPort = socks, held = true)

    private val anyKind = Filter().kind(Kind(1u))

    /** Long enough for a connection already asked for to reach the proxy; rust-nostr's retries (10 s) are watched in the test that holds a live relay. */
    private suspend fun settle() = delay(1_500)

    @Test
    fun `a held engine dials nothing, whatever is asked of it`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true, answerEose = true).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { socks ->
                val e = held(socks.port)
                try {
                    e.connect()
                    val short = Duration.ofMillis(100)
                    e.fetch(anyKind, short)
                    e.fetchSettled(anyKind, short)
                    e.fetchFrom(listOf("ws://foreign.example"), anyKind, short)
                    e.fetch(anyKind, short, exit = Exit.AllIds(setOf("a".repeat(64))))
                    e.subscribe(anyKind)
                    e.attachTemporary(listOf("ws://temporary.example"))
                    e.addRelays(listOf("ws://added.example"))
                    e.reconnectIfLost()
                    e.reconnectAll()
                    val published = e.signAndPublish(note())
                    assertFalse(published.sentToAny, "published: $published")
                    e.sendTo(listOf("ws://watcher.example"), published.eventId, Duration.ofMillis(100))
                    settle()
                    assertEquals(emptyList<FakeSocks5.Ask>(), socks.asks.toList())
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `the database stays readable while held`() = runBlocking<Unit> {
        FakeSocks5().use { socks ->
            val e = held(socks.port)
            try {
                val event = note().signWithKeys(Keys.generate())
                e.save(event)
                assertEquals(listOf(event.id().toHex()), e.query(anyKind).map { it.id().toHex() })
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `opening connects through the port, and what was subscribed while held reaches the relay`() = runBlocking<Unit> {
        SilentWsRelay(answerEose = true).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { socks ->
                val e = held(socks.port)
                try {
                    e.connect()
                    val id = e.subscribe(anyKind)
                    assertEquals(0, relay.connections)
                    assertTrue(e.openConnections(), "the route changed")
                    assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "asked: ${socks.asks}")
                    assertTrue(awaitTrue { relay.requests.any { id in it } }, "requests: ${relay.requests}")
                    assertEquals(FakeSocks5.Ask(3, "relay.example", 80), socks.asks.first())
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `holding closes the connections, no retry comes, and opening again brings relay and subscription back`() = runBlocking<Unit> {
        SilentWsRelay(answerEose = true).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { socks ->
                val e = held(socks.port)
                try {
                    e.connect()
                    e.openConnections()
                    val id = e.subscribe(anyKind)
                    assertTrue(awaitTrue { relay.requests.any { id in it } })

                    e.holdConnections()
                    assertTrue(awaitTrue { relay.open == 0 }, "the relay connection is still open")
                    val asked = socks.asks.size
                    delay(12_000) // rust-nostr retries a lost relay after 10 s
                    assertEquals(asked, socks.asks.size, "a retry through a port that may not be ours: ${socks.asks}")

                    val before = relay.requests.size
                    assertTrue(e.openConnections())
                    assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() })
                    assertTrue(awaitTrue { relay.requests.drop(before).any { id in it } }, "the subscription did not come back: ${relay.requests}")
                } finally {
                    e.close()
                }
            }
        }
    }

    /**
     * Pixel 2026-11-08: Android ends the :tor process when a worker thaws the app, Tor comes back and the route opens again, and
     * the worker's DM fetch went out at once while the relays were still connecting: rust-nostr answered in 158 ms with nothing,
     * and that counted as a complete round. A route that opens again is a new start for the fetches' grace.
     */
    @Test
    fun `a fetch right after the route opens again waits for the relay, as after the start`() = runBlocking<Unit> {
        val stored = EventBuilder.textNote("kept").signWithKeys(Keys.generate())
        SilentWsRelay(serve = listOf(stored.asJson()), handshakeDelayMs = 500).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { socks ->
                val e = held(socks.port)
                try {
                    e.startWindow = Duration.ofSeconds(3)
                    e.connect()
                    e.openConnections()
                    assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() })
                    delay(3_500) // the start's grace is over
                    e.holdConnections()
                    assertTrue(awaitTrue { relay.open == 0 }, "the relay connection is still open")
                    assertTrue(e.openConnections())
                    val events = e.fetchFrom(listOf("ws://relay.example"), anyKind, Duration.ofSeconds(8))
                    assertEquals(1, events.size, "the fetch asked before the relay was connected again")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a fetch waits for the route within its own timeout`() = runBlocking<Unit> {
        SilentWsRelay(answerEose = true).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { socks ->
                val e = held(socks.port)
                try {
                    e.connect()
                    val running = async(Dispatchers.Default) { e.fetch(anyKind, Duration.ofSeconds(8)) }
                    delay(500)
                    assertFalse(running.isCompleted, "it should wait for the route")
                    e.openConnections()
                    withTimeout(10_000) { running.await() }
                    assertTrue(awaitTrue { relay.requests.isNotEmpty() }, "the fetch reached the relay")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a fetch that gave up is announced when the route opens, one that was answered is not`() = runBlocking<Unit> {
        SilentWsRelay(answerEose = true).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { socks ->
                val e = held(socks.port)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    e.connect()
                    assertEquals(emptyList<Any>(), e.fetch(anyKind, Duration.ofMillis(100)))
                    val announced = scope.async { e.reopened.first() }
                    delay(100)
                    e.openConnections()
                    withTimeout(3_000) { announced.await() }

                    assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() })
                    e.holdConnections()
                    val silent = scope.async { e.reopened.first() }
                    delay(100)
                    e.openConnections()
                    assertNull(withTimeoutOrNull(500) { silent.await() }, "nothing was missed")
                    silent.cancel()
                } finally {
                    scope.cancel()
                    e.close()
                }
            }
        }
    }

    @Test
    fun `while the connect is still to come nobody is let through, and a fetch that gives up then hears of the opening`() = runBlocking<Unit> {
        SilentWsRelay(answerEose = true).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { socks ->
                val e = held(socks.port)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    e.connect()
                    val announced = scope.async { e.reopened.first() }
                    delay(100)
                    var seenOpen: Boolean? = null
                    var answer: List<*>? = null
                    e.poolProbe = { at ->
                        if (at == "opening") {
                            seenOpen = e.routeOpen.value
                            answer = e.fetch(anyKind, Duration.ofMillis(100))
                        }
                    }
                    e.openConnections()
                    assertEquals(false, seenOpen, "open before the relays were told to connect")
                    assertEquals(emptyList<Any>(), answer)
                    assertEquals(true, e.routeOpen.value)
                    withTimeout(3_000) { announced.await() }
                } finally {
                    scope.cancel()
                    e.close()
                }
            }
        }
    }

    @Test
    fun `the wait for the route comes out of the fetch's budget`() = runBlocking<Unit> {
        SilentWsRelay().use { silent ->
            FakeSocks5(relayToPort = silent.port).use { socks ->
                val e = held(socks.port)
                try {
                    e.connect()
                    val began = System.nanoTime()
                    val running = async(Dispatchers.Default) { e.fetch(anyKind, Duration.ofSeconds(3)) }
                    delay(1_500)
                    e.openConnections()
                    withTimeout(10_000) { running.await() }
                    val tookMs = (System.nanoTime() - began) / 1_000_000
                    assertTrue(tookMs < 4_000, "took $tookMs ms of a 3 s budget")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a fetch with too little left after the wait gives up and hears of the opening`() = runBlocking<Unit> {
        SilentWsRelay().use { silent ->
            FakeSocks5(relayToPort = silent.port).use { socks ->
                val e = held(socks.port)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    e.connect()
                    val announced = scope.async { e.reopened.first() }
                    val running = async(Dispatchers.Default) { e.fetch(anyKind, Duration.ofMillis(600)) }
                    delay(400)
                    e.openConnections()
                    assertEquals(emptyList<Any>(), withTimeout(2_000) { running.await() })
                    withTimeout(3_000) { announced.await() }
                } finally {
                    scope.cancel()
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a publish after a hold fails and dials nothing, the relays being terminated by then`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true, answerEose = true).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { socks ->
                val e = held(socks.port)
                try {
                    e.connect()
                    e.openConnections()
                    assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() })
                    e.holdConnections()
                    assertTrue(awaitTrue { relay.open == 0 })
                    val asked = socks.asks.size
                    val published = e.signAndPublish(note())
                    assertFalse(published.sentToAny, "published: $published")
                    settle()
                    assertEquals(asked, socks.asks.size, "dialed: ${socks.asks}")
                    assertEquals(0, relay.events.size)
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `after close opening dials nothing and says so`() = runBlocking<Unit> {
        SilentWsRelay(answerEose = true).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { socks ->
                val e = held(socks.port)
                e.connect()
                e.close()
                assertFalse(e.openConnections())
                settle()
                assertEquals(emptyList<FakeSocks5.Ask>(), socks.asks.toList())
            }
        }
    }

    @Test
    fun `opening twice and holding twice change the route once each`() = runBlocking<Unit> {
        SilentWsRelay(answerEose = true).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { socks ->
                val e = held(socks.port)
                try {
                    e.connect()
                    assertTrue(e.openConnections())
                    assertFalse(e.openConnections())
                    assertTrue(awaitTrue { relay.open == 1 })
                    e.holdConnections()
                    e.holdConnections()
                    assertTrue(awaitTrue { relay.open == 0 })
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `an engine not held behaves as before`() = runBlocking<Unit> {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = NostrEngine.create(dir.resolve("direct").toString(), LocalSigner(sk), listOf(relay.url))
            try {
                assertTrue(e.routeOpen.value)
                e.connect()
                assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() })
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `holding a direct engine stops its new connections too`() = runBlocking<Unit> {
        SilentWsRelay(answerEose = true).use { relay ->
            SilentWsRelay(answerEose = true).use { foreign ->
                val e = NostrEngine.create(dir.resolve("direct").toString(), LocalSigner(sk), listOf(relay.url))
                try {
                    e.connect()
                    assertTrue(awaitTrue { relay.open == 1 })
                    e.holdConnections()
                    e.attachTemporary(listOf(foreign.url))
                    e.reconnectAll()
                    assertTrue(awaitTrue { relay.open == 0 })
                    settle()
                    assertEquals(0, foreign.connections)
                    assertEquals(1, relay.connections)
                } finally {
                    e.close()
                }
            }
        }
    }
}
