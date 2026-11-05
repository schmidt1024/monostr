package com.monostr.nostr

import com.monostr.nostr.model.RelayMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.SecretKey
import java.nio.file.Path

/** Spec 2026-11-05 relay read/write: the engine asks relays that read and sends to relays that write. A = write only, B = read only. */
class EngineRelayModesTest {
    @TempDir lateinit var dir: Path

    private val sk = "0000000000000000000000000000000000000000000000000000000000000001"

    private fun relay(port: Int = 0) = SilentWsRelay(answerEose = true, acceptEvents = true, port = port)

    private fun notes() = Filter().kind(Kind(1u)).limit(5u)

    private fun SilentWsRelay.sawSubscription(id: String) = requests.any { it.contains("\"$id\"") }

    /** Long enough for a frame that was wrongly sent to arrive. */
    private fun settle() = Thread.sleep(400)

    /** Engine with [a] write only and [b] read only, both connected. */
    private suspend fun oneWay(a: SilentWsRelay, b: SilentWsRelay): NostrEngine =
        connectedEngine(dir, listOf(a, b)).also { it.setRelayModes(mapOf(a.url to RelayMode.WRITE, b.url to RelayMode.READ)) }

    private suspend fun awaitOnlyB(e: NostrEngine, a: SilentWsRelay, b: SilentWsRelay) {
        a.shutdown()
        assertTrue(awaitTrue(15_000) { e.connectedNormalRelayUrls() == setOf(b.url) }, "only B connected: ${e.connectedNormalRelayUrls()}")
    }

    @Test
    fun `a fetch and a subscription reach B only`() = runTest {
        relay().use { a ->
            relay().use { b ->
                val e = oneWay(a, b)
                try {
                    e.fetch(notes())
                    assertEquals(1, b.requests.size, "fetch: ${b.requests}")
                    e.fetchSettled(notes())
                    assertEquals(2, b.requests.size, "fetchSettled: ${b.requests}")
                    e.fetch(notes(), exit = Exit.AnyEvent)
                    assertEquals(3, b.requests.size, "fetch with exit: ${b.requests}")
                    val id = e.subscribe(notes())
                    assertTrue(awaitTrue { b.sawSubscription(id) }, "subscription: ${b.requests}")
                    settle()
                    assertEquals(emptyList<String>(), a.requests.toList())
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a send reaches A only`() = runTest {
        relay().use { a ->
            relay().use { b ->
                val e = oneWay(a, b)
                try {
                    withContext(Dispatchers.Default) {
                        val results = listOf(
                            e.signAndSend(EventBuilder.textNote("one")),
                            e.signAndPublish(EventBuilder.textNote("two")),
                            e.signAndPublishUnstored(EventBuilder.textNote("three")),
                        )
                        results.forEach {
                            assertEquals(listOf(a.url), it.successRelays.map { url -> url.trimEnd('/') }, "$it")
                            assertTrue(it.failedRelays.isEmpty(), "B is no target at all, so no failure: $it")
                        }
                        val again = e.resend(results[0].eventId)
                        assertEquals(listOf(a.url), again.successRelays.map { url -> url.trimEnd('/') }, "$again")
                    }
                    settle()
                    assertTrue(a.events.size >= 3, "A: ${a.events}")
                    assertEquals(emptyList<String>(), b.events.toList())
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a send reaches A only, also when only B is connected`() = runTest {
        val a = relay()
        relay().use { b ->
            val e = oneWay(a, b)
            try {
                awaitOnlyB(e, a, b)
                withContext(Dispatchers.Default) {
                    val sent = e.signAndPublish(EventBuilder.textNote("no write relay"))
                    assertFalse(sent.sentToAny, "$sent")
                    assertTrue(sent.failedRelays.keys.any { it.contains(":${a.port}") }, "$sent")
                    val unstored = e.signAndPublishUnstored(EventBuilder.textNote("no write relay either"))
                    assertFalse(unstored.sentToAny, "$unstored")
                    assertEquals(NOT_CONNECTED, unstored.failedRelays.entries.firstOrNull { it.key.contains(":${a.port}") }?.value, "$unstored")
                }
                settle()
                assertEquals(emptyList<String>(), b.events.toList(), "the event went to a read-only relay")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a publish wakes a lost write relay although a read-only relay is connected`() = runTest {
        // v0.8.1 for one-way relays: the only relay that can take the event waits for its retry while a publish fails
        val a = relay()
        relay().use { b ->
            val e = oneWay(a, b)
            try {
                awaitOnlyB(e, a, b)
                relay(a.port).use { back ->
                    val result = withContext(Dispatchers.Default) { e.signAndPublish(EventBuilder.textNote("after the gap")) }
                    assertTrue(result.sentToAny, "$result")
                    assertEquals(1, back.events.size, "${back.events}")
                }
                assertEquals(1, b.connections, "the working connection stays")
                assertEquals(emptyList<String>(), b.events.toList())
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `fetchWriteOnly asks A only and asks nobody without a write-only relay`() = runTest {
        val own = EventBuilder.textNote("kept on a paid relay").signWithKeys(Keys(SecretKey.parse(sk))).asJson()
        SilentWsRelay(answerEose = true, acceptEvents = true, serve = listOf(own)).use { a ->
            relay().use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                try {
                    assertEquals(0, e.fetchWriteOnly(notes()).size, "no modes")
                    e.setRelayModes(mapOf(b.url to RelayMode.READ))
                    assertEquals(0, e.fetchWriteOnly(notes()).size, "a read-only relay alone")
                    settle()
                    assertEquals(0, a.requests.size + b.requests.size, "asked nobody: ${a.requests} ${b.requests}")

                    e.setRelayModes(mapOf(a.url to RelayMode.WRITE, b.url to RelayMode.READ))
                    assertEquals(1, e.fetchWriteOnly(notes()).size)
                    assertEquals(1, a.requests.size, "${a.requests}")
                    assertEquals(0, b.requests.size, "${b.requests}")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `fetchWriteOnly never asks a temporary relay a stale mode names`() = runTest {
        relay().use { home ->
            relay().use { temp ->
                val e = connectedEngine(dir, listOf(home))
                try {
                    val held = e.attachTemporary(listOf(temp.url))
                    e.setRelayModes(mapOf(temp.url to RelayMode.WRITE))
                    assertEquals(0, e.fetchWriteOnly(notes()).size)
                    settle()
                    assertEquals(0, temp.requests.size, "${temp.requests}")
                    e.detachTemporary(held)
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a reconnecting write-only relay gets no replayed subscription`() = runTest {
        val a = relay()
        relay().use { b ->
            val e = oneWay(a, b)
            try {
                val id = e.subscribe(notes())
                assertTrue(awaitTrue { b.sawSubscription(id) })
                awaitOnlyB(e, a, b)
                relay(a.port).use { back ->
                    e.reconnectAll()
                    assertTrue(awaitTrue(15_000) { e.connectedNormalRelayUrls() == setOf(a.url, b.url) }, "back: ${e.connectedNormalRelayUrls()}")
                    assertTrue(awaitTrue { b.requests.count { it.contains("\"$id\"") } >= 2 }, "B is asked again: ${b.requests}")
                    settle()
                    assertEquals(emptyList<String>(), back.requests.toList())
                }
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a relay that turned write-only loses its subscription on a reconnect`() = runTest {
        // the relay carries the subscription from when it still read: the renewal does not hand it over
        val a = relay()
        relay().use { b ->
            val e = connectedEngine(dir, listOf(a, b))
            try {
                val id = e.subscribe(notes())
                assertTrue(awaitTrue { a.sawSubscription(id) && b.sawSubscription(id) })
                e.setRelayModes(mapOf(a.url to RelayMode.WRITE))
                // connected: renewed
                e.reconnectAll()
                assertTrue(awaitTrue { a.connections == 2 }, "renewed: ${a.connections}")
                assertTrue(awaitTrue { b.requests.count { it.contains("\"$id\"") } >= 2 }, "B is asked again: ${b.requests}")
                settle()
                assertEquals(1, a.requests.count { it.contains("\"$id\"") }, "${a.requests}")
                // what the renewal handed over is gone too: a later loss brings nothing back
                awaitOnlyB(e, a, b)
                relay(a.port).use { back ->
                    e.reconnectAll()
                    assertTrue(awaitTrue(15_000) { a.url in e.connectedNormalRelayUrls() }, "back: ${e.connectedNormalRelayUrls()}")
                    settle()
                    assertEquals(emptyList<String>(), back.requests.toList())
                }
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a relay that stops reading and is added again keeps no subscription for a later reconnect`() = runTest {
        // what setRelayModes' doc asks of a caller. Without the remove and add, a lost relay still holds the subscription
        // and rust-nostr can ask it again on its own reconnect (seen here 1 run in 3), which the engine cannot intercept
        val a = relay()
        relay().use { b ->
            val e = connectedEngine(dir, listOf(a, b))
            try {
                val id = e.subscribe(notes())
                assertTrue(awaitTrue { a.sawSubscription(id) && b.sawSubscription(id) })
                e.setRelayModes(mapOf(a.url to RelayMode.WRITE))
                e.removeRelays(listOf(a.url))
                e.addRelays(listOf(a.url))
                assertTrue(awaitTrue { a.url in e.connectedNormalRelayUrls() }, "added again: ${e.connectedNormalRelayUrls()}")
                awaitOnlyB(e, a, b)
                relay(a.port).use { back ->
                    e.reconnectAll()
                    assertTrue(awaitTrue(15_000) { a.url in e.connectedNormalRelayUrls() }, "back: ${e.connectedNormalRelayUrls()}")
                    settle()
                    assertEquals(emptyList<String>(), back.requests.toList())
                }
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a renewed write-only relay keeps an explicit subscription and drops the live one`() = runTest {
        // a DM inbox relay that is also in the main list: DmSync's subscribeTo must survive a network switch
        relay().use { a ->
            relay().use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                try {
                    val live = e.subscribe(notes())
                    val wraps = e.subscribeTo(listOf(a.url), Filter().kind(Kind(1059u)).limit(5u))
                    assertTrue(awaitTrue { a.sawSubscription(live) && a.sawSubscription(wraps) }, "${a.requests}")
                    e.setRelayModes(mapOf(a.url to RelayMode.WRITE))
                    e.reconnectAll()
                    assertTrue(awaitTrue { a.connections == 2 }, "renewed: ${a.connections}")
                    assertTrue(awaitTrue { a.requests.count { it.contains("\"$wraps\"") } >= 2 }, "the DM subscription ended: ${a.requests}")
                    settle()
                    assertEquals(1, a.requests.count { it.contains("\"$live\"") }, "${a.requests}")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a lost write-only relay is asked its explicit subscription again`() = runTest {
        val a = relay()
        relay().use { b ->
            val e = oneWay(a, b)
            try {
                val wraps = e.subscribeTo(listOf(a.url), Filter().kind(Kind(1059u)).limit(5u))
                assertTrue(awaitTrue { a.sawSubscription(wraps) }, "${a.requests}")
                awaitOnlyB(e, a, b)
                relay(a.port).use { back ->
                    e.reconnectAll()
                    assertTrue(awaitTrue(15_000) { back.sawSubscription(wraps) }, "the DM subscription ended: ${back.requests}")
                }
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a write-only relay added later connects but gets no replayed subscription`() = runTest {
        relay().use { home ->
            relay().use { writer ->
                relay().use { both ->
                    val e = connectedEngine(dir, listOf(home))
                    try {
                        val id = e.subscribe(notes())
                        assertTrue(awaitTrue { home.sawSubscription(id) })
                        e.setRelayModes(mapOf(writer.url to RelayMode.WRITE))
                        e.addRelays(listOf(writer.url, both.url))
                        assertTrue(awaitTrue { both.sawSubscription(id) }, "a normal relay still gets it: ${both.requests}")
                        assertTrue(awaitTrue { writer.url in e.connectedNormalRelayUrls() }, "write-only relays connect like any other")
                        settle()
                        assertEquals(emptyList<String>(), writer.requests.toList())
                    } finally {
                        e.close()
                    }
                }
            }
        }
    }

    @Test
    fun `a temporary relay promoted as write-only gets no replayed subscription`() = runTest {
        relay().use { home ->
            relay().use { promoted ->
                val e = connectedEngine(dir, listOf(home))
                try {
                    val id = e.subscribe(notes())
                    assertTrue(awaitTrue { home.sawSubscription(id) })
                    e.attachTemporary(listOf(promoted.url))
                    e.setRelayModes(mapOf(promoted.url to RelayMode.WRITE))
                    e.addRelays(listOf(promoted.url))
                    assertTrue(promoted.url in e.relayUrls())
                    assertEquals(listOf(home.url), e.readRelayUrls())
                    settle()
                    assertEquals(emptyList<String>(), promoted.requests.toList())
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a relay that stops reading loses the live subscription only, on the same connection`() = runTest {
        relay().use { a ->
            relay().use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                try {
                    val live = e.subscribe(notes())
                    val wraps = e.subscribeTo(listOf(a.url), Filter().kind(Kind(1059u)).limit(5u))
                    assertTrue(awaitTrue { a.sawSubscription(live) && a.sawSubscription(wraps) && b.sawSubscription(live) }, "${a.requests}")
                    e.applyRelayModes(mapOf(a.url to RelayMode.WRITE))
                    assertTrue(awaitTrue { live in a.closes }, "CLOSE for the live one: ${a.closes}")
                    assertEquals(listOf(b.url), e.readRelayUrls())
                    settle()
                    assertFalse(wraps in a.closes, "the DM subscription ended: ${a.closes}")
                    assertEquals(emptyList<String>(), b.closes.toList(), "the other relay keeps it")
                    assertEquals(1, a.requests.count { it.contains("\"$wraps\"") }, "no second REQ: ${a.requests}")
                    assertEquals(1, a.connections, "no reconnect")
                    assertEquals(1, a.open, "connection kept")
                    assertEquals(1, b.connections, "no reconnect")
                    assertEquals(1, b.open, "connection kept")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a relay that stopped reading asks only the explicit subscription after a lost connection`() = runTest {
        val a = relay()
        relay().use { b ->
            val e = connectedEngine(dir, listOf(a, b))
            try {
                val live = e.subscribe(notes())
                val wraps = e.subscribeTo(listOf(a.url), Filter().kind(Kind(1059u)).limit(5u))
                assertTrue(awaitTrue { a.sawSubscription(live) && a.sawSubscription(wraps) }, "${a.requests}")
                e.applyRelayModes(mapOf(a.url to RelayMode.WRITE))
                assertTrue(awaitTrue { live in a.closes }, "${a.closes}")
                awaitOnlyB(e, a, b)
                relay(a.port).use { back ->
                    e.reconnectAll()
                    assertTrue(awaitTrue(15_000) { back.sawSubscription(wraps) }, "the DM subscription ended: ${back.requests}")
                    settle()
                    assertFalse(back.sawSubscription(live), "${back.requests}")
                }
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a relay that is away when it stops reading gets no live subscription when it returns`() = runTest {
        val a = relay()
        relay().use { b ->
            val e = connectedEngine(dir, listOf(a, b))
            try {
                val first = e.subscribe(notes())
                val second = e.subscribe(notes())
                assertTrue(awaitTrue { a.sawSubscription(first) && a.sawSubscription(second) }, "${a.requests}")
                awaitOnlyB(e, a, b)
                e.applyRelayModes(mapOf(a.url to RelayMode.WRITE))
                relay(a.port).use { back ->
                    e.reconnectAll()
                    assertTrue(awaitTrue(15_000) { a.url in e.connectedNormalRelayUrls() }, "back: ${e.connectedNormalRelayUrls()}")
                    settle()
                    assertEquals(emptyList<String>(), back.requests.toList())
                }
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a relay that starts reading gets the live subscriptions, on the same connection`() = runTest {
        relay().use { a ->
            relay().use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                try {
                    e.setRelayModes(mapOf(a.url to RelayMode.WRITE))
                    val live = e.subscribe(notes())
                    val wraps = e.subscribeTo(listOf(a.url), Filter().kind(Kind(1059u)).limit(5u))
                    assertTrue(awaitTrue { b.sawSubscription(live) && a.sawSubscription(wraps) })
                    assertFalse(a.sawSubscription(live), "${a.requests}")
                    e.applyRelayModes(mapOf(a.url to RelayMode.READ))
                    assertTrue(awaitTrue { a.sawSubscription(live) }, "${a.requests}")
                    assertEquals(setOf(a.url, b.url), e.readRelayUrls().toSet())
                    settle()
                    assertEquals(1, a.requests.count { it.contains("\"$wraps\"") }, "${a.requests}")
                    assertEquals(1, b.requests.count { it.contains("\"$live\"") }, "B has it already: ${b.requests}")
                    assertEquals(1, a.connections, "no reconnect")
                    assertEquals(1, b.connections, "no reconnect")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a write-only relay removed before the modes clear gets no request`() = runTest {
        // the order of back to defaults: a paid write-only relay that leaves never sees a REQ naming other authors
        relay().use { a ->
            relay().use { kept ->
                relay().use { b ->
                    val e = connectedEngine(dir, listOf(a, kept, b))
                    try {
                        e.setRelayModes(mapOf(a.url to RelayMode.WRITE, kept.url to RelayMode.WRITE))
                        val live = e.subscribe(notes())
                        assertTrue(awaitTrue { b.sawSubscription(live) })
                        e.removeRelays(listOf(a.url))
                        e.applyRelayModes(emptyMap())
                        assertTrue(awaitTrue { kept.sawSubscription(live) }, "a relay that stays starts reading: ${kept.requests}")
                        settle()
                        assertEquals(emptyList<String>(), a.requests.toList())
                    } finally {
                        e.close()
                    }
                }
            }
        }
    }

    @Test
    fun `applying modes that change no relay's reading sends nothing`() = runTest {
        relay().use { a ->
            relay().use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                try {
                    val live = e.subscribe(notes())
                    assertTrue(awaitTrue { a.sawSubscription(live) && b.sawSubscription(live) })
                    // both to read only and back: a read-only relay keeps reading
                    e.applyRelayModes(mapOf(a.url to RelayMode.READ))
                    e.applyRelayModes(mapOf(a.url to RelayMode.BOTH))
                    settle()
                    assertEquals(1, a.requests.size, "${a.requests}")
                    assertEquals(1, b.requests.size, "${b.requests}")
                    assertEquals(emptyList<String>(), a.closes.toList())
                    assertEquals(emptyList<String>(), b.closes.toList())
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `without modes every relay is read and written`() = runTest {
        relay().use { a ->
            relay().use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                try {
                    assertEquals(e.relayUrls(), e.readRelayUrls())
                    e.fetch(notes())
                    assertEquals(1, a.requests.size, "${a.requests}")
                    assertEquals(1, b.requests.size, "${b.requests}")
                    val id = e.subscribe(notes())
                    assertTrue(awaitTrue { a.sawSubscription(id) && b.sawSubscription(id) })
                    val sent = withContext(Dispatchers.Default) { e.signAndPublish(EventBuilder.textNote("everywhere")) }
                    assertEquals(setOf(a.url, b.url), sent.successRelays.map { it.trimEnd('/') }.toSet(), "$sent")
                    assertTrue(awaitTrue { a.events.size == 1 && b.events.size == 1 })
                    assertEquals(1, a.connections)
                    assertEquals(1, b.connections)
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `modes can be cleared, a repeated call touches nothing and a slash does not matter`() = runTest {
        relay().use { a ->
            relay().use { b ->
                val e = connectedEngine(dir, listOf(a, b))
                try {
                    val modes = mapOf("${a.url}/" to RelayMode.WRITE, " ${b.url}/ " to RelayMode.READ, "not a url" to RelayMode.READ)
                    repeat(2) { e.setRelayModes(modes) }
                    assertEquals(listOf(b.url), e.readRelayUrls())
                    assertEquals(setOf(a.url, b.url), e.relayUrls().toSet(), "relayUrls keeps every relay")
                    assertEquals(1, a.connections)
                    assertEquals(1, b.connections)
                    assertEquals(0, a.requests.size + b.requests.size, "no network")

                    e.setRelayModes(mapOf(a.url to RelayMode.BOTH, b.url to RelayMode.BOTH))
                    assertEquals(setOf(a.url, b.url), e.readRelayUrls().toSet(), "explicitly both")
                    e.setRelayModes(mapOf(a.url to RelayMode.WRITE))
                    assertEquals(listOf(b.url), e.readRelayUrls())
                    e.setRelayModes(emptyMap())
                    assertEquals(setOf(a.url, b.url), e.readRelayUrls().toSet(), "cleared")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `explicit targets and status ignore the modes`() = runTest {
        relay().use { a ->
            relay().use { b ->
                val e = oneWay(a, b)
                try {
                    assertEquals(setOf(a.url, b.url), e.connectedNormalRelayUrls(), "one-way relays stay connected")
                    assertEquals(setOf(a.url, b.url), e.relayStates().first().map { it.url.trimEnd('/') }.toSet(), "and show their status")
                    e.fetchFrom(listOf(a.url), notes())
                    assertEquals(1, a.requests.size, "fetchFrom: ${a.requests}")
                    val id = e.subscribeTo(listOf(a.url), notes())
                    assertTrue(awaitTrue { a.sawSubscription(id) }, "subscribeTo: ${a.requests}")
                    val note = withContext(Dispatchers.Default) { e.signAndSend(EventBuilder.textNote("to the writer")) }
                    assertEquals(0, b.events.size)
                    val sent = withContext(Dispatchers.Default) { e.sendTo(listOf(b.url), note.eventId) }
                    assertTrue(sent.sentToAny, "$sent")
                    assertTrue(awaitTrue { b.events.size == 1 }, "sendTo: ${b.events}")
                } finally {
                    e.close()
                }
            }
        }
    }
}
