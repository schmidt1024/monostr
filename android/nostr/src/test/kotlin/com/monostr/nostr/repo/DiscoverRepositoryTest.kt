package com.monostr.nostr.repo

import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.connectedEngine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path
import java.time.Duration

/** Spec 2026-10-27 §3, §8: the four curated lists of the monostr account, local first, one request, an hourly gate. */
class DiscoverRepositoryTest {
    @TempDir lateinit var dir: Path

    // a stand-in for the monostr account: the repository is told its author
    private val author = Keys.generate()
    private val other = Keys.generate()
    private val member = Keys.generate().publicKey().toHex()

    private fun list(d: String, keys: Keys = author, at: Long = 1_000) = EventBuilder(Kind(30000u), "")
        .tags(listOf(Tag.parse(listOf("d", d)), Tag.parse(listOf("title", "T $d")), Tag.parse(listOf("p", member))))
        .customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(keys)

    private fun repo(e: com.monostr.nostr.NostrEngine, clock: () -> Long = { 10_000 }) =
        NostrDiscoverRepository(e, extraRelays = emptyList(), author = author.publicKey().toHex(), fetchTimeout = Duration.ofSeconds(2), now = clock)

    private fun isListRequest(req: String) = "\"kinds\":[30000]" in req

    @Test
    fun `local returns stored lists in fixed order and skips missing ones`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                e.save(list("tech")); e.save(list("monero"))
                assertEquals(listOf("monero", "tech"), repo(e).local().map { it.address.d })
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `refresh asks once for all four d tags and stores what comes`() = runTest {
        val served: List<Event> = listOf(list("popular"), list("art"))
        SilentWsRelay(recordRequests = true, serveFor = { req -> if (isListRequest(req)) served.map { it.asJson() } else emptyList() }).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                assertTrue(repo(e).refresh())
                val asked = relay.requests.filter(::isListRequest)
                assertEquals(1, asked.size, "requests: ${relay.requests}")
                for (d in DiscoverLists.D_TAGS) assertTrue("\"$d\"" in asked[0], "missing $d in ${asked[0]}")
                assertTrue(author.publicKey().toHex() in asked[0])
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `refresh within the gate does not ask again`() = runTest {
        SilentWsRelay(recordRequests = true, serveFor = { req -> if (isListRequest(req)) listOf(list("monero").asJson()) else emptyList() }).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                var now = 10_000L
                val r = repo(e) { now }
                r.refresh(); r.refresh()
                assertEquals(1, relay.requests.count(::isListRequest))
                now += 3_601
                r.refresh()
                assertEquals(2, relay.requests.count(::isListRequest))
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a refresh that found nothing does not hold the gate`() = runTest {
        // final review I1: offline at start, «Retry» once online must ask again at once
        var serve = false
        SilentWsRelay(recordRequests = true, answerEose = true, serveFor = { req -> if (serve && isListRequest(req)) listOf(list("monero").asJson()) else emptyList() }).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                val r = repo(e)
                assertEquals(false, r.refresh())
                serve = true
                assertEquals(true, r.refresh())
                assertEquals(2, relay.requests.count(::isListRequest))
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a list by another author with the same d is ignored and the newest version wins`() = runTest {
        SilentWsRelay(answerEose = true).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                e.save(list("art", keys = other))
                e.save(list("monero", at = 1_000)); e.save(list("monero", at = 2_000))
                val got = repo(e).local()
                assertEquals(listOf("monero"), got.map { it.address.d })
                assertEquals(2_000L, got[0].createdAt)
            } finally {
                e.close()
            }
        }
    }
}
