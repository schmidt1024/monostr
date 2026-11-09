package com.monostr.nostr.net

import com.monostr.nostr.DetachedSender
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import java.nio.file.Path
import java.time.Duration

/** Tor F1: whether this process ever gave a relay a direct route decides how a switch to Tor ends, whoever added the relay. */
class DirectRelayRouteTest {
    @TempDir lateinit var dir: Path

    private val sk = "0000000000000000000000000000000000000000000000000000000000000001"
    private val closedPort = "ws://127.0.0.1:1"

    /** It never goes back in the app, which has no way to: each test starts as a new process would. */
    @BeforeEach fun forget() {
        DirectRelayRoute::class.java.getDeclaredField("used").apply { isAccessible = true }.setBoolean(null, false)
    }

    private suspend fun <T> engine(relays: List<String>, port: Int?, block: suspend (NostrEngine) -> T): T {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), relays, socksPort = port, held = port != null)
        try {
            return block(e)
        } finally {
            e.close()
        }
    }

    private fun event() = EventBuilder.textNote("route").signWithKeys(Keys.generate())

    @Test
    fun `an engine with no relays has no direct route yet`() = runBlocking {
        engine(emptyList(), null) { assertFalse(DirectRelayRoute.wasUsed) }
    }

    @Test
    fun `an engine built with a relay on no proxy has one`() = runBlocking {
        engine(listOf(closedPort), null) { assertTrue(DirectRelayRoute.wasUsed) }
    }

    @Test
    fun `a relay added later on no proxy counts`() = runBlocking {
        engine(emptyList(), null) {
            it.addRelays(listOf(closedPort))
            assertTrue(DirectRelayRoute.wasUsed)
        }
    }

    @Test
    fun `a temporary relay on no proxy counts`() = runBlocking {
        engine(emptyList(), null) {
            it.attachTemporary(listOf(closedPort))
            assertTrue(DirectRelayRoute.wasUsed)
        }
    }

    @Test
    fun `an onion address on no proxy is never added, so it does not count`() = runBlocking {
        engine(listOf("wss://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion"), null) { assertFalse(DirectRelayRoute.wasUsed) }
    }

    @Test
    fun `relays through the proxy never count, normal or temporary`() = runBlocking {
        engine(listOf(closedPort), 1) {
            it.addRelays(listOf("ws://127.0.0.1:2"))
            it.attachTemporary(listOf("ws://127.0.0.1:3"))
            assertFalse(DirectRelayRoute.wasUsed)
        }
    }

    private fun gate(mode: NetworkMode) = NetworkGate().apply { set(mode) }

    @Test
    fun `a detached send on no proxy counts`() = runBlocking {
        DetachedSender(gate = gate(NetworkMode.Direct)).send(event(), listOf(closedPort), Duration.ofMillis(200))
        assertTrue(DirectRelayRoute.wasUsed)
    }

    @Test
    fun `a detached send with nowhere to go does not`() = runBlocking {
        DetachedSender(gate = gate(NetworkMode.Direct)).send(event(), emptyList(), Duration.ofMillis(200))
        assertFalse(DirectRelayRoute.wasUsed)
    }

    @Test
    fun `a detached send through the isolated port does not`() = runBlocking {
        DetachedSender(gate = gate(NetworkMode.Tor(1, 2)), torReady = flowOf(true)).send(event(), listOf(closedPort), Duration.ofMillis(200))
        assertFalse(DirectRelayRoute.wasUsed)
    }
}
