package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.connectedEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class ForeignRelaysTest {
    @TempDir lateinit var dir: Path
    /** Real dispatcher: the linger timer and the engine's IO run in real time, virtual test time would skip them. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** An engine whose normal set is [own] (one loopback relay), and [n] foreign loopback relays. */
    private suspend fun setup(n: Int, block: suspend (NostrEngine, SilentWsRelay, List<SilentWsRelay>) -> Unit) {
        val own = SilentWsRelay()
        val foreign = List(n) { SilentWsRelay() }
        val engine = connectedEngine(dir, own)
        try {
            block(engine, own, foreign)
        } finally {
            engine.close()
            foreign.forEach { it.shutdown() }
            own.shutdown()
        }
    }

    @Test
    fun `lease beyond the cap waits then takes a lingering slot`() = runTest {
        setup(3) { e, _, (a, b, c) ->
            val pool = ForeignRelays(e, io, cap = 2, lingerMs = 60_000)
            assertEquals(listOf(a.url, b.url), pool.lease(listOf(a.url, b.url)))
            pool.release(listOf(a.url))
            val got = withContext(Dispatchers.IO) { pool.lease(listOf(c.url), wait = 1.seconds) }
            assertEquals(listOf(c.url), got)
            assertEquals(setOf(b.url, c.url), pool.attached())
            assertFalse(a.url in e.temporaryRelayUrls(), "A still attached: ${e.temporaryRelayUrls()}")
            assertEquals(setOf(b.url, c.url), e.temporaryRelayUrls().toSet())
            pool.close()
            assertTrue(e.temporaryRelayUrls().isEmpty())
        }
    }

    @Test
    fun `a full budget without lingering relays returns nothing at once`() = runTest {
        setup(2) { e, _, (a, b) ->
            val pool = ForeignRelays(e, io, cap = 1, lingerMs = 60_000)
            assertEquals(listOf(a.url), pool.lease(listOf(a.url)))
            assertEquals(emptyList<String>(), pool.lease(listOf(b.url)))
            assertEquals(setOf(a.url), pool.attached())
            // a held relay that is asked for again fits although the budget is full
            assertEquals(listOf(a.url), pool.lease(listOf(a.url, b.url)))
            pool.close()
        }
    }

    @Test
    fun `a waiting lease gets the slot a release frees`() = runTest {
        setup(2) { e, _, (a, b) ->
            val pool = ForeignRelays(e, io, cap = 1, lingerMs = 60_000)
            pool.lease(listOf(a.url))
            val waiting = CompletableDeferred<List<String>>()
            io.launch { waiting.complete(pool.lease(listOf(b.url), wait = 5.seconds)) }
            Thread.sleep(300)
            assertFalse(waiting.isCompleted)
            pool.release(listOf(a.url))
            assertEquals(listOf(b.url), withContext(Dispatchers.IO) { waiting.await() })
            assertEquals(setOf(b.url), pool.attached())
            assertEquals(listOf(b.url), e.temporaryRelayUrls())
            pool.close()
        }
    }

    @Test
    fun `lingering relay is reused without a new connection`() = runTest {
        setup(1) { e, _, (a) ->
            val pool = ForeignRelays(e, io, lingerMs = 60_000)
            assertEquals(listOf(a.url), pool.lease(listOf(a.url)))
            assertTrue(awaitTrue { a.connections == 1 }, "A never connected")
            pool.release(listOf(a.url))
            assertEquals(setOf(a.url), pool.attached())
            assertEquals(listOf(a.url), pool.lease(listOf(a.url)))
            Thread.sleep(300)
            assertEquals(1, a.connections)
            pool.close()
        }
    }

    @Test
    fun `linger expires`() = runTest {
        setup(1) { e, _, (a) ->
            val pool = ForeignRelays(e, io, lingerMs = 100)
            pool.lease(listOf(a.url))
            pool.release(listOf(a.url))
            Thread.sleep(300)
            assertTrue(awaitTrue(1000) { pool.attached().isEmpty() }, "still attached: ${pool.attached()}")
            assertTrue(awaitTrue(1000) { e.temporaryRelayUrls().isEmpty() }, "engine: ${e.temporaryRelayUrls()}")
        }
    }

    @Test
    fun `two holders keep a relay until both released`() = runTest {
        setup(1) { e, _, (a) ->
            val pool = ForeignRelays(e, io, lingerMs = 100)
            pool.lease(listOf(a.url))
            pool.lease(listOf(a.url))
            pool.release(listOf(a.url))
            Thread.sleep(300)
            assertEquals(setOf(a.url), pool.attached())
            assertEquals(listOf(a.url), e.temporaryRelayUrls())
            pool.release(listOf(a.url))
            assertTrue(awaitTrue(1000) { e.temporaryRelayUrls().isEmpty() }, "engine: ${e.temporaryRelayUrls()}")
            assertTrue(pool.attached().isEmpty())
        }
    }

    @Test
    fun `own relays are not counted`() = runTest {
        setup(0) { e, own, _ ->
            val pool = ForeignRelays(e, io, cap = 1)
            assertEquals(listOf(own.url), pool.lease(listOf("${own.url}/", "not a url")))
            assertTrue(pool.attached().isEmpty())
            assertTrue(e.temporaryRelayUrls().isEmpty())
            pool.release(listOf(own.url))
            assertTrue(own.url in e.relayUrls())
        }
    }

    @Test
    fun `three pages in a row stay within the cap`() = runTest {
        setup(15) { e, _, foreign ->
            val pool = ForeignRelays(e, io, cap = 8, lingerMs = 60_000)
            for (page in foreign.chunked(5)) {
                val urls = page.map { it.url }
                assertEquals(urls, pool.lease(urls))
                assertTrue(pool.attached().size <= 8, "attached: ${pool.attached()}")
                assertTrue(e.temporaryRelayUrls().size <= 8, "engine: ${e.temporaryRelayUrls()}")
                assertTrue(pool.attached().containsAll(urls))
                pool.release(urls)
            }
            assertEquals(8, pool.attached().size)
            pool.close()
            assertTrue(pool.attached().isEmpty())
            assertTrue(e.temporaryRelayUrls().isEmpty())
        }
    }

    @Test
    fun `cancelled waiter takes nothing`() = runTest {
        setup(2) { e, _, (a, b) ->
            val pool = ForeignRelays(e, io, cap = 1, lingerMs = 100)
            pool.lease(listOf(a.url))
            val waiter = io.launch { pool.lease(listOf(b.url), wait = 10.seconds) }
            Thread.sleep(300)
            waiter.cancelAndJoin()
            assertEquals(setOf(a.url), pool.attached())
            pool.release(listOf(a.url))
            assertTrue(awaitTrue(1000) { pool.attached().isEmpty() }, "still attached: ${pool.attached()}")
            assertTrue(awaitTrue(1000) { e.temporaryRelayUrls().isEmpty() }, "engine: ${e.temporaryRelayUrls()}")
            assertEquals(0, b.connections)
        }
    }

    @Test
    fun `a lease cancelled during the attach leaks no reference`() = runTest {
        setup(1) { e, _, (a) ->
            val pool = ForeignRelays(e, io, lingerMs = 100)
            val started = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            e.poolProbe = { at -> if (at == "added") { started.complete(Unit); proceed.await() } }
            val leasing = io.launch { pool.lease(listOf(a.url)) }
            withContext(Dispatchers.IO) { started.await() }
            leasing.cancel()
            proceed.complete(Unit)
            leasing.join()
            e.poolProbe = null
            assertTrue(awaitTrue(1000) { e.temporaryRelayUrls().isEmpty() }, "engine: ${e.temporaryRelayUrls()}")
            assertTrue(pool.attached().isEmpty(), "pool: ${pool.attached()}")
        }
    }

    @Test
    fun `dropLingering detaches lingering relays at once and keeps held ones`() = runTest {
        // spec 2026-10-23 §7 / §3: after a network switch (or the switch turned off) no idle foreign relay stays on a dead socket
        setup(2) { e, _, (a, b) ->
            val pool = ForeignRelays(e, io, lingerMs = 60_000)
            assertEquals(listOf(a.url, b.url), pool.lease(listOf(a.url, b.url)))
            assertTrue(awaitTrue { a.connections == 1 }, "A never connected")
            pool.release(listOf(a.url))
            assertEquals(setOf(a.url, b.url), pool.attached())
            pool.dropLingering()
            assertEquals(setOf(b.url), pool.attached())
            assertEquals(listOf(b.url), e.temporaryRelayUrls())
            // the next lease attaches it again: a fresh connection
            assertEquals(listOf(a.url), pool.lease(listOf(a.url)))
            assertTrue(awaitTrue { a.connections == 2 }, "connections: ${a.connections}")
            pool.release(listOf(a.url, b.url))
            pool.close()
            assertTrue(e.temporaryRelayUrls().isEmpty())
        }
    }
}
