package com.monostr.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Timestamp
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes

/** Spec wave 2 B2: the rust callback hands events over untouched; one decoder reads each across the FFI once. */
class EngineIncomingTest {
    @TempDir lateinit var dir: Path
    private val author = Keys.generate()
    private fun note(text: String) = EventBuilder.textNote(text).signWithKeys(author)

    @Test
    fun `each incoming event is read across the FFI once, however many collectors listen, in arrival order`() = runTest {
        val notes = (1..5).map { note("n$it") }
        SilentWsRelay(answerEose = true, recordRequests = true).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                val got = List(3) { CopyOnWriteArrayList<Incoming>() }
                val jobs = got.map { sink -> CoroutineScope(Dispatchers.IO).launch { e.incoming.collect { sink += it } } }
                // a live subscription and pushed events: independent of Task 0 (e) (whether fetched events reach the callback)
                val sub = e.subscribe(Filter().kind(Kind(1u)))
                assertTrue(awaitTrue { relay.requests.isNotEmpty() }, "no REQ")
                Thread.sleep(200)
                val before = e.incomingDecodes.get()
                notes.forEach { relay.push(it.asJson()) }
                assertTrue(awaitTrue { got.all { it.size == 5 } }, "sizes ${got.map { it.size }}")
                e.unsubscribe(sub)
                assertEquals(5L, e.incomingDecodes.get() - before)
                assertEquals(got[0].map { it.json.id }, got[1].map { it.json.id })
                assertEquals(notes.map { it.id().toHex() }.toSet(), got[0].map { it.json.id }.toSet())
                val first = got[0].first()
                assertEquals(relay.url, first.relay)
                assertEquals(first.event.id().toHex(), first.json.id)
                assertTrue(first.subscriptionId.isNotBlank())
                jobs.forEach { it.cancel() }
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `after a burst the newest event still reaches a slow collector`() = runTest(timeout = 3.minutes) {
        val burst = (1..3000).map { note("burst $it").asJson() }
        SilentWsRelay(serve = burst, recordRequests = true).use { relay ->
            val e = connectedEngine(dir, relay)
            try {
                val seen = CopyOnWriteArrayList<String>()
                val slow = CoroutineScope(Dispatchers.IO).launch { e.incoming.collect { seen += it.json.content; delay(1) } }
                Thread.sleep(200)
                e.fetch(Filter().kind(Kind(1u)), Duration.ofSeconds(30))
                val sub = e.subscribe(Filter().kind(Kind(1u)).since(Timestamp.fromSecs((System.currentTimeMillis() / 1000).toULong())))
                Thread.sleep(300)
                relay.push(note("newest").asJson())
                assertTrue(awaitTrue(30_000) { "newest" in seen }, "seen ${seen.size} events, not the newest")
                e.unsubscribe(sub)
                slow.cancel()
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `when rust ends the notification loop on lag the engine restarts it, a later OK and live event still arrive`() = runTest(timeout = 3.minutes) {
        // Task 0 (f2) setup: the callback is held while far more notifications arrive than rust-nostr's channel holds
        val burst = (1..8000).map { note("burst $it").asJson() }
        SilentWsRelay(serve = burst, acceptEvents = true).use { relay ->
            val e = connectedEngine(dir, relay)
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            e.callbackHold = { held.complete(Unit); release.await() }
            try {
                Thread.sleep(300)
                assertEquals(8000, e.fetch(Filter().kind(Kind(1u)), Duration.ofSeconds(30)).size)
                assertTrue(held.isCompleted, "the callback never saw the first event")
                e.callbackHold = null
                release.complete(Unit)
                assertTrue(awaitTrue(30_000) { e.notificationRestarts.get() >= 1 }, "the notification loop was not restarted")
                // the OK reaches a publish only through the notification loop (okWaiters, handleMsg); one sent while the
                // restarted loop is still subscribing to rust's channel may lose its OK, so publish until one gets through
                var after: PublishResult? = null
                assertTrue(
                    awaitTrue(30_000) { e.signAndPublishUnstored(EventBuilder.textNote("after the restart"), Duration.ofSeconds(2)).also { after = it }.sentToAny },
                    "no OK after the restart: ${after?.failedRelays}, restarts ${e.notificationRestarts.get()}",
                )
                val seen = CopyOnWriteArrayList<String>()
                val job = CoroutineScope(Dispatchers.IO).launch { e.incoming.collect { seen += it.json.content } }
                val sub = e.subscribe(Filter().kind(Kind(1u)).since(Timestamp.fromSecs((System.currentTimeMillis() / 1000).toULong())))
                val live = note("live after the restart").asJson()
                assertTrue(awaitTrue(10_000) { relay.push(live); "live after the restart" in seen }, "no live event after the restart")
                e.unsubscribe(sub)
                job.cancel()
                e.close()
                val restarts = e.notificationRestarts.get()
                Thread.sleep(1_000)
                assertEquals(restarts, e.notificationRestarts.get(), "the loop kept restarting after close")
            } finally {
                release.complete(Unit)
                e.close()
            }
        }
    }
}
