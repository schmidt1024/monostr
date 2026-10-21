package com.monostr.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Timestamp
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Spec wave 2 B4: a lookup that hits returns at the fastest relay that has it; a miss waits as before. */
class EngineEarlyExitTest {
    @TempDir lateinit var dir: Path
    private val author = Keys.generate()
    private val missing = "f".repeat(64)
    private fun note(text: String) = EventBuilder.textNote(text).signWithKeys(author)
    private fun profile(keys: Keys, name: String, at: Long) =
        EventBuilder(Kind(0u), """{"name":"$name"}""").customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(keys)

    /** [block]'s result and how long it took in real time. */
    private suspend fun <T> timed(block: suspend () -> T): Pair<T, Long> = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val result = block()
        result to (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun `AllIds returns at the fast relay's event while another relay stays silent, and the event is stored`() = runTest {
        val n = note("wanted")
        val id = n.id().toHex()
        SilentWsRelay(serve = listOf(n.asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent))
                try {
                    val (got, ms) = timed { e.fetch(Filter().id(n.id()), Duration.ofSeconds(8), Exit.AllIds(setOf(id))) }
                    assertEquals(listOf(id), got.map { it.id().toHex() })
                    assertTrue(ms < 1_000, "took $ms ms")
                    assertNotNull(e.eventById(id))
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a miss still waits for the timeout`() = runTest {
        SilentWsRelay(answerEose = true).use { empty ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(empty, silent))
                try {
                    e.earlyExitCap = null
                    val (got, ms) = timed { e.fetch(Filter().id(EventId.parse(missing)), Duration.ofMillis(1500), Exit.AllIds(setOf(missing))) }
                    assertTrue(got.isEmpty())
                    assertTrue(ms >= 1_300, "a miss returned after $ms ms")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `AllIds with only one of two ids on the relay is not met and returns the hit at the end of the stream`() = runTest {
        val a = note("a")
        val aId = a.id().toHex()
        val bId = "b".repeat(64)
        SilentWsRelay(serve = listOf(a.asJson()), eoseDelayMs = 600).use { r ->
            val e = connectedEngine(dir, listOf(r))
            try {
                val filter = Filter().ids(listOf(a.id(), EventId.parse(bId)))
                val (res, ms) = timed { e.fetchExit(null, filter, Duration.ofMillis(1500), Exit.AllIds(setOf(aId, bId))) }
                assertTrue(!res.met)
                assertEquals(listOf(aId), res.events.map { it.id().toHex() })
                assertNotNull(e.eventById(aId))
                // ends when the relay says EOSE (600 ms after the event), neither earlier nor past the cap
                assertTrue(ms in 500..4_000, "took $ms ms")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `without exit the fetch waits for every relay as before`() = runTest {
        val n = note("wanted")
        SilentWsRelay(serve = listOf(n.asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent))
                try {
                    val (got, ms) = timed { e.fetch(Filter().id(n.id()), Duration.ofMillis(1500)) }
                    assertEquals(1, got.size)
                    assertTrue(ms >= 1_300, "the plain fetch returned after $ms ms")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `NewestPerAuthor waits the grace for a newer version from a slower relay`() = runTest {
        val alice = Keys.generate()
        SilentWsRelay(serve = listOf(profile(alice, "old", 1000).asJson())).use { r1 ->
            SilentWsRelay(serve = listOf(profile(alice, "new", 2000).asJson()), serveDelayMs = 100).use { r2 ->
                SilentWsRelay().use { silent ->
                    val e = connectedEngine(dir, listOf(r1, r2, silent))
                    try {
                        val filter = Filter().kind(Kind(0u)).author(alice.publicKey())
                        val (got, ms) = timed { e.fetch(filter, Duration.ofSeconds(8), Exit.NewestPerAuthor(setOf(alice.publicKey().toHex()))) }
                        assertTrue(ms < 2_500, "took $ms ms")
                        // the returned list carries the newer version too (arrival order), not only the store
                        assertTrue(got.any { "new" in it.content() }, "got ${got.map { it.content() }}")
                        assertTrue("new" in e.query(filter.limit(1u)).first().content())
                    } finally {
                        e.close()
                    }
                }
            }
        }
    }

    @Test
    fun `a version slower than the grace is not waited for (accepted trade-off)`() = runTest {
        val alice = Keys.generate()
        SilentWsRelay(serve = listOf(profile(alice, "old", 1000).asJson())).use { r1 ->
            SilentWsRelay(serve = listOf(profile(alice, "new", 2000).asJson()), serveDelayMs = 1_500).use { r2 ->
                val e = connectedEngine(dir, listOf(r1, r2))
                try {
                    val filter = Filter().kind(Kind(0u)).author(alice.publicKey())
                    val (got, ms) = timed { e.fetch(filter, Duration.ofSeconds(8), Exit.NewestPerAuthor(setOf(alice.publicKey().toHex()))) }
                    assertTrue(ms < 1_000, "took $ms ms")
                    assertEquals(listOf("""{"name":"old"}"""), got.map { it.content() })
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `NewestPerAuthor over two relays returns once every author was seen`() = runTest {
        val a1 = Keys.generate(); val a2 = Keys.generate(); val a3 = Keys.generate()
        SilentWsRelay(serve = listOf(profile(a1, "a1", 1000).asJson(), profile(a2, "a2", 1000).asJson())).use { r1 ->
            SilentWsRelay(serve = listOf(profile(a3, "a3", 1000).asJson()), serveDelayMs = 200).use { r2 ->
                SilentWsRelay().use { silent ->
                    val e = connectedEngine(dir, listOf(r1, r2, silent))
                    try {
                        val authors = listOf(a1, a2, a3).map { it.publicKey() }
                        val hex = authors.map { it.toHex() }.toSet()
                        val (got, ms) = timed { e.fetch(Filter().kind(Kind(0u)).authors(authors), Duration.ofSeconds(8), Exit.NewestPerAuthor(hex)) }
                        assertTrue(ms < 1_500, "took $ms ms")
                        assertEquals(hex, got.map { it.author().toHex() }.toSet())
                    } finally {
                        e.close()
                    }
                }
            }
        }
    }

    @Test
    fun `AnyEvent returns at the first event`() = runTest {
        SilentWsRelay(serve = listOf(note("exists").asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent))
                try {
                    val (got, ms) = timed { e.fetch(Filter().kind(Kind(1u)).author(author.publicKey()), Duration.ofSeconds(8), Exit.AnyEvent) }
                    assertEquals(1, got.size)
                    assertTrue(ms < 1_000, "took $ms ms")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `the same event from two relays is returned once`() = runTest {
        val n = note("twice")
        SilentWsRelay(serve = listOf(n.asJson())).use { r1 ->
            SilentWsRelay(serve = listOf(n.asJson())).use { r2 ->
                SilentWsRelay().use { silent ->
                    val e = connectedEngine(dir, listOf(r1, r2, silent))
                    try {
                        val got = e.fetch(Filter().id(n.id()), Duration.ofSeconds(8), Exit.AllIds(setOf(n.id().toHex())))
                        assertEquals(listOf(n.id().toHex()), got.map { it.id().toHex() })
                    } finally {
                        e.close()
                    }
                }
            }
        }
    }

    @Test
    fun `cancelling the caller ends the early-exit fetch and the engine keeps working`() = runTest {
        SilentWsRelay().use { silent ->
            val e = connectedEngine(dir, listOf(silent))
            try {
                val job = CoroutineScope(Dispatchers.IO).launch {
                    e.fetch(Filter().id(EventId.parse(missing)), Duration.ofSeconds(8), Exit.AllIds(setOf(missing)))
                }
                assertTrue(awaitTrue { e.openExitFetches.get() == 1 }, "the fetch never started")
                job.cancelAndJoin()
                assertEquals(0, e.openExitFetches.get())
                assertTrue(e.fetch(Filter().kind(Kind(1u)), Duration.ofMillis(300)).isEmpty())
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `closing the engine ends a waiting early-exit fetch`() = runTest {
        SilentWsRelay().use { silent ->
            val e = connectedEngine(dir, listOf(silent))
            e.earlyExitCap = null
            val running = async(Dispatchers.IO) {
                runCatching { e.fetch(Filter().id(EventId.parse(missing)), Duration.ofSeconds(20), Exit.AllIds(setOf(missing))) }
            }
            assertTrue(awaitTrue { e.openExitFetches.get() == 1 }, "the fetch never started")
            e.close()
            val outcome = withContext(Dispatchers.IO) { withTimeout(5_000) { running.await() } }
            assertTrue(outcome.exceptionOrNull() !is kotlinx.coroutines.CancellationException, "got $outcome")
            assertEquals(0, e.openExitFetches.get())
        }
    }

    @Test
    fun `no relay - an early-exit fetch returns empty at once`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())
        val (got, ms) = timed { e.fetch(Filter().id(EventId.parse(missing)), Duration.ofSeconds(8), Exit.AllIds(setOf(missing))) }
        assertTrue(got.isEmpty())
        assertTrue(ms < 300, "took $ms ms")
        e.close()
    }

    @Test
    fun `early-exit fetches end at the cap`() = runTest {
        SilentWsRelay().use { silent ->
            val e = connectedEngine(dir, listOf(silent))
            try {
                e.earlyExitCap = Duration.ofMillis(800)
                val (got, ms) = timed { e.fetch(Filter().id(EventId.parse(missing)), Duration.ofSeconds(8), Exit.AllIds(setOf(missing))) }
                assertTrue(got.isEmpty())
                assertTrue(ms in 600..3_000, "took $ms ms")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `the default cap follows the Task 0 CLOSE finding`() {
        // Task 0 results: no CLOSE on close/cancel → 5 s (spec D8); a CLOSE goes out → no cap.
        assertEquals(Duration.ofSeconds(5), NostrEngine.EARLY_EXIT_CAP)
    }

    @Test
    fun `fetchFrom with exit attaches the relays it names`() = runTest {
        val n = note("temporary")
        SilentWsRelay(serve = listOf(n.asJson())).use { fast ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())
            e.connect()
            try {
                val got = e.fetchFrom(listOf(fast.url), Filter().id(n.id()), Duration.ofSeconds(8), exit = Exit.AllIds(setOf(n.id().toHex())))
                assertEquals(1, got.size)
                assertEquals(listOf(fast.url), e.temporaryRelayUrls())
                e.detachTemporary(listOf(fast.url))
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a met early exit is marked for the perf build, without ids or relays`() = runTest {
        val n = note("marked")
        val id = n.id().toHex()
        SilentWsRelay(serve = listOf(n.asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent))
                val lines = CopyOnWriteArrayList<String>()
                val enabled = PerfTrace.enabled
                val sink = PerfTrace.sink
                PerfTrace.sink = { lines += it }
                PerfTrace.enabled = true
                try {
                    e.fetch(Filter().id(n.id()), Duration.ofSeconds(8), Exit.AllIds(setOf(id)))
                } finally {
                    PerfTrace.enabled = enabled
                    PerfTrace.sink = sink
                    e.close()
                }
                assertTrue(lines.any { Regex("early exit AllIds \\d+$").containsMatchIn(it) }, "lines: $lines")
                assertTrue(lines.none { id in it || fast.url in it })
            }
        }
    }
}
