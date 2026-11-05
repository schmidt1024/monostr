package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import com.monostr.nostr.connectedEngine
import com.monostr.nostr.model.AddressRef
import com.monostr.nostr.model.RelayEntry
import com.monostr.nostr.model.RelayMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Spec 2026-11-05 relay read/write, task 3: other people's data is asked of the relays that read.
 * A = write only, B = read only; nothing about another author may reach A.
 */
class ReadRelayCallersTest {
    @TempDir lateinit var dir: Path

    private val other = "b".repeat(64)

    private fun relay() = SilentWsRelay(answerEose = true, acceptEvents = true)

    /** Long enough for a frame that was wrongly sent to arrive. */
    private fun settle() = Thread.sleep(400)

    private suspend fun oneWay(a: SilentWsRelay, b: SilentWsRelay): NostrEngine =
        connectedEngine(dir, listOf(a, b)).also { it.setRelayModes(mapOf(a.url to RelayMode.WRITE, b.url to RelayMode.READ)) }

    private suspend fun both(block: suspend (NostrEngine, SilentWsRelay, SilentWsRelay) -> Unit) {
        relay().use { a -> relay().use { b ->
            val e = oneWay(a, b)
            try { block(e, a, b) } finally { e.close() }
        } }
    }

    @Test
    fun `other people's follower pages do not ask a write-only relay`() = runTest {
        both { e, a, b ->
            NostrFollowListsRepository(e, kept = { emptySet() }, countRelays = emptyList(), indexRelays = emptyList(), timeout = Duration.ofSeconds(2)).followers(other, null, emptySet())
            assertTrue(b.requests.isNotEmpty(), "B asked")
            assertEquals(emptyList<String>(), a.requests.toList())
        }
    }

    @Test
    fun `other people's follow counts do not ask a write-only relay`() = runTest {
        both { e, a, b ->
            withContext(Dispatchers.Default) {
                NostrFollowCountsRepository(e, countRelays = emptyList(), indexRelays = emptyList(), timeout = Duration.ofSeconds(2)).counts(other).toList()
            }
            assertTrue(b.counts.isNotEmpty(), "B counted")
            assertEquals(emptyList<String>(), a.counts.toList())
            assertEquals(emptyList<String>(), a.requests.toList())
        }
    }

    @Test
    fun `other people's DM relay list does not ask a write-only relay`() = runTest {
        both { e, a, b ->
            val authors = NostrAuthorRelaysRepository(e, indexers = emptyList(), fetchTimeout = Duration.ofSeconds(2))
            NostrDmRelaysRepository(e, authors, indexers = emptyList(), fetchTimeout = Duration.ofSeconds(2)).dmRelays(other)
            assertTrue(b.requests.isNotEmpty(), "B asked")
            assertEquals(emptyList<String>(), a.requests.toList())
        }
    }

    @Test
    fun `other people's relay lists do not ask a write-only relay`() = runTest {
        both { e, a, b ->
            NostrAuthorRelaysRepository(e, indexers = emptyList(), fetchTimeout = Duration.ofSeconds(2)).readRelays(other)
            assertTrue(b.requests.isNotEmpty(), "B asked")
            assertEquals(emptyList<String>(), a.requests.toList())
        }
    }

    @Test
    fun `other people's relay lists through the foreign budget do not ask a write-only relay`() = runTest {
        both { e, a, b ->
            val foreign = ForeignRelays(e)
            NostrAuthorRelaysRepository(e, indexers = emptyList(), fetchTimeout = Duration.ofSeconds(2), foreign = foreign).readRelays(other)
            assertTrue(b.requests.isNotEmpty(), "B asked")
            assertEquals(emptyList<String>(), a.requests.toList())
        }
    }

    @Test
    fun `an addressable event of another author does not ask a write-only relay`() = runTest {
        both { e, a, b ->
            val ref = AddressRef(30023, other, "post", emptyList())
            NostrAddressableRepository(e, timeout = Duration.ofSeconds(2)).get(ref, allowForeign = false)
            assertTrue(b.requests.isNotEmpty(), "B asked")
            assertEquals(emptyList<String>(), a.requests.toList())
        }
    }

    @Test
    fun `a write-only index relay is not asked for other people's profiles`() = runTest {
        both { e, a, b ->
            val repo = NostrProfileRepository(e, indexRelays = listOf(a.url), indexTimeout = Duration.ofSeconds(2), batchDelayMs = 0)
            repo.getAll(listOf(other), 0)
            assertTrue(awaitTrue { b.requests.isNotEmpty() }, "B asked")
            Thread.sleep(400)
            assertEquals(emptyList<String>(), a.requests.toList())
        }
    }

    @Test
    fun `a fixed relay list naming a write-only relay does not ask it for follower pages`() = runTest {
        both { e, a, b ->
            NostrFollowListsRepository(e, kept = { emptySet() }, countRelays = listOf("${a.url}/"), indexRelays = emptyList(), timeout = Duration.ofSeconds(2)).followers(other, null, emptySet())
            assertTrue(b.requests.isNotEmpty(), "B asked")
            assertEquals(emptyList<String>(), a.requests.toList())
        }
    }

    @Test
    fun `a fixed relay list naming a write-only relay does not ask it for follow counts`() = runTest {
        both { e, a, b ->
            withContext(Dispatchers.Default) {
                NostrFollowCountsRepository(e, countRelays = listOf(a.url), indexRelays = emptyList(), timeout = Duration.ofSeconds(2)).counts(other).toList()
            }
            assertTrue(b.counts.isNotEmpty(), "B counted")
            assertEquals(emptyList<String>(), a.counts.toList())
            assertEquals(emptyList<String>(), a.requests.toList())
        }
    }

    @Test
    fun `an indexer that is a write-only relay is not asked for another account's DM relays`() = runTest {
        both { e, a, b ->
            val authors = NostrAuthorRelaysRepository(e, indexers = listOf(a.url), fetchTimeout = Duration.ofSeconds(2))
            NostrDmRelaysRepository(e, authors, indexers = listOf(a.url), fetchTimeout = Duration.ofSeconds(2)).dmRelays(other)
            assertTrue(b.requests.isNotEmpty(), "B asked")
            assertEquals(emptyList<String>(), a.requests.toList())
        }
    }

    @Test
    fun `an indexer that is a write-only relay still gets the user's own DM relay lookup`() = runTest {
        both { e, a, _ ->
            val authors = NostrAuthorRelaysRepository(e, indexers = listOf(a.url), fetchTimeout = Duration.ofSeconds(2))
            NostrDmRelaysRepository(e, authors, indexers = listOf(a.url), fetchTimeout = Duration.ofSeconds(2)).ownDmRelays()
            assertTrue(a.requests.isNotEmpty(), "the own list may live there")
        }
    }

    @Test
    fun `an indexer that is a write-only relay is not asked for another account's relay list`() = runTest {
        both { e, a, b ->
            NostrAuthorRelaysRepository(e, indexers = listOf(a.url), fetchTimeout = Duration.ofSeconds(2)).readRelays(other)
            assertTrue(b.requests.isNotEmpty(), "B asked")
            assertEquals(emptyList<String>(), a.requests.toList())
        }
    }

    @Test
    fun `an indexer that is a write-only relay is not leased, and the answer counts as complete`() = runTest {
        both { e, a, b ->
            val repo = NostrAuthorRelaysRepository(e, indexers = listOf(a.url), fetchTimeout = Duration.ofSeconds(2), foreign = ForeignRelays(e))
            repo.readRelays(other)
            assertTrue(b.requests.isNotEmpty(), "B asked")
            assertEquals(emptyList<String>(), a.requests.toList())
            val asked = b.requests.size
            repo.readRelays(other)
            assertEquals(asked, b.requests.size, "the indexer was not short of a slot: the answer is cached")
        }
    }

    @Test
    fun `the lease drops a write-only relay and lets a relay that reads through`() = runTest {
        both { e, a, b ->
            val foreign = ForeignRelays(e)
            assertEquals(listOf(b.url), foreign.lease(listOf(a.url, "${a.url}/", b.url)))
            assertEquals(emptyList<String>(), foreign.lease(listOf(a.url), wait = 50.milliseconds))
        }
    }

    @Test
    fun `without a write-only relay the lease passes normal relays as before`() = runTest {
        relay().use { r ->
            val e = connectedEngine(dir, r)
            try {
                assertEquals(listOf(r.url), ForeignRelays(e).lease(listOf(r.url)))
            } finally { e.close() }
        }
    }

    @Test
    fun `a naddr hint naming a write-only relay does not reach it`() = runTest {
        both { e, a, b ->
            val ref = AddressRef(30023, other, "post", listOf(a.url))
            val repo = NostrAddressableRepository(e, timeout = Duration.ofSeconds(2), relayFilter = { true }, foreign = ForeignRelays(e))
            repo.get(ref, allowForeign = true)
            settle()
            assertTrue(b.requests.isNotEmpty(), "B asked")
            assertEquals(emptyList<String>(), a.requests.toList())
            val asked = b.requests.size
            repo.get(ref, allowForeign = true)
            settle()
            assertEquals(asked, b.requests.size, "a dropped hint is no missing slot: the miss is remembered")
        }
    }

    @Test
    fun `a note hint naming a write-only relay does not reach it`() = runTest {
        both { e, a, b ->
            val id = "cd".repeat(32)
            val repo = NostrQuoteRepository(e, timeout = Duration.ofSeconds(2), relayFilter = { true }, foreign = ForeignRelays(e))
            repo.get(id, hints = listOf(a.url))
            settle()
            assertTrue(b.requests.isNotEmpty(), "B asked")
            assertEquals(emptyList<String>(), a.requests.toList())
            val asked = b.requests.size
            repo.get(id, hints = listOf(a.url))
            settle()
            assertEquals(asked, b.requests.size, "a dropped hint is no missing slot: the miss is remembered")
        }
    }

    @Test
    fun `the locator does not ask a write-only relay it was hinted, and an answer from the rest counts`() = runTest {
        both { e, a, _ ->
            relay().use { foreignRelay ->
                val id = "ab".repeat(32)
                val pool = ForeignRelays(e)
                val authors = NostrAuthorRelaysRepository(e, indexers = emptyList(), fetchTimeout = Duration.ofSeconds(2), foreign = pool)
                val locator = NostrEventLocator(e, authors, pool, enabled = { true }, timeout = Duration.ofSeconds(2), relayFilter = { true })
                withContext(Dispatchers.IO) { locator.find(listOf(Wanted(id, relays = listOf(a.url, foreignRelay.url)))) }
                assertTrue(foreignRelay.requests.isNotEmpty(), "the other hint asked")
                assertEquals(emptyList<String>(), a.requests.toList())
                assertFalse(locator.searchable(id), "every relay left in the plan was asked: searched, not found")
                pool.close()
            }
        }
    }

    @Test
    fun `the published relay list carries the read and write markers`() = runTest {
        relay().use { a -> relay().use { b ->
            val e = oneWay(a, b)
            try {
                val c = "wss://both.example"
                NostrPublishRepository(e).relayList(listOf(RelayEntry(a.url, RelayMode.WRITE), RelayEntry(b.url, RelayMode.READ), RelayEntry(c, RelayMode.BOTH)))
                assertTrue(awaitTrue { a.events.isNotEmpty() }, "the writing relay has it")
                assertEquals(emptyList<String>(), b.events.toList(), "no event on a read-only relay")
                assertTrue(
                    """"tags":[["r","${a.url}","write"],["r","${b.url}","read"],["r","$c"]]""" in a.events.single().replace(" ", ""),
                    a.events.single(),
                )
            } finally { e.close() }
        } }
    }

    @Test
    fun `a quoted note of another author does not ask a write-only relay`() = runTest {
        both { e, a, b ->
            NostrQuoteRepository(e, timeout = Duration.ofSeconds(2)).get("cd".repeat(32))
            assertTrue(b.requests.isNotEmpty(), "B asked")
            assertEquals(emptyList<String>(), a.requests.toList())
        }
    }

    @Test
    fun `own DM relay list that lives only on a write-only relay is not reported as none`() = runTest {
        val ev = rust.nostr.sdk.EventBuilder(rust.nostr.sdk.Kind(10050u), "")
            .tags(listOf(rust.nostr.sdk.Tag.parse(listOf("relay", "wss://inbox.example.com"))))
            .signWithKeys(rust.nostr.sdk.Keys(rust.nostr.sdk.SecretKey.parse("0000000000000000000000000000000000000000000000000000000000000001")))
        SilentWsRelay(serve = listOf(ev.asJson())).use { a -> relay().use { b ->
            val e = oneWay(a, b)
            try {
                val authors = NostrAuthorRelaysRepository(e, indexers = emptyList(), fetchTimeout = Duration.ofSeconds(2))
                val own = NostrDmRelaysRepository(e, authors, indexers = emptyList(), fetchTimeout = Duration.ofSeconds(2)).ownDmRelays()
                assertEquals(OwnDmRelays.Found(listOf("wss://inbox.example.com")), own)
            } finally { e.close() }
        } }
    }

    @Test
    fun `note counts do not ask a write-only relay`() = runTest {
        relay().use { a -> SilentWsRelay(countWith = 3).use { b ->
            val e = oneWay(a, b)
            try {
                val counts = NostrCountsRepository(e, e.backgroundScope)
                counts.request(listOf("ef".repeat(32)))
                assertTrue(awaitTrue { b.counts.isNotEmpty() }, "B counted")
                Thread.sleep(400)
                assertEquals(emptyList<String>(), a.counts.toList())
            } finally { e.close() }
        } }
    }
}
