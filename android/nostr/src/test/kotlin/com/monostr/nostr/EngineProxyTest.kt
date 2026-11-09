package com.monostr.nostr

import com.monostr.nostr.FakeSocks5.Ask
import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.ConnectionMode
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.RelayUrl
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.time.Duration

/** Spec 2026-11-06 Tor 7.1, 7.3: with a port, every relay connection goes through it by name; nothing resolves locally or goes direct. */
class EngineProxyTest {
    @TempDir lateinit var dir: Path

    private val sk = "0000000000000000000000000000000000000000000000000000000000000001"
    private fun note() = EventBuilder.textNote("proxied ${System.nanoTime()}")
    private fun signed() = note().signWithKeys(Keys.generate())
    private fun gate(mode: NetworkMode) = NetworkGate().apply { set(mode) }

    private suspend fun engine(socks: Int, relays: List<String>) =
        NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), relays, socksPort = socks)

    /** A port nothing listens on. */
    private fun deadPort() = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }

    @Test
    fun `engine with a port connects only through it, by host name`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { socks ->
                val e = engine(socks.port, listOf("ws://relay.example"))
                try {
                    e.connect()
                    assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "connected through the proxy; asked: ${socks.asks}")
                    assertEquals(setOf(Ask(3, "relay.example", 80)), socks.asks.toSet())
                    val result = e.signAndPublish(note())
                    assertTrue(result.sentToAny, "failed: ${result.failedRelays}")
                    assertTrue(awaitTrue { relay.events.size == 1 })
                } finally {
                    e.close()
                }
            }
        }
    }

    /**
     * The real routes of a relay that is not in the normal set. Everything the proxy is asked for goes to [tunnel]; [canary] listens
     * on the address of a relay in the list and so is reached only by a connection that left the proxy out.
     */
    private fun withProxy(block: suspend (socks: FakeSocks5, canary: SilentWsRelay, canaryUrl: String) -> Unit) = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true, answerEose = true).use { tunnel ->
            SilentWsRelay(acceptEvents = true, answerEose = true).use { canary ->
                FakeSocks5(relayToPort = tunnel.port).use { socks -> block(socks, canary, canary.url) }
            }
        }
    }

    private val onionUser = "ws://abcdefghijklmnopabcdefghijklmnopabcdefghijklmnopabcdefgh.onion"

    @Test
    fun `Direct mode - the engine gets no onion relay, on any path`() = runBlocking<Unit> {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), listOf("ws://normal.example", onionUser, "wss://x.ONION."), socksPort = null)
        try {
            e.addRelays(listOf("ws://other.onion"))
            assertEquals(listOf("ws://normal.example"), e.relayUrls())
            assertEquals(emptyList<String>(), e.attachTemporary(listOf("ws://foreign.onion", "wss://Y.onion/")))
            e.fetchFrom(listOf("ws://foreign.onion"), Filter().kind(Kind(1u)), Duration.ofMillis(300))
            val published = e.signAndPublish(note())
            e.sendTo(listOf("ws://watcher.onion"), published.eventId, Duration.ofMillis(100))
            assertTrue(runCatching { e.subscribeTo(listOf("ws://sub.onion"), Filter().kind(Kind(1u))) }.isFailure)
            assertEquals(emptyList<String>(), e.temporaryRelayUrls())
            val pool = e.client.relays().keys.map { it.toString().trimEnd('/') }
            assertEquals(listOf("ws://normal.example"), pool, "the pool holds only the normal relay")
        } finally {
            e.close()
        }
    }

    @Test
    fun `Tor mode - the engine gets the users onion relay through the proxy, foreign ones stay out`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true, answerEose = true).use { tunnel ->
            FakeSocks5(relayToPort = tunnel.port).use { socks ->
                val e = engine(socks.port, listOf(onionUser))
                try {
                    e.connect()
                    assertEquals(listOf(onionUser), e.relayUrls())
                    assertTrue(awaitTrue { socks.times(Ask(3, onionUser.removePrefix("ws://"), 80)) >= 1 }, "asked: ${socks.asks}")
                    assertEquals(emptyList<String>(), e.attachTemporary(listOf("ws://foreign.onion", "wss://z.onion")))
                    e.fetchFrom(listOf("ws://foreign.onion"), Filter().kind(Kind(1u)), Duration.ofMillis(300))
                    assertEquals(0, socks.asks.count { it.host == "foreign.onion" || it.host == "z.onion" }, "asked: ${socks.asks}")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a detached send and subscription never take an onion relay, Direct or Tor`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true, answerEose = true).use { tunnel ->
            FakeSocks5(relayToPort = tunnel.port).use { socks ->
                val onion = listOf("ws://watcher.onion")
                val direct = DetachedSender(gate = gate(NetworkMode.Direct)).send(signed(), onion)
                assertFalse(direct.sentToAny)
                val tor = DetachedSender(gate = gate(NetworkMode.Tor(socks.port, socks.port)), torReady = flowOf(true)).send(signed(), onion)
                assertFalse(tor.sentToAny)
                assertEquals(emptyList<Ask>(), socks.asks, "nothing was asked of the proxy")
            }
        }
    }

    /** What rust-nostr parses but java.net.URI reads without a host: the guards judge the rust-normalised URL. */
    private val oddOnions = listOf("wss://a_b.onion", "ws://foo_bar.abc.onion", "ws://abc%2Eonion", "wss://abc.onion/ x", "wss://abc.onion\\x")

    @Test
    fun `Direct mode - odd spellings of an onion are never added, attached, sent to or dialled`() = runBlocking<Unit> {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), listOf("ws://normal.example") + oddOnions, socksPort = null)
        try {
            e.addRelays(oddOnions)
            assertEquals(emptyList<String>(), e.attachTemporary(oddOnions))
            val published = e.signAndPublish(note())
            e.sendTo(oddOnions, published.eventId, Duration.ofMillis(100))
            e.fetchFrom(oddOnions, Filter().kind(Kind(1u)), Duration.ofMillis(300), attach = false)
            assertTrue(runCatching { e.subscribeTo(oddOnions, Filter().kind(Kind(1u))) }.isFailure)
            val pool = e.client.relays().keys.map { it.toString().trimEnd('/') }
            assertEquals(listOf("ws://normal.example"), pool)
            assertEquals(emptyList<String>(), e.temporaryRelayUrls())
        } finally {
            e.close()
        }
        val sent = DetachedSender(gate = gate(NetworkMode.Direct)).send(signed(), oddOnions)
        assertFalse(sent.sentToAny)
        assertEquals(emptyMap<String, String>(), sent.failedRelays, "no target was even tried")
        val sub = runCatching { DetachedSender(gate = gate(NetworkMode.Direct)).subscribe(Filter().kind(Kind(1u)), oddOnions).collect() }
        assertTrue(sub.isSuccess, "ends at once without a usable relay")
    }

    @Test
    fun `Tor mode - a wss onion of the users list is asked of the proxy by name on 443`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true, answerEose = true).use { tunnel ->
            FakeSocks5(relayToPort = tunnel.port).use { socks ->
                val e = engine(socks.port, listOf("wss://xabcdefghijklmnop.onion"))
                try {
                    e.connect()
                    assertTrue(awaitTrue { socks.times(Ask(3, "xabcdefghijklmnop.onion", 443)) >= 1 }, "asked: ${socks.asks}")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `Direct mode - a list whose only writer is an onion relay publishes nothing and does not throw`() = runBlocking<Unit> {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), listOf("ws://normal.example", onionUser), socksPort = null)
        try {
            e.setRelayModes(mapOf("ws://normal.example" to com.monostr.nostr.model.RelayMode.READ, onionUser to com.monostr.nostr.model.RelayMode.WRITE))
            val r = e.signAndPublish(note())
            assertFalse(r.sentToAny)
        } finally {
            e.close()
        }
    }

    private fun FakeSocks5.times(ask: Ask) = asks.count { it == ask }
    private fun SilentWsRelay.literal() = Ask(1, "127.0.0.1", port)

    @Test
    fun `fetchFrom on a foreign relay asks the proxy by name and opens nothing direct`() = withProxy { socks, canary, canaryUrl ->
        val e = engine(socks.port, listOf("ws://normal.example"))
        try {
            e.fetchFrom(listOf("ws://foreign.example", canaryUrl), Filter().kind(Kind(1u)), Duration.ofSeconds(3))
            assertTrue(socks.times(Ask(3, "foreign.example", 80)) >= 1, "asked: ${socks.asks}")
            assertTrue(socks.times(canary.literal()) >= 1, "asked: ${socks.asks}")
            assertEquals(0, canary.connections, "a foreign relay was reached directly")
        } finally {
            e.close()
        }
    }

    @Test
    fun `a temporary relay renewed by reconnectAll asks the proxy again and opens nothing direct`() = withProxy { socks, canary, canaryUrl ->
        val e = engine(socks.port, listOf("ws://normal.example"))
        try {
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() })
            val held = listOf("ws://held.example", canaryUrl)
            e.attachTemporary(held)
            e.subscribeTo(held, Filter().kind(Kind(1u)))
            assertTrue(awaitTrue { socks.times(Ask(3, "held.example", 80)) == 1 && socks.times(canary.literal()) == 1 }, "asked: ${socks.asks}")
            e.reconnectAll()
            assertTrue(awaitTrue { socks.times(Ask(3, "held.example", 80)) >= 2 && socks.times(canary.literal()) >= 2 }, "not renewed; asked: ${socks.asks}")
            assertEquals(0, canary.connections, "a renewed relay was reached directly")
        } finally {
            e.close()
        }
    }

    @Test
    fun `the rollback of a failed promotion asks the proxy again and opens nothing direct`() = withProxy { socks, canary, canaryUrl ->
        val e = engine(socks.port, emptyList())
        try {
            val urls = listOf("ws://promoted.example", canaryUrl)
            e.attachTemporary(urls)
            assertTrue(awaitTrue { socks.times(Ask(3, "promoted.example", 80)) == 1 && socks.times(canary.literal()) == 1 }, "asked: ${socks.asks}")
            // the promotion's add fails after it left the pool, so the rollback adds the relay itself
            val next = urls.iterator()
            e.poolProbe = { at ->
                if (at == "added" && next.hasNext()) {
                    e.client.forceRemoveRelay(RelayUrl.parse(next.next()))
                    throw IllegalStateException("promotion fails")
                }
            }
            e.addRelays(urls)
            e.poolProbe = null
            assertEquals(urls.toSet(), e.temporaryRelayUrls().toSet(), "rolled back")
            e.connect()
            assertTrue(awaitTrue { socks.times(Ask(3, "promoted.example", 80)) >= 2 && socks.times(canary.literal()) >= 2 }, "asked: ${socks.asks}")
            assertEquals(0, canary.connections, "a rolled back relay was reached directly")
        } finally {
            e.close()
        }
    }

    @Test
    fun `sendTo a watcher relay and a relay added later go through the proxy as well`() = withProxy { socks, canary, canaryUrl ->
        val e = engine(socks.port, listOf("ws://normal.example"))
        try {
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() })
            val published = e.signAndPublish(note())
            e.sendTo(listOf("ws://watcher.example", canaryUrl), published.eventId)
            assertTrue(socks.times(Ask(3, "watcher.example", 80)) >= 1, "sendTo; asked: ${socks.asks}")
            e.addRelays(listOf("ws://added.example"))
            e.connect()
            assertTrue(awaitTrue { socks.times(Ask(3, "added.example", 80)) >= 1 }, "addRelays; asked: ${socks.asks}")
            assertEquals(0, canary.connections)
        } finally {
            e.close()
        }
    }

    @Test
    fun `without a port a temporary relay still connects directly`() = runBlocking<Unit> {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())
            try {
                e.attachTemporary(listOf(relay.url))
                assertTrue(awaitTrue { relay.connections == 1 })
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `detached sender uses the isolated port, the engine the session port`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { session ->
                FakeSocks5(relayToPort = relay.port).use { isolated ->
                    val e = engine(session.port, listOf("ws://session.example"))
                    try {
                        e.connect()
                        assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() })
                        val sender = DetachedSender(gate = gate(NetworkMode.Tor(session.port, isolated.port)), torReady = flowOf(true))
                        val sent = sender.send(signed(), listOf("ws://tip.example"))
                        assertTrue(sent.sentToAny, "failed: ${sent.failedRelays}")
                        assertEquals(setOf(Ask(3, "session.example", 80)), session.asks.toSet())
                        assertEquals(setOf(Ask(3, "tip.example", 80)), isolated.asks.toSet())
                    } finally {
                        e.close()
                    }
                }
            }
        }
    }

    @Test
    fun `a detached subscription uses the isolated port too`() = runBlocking<Unit> {
        val stored = signed()
        SilentWsRelay(serve = listOf(stored.asJson())).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { isolated ->
                FakeSocks5().use { session ->
                    val sender = DetachedSender(gate = gate(NetworkMode.Tor(session.port, isolated.port)), torReady = flowOf(true))
                    val first = CompletableDeferred<Unit>()
                    val job = launch(Dispatchers.Default) { sender.subscribe(Filter().kind(Kind(1u)), listOf("ws://tip.example")).collect { first.complete(Unit) } }
                    try {
                        withTimeout(10_000) { first.await() }
                    } finally {
                        job.cancelAndJoin()
                    }
                    assertEquals(setOf(Ask(3, "tip.example", 80)), isolated.asks.toSet())
                    assertTrue(session.asks.isEmpty())
                }
            }
        }
    }

    @Test
    fun `detached sender on a dead isolated port fails without another connection`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true).use { canary ->
            val sender = DetachedSender(gate = gate(NetworkMode.Tor(deadPort(), deadPort())), torReady = flowOf(true))
            val sent = sender.send(signed(), listOf(canary.url), Duration.ofSeconds(1))
            assertFalse(sent.sentToAny)
            assertEquals(0, canary.connections)
        }
    }

    @Test
    fun `while the mode is unknown the detached sender fails and connects nowhere`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true, answerEose = true).use { canary ->
            val sender = DetachedSender(gate = NetworkGate())
            val send = runCatching { sender.send(signed(), listOf(canary.url)) }
            assertTrue(send.exceptionOrNull() is IOException, "send: $send")
            val subscribe = runCatching { sender.subscribe(Filter().kind(Kind(1u)), listOf(canary.url)).collect() }
            assertTrue(subscribe.exceptionOrNull() is IOException, "subscribe: $subscribe")
            assertEquals(0, canary.connections)
        }
    }

    @Test
    fun `a send for Tor only fails in direct mode and connects nowhere, one without the demand goes out`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true).use { canary ->
            val sender = DetachedSender(gate = gate(NetworkMode.Direct))
            val send = runCatching { sender.send(signed(), listOf(canary.url), torOnly = true) }
            assertTrue(send.exceptionOrNull() is IOException, "send: $send")
            assertEquals(0, canary.connections)
            assertTrue(sender.send(signed(), listOf(canary.url)).sentToAny)
        }
    }

    @Test
    fun `a send for Tor only goes through the isolated port`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { isolated ->
                val sender = DetachedSender(gate = gate(NetworkMode.Tor(deadPort(), isolated.port)), torReady = flowOf(true))
                assertTrue(sender.send(signed(), listOf("ws://tip.example"), torOnly = true).sentToAny)
                assertEquals(setOf(Ask(3, "tip.example", 80)), isolated.asks.toSet())
            }
        }
    }

    @Test
    fun `while Tor is not ready the detached sender fails and the port hears nothing`() = runBlocking<Unit> {
        FakeSocks5().use { squatter ->
            val gate = gate(NetworkMode.Tor(deadPort(), squatter.port))
            val sender = DetachedSender(gate = gate, torReady = MutableStateFlow(false))
            val send = runCatching { sender.send(signed(), listOf("ws://relay.example")) }
            assertTrue(send.exceptionOrNull() is IOException, "send: $send")
            val subscribe = runCatching { sender.subscribe(Filter().kind(Kind(1u)), listOf("ws://relay.example")).collect() }
            assertTrue(subscribe.exceptionOrNull() is IOException, "subscribe: $subscribe")
            delay(300)
            assertEquals(emptyList<Ask>(), squatter.asks.toList())
        }
    }

    @Test
    fun `a detached subscription ends when Tor stops being ready, its connection goes with it`() = runBlocking<Unit> {
        SilentWsRelay(answerEose = true).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { socks ->
                val ready = MutableStateFlow(true)
                val sender = DetachedSender(gate = gate(NetworkMode.Tor(deadPort(), socks.port)), torReady = ready)
                val running = async(Dispatchers.Default) { runCatching { sender.subscribe(Filter().kind(Kind(1u)), listOf("ws://relay.example")).collect() } }
                assertTrue(awaitTrue { relay.connections == 1 })
                ready.value = false
                val ended = withTimeout(10_000) { running.await() }
                assertTrue(ended.exceptionOrNull() is IOException, "ended: $ended")
                assertTrue(awaitTrue { relay.open == 0 }, "the connection is still open")
            }
        }
    }

    @Test
    fun `told nothing about Tor, the sender takes it as not ready`() = runBlocking<Unit> {
        FakeSocks5().use { squatter ->
            val send = runCatching { DetachedSender(gate = gate(NetworkMode.Tor(deadPort(), squatter.port))).send(signed(), listOf("ws://relay.example")) }
            assertTrue(send.exceptionOrNull() is IOException, "send: $send")
            assertEquals(emptyList<Ask>(), squatter.asks.toList())
        }
    }

    @Test
    fun `direct mode never asks whether Tor is ready`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true).use { relay ->
            val asked = kotlinx.coroutines.flow.flow<Boolean> { error("Tor was touched in direct mode") }
            val result = DetachedSender(gate = gate(NetworkMode.Direct), torReady = asked).send(signed(), listOf(relay.url))
            assertTrue(result.sentToAny, "result: $result")
        }
    }

    @Test
    fun `a detached subscription started direct ends when the mode changes, its connection goes with it`() = runBlocking<Unit> {
        SilentWsRelay(answerEose = true).use { relay ->
            val gate = gate(NetworkMode.Direct)
            val running = async(Dispatchers.Default) { runCatching { DetachedSender(gate = gate).subscribe(Filter().kind(Kind(1u)), listOf(relay.url)).collect() } }
            assertTrue(awaitTrue { relay.connections == 1 })
            gate.set(NetworkMode.Tor(deadPort(), deadPort()))
            val ended = withTimeout(10_000) { running.await() }
            assertTrue(ended.exceptionOrNull() is IOException, "ended: $ended")
            assertTrue(awaitTrue { relay.open == 0 }, "the direct connection is still open")
        }
    }

    @Test
    fun `a detached send under way fails when the mode changes`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true, okDelayMs = 8_000).use { relay ->
            val gate = gate(NetworkMode.Direct)
            val running = async(Dispatchers.Default) { runCatching { DetachedSender(gate = gate).send(signed(), listOf(relay.url)) } }
            assertTrue(awaitTrue { relay.events.size == 1 })
            gate.set(NetworkMode.Tor(deadPort(), deadPort()))
            val ended = withTimeout(4_000) { running.await() }
            assertTrue(ended.exceptionOrNull() is IOException, "ended: $ended")
        }
    }

    @Test
    fun `collected again after the mode changed, a detached subscription takes the new route`() = runBlocking<Unit> {
        val stored = signed()
        SilentWsRelay(serve = listOf(stored.asJson())).use { relay ->
            FakeSocks5(relayToPort = relay.port).use { isolated ->
                val gate = gate(NetworkMode.Direct)
                val sender = DetachedSender(gate = gate, torReady = flowOf(true))
                val first = async(Dispatchers.Default) { runCatching { sender.subscribe(Filter().kind(Kind(1u)), listOf(relay.url)).collect() } }
                assertTrue(awaitTrue { relay.connections == 1 })
                gate.set(NetworkMode.Tor(deadPort(), isolated.port))
                assertTrue(withTimeout(10_000) { first.await() }.exceptionOrNull() is IOException)
                val seen = CompletableDeferred<Unit>()
                val again = launch(Dispatchers.Default) { sender.subscribe(Filter().kind(Kind(1u)), listOf(relay.url)).collect { seen.complete(Unit) } }
                try {
                    withTimeout(10_000) { seen.await() }
                } finally {
                    again.cancelAndJoin()
                }
                assertEquals(listOf(Ask(1, "127.0.0.1", relay.port)), isolated.asks.toList())
                assertEquals(2, relay.connections, "one direct before the switch, one through the proxy after")
            }
        }
    }

    private class Case(val name: String, val canary: SilentWsRelay, val engine: NostrEngine? = null)

    /**
     * Every route with a proxy that cannot carry it, watched across rust-nostr's retry of a refused relay (10 s). All cases share one
     * window that starts when the last of them has had its first failure, so the set costs one window, not one each.
     */
    @Test
    fun `proxy failures, nothing goes direct across a retry`(): Unit = runBlocking {
        val canaries = java.util.concurrent.ConcurrentHashMap<String, SilentWsRelay>()
        val engines = java.util.concurrent.CopyOnWriteArrayList<NostrEngine>()
        val closers = java.util.concurrent.CopyOnWriteArrayList<AutoCloseable>()
        val refusing = FakeSocks5().also { closers += it }
        val tunnel = SilentWsRelay(acceptEvents = true, answerEose = true).also { closers += it }
        fun canary(name: String) = SilentWsRelay(acceptEvents = true, answerEose = true).also { closers += it; canaries[name] = it }
        suspend fun engine(name: String, port: Int, relays: List<String>) =
            NostrEngine.create(dir.resolve(name).toString(), LocalSigner(sk), relays, socksPort = port).also { engines += it }
        suspend fun NostrEngine.modeOf(url: String) = client.relay(RelayUrl.parse(url)).connectionMode()
        val modes = java.util.concurrent.ConcurrentHashMap<String, ConnectionMode>()
        val cut = FakeSocks5(relayToPort = tunnel.port).also { closers += it }
        var subscription: kotlinx.coroutines.Job? = null
        try {
            coroutineScope {
                for ((label, port) in listOf("dead" to deadPort(), "refusing" to refusing.port)) {
                    launch(Dispatchers.Default) {
                        val c = canary("publish-$label")
                        val e = engine("publish-$label", port, listOf(c.url))
                        e.connect()
                        assertFalse(e.signAndPublish(note()).sentToAny, "publish-$label")
                    }
                    launch(Dispatchers.Default) {
                        val c = canary("fetch-$label")
                        val e = engine("fetch-$label", port, listOf("ws://normal.example"))
                        e.fetchFrom(listOf(c.url), Filter().kind(Kind(1u)), Duration.ofSeconds(2))
                        modes["fetch-$label"] = e.modeOf(c.url)
                    }
                    launch(Dispatchers.Default) {
                        val c = canary("renew-$label")
                        val e = engine("renew-$label", port, listOf("ws://normal.example"))
                        e.connect()
                        e.attachTemporary(listOf(c.url))
                        e.subscribeTo(listOf(c.url), Filter().kind(Kind(1u)))
                        e.reconnectAll()
                        modes["renew-$label"] = e.modeOf(c.url)
                    }
                    launch(Dispatchers.Default) {
                        val c = canary("rollback-$label")
                        val e = engine("rollback-$label", port, emptyList())
                        e.attachTemporary(listOf(c.url))
                        var failed = false
                        e.poolProbe = { at ->
                            if (at == "added" && !failed) {
                                failed = true
                                e.client.forceRemoveRelay(RelayUrl.parse(c.url))
                                throw IllegalStateException("promotion fails")
                            }
                        }
                        e.addRelays(listOf(c.url))
                        e.poolProbe = null
                        assertEquals(listOf(c.url), e.temporaryRelayUrls(), "rollback-$label")
                        e.connect()
                        modes["rollback-$label"] = e.modeOf(c.url)
                    }
                }
                // tor stops under a live relay connection
                launch(Dispatchers.Default) {
                    val stopping = FakeSocks5(relayToPort = tunnel.port).also { closers += it }
                    val c = canary("tor stops")
                    val e = engine("stops", stopping.port, listOf(c.url))
                    e.connect()
                    assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "connected through the proxy")
                    stopping.killTunnels(); stopping.close()
                    assertTrue(awaitTrue { e.connectedNormalRelayUrls().isEmpty() }, "noticed the proxy going away")
                    assertFalse(e.signAndPublish(note()).sentToAny, "published with the proxy gone")
                }
                // the tunnel is cut, the proxy stays: the relay comes back through it
                launch(Dispatchers.Default) {
                    val c = canary("tunnel cut")
                    val e = engine("cut", cut.port, listOf(c.url))
                    e.connect()
                    assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "connected through the proxy")
                    cut.killTunnels()
                }
                // tor stops under a detached subscription
                launch(Dispatchers.Default) {
                    val isolated = FakeSocks5(relayToPort = tunnel.port).also { closers += it }
                    val c = canary("tor stops, detached")
                    val before = tunnel.connections
                    val sender = DetachedSender(gate = gate(NetworkMode.Tor(deadPort(), isolated.port)), torReady = flowOf(true))
                    // outside the setup's scope: it stays until the window is over
                    subscription = this@runBlocking.launch(Dispatchers.Default) { runCatching { sender.subscribe(Filter().kind(Kind(1u)), listOf(c.url)).collect() } }
                    assertTrue(awaitTrue { isolated.asks.isNotEmpty() && tunnel.connections > before }, "subscribed through the proxy")
                    isolated.killTunnels(); isolated.close()
                }
            }
            // every case has had its first failure: from here one retry of each falls into the window
            delay(RETRY_WINDOW_MS)
            subscription?.cancelAndJoin()
            assertEquals(emptyMap<String, Int>(), canaries.filterValues { it.connections > 0 }.mapValues { it.value.connections })
            assertEquals(6, modes.size)
            assertTrue(modes.values.all { it is ConnectionMode.Proxy }, "modes: $modes")
            // the retries come at about 10 s, later on a loaded machine; every further second is one more for the canaries, asked again below
            assertTrue(awaitTrue(15_000) { refusing.asks.groupBy { it.port }.values.any { it.size >= 2 } }, "no retry seen: ${refusing.asks}")
            assertTrue(awaitTrue(15_000) { cut.asks.size >= 2 }, "did not come back through the proxy: ${cut.asks}")
            assertEquals(emptyMap<String, Int>(), canaries.filterValues { it.connections > 0 }.mapValues { it.value.connections })
        } finally {
            engines.forEach { runCatching { it.close() } }
            closers.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `the mode changes during a stalled connect, the intent is not sent on the old route`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true).use { ready ->
            SilentWsRelay(acceptEvents = true, handshakeDelayMs = 1_500).use { stalled ->
                val gate = gate(NetworkMode.Direct)
                val running = async(Dispatchers.Default) { runCatching { DetachedSender(gate = gate).send(signed(), listOf(ready.url, stalled.url), Duration.ofSeconds(10)) } }
                // one relay is there, so a send let through would reach it; the connect still waits for the other
                assertTrue(awaitTrue { ready.connections == 1 && stalled.connections == 1 }, "connecting")
                delay(200)
                gate.set(NetworkMode.Tor(deadPort(), deadPort()))
                val ended = withTimeout(1_000) { running.await() }
                assertTrue(ended.exceptionOrNull() is IOException, "ended: $ended")
                delay(2_000) // past the handshake: a client still alive would send now
                assertEquals(emptyList<String>(), ready.events.toList() + stalled.events, "sent on the old route")
            }
        }
    }

    /**
     * Relays that keep the sender in its add loop for a while: all at the canary's address, so any dial shows there. Adding a relay
     * does not suspend in rust-nostr (no dispatch, measured), so no dispatcher can land a change exactly between the last add and the
     * connect, and nothing in production exists to hook. The nearest reachable moment: the sender's watcher subscribing to the mode,
     * which happens right before the loop. The change lands early in the loop, and the check at its end is what has to catch it.
     */
    private fun manyUrls(canary: SilentWsRelay) = List(ADD_LOOP_RELAYS) { "${canary.url}/p$it" }

    private fun CoroutineScope.flipOnceTheSenderWatches(gate: NetworkGate) = launch(Dispatchers.IO) {
        (gate.mode as MutableStateFlow).subscriptionCount.first { it > 0 }
        gate.set(NetworkMode.Tor(deadPort(), deadPort()))
    }

    @Test
    fun `the mode changes while relays are added, a send dials nothing and ends with an IOException`() = runBlocking<Unit> {
        SilentWsRelay(acceptEvents = true).use { canary ->
            val gate = gate(NetworkMode.Direct)
            val urls = manyUrls(canary)
            val event = signed()
            flipOnceTheSenderWatches(gate)
            val outcome = runCatching { DetachedSender(gate = gate).send(event, urls, Duration.ofSeconds(1)) }
            assertTrue(outcome.exceptionOrNull() is IOException, "outcome: $outcome")
            delay(500)
            assertEquals(0, canary.connections, "dialled the old route")
        }
    }

    @Test
    fun `the mode changes while relays are added, a subscription dials nothing and ends with an IOException`() = runBlocking<Unit> {
        SilentWsRelay(answerEose = true).use { canary ->
            val gate = gate(NetworkMode.Direct)
            val urls = manyUrls(canary)
            flipOnceTheSenderWatches(gate)
            val outcome = runCatching { DetachedSender(gate = gate).subscribe(Filter().kind(Kind(1u)), urls).collect() }
            assertTrue(outcome.exceptionOrNull() is IOException, "outcome: $outcome")
            delay(500)
            assertEquals(0, canary.connections, "dialled the old route")
        }
    }

    @Test
    fun `sendTo under tor sends once one watcher relay is connected, whatever a relay whose connect never answers does`() = runBlocking<Unit> {
        // Pixel 2026-11-07: relay.damus.io and nos.lol took no tor circuit; rust-nostr's tryConnect ignores its timeout behind SOCKS
        SilentWsRelay(acceptEvents = true).use { relay ->
            FakeSocks5(relayToPort = relay.port, hangs = { it.startsWith("hangs") }).use { socks ->
                val e = engine(socks.port, listOf("ws://relay.example", "ws://hangs.example"))
                try {
                    e.connect()
                    assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() })
                    val published = e.signAndPublish(note())
                    val started = System.nanoTime()
                    val out = withTimeout(20_000) {
                        e.sendTo(listOf("ws://hangs-watcher.example", "ws://watcher.example"), published.eventId, Duration.ofSeconds(5))
                    }
                    val tookMs = (System.nanoTime() - started) / 1_000_000
                    assertEquals(listOf("ws://watcher.example"), out.successRelays)
                    assertTrue("ws://hangs-watcher.example" in out.failedRelays, "failed: ${out.failedRelays}")
                    assertTrue(tookMs < 2_000, "sent once the first relay connected, not at the connect timeout: took $tookMs ms")
                } finally {
                    e.close()
                }
            }
        }
    }

    private companion object {
        /** rust-nostr retries a refused relay after 10 s. */
        const val RETRY_WINDOW_MS = 11_000L
        /** About 0.1 ms per add on the dev machine. */
        const val ADD_LOOP_RELAYS = 1_000
    }
}
