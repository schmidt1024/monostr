package com.monostr.nostr.net

import com.monostr.nostr.DetachedSender
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import rust.nostr.sdk.RelayUrl
import java.time.Duration

/** Backlog 99 review I1: a DM's recipient leg under Tor must give slower inboxes more than the default grace. */
class ConnectWaitTest {
    private val a = RelayUrl.parse("wss://a.example")
    private val b = RelayUrl.parse("wss://b.example")

    /** [a] connects at once, [b] [lateMs] later; returns how many were connected when the wait ended. */
    private fun connectedAtEnd(graceMs: Long, lateMs: Long): Int = runBlocking {
        val start = System.nanoTime()
        val up: (RelayUrl) -> Boolean = { it == a || (System.nanoTime() - start) / 1_000_000 >= lateMs }
        awaitConnected(listOf(a, b), Duration.ofSeconds(10), graceMs) { up(it) }
        listOf(a, b).count(up)
    }

    @Test
    fun `the default grace leaves a relay that connects a second later behind`() {
        assertEquals(1, connectedAtEnd(FIRST_CONNECTED_GRACE_MS, 1_000))
    }

    @Test
    fun `the DM grace waits for a relay that connects a second later`() {
        assertEquals(2, connectedAtEnd(DetachedSender.DM_GRACE_MS, 1_000))
    }
}
