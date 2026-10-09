package com.monostr.nostr

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import java.nio.file.Path
import java.time.Duration

/** NIP-45: one COUNT against one relay, attached for the call only. */
class EngineCountTest {
    @TempDir lateinit var dir: Path

    private suspend fun engine() = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())

    @Test
    fun `a relay that answers COUNT gives its number and is detached again`() = runTest {
        val e = engine()
        SilentWsRelay(countWith = 3192).use { r ->
            assertEquals(3192L, e.countFrom(r.url, Filter().kind(Kind(3u)), Duration.ofSeconds(3)))
            assertEquals(1, r.counts.size)
            assertTrue(e.temporaryRelayUrls().isEmpty())
        }
        e.close()
    }

    @Test
    fun `a relay without NIP-45, a silent one and a dead one give null`() = runTest {
        val e = engine()
        SilentWsRelay(answerEose = true).use { unsupported ->
            assertNull(e.countFrom(unsupported.url, Filter().kind(Kind(3u)), Duration.ofSeconds(2)))
        }
        SilentWsRelay().use { silent ->
            assertNull(e.countFrom(silent.url, Filter().kind(Kind(3u)), Duration.ofMillis(300)))
        }
        assertNull(e.countFrom("ws://127.0.0.1:9", Filter().kind(Kind(3u)), Duration.ofMillis(300)))
        assertTrue(e.temporaryRelayUrls().isEmpty())
        e.close()
    }

    @Test
    fun `a relay that is still connecting is waited for`() = runTest {
        val e = engine()
        SilentWsRelay(countWith = 5, handshakeDelayMs = 1500).use { slow ->
            assertEquals(5L, e.countFrom(slow.url, Filter().kind(Kind(3u)), Duration.ofSeconds(5)))
        }
        e.close()
    }

    @Test
    fun `a relay of the normal set is asked and stays`() = runTest {
        SilentWsRelay(countWith = 9).use { r ->
            val e = NostrEngine.create(dir.resolve("lmdb").toString(), null, listOf(r.url))
            e.connect()
            assertEquals(9L, e.countFrom(r.url, Filter().kind(Kind(3u)), Duration.ofSeconds(3)))
            assertEquals(listOf(r.url), e.relayUrls())
            assertTrue(e.temporaryRelayUrls().isEmpty())
            e.close()
        }
    }
}
