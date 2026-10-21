package com.monostr.nostr

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** Spec wave 2 B1: the engine's view of its relays equals the pool after every change (baseline before the cache, guard after it). */
class EngineRelaySetTest {
    @TempDir lateinit var dir: Path

    private suspend fun NostrEngine.poolNormal(): Set<String> =
        client.relays().keys.map { it.toString().trimEnd('/') }.toSet() - temporaryRelayUrls().toSet()

    private suspend fun NostrEngine.check(step: String) = assertEquals(poolNormal(), relayUrls().toSet(), "after $step")

    @Test
    fun `relayUrls is the pool minus the temporary relays after every change of the pool`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), null, listOf("wss://a.example", "wss://b.example/"))
        e.check("create")
        e.addRelays(listOf("wss://c.example")); e.check("addRelays")
        e.removeRelays(listOf("wss://a.example")); e.check("removeRelays")
        e.attachTemporary(listOf("wss://t.example")); e.check("attachTemporary")
        assertFalse("wss://t.example" in e.relayUrls())
        e.addRelays(listOf("wss://t.example")); e.check("promotion")
        assertTrue("wss://t.example" in e.relayUrls())
        e.attachTemporary(listOf("wss://u.example")); e.detachTemporary(listOf("wss://u.example")); e.check("detachTemporary")
        assertEquals(setOf("wss://b.example", "wss://c.example", "wss://t.example"), e.relayUrls().toSet())
        e.close()
    }

    @Test
    fun `a connected temporary relay counts for connectedRelayUrls only, and leaves with its detach`() = runTest {
        SilentWsRelay().use { normal ->
            SilentWsRelay().use { search ->
                val e = connectedEngine(dir, normal)
                try {
                    e.attachTemporary(listOf(search.url))
                    assertTrue(awaitTrue { search.url in e.connectedRelayUrls() }, "search relay never connected")
                    assertEquals(setOf(normal.url), e.connectedNormalRelayUrls())
                    assertEquals(setOf(normal.url, search.url), e.connectedRelayUrls())
                    e.detachTemporary(listOf(search.url))
                    assertEquals(setOf(normal.url), e.connectedRelayUrls())
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `relayUrls comes from the cached set without a call into rust`() = runTest {
        val dispatches = AtomicInteger(0)
        val counting = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                dispatches.incrementAndGet()
                Dispatchers.IO.dispatch(context, block)
            }
        }
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), null, listOf("wss://a.example"), ffi = counting)
        dispatches.set(0)
        repeat(10) { assertEquals(listOf("wss://a.example"), e.relayUrls()) }
        assertEquals(0, dispatches.get(), "relayUrls went through the ffi dispatcher")
        e.close()
    }

    @Test
    fun `a relay sendTo adds for one send never becomes a target of fetch or subscribe`() = runTest {
        SilentWsRelay(answerEose = true).use { normal ->
            SilentWsRelay(acceptEvents = true, okDelayMs = 2_000, recordRequests = true).use { watcher ->
                val e = connectedEngine(dir, normal)
                try {
                    val intent = EventBuilder.textNote("an intent").signWithKeys(Keys.generate())
                    e.save(intent)
                    val send = async(Dispatchers.IO) { e.sendTo(listOf(watcher.url), intent.id().toHex()) }
                    // the watcher's relay is in the pool now, waiting for its OK
                    assertTrue(awaitTrue { watcher.events.isNotEmpty() }, "the event never reached the watcher relay")
                    assertFalse(watcher.url in e.relayUrls(), "relayUrls: ${e.relayUrls()}")
                    e.fetch(Filter().kind(Kind(1u)), Duration.ofMillis(500))
                    val sub = e.subscribe(Filter().kind(Kind(1u)))
                    Thread.sleep(300)
                    assertTrue(watcher.requests.isEmpty(), "REQs on the watcher relay: ${watcher.requests}")
                    e.unsubscribe(sub)
                    assertTrue(send.await().sentToAny)
                    assertFalse(watcher.url in e.relayUrls())
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a refresh forced while a temporary relay is added or removed never puts it in the normal set`() = runTest {
        SilentWsRelay(answerEose = true).use { normal ->
            SilentWsRelay(answerEose = true, recordRequests = true).use { temp ->
                val e = connectedEngine(dir, normal)
                try {
                    val seen = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
                    // right after the pool changed (and right before the relay leaves it) another coroutine refreshes the set
                    e.poolProbe = { at ->
                      if (at == "added" || at == "removing") {
                        e.refreshRelaySetForTest()
                        seen += e.relayUrls()
                        e.subscribe(Filter().kind(Kind(1u))).also { e.unsubscribe(it) }
                      }
                    }
                    e.attachTemporary(listOf(temp.url))
                    e.detachTemporary(listOf(temp.url))
                    e.poolProbe = null
                    assertTrue(seen.size >= 2, "the probe ran ${seen.size} times")
                    seen.forEach { assertFalse(temp.url in it, "normal set: $it") }
                    Thread.sleep(300)
                    assertTrue(temp.requests.isEmpty(), "REQs on the temporary relay: ${temp.requests}")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a cancelled addRelays or removeRelays still refreshes the set`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), null, listOf("wss://a.example", "wss://b.example"))
        e.poolProbe = { at ->
            if (at == "added" || at == "removed") {
                currentCoroutineContext().job.cancel()
                currentCoroutineContext().ensureActive()
            }
        }
        val add = launch(Dispatchers.IO) { e.addRelays(listOf("wss://c.example")) }
        add.join()
        assertTrue("wss://c.example" in e.relayUrls(), "after a cancelled addRelays: ${e.relayUrls()}")
        val remove = launch(Dispatchers.IO) { e.removeRelays(listOf("wss://a.example")) }
        remove.join()
        assertFalse("wss://a.example" in e.relayUrls(), "after a cancelled removeRelays: ${e.relayUrls()}")
        e.poolProbe = null
        e.close()
    }

    @Test
    fun `after a refresh that failed the next read reflects the pool`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), null, listOf("wss://a.example"))
        e.failNextRefresh = true
        e.addRelays(listOf("wss://c.example"))
        assertEquals(setOf("wss://a.example", "wss://c.example"), e.relayUrls().toSet())
        e.close()
    }

    @Test
    fun `an attachTemporary cancelled right after rust added the relay leaves no relay behind`() = runTest {
        SilentWsRelay(answerEose = true).use { normal ->
            SilentWsRelay(answerEose = true, recordRequests = true).use { temp ->
                val e = connectedEngine(dir, normal)
                try {
                    e.poolProbe = { at ->
                        if (at == "add-returned") {
                            currentCoroutineContext().job.cancel()
                            currentCoroutineContext().ensureActive()
                        }
                    }
                    launch(Dispatchers.IO) { e.attachTemporary(listOf(temp.url)) }.join()
                    e.poolProbe = null
                    val sub = e.subscribe(Filter().kind(Kind(1u)))
                    e.unsubscribe(sub)
                    assertFalse(temp.url in e.client.relays().keys.map { it.toString().trimEnd('/') }, "still in the pool")
                    assertFalse(temp.url in e.relayUrls(), "relayUrls: ${e.relayUrls()}")
                    assertTrue(e.temporaryRelayUrls().isEmpty(), "marks: ${e.temporaryRelayUrls()}")
                    Thread.sleep(300)
                    assertTrue(temp.requests.isEmpty(), "REQs on the relay: ${temp.requests}")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a sendTo cancelled right after rust added its relay leaves no relay behind`() = runTest {
        SilentWsRelay(answerEose = true).use { normal ->
            SilentWsRelay(acceptEvents = true).use { watcher ->
                val e = connectedEngine(dir, normal)
                try {
                    val intent = EventBuilder.textNote("an intent").signWithKeys(Keys.generate())
                    e.save(intent)
                    e.poolProbe = { at ->
                        if (at == "add-returned") {
                            currentCoroutineContext().job.cancel()
                            currentCoroutineContext().ensureActive()
                        }
                    }
                    launch(Dispatchers.IO) { e.sendTo(listOf(watcher.url), intent.id().toHex()) }.join()
                    e.poolProbe = null
                    assertFalse(watcher.url in e.client.relays().keys.map { it.toString().trimEnd('/') }, "still in the pool")
                    assertFalse(watcher.url in e.relayUrls(), "relayUrls: ${e.relayUrls()}")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a refresh that read the pool before a detach removed the relay does not cache it`() = runTest {
        SilentWsRelay(answerEose = true).use { normal ->
            SilentWsRelay(answerEose = true).use { temp ->
                val e = connectedEngine(dir, normal)
                try {
                    e.attachTemporary(listOf(temp.url))
                    var detach: kotlinx.coroutines.Deferred<Unit>? = null
                    val fired = java.util.concurrent.atomic.AtomicBoolean(false)
                    e.poolProbe = { at ->
                        if (at == "read" && fired.compareAndSet(false, true)) {
                            // between this refresh's pool read and its filter the detach removes the relay and unmarks it
                            detach = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async { e.detachTemporary(listOf(temp.url)) }
                            withContext(Dispatchers.IO) { awaitTrue { temp.url !in e.temporaryRelayUrls() } }
                        }
                    }
                    e.refreshRelaySetForTest()
                    e.poolProbe = null
                    assertFalse(temp.url in e.relayUrls(), "relayUrls: ${e.relayUrls()}")
                    detach!!.await()
                    assertFalse(temp.url in e.relayUrls(), "relayUrls: ${e.relayUrls()}")
                } finally {
                    e.close()
                }
            }
        }
    }
}
