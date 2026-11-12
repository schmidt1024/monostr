package com.monostr.nostr

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import java.nio.file.Path

/** Pixel 2026-11-12: a note stuck on «Sending…» for minutes, its send waiting for a wake that did not come back. */
class EngineSendWakeTest {
    @TempDir lateinit var dir: Path

    private val sk = "0000000000000000000000000000000000000000000000000000000000000001"

    @Test
    fun `a wake that hangs holds a send up no longer than the send's wait for it`() = runBlocking<Unit> {
        // nothing listens there: no relay is connected, so the send's wake goes on to reconnect and meets the probe
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(sk), listOf("ws://127.0.0.1:9"))
        try {
            e.connect()
            var waking = false
            e.poolProbe = { at -> if (at == "waking") { waking = true; awaitCancellation() } }
            val started = System.nanoTime()
            val published = withTimeout(NostrEngine.SEND_WAKE_WAIT.toMillis() + 15_000) { e.signAndPublish(EventBuilder.textNote("stuck?")) }
            assertTrue(waking, "the wake did not reach the probe")
            assertFalse(published.sentToAny, "published: $published")
            assertTrue(System.nanoTime() - started >= NostrEngine.SEND_WAKE_WAIT.toNanos(), "the send did not wait for its wake")
        } finally {
            e.poolProbe = null
            e.close()
        }
    }

    @Test
    fun `a delete is not held up by a wake that hangs either`() = runBlocking<Unit> {
        val e = NostrEngine.create(dir.resolve("lmdb2").toString(), LocalSigner(sk), listOf("ws://127.0.0.1:9"))
        try {
            e.connect()
            var waking = false
            e.poolProbe = { at -> if (at == "waking") { waking = true; awaitCancellation() } }
            val published = withTimeout(NostrEngine.SEND_WAKE_WAIT.toMillis() + 15_000) { e.signAndPublishUnstored(EventBuilder.textNote("gone")) }
            assertTrue(waking, "the wake did not reach the probe")
            assertFalse(published.sentToAny, "published: $published")
        } finally {
            e.poolProbe = null
            e.close()
        }
    }
}
