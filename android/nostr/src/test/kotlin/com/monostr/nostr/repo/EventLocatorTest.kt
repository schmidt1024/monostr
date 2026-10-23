package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.connectedEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class EventLocatorTest {
    @TempDir lateinit var dir: Path
    /** Real dispatcher: leases, linger and the engine's IO run in real time, virtual test time would skip them. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val alice = Keys.generate()
    private val bob = Keys.generate()
    private var time = 1_000_000L
    @Volatile private var on = true
    private val any: (String) -> Boolean = { true }

    private fun note(text: String) = EventBuilder.textNote(text).signWithKeys(bob)
    private fun relayList(k: Keys, vararg tags: List<String>) = EventBuilder(Kind(10002u), "").tags(tags.map { Tag.parse(it) }).signWithKeys(k)

    /** A foreign relay that serves [events] to a REQ naming their id, else nothing (then EOSE). */
    private fun serving(vararg events: Event, delayMs: Long = 0) = SilentWsRelay(
        serveFor = { req -> events.filter { it.id().toHex() in req }.map { it.asJson() } },
        serveDelayMs = delayMs,
    )

    /** An indexer that serves the kind 10002 in [lists] to a REQ for kind 10002. */
    private fun indexer(vararg lists: Event, delayMs: Long = 0) = SilentWsRelay(
        serveFor = { req -> if ("10002" in req) lists.map { it.asJson() } else emptyList() },
        serveDelayMs = delayMs,
    )

    private class Setup(val engine: NostrEngine, val pool: ForeignRelays, val locator: NostrEventLocator)

    /** An engine whose own relay is empty, the shared pool, and a locator asking [indexer] for kind 10002. */
    private suspend fun setup(indexer: SilentWsRelay? = null, lingerMs: Long = 60_000, cap: Int = 8, block: suspend (Setup) -> Unit) {
        val own = SilentWsRelay(answerEose = true)
        val engine = connectedEngine(dir, own)
        val pool = ForeignRelays(engine, io, cap = cap, lingerMs = lingerMs)
        val authors = NostrAuthorRelaysRepository(
            engine, indexers = listOfNotNull(indexer?.url), now = { time }, fetchTimeout = Duration.ofSeconds(3), foreign = pool, relayFilter = any,
        )
        val locator = NostrEventLocator(engine, authors, pool, enabled = { on }, scope = io, now = { time }, timeout = Duration.ofSeconds(3), relayFilter = any)
        try {
            withContext(Dispatchers.IO) { block(Setup(engine, pool, locator)) }
        } finally {
            pool.close()
            engine.close()
            own.shutdown()
        }
    }

    @Test
    fun `hint relay finds the note`() = runTest {
        val ev = note("from the hint")
        val id = ev.id().toHex()
        serving(ev).use { f1 ->
            setup(lingerMs = 500) { s ->
                assertEquals(setOf(id), s.locator.find(listOf(Wanted(id, relays = listOf(f1.url)))))
                assertTrue(s.engine.eventById(id) != null)
                // released: lingering only, then detached once the linger ends
                assertEquals(setOf(f1.url), s.pool.attached())
                assertTrue(awaitTrue(3000) { s.pool.attached().isEmpty() }, "still attached: ${s.pool.attached()}")
            }
        }
    }

    @Test
    fun `author write relay finds the note`() = runTest {
        val ev = note("from the outbox")
        val id = ev.id().toHex()
        serving(ev).use { f2 ->
            indexer(relayList(bob, listOf("r", f2.url, "write"))).use { idx ->
                setup(idx) { s ->
                    assertEquals(setOf(id), s.locator.find(listOf(Wanted(id, authors = listOf(bob.publicKey().toHex())))))
                    assertTrue(s.engine.eventById(id) != null)
                    assertEquals(1, f2.requests.size)
                }
            }
        }
    }

    @Test
    fun `hint is asked before the author lookup finishes`() = runTest {
        val ev = note("fast hint")
        val id = ev.id().toHex()
        serving(ev).use { f1 ->
            indexer(relayList(bob, listOf("r", "wss://w.example", "write")), delayMs = 2000).use { idx ->
                setup(idx) { s ->
                    val start = System.currentTimeMillis()
                    val got = s.locator.find(listOf(Wanted(id, relays = listOf(f1.url), authors = listOf(bob.publicKey().toHex()))))
                    val ms = System.currentTimeMillis() - start
                    assertEquals(setOf(id), got)
                    assertTrue(ms < 1500, "took $ms ms")
                }
            }
        }
    }

    @Test
    fun `switch off asks nobody and marks nothing`() = runTest {
        val ev = note("off")
        val id = ev.id().toHex()
        serving(ev).use { f1 ->
            setup { s ->
                on = false
                assertFalse(s.locator.searchable(id))
                assertEquals(emptySet<String>(), s.locator.find(listOf(Wanted(id, relays = listOf(f1.url)))))
                assertEquals(0, f1.requests.size)
                assertTrue(s.pool.attached().isEmpty())
                on = true
                assertTrue(s.locator.searchable(id))
            }
        }
    }

    @Test
    fun `not found is marked ten minutes`() = runTest {
        val id = "ab".repeat(32)
        serving().use { f1 ->
            setup { s ->
                val w = listOf(Wanted(id, relays = listOf(f1.url)))
                assertEquals(emptySet<String>(), s.locator.find(w))
                assertEquals(1, f1.requests.size)
                assertFalse(s.locator.searchable(id))
                assertEquals(emptySet<String>(), s.locator.find(w))
                assertEquals(1, f1.requests.size)
                time += 601
                assertTrue(s.locator.searchable(id))
                assertEquals(emptySet<String>(), s.locator.find(w))
                assertEquals(2, f1.requests.size)
            }
        }
    }

    @Test
    fun `forget clears marks and emits retries`() = runTest {
        val id = "cd".repeat(32)
        serving().use { f1 ->
            setup { s ->
                val w = listOf(Wanted(id, relays = listOf(f1.url)))
                s.locator.find(w)
                assertFalse(s.locator.searchable(id))
                val retry = async(start = CoroutineStart.UNDISPATCHED) { s.locator.retries.first() }
                s.locator.forget()
                withTimeout(2000) { retry.await() }
                assertTrue(s.locator.searchable(id))
                s.locator.find(w)
                assertEquals(2, f1.requests.size)
            }
        }
    }

    @Test
    fun `forget without notify clears marks silently`() = runTest {
        val id = "ce".repeat(32)
        serving().use { f1 ->
            setup { s ->
                s.locator.find(listOf(Wanted(id, relays = listOf(f1.url))))
                assertFalse(s.locator.searchable(id))
                val retry = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(300) { s.locator.retries.first() } }
                s.locator.forget(notify = false)
                assertNull(retry.await())
                assertTrue(s.locator.searchable(id))
            }
        }
    }

    @Test
    fun `same id twice runs once`() = runTest {
        val ev = note("once")
        val id = ev.id().toHex()
        serving(ev, delayMs = 500).use { f1 ->
            setup { s ->
                val w = listOf(Wanted(id, relays = listOf(f1.url)))
                val both = listOf(io.async { s.locator.find(w) }, io.async { s.locator.find(w) }).awaitAll()
                assertEquals(listOf(setOf(id), setOf(id)), both)
                assertEquals(1, f1.requests.size)
            }
        }
    }

    @Test
    fun `crafted note stays within limits`() = runTest {
        val id = "ef".repeat(32)
        val relays = List(20) { SilentWsRelay(answerEose = true) }
        val keys = List(50) { Keys.generate() }
        try {
            indexer(*keys.map { k -> relayList(k, listOf("r", relays[0].url, "write")) }.toTypedArray()).use { idx ->
                setup(idx) { s ->
                    val peak = AtomicInteger()
                    val sampler = io.launch { while (isActive) { peak.accumulateAndGet(s.pool.attached().size, ::maxOf); delay(5) } }
                    s.locator.find(listOf(Wanted(id, relays = relays.map { it.url }, authors = keys.map { it.publicKey().toHex() })))
                    sampler.cancel()
                    peak.accumulateAndGet(s.pool.attached().size, ::maxOf)
                    val asked = relays.count { it.requests.isNotEmpty() }
                    assertTrue(asked <= 3, "asked $asked relays")
                    assertTrue(peak.get() <= 8, "peak ${peak.get()}")
                    // at most three authors per wanted note reach the lookup
                    idx.requests.forEach { req -> assertTrue(keys.count { it.publicKey().toHex() in req } <= 3, req) }
                }
            }
        } finally {
            relays.forEach { it.shutdown() }
        }
    }

    @Test
    fun `crafted note with many authors stays within limits`() = runTest {
        val id = "fe".repeat(32)
        val relays = List(12) { SilentWsRelay(answerEose = true) }
        val keys = List(50) { Keys.generate() }
        // every author names three write relays of its own, twelve distinct ones in all
        val lists = keys.mapIndexed { i, k -> relayList(k, *(0 until 3).map { j -> listOf("r", relays[(i * 3 + j) % 12].url, "write") }.toTypedArray()) }
        try {
            indexer(*lists.toTypedArray()).use { idx ->
                setup(idx) { s ->
                    val peak = AtomicInteger()
                    val sampler = io.launch { while (isActive) { peak.accumulateAndGet(s.pool.attached().size, ::maxOf); delay(5) } }
                    s.locator.find(listOf(Wanted(id, relays = listOf(relays[11].url), authors = keys.map { it.publicKey().toHex() })))
                    sampler.cancel()
                    peak.accumulateAndGet(s.pool.attached().size, ::maxOf)
                    val lookups = idx.requests.filter { "10002" in it }
                    assertTrue(lookups.isNotEmpty(), "the author lookup never ran")
                    lookups.forEach { req -> assertTrue(keys.count { it.publicKey().toHex() in req } <= 3, req) }
                    val asked = relays.count { r -> r.requests.any { id in it } }
                    assertEquals(3, asked, "asked ${relays.map { it.requests.size }}")
                    assertTrue(relays[11].requests.any { id in it }, "the hint relay was not asked")
                    assertTrue(peak.get() <= 8, "peak ${peak.get()}")
                }
            }
        } finally {
            relays.forEach { it.shutdown() }
        }
    }

    @Test
    fun `a full budget makes the hint wait for room`() = runTest {
        val ev = note("after the wait")
        val id = ev.id().toHex()
        SilentWsRelay().use { blocker ->
            serving(ev).use { f1 ->
                setup(cap = 1) { s ->
                    assertEquals(listOf(blocker.url), s.pool.lease(listOf(blocker.url)))
                    val running = io.async { s.locator.find(listOf(Wanted(id, relays = listOf(f1.url)))) }
                    delay(300)
                    assertEquals(0, f1.requests.size)
                    s.pool.release(listOf(blocker.url))
                    assertEquals(setOf(id), running.await())
                    assertEquals(1, f1.requests.size)
                }
            }
        }
    }

    @Test
    fun `a budget that stays full marks nothing`() = runTest {
        val id = "34".repeat(32)
        SilentWsRelay().use { blocker ->
            serving().use { f1 ->
                setup(cap = 1) { s ->
                    assertEquals(listOf(blocker.url), s.pool.lease(listOf(blocker.url)))
                    assertEquals(emptySet<String>(), s.locator.find(listOf(Wanted(id, relays = listOf(f1.url)))))
                    assertEquals(0, f1.requests.size)
                    assertTrue(s.locator.searchable(id))
                    s.pool.release(listOf(blocker.url))
                }
            }
        }
    }

    @Test
    fun `switch off during a round`() = runTest {
        val first = note("first")
        val second = note("second")
        serving(first, second, delayMs = 800).use { f1 ->
            setup { s ->
                val running = io.async { s.locator.find(listOf(Wanted(first.id().toHex(), relays = listOf(f1.url)))) }
                assertTrue(awaitTrue { f1.requests.size == 1 })
                on = false
                assertEquals(setOf(first.id().toHex()), running.await())
                assertEquals(emptySet<String>(), s.locator.find(listOf(Wanted(second.id().toHex(), relays = listOf(f1.url)))))
                assertEquals(1, f1.requests.size)
                on = true
                assertTrue(s.locator.searchable(second.id().toHex()))
            }
        }
    }

    @Test
    fun `repliesFrom asks the read relays`() = runTest {
        val id = "12".repeat(32)
        SilentWsRelay(answerEose = true).use { f3 ->
            setup { s ->
                s.engine.save(relayList(alice, listOf("r", f3.url, "read")))
                assertTrue(s.locator.repliesFrom(alice.publicKey().toHex(), listOf(id)))
                assertEquals(1, f3.requests.size)
                assertTrue("\"#e\"" in f3.requests[0] && id in f3.requests[0], f3.requests[0])
                on = false
                assertFalse(s.locator.repliesFrom(alice.publicKey().toHex(), listOf(id)))
                assertEquals(1, f3.requests.size)
            }
        }
    }

    @Test
    fun `an offline round marks nothing`() = runTest {
        // spec 2026-10-23 §4.3: a relay that could not be connected to was not asked
        val id = "56".repeat(32)
        val dead = SilentWsRelay().also { it.shutdown() }.url
        setup { s ->
            assertEquals(emptySet<String>(), s.locator.find(listOf(Wanted(id, relays = listOf(dead)))))
            assertTrue(s.locator.searchable(id), "marked although no relay was reached")
        }
    }

    @Test
    fun `an id whose planned relays did not all get a slot stays unmarked`() = runTest {
        // spec 2026-10-23 §4.3/§4.4: one hint asked, the other found no room in the budget
        val id = "78".repeat(32)
        SilentWsRelay().use { blocker ->
            serving().use { f1 ->
                serving().use { f2 ->
                    setup(cap = 2) { s ->
                        assertEquals(listOf(blocker.url), s.pool.lease(listOf(blocker.url)))
                        assertEquals(emptySet<String>(), s.locator.find(listOf(Wanted(id, relays = listOf(f1.url, f2.url)))))
                        assertEquals(1, f1.requests.size + f2.requests.size, "f1 ${f1.requests} f2 ${f2.requests}")
                        assertTrue(s.locator.searchable(id), "marked although a planned relay was not asked")
                        s.pool.release(listOf(blocker.url))
                    }
                }
            }
        }
    }

    @Test
    fun `phase one leaves a slot for the indexers`() = runTest {
        // spec 2026-10-23 §4.2: the hint leases take at most cap-1 slots, the ones most open ids need first
        val one = "9a".repeat(32)
        val two = "9b".repeat(32)
        serving().use { shared ->
            serving().use { single ->
                indexer(relayList(bob, listOf("r", "wss://w.example", "write"))).use { idx ->
                    setup(idx, cap = 2) { s ->
                        val bobHex = bob.publicKey().toHex()
                        s.locator.find(listOf(
                            Wanted(one, relays = listOf(single.url, shared.url), authors = listOf(bobHex)),
                            Wanted(two, relays = listOf(shared.url)),
                        ))
                        assertTrue(shared.requests.any { one in it && two in it }, "shared: ${shared.requests}")
                        assertTrue(idx.requests.any { "10002" in it }, "the indexer got no slot: ${idx.requests}")
                    }
                }
            }
        }
    }

    @Test
    fun `searchable for many ids reads the switch once`() = runTest {
        val calls = AtomicInteger()
        val own = SilentWsRelay(answerEose = true)
        val engine = connectedEngine(dir, own)
        val pool = ForeignRelays(engine, io)
        try {
            val authors = NostrAuthorRelaysRepository(engine, indexers = emptyList(), foreign = pool, relayFilter = any)
            val locator = NostrEventLocator(engine, authors, pool, enabled = { calls.incrementAndGet(); true }, scope = io, now = { time }, relayFilter = any)
            val ids = listOf("a1".repeat(32), "a2".repeat(32), "A3".repeat(32), "not hex")
            assertEquals(setOf("a1".repeat(32), "a2".repeat(32), "A3".repeat(32)), locator.searchable(ids))
            assertEquals(1, calls.get())
        } finally {
            pool.close()
            engine.close()
            own.shutdown()
        }
    }
}
