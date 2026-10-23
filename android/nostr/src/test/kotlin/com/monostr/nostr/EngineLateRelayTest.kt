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
import kotlin.concurrent.thread

/**
 * Spec 2026-10-23 §8: a relay attached later (search, quotes, author relays) that is still connecting is waited for
 * as briefly as the relays right after the start, for [NostrEngine.lateWindow] after it was attached.
 */
class EngineLateRelayTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.generate()
    private val alice = Keys.generate()
    private val note = EventBuilder.textNote("hello").signWithKeys(alice)
    private val filter get() = Filter().kind(Kind(1u)).author(alice.publicKey())

    /** A relay that takes the TCP connection and drops it after [ms] without a handshake: still connecting when a fetch starts, refused a moment later. */
    private fun slowRefusing(ms: Long): ServerSocket = bindLoopback(0).also { server ->
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { Thread.sleep(ms); runCatching { socket.close() } }
            }
        }
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val started = System.nanoTime()
        val out = block()
        return out to (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun `late attached relay still connecting is waited for`() = runBlocking {
        SilentWsRelay(answerEose = true).use { home ->
            SilentWsRelay(serve = listOf(note.asJson()), handshakeDelayMs = 800).use { late ->
                val e = connectedEngine(dir, home, me.secretKey().toHex())
                try {
                    e.startWindow = Duration.ZERO // long after the start
                    val held = e.attachTemporary(listOf(late.url))
                    // a short fetch (rust-nostr waits for a connecting relay within its timeout only): without the grace it
                    // would end before the handshake
                    val (events, ms) = timed { e.fetchFrom(listOf(late.url), filter, Duration.ofMillis(500), attach = false) }
                    assertEquals(1, events.size, "nothing from the relay that was still connecting ($ms ms)")
                    e.detachTemporary(held)
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `late attached refusing relay is dropped within two seconds`() = runBlocking {
        SilentWsRelay(answerEose = true).use { home ->
            // the good relay's handshake ends well after the refuser dropped its connection: the grace must not wait for the refuser
            SilentWsRelay(serve = listOf(note.asJson()), handshakeDelayMs = 1_500).use { late ->
                val e = connectedEngine(dir, home, me.secretKey().toHex())
                try {
                    e.startWindow = Duration.ZERO
                    val refuser = slowRefusing(400)
                    val refusing = "ws://127.0.0.1:${refuser.localPort}"
                    val held = e.attachTemporary(listOf(late.url, refusing))
                    val (events, ms) = timed { e.fetchFrom(listOf(late.url, refusing), filter, Duration.ofSeconds(8), attach = false) }
                    assertEquals(1, events.size)
                    assertTrue(ms < 3_000, "the fetch took $ms ms")
                    e.detachTemporary(held)
                    refuser.close()
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `old temporary relay gets no grace`() = runBlocking {
        SilentWsRelay(answerEose = true).use { home ->
            SilentWsRelay(answerEose = true, handshakeDelayMs = 3_000).use { late ->
                val e = connectedEngine(dir, home, me.secretKey().toHex())
                try {
                    e.startWindow = Duration.ZERO
                    e.lateWindow = Duration.ZERO // attached long ago
                    val held = e.attachTemporary(listOf(late.url))
                    val (_, ms) = timed { e.fetchFrom(listOf(late.url), filter, Duration.ofMillis(100), attach = false) }
                    assertTrue(ms < 300, "the fetch waited $ms ms")
                    e.detachTemporary(held)
                } finally {
                    e.close()
                }
            }
        }
    }
}
