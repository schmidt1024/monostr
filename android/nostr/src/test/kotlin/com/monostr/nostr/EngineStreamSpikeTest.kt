package com.monostr.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.ClientBuilder
import rust.nostr.sdk.ClientMessage
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventStream
import rust.nostr.sdk.Filter
import rust.nostr.sdk.HandleNotification
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.NostrDatabase
import rust.nostr.sdk.RelayMessage
import rust.nostr.sdk.RelayMessageEnum
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.ReqExitPolicy
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

/**
 * Spec wave 2 B0 (spike; the tests stay): what rust-nostr 0.44.8 does with `streamEventsFrom` and with a per-relay
 * `Relay.fetchEvents`, against loopback relays. A `SPIKE <name>: <value>` line records a timing only; every other
 * observation is pinned as an assertion of the value seen.
 *
 * Observed 2026-10-20, 8 runs (6 observing, 2 with the pins), all tests green every run (values min–max); after fix round 1
 * (h added, f2 held on a latch) 5 more runs, all 11 green: (h) CLOSE 1003–1010 ms after a 1 s stream was dropped, (f2) 1 handled.
 * - (a) a streamed event is stored in the database: yes. (b) the stream ends after the last EOSE: 342–344 ms (EOSE delay 300).
 * - (c) close() of a stream mid-way: no CLOSE (within 500 ms), next fetch works. (c2) cancel of a pending next(): 0–2 ms,
 *   no CLOSE (within 300 ms), next fetch works. (h) is the positive control: the CLOSE of a dropped stream goes out only
 *   when the stream's original timeout runs out, so the relay holds the REQ until then (spec D8 cap). A throwaway probe
 *   showed the same for a cancelled per-relay fetch.
 * - (d) one event from two relays: yielded once. (e) fetched and streamed events both reach the notification callback.
 * - (f1) OK after a 3000-event burst with a slow collector: yes, though in a cold JVM only through a restart of the
 *   notification loop (rust may end it on lag, see f2), so the test shows the engine recovers, not that the loop survives. (f2) raw loop whose callback is held on a latch while
 *   8000 events arrive: once released, handleNotifications returns normally (no exception; the lagged broadcast channel
 *   ends rust's loop), so the later OK never arrives. The OK assertion is a canary for that rust-nostr 0.44.8 defect.
 * - (g1) per-relay fetch with ExitOnEose stores the event and returns after 46–82 ms. (g2) cancel: 1 ms, no CLOSE
 *   (within 300 ms), next fetch works.
 * - A timed-out fetch, per-relay fetch or stream returns what it has (empty, or next() == null) without throwing;
 *   a timed-out `Relay.countEvents` throws `NostrSdkException.Generic` with message "timeout".
 */
class EngineStreamSpikeTest {
    @TempDir lateinit var dir: Path
    private val author = Keys.generate()
    private fun note(text: String): Event = EventBuilder.textNote(text).signWithKeys(author)
    private fun record(name: String, value: Any?) = println("SPIKE $name: $value")
    private fun urls(vararg relays: SilentWsRelay): List<RelayUrl> = relays.map { RelayUrl.parse(it.url) }

    /** Every event of [stream] until it ends (null), in real time on IO. */
    private suspend fun drain(stream: EventStream): List<Event> = withContext(Dispatchers.IO) {
        val out = ArrayList<Event>()
        while (true) out += stream.next() ?: break
        out
    }

    @Test
    fun `(a) a streamed event is stored in the database without a second save`() = runTest {
        val a = note("streamed")
        SilentWsRelay(serve = listOf(a.asJson())).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                val stream = e.client.streamEventsFrom(urls(relay), Filter().kind(Kind(1u)), Duration.ofSeconds(5))
                val got = try { drain(stream) } finally { stream.close() }
                assertEquals(listOf(a.id().toHex()), got.map { it.id().toHex() })
                assertNotNull(e.eventById(a.id().toHex()), "the streamed event is not in the database")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `(b) the stream ends after every relay's EOSE, well before the timeout`() = runTest {
        SilentWsRelay(serve = listOf(note("one").asJson())).use { r1 ->
            SilentWsRelay(answerEose = true, eoseDelayMs = 300).use { r2 ->
                val e = connectedEngine(dir, listOf(r1, r2))
                try {
                    val started = System.nanoTime()
                    val stream = e.client.streamEventsFrom(urls(r1, r2), Filter().kind(Kind(1u)), Duration.ofSeconds(8))
                    val got = try { drain(stream) } finally { stream.close() }
                    val ms = (System.nanoTime() - started) / 1_000_000
                    record("b ended after ms", ms)
                    assertEquals(1, got.size)
                    assertTrue(ms < 3_000, "the stream waited for the timeout: $ms ms")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `(c) closing a stream mid-way does not crash and the next fetch works`() = runTest {
        val first = note("first")
        val later = note("later")
        SilentWsRelay(serve = listOf(first.asJson()), eoseDelayMs = 30_000).use { slow ->
            SilentWsRelay(serve = listOf(later.asJson())).use { fast ->
                val e = connectedEngine(dir, listOf(slow, fast))
                try {
                    val stream = e.client.streamEventsFrom(urls(slow), Filter().kind(Kind(1u)), Duration.ofSeconds(20))
                    val one = withContext(Dispatchers.IO) { stream.next() }
                    assertEquals(first.id().toHex(), one?.id()?.toHex())
                    stream.close()
                    Thread.sleep(500)
                    assertTrue(slow.closes.isEmpty(), "rust-nostr 0.44.8 sends no CLOSE within 500 ms of dropping a stream (spec R2)")
                    val again = e.fetchFrom(listOf(fast.url), Filter().kind(Kind(1u)), Duration.ofSeconds(5), attach = false)
                    assertEquals(listOf(later.id().toHex()), again.map { it.id().toHex() })
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `(c2) cancelling a pending next() and closing the stream leaves the client usable`() = runTest {
        val later = note("later")
        SilentWsRelay(recordRequests = true).use { silent ->
            SilentWsRelay(serve = listOf(later.asJson())).use { fast ->
                val e = connectedEngine(dir, listOf(silent, fast))
                var waiting: kotlinx.coroutines.Job? = null
                try {
                    val stream = e.client.streamEventsFrom(urls(silent), Filter().kind(Kind(1u)), Duration.ofSeconds(20))
                    waiting = CoroutineScope(Dispatchers.IO).launch { stream.next() }
                    assertTrue(awaitTrue { silent.requests.isNotEmpty() }, "the stream never sent its REQ")
                    val started = System.nanoTime()
                    waiting.cancelAndJoin()
                    val cancelMs = (System.nanoTime() - started) / 1_000_000
                    record("c2 cancel of next() took ms", cancelMs)
                    assertTrue(cancelMs < 2_000, "cancelling next() waited for the stream: $cancelMs ms")
                    stream.close()
                    Thread.sleep(300)
                    assertTrue(silent.closes.isEmpty(), "rust-nostr 0.44.8 sends no CLOSE within 300 ms of dropping a stream (spec R2)")
                    val again = e.fetchFrom(listOf(fast.url), Filter().kind(Kind(1u)), Duration.ofSeconds(5), attach = false)
                    assertEquals(listOf(later.id().toHex()), again.map { it.id().toHex() })
                } finally {
                    waiting?.cancel()
                    e.close()
                }
            }
        }
    }

    @Test
    fun `(d) the same event from two relays`() = runTest {
        val same = note("same")
        SilentWsRelay(serve = listOf(same.asJson())).use { r1 ->
            SilentWsRelay(serve = listOf(same.asJson())).use { r2 ->
                val e = connectedEngine(dir, listOf(r1, r2))
                try {
                    val stream = e.client.streamEventsFrom(urls(r1, r2), Filter().kind(Kind(1u)), Duration.ofSeconds(5))
                    val got = try { drain(stream) } finally { stream.close() }
                    assertEquals(1, got.size, "the stream yields one event from two relays once")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `(e) fetched and streamed events reach the notification callback`() = runTest {
        val fetched = note("fetched")
        val streamed = note("streamed")
        SilentWsRelay(serve = listOf(fetched.asJson())).use { r1 ->
            SilentWsRelay(serve = listOf(streamed.asJson())).use { r2 ->
                val e = connectedEngine(dir, listOf(r1, r2))
                val seen = CopyOnWriteArrayList<String>()
                val subscribed = CompletableDeferred<Unit>()
                val job = CoroutineScope(Dispatchers.IO).launch {
                    e.incoming.onSubscription { subscribed.complete(Unit) }.collect { seen += it.json.id }
                }
                try {
                    withContext(Dispatchers.IO) { withTimeout(5_000) { subscribed.await() } }
                    e.fetchFrom(listOf(r1.url), Filter().kind(Kind(1u)), Duration.ofSeconds(5), attach = false)
                    val stream = e.client.streamEventsFrom(urls(r2), Filter().kind(Kind(1u)), Duration.ofSeconds(5))
                    try { drain(stream) } finally { stream.close() }
                    assertTrue(awaitTrue(2_000) { seen.size >= 2 }, "only ${seen.size} events reached the callback")
                    assertTrue(fetched.id().toHex() in seen, "a fetched event reaches the notification callback")
                    assertTrue(streamed.id().toHex() in seen, "a streamed event reaches the notification callback")
                } finally {
                    job.cancel()
                    e.close()
                }
            }
        }
    }

    @Test
    fun `(f1) after a burst of 3000 events with a slow collector a publish still gets its OK, through a restart of the loop if rust ended it`() = runTest(timeout = 3.minutes) {
        val burst = (1..3000).map { note("burst $it").asJson() }
        SilentWsRelay(serve = burst, acceptEvents = true).use { relay ->
            val e = connectedEngine(dir, relay)
            val slow = CoroutineScope(Dispatchers.IO).launch { e.incoming.collect { delay(5) } }
            try {
                assertEquals(3000, e.fetch(Filter().kind(Kind(1u)), Duration.ofSeconds(30)).size)
                // the OK reaches this publish only through the notification loop (okWaiters, handleMsg)
                val after = e.signAndPublishUnstored(EventBuilder.textNote("after the burst"))
                assertTrue(after.sentToAny, "no OK after the burst: ${after.failedRelays}")
            } finally {
                slow.cancel()
                e.close()
            }
        }
    }

    @Test
    fun `(f2) canary - a callback held while a burst arrives ends rust-nostr's notification loop`() = runTest(timeout = 3.minutes) {
        // far more notifications than rust-nostr's broadcast channel holds while the callback is held (its size not checked)
        val burst = (1..8000).map { note("burst $it").asJson() }
        SilentWsRelay(serve = burst, acceptEvents = true).use { relay ->
            val client = ClientBuilder().database(NostrDatabase.lmdb(dir.resolve("raw").toString())).build()
            val url = RelayUrl.parse(relay.url)
            client.addRelay(url)
            client.connect()
            assertTrue(awaitTrue { client.relay(url).isConnected() }, "loopback relay never connected")
            val oks = CopyOnWriteArrayList<String>()
            val handled = AtomicInteger()
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val outcome = CompletableDeferred<Result<Unit>>()
            val loop = CoroutineScope(Dispatchers.IO).launch {
                outcome.complete(runCatching {
                    client.handleNotifications(object : HandleNotification {
                        override suspend fun handle(relayUrl: RelayUrl, subscriptionId: String, event: Event) {
                            handled.incrementAndGet()
                            held.complete(Unit)
                            release.await() // the first event holds the loop until the whole burst is in
                        }
                        override suspend fun handleMsg(relayUrl: RelayUrl, msg: RelayMessage) {
                            (msg.asEnum() as? RelayMessageEnum.Ok)?.let { oks += it.eventId.toHex() }
                        }
                    })
                })
            }
            try {
                Thread.sleep(300)
                val fetched = withContext(Dispatchers.IO) { client.fetchEventsFrom(listOf(url), Filter().kind(Kind(1u)), Duration.ofSeconds(30)).toVec().size }
                assertEquals(8000, fetched)
                assertTrue(held.isCompleted, "the callback never saw the first event")
                release.complete(Unit)
                val result = withContext(Dispatchers.IO) { withTimeout(30_000) { outcome.await() } }
                record("f2 events handled", handled.get())
                assertTrue(result.isSuccess, "handleNotifications threw instead of returning: ${result.exceptionOrNull()}")
                val probe = EventBuilder.textNote("probe").signWithKeys(author)
                withContext(Dispatchers.IO) { client.relay(url).sendMsg(ClientMessage.event(probe)) }
                Thread.sleep(1_000)
                assertFalse(
                    probe.id().toHex() in oks,
                    "canary: rust-nostr 0.44.8 defect, its notification loop ends on lag; red after an upgrade is intended, revisit the engine's loop handling",
                )
            } finally {
                release.complete(Unit)
                loop.cancel()
                runCatching { client.shutdown() }
            }
        }
    }

    @Test
    fun `(g1) a per-relay fetch with ExitOnEose stores its events and returns at that relay's EOSE`() = runTest {
        val a = note("per relay")
        SilentWsRelay(serve = listOf(a.asJson())).use { fast ->
            SilentWsRelay().use { silent ->
                val e = connectedEngine(dir, listOf(fast, silent))
                try {
                    val started = System.nanoTime()
                    val got = withContext(Dispatchers.IO) {
                        e.client.relay(RelayUrl.parse(fast.url)).fetchEvents(Filter().kind(Kind(1u)), Duration.ofSeconds(8), ReqExitPolicy.ExitOnEose).toVec()
                    }
                    val ms = (System.nanoTime() - started) / 1_000_000
                    record("g1 per-relay fetch returned after ms", ms)
                    assertEquals(listOf(a.id().toHex()), got.map { it.id().toHex() })
                    assertTrue(ms < 2_000, "the per-relay fetch waited $ms ms")
                    assertNotNull(e.eventById(a.id().toHex()), "the per-relay fetch did not store the event")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `(g2) cancelling a per-relay fetch leaves the client usable`() = runTest {
        val later = note("later")
        SilentWsRelay(recordRequests = true).use { silent ->
            SilentWsRelay(serve = listOf(later.asJson())).use { fast ->
                val e = connectedEngine(dir, listOf(silent, fast))
                var waiting: kotlinx.coroutines.Job? = null
                try {
                    waiting = CoroutineScope(Dispatchers.IO).launch {
                        e.client.relay(RelayUrl.parse(silent.url)).fetchEvents(Filter().kind(Kind(1u)), Duration.ofSeconds(20), ReqExitPolicy.ExitOnEose)
                    }
                    assertTrue(awaitTrue { silent.requests.isNotEmpty() }, "the per-relay fetch never sent its REQ")
                    val started = System.nanoTime()
                    waiting.cancelAndJoin()
                    val cancelMs = (System.nanoTime() - started) / 1_000_000
                    record("g2 cancel took ms", cancelMs)
                    assertTrue(cancelMs < 2_000, "cancelling waited for the fetch: $cancelMs ms")
                    Thread.sleep(300)
                    assertTrue(silent.closes.isEmpty(), "rust-nostr 0.44.8 sends no CLOSE within 300 ms of cancelling a per-relay fetch (spec R2)")
                    val again = e.fetchFrom(listOf(fast.url), Filter().kind(Kind(1u)), Duration.ofSeconds(5), attach = false)
                    assertEquals(listOf(later.id().toHex()), again.map { it.id().toHex() })
                } finally {
                    waiting?.cancel()
                    e.close()
                }
            }
        }
    }

    @Test
    fun `(h) a dropped stream sends its CLOSE only when its original timeout runs out`() = runTest {
        SilentWsRelay(recordRequests = true).use { silent ->
            val e = connectedEngine(dir, silent)
            var waiting: kotlinx.coroutines.Job? = null
            try {
                val started = System.nanoTime()
                val stream = e.client.streamEventsFrom(urls(silent), Filter().kind(Kind(1u)), Duration.ofSeconds(1))
                waiting = CoroutineScope(Dispatchers.IO).launch { stream.next() }
                assertTrue(awaitTrue { silent.requests.isNotEmpty() }, "the stream never sent its REQ")
                val reqId = Regex("^\\[\\s*\"REQ\"\\s*,\\s*\"([^\"]*)\"").find(silent.requests.first())!!.groupValues[1]
                waiting.cancelAndJoin()
                stream.close()
                Thread.sleep(300)
                assertTrue(silent.closes.isEmpty(), "no CLOSE right after the drop")
                assertTrue(awaitTrue(5_000) { silent.closes.isNotEmpty() }, "the dropped stream never sent a CLOSE")
                val ms = (System.nanoTime() - started) / 1_000_000
                record("h CLOSE after ms (timeout 1000)", ms)
                assertEquals(listOf(reqId), silent.closes.toList(), "the CLOSE names the stream's REQ")
                assertTrue(ms >= 900, "the CLOSE came before the original timeout: $ms ms")
            } finally {
                waiting?.cancel()
                e.close()
            }
        }
    }
}
