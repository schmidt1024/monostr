package com.monostr.nostr

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

/** Spec wave 2 B3: database reads decoded once in Kotlin, in the database's order. */
class EngineQueryDecodedTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `queryDecoded and queryBoth read each stored event once, in query order`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())
        val author = Keys.generate()
        (1..3).forEach { e.save(EventBuilder.textNote("n$it").customCreatedAt(Timestamp.fromSecs((1000 + it).toULong())).signWithKeys(author)) }
        val filter = Filter().kind(Kind(1u))
        val raw = e.query(filter).map { it.id().toHex() }
        assertEquals(raw, e.queryDecoded(filter).map { it.id })
        val both = e.queryBoth(filter)
        assertEquals(raw, both.map { it.first.id().toHex() })
        both.forEach { (event, json) ->
            assertEquals(event.id().toHex(), json.id)
            assertEquals(event.content(), json.content)
            assertEquals(author.publicKey().toHex(), json.pubkey)
        }
        assertTrue(e.queryDecoded(Filter().kind(Kind(7u))).isEmpty())
        e.close()
    }

    /** Bug bounty 2026-11-26: one event dated past the signed range made every database read that met it throw, for good. */
    @Test
    fun `an event dated far in the future is left out of the reads and deleted, the rest still reads`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), null, emptyList())
        val author = Keys.generate()
        val now = System.currentTimeMillis() / 1000
        val good = EventBuilder.textNote("good").customCreatedAt(Timestamp.fromSecs(now.toULong())).signWithKeys(author)
        val ahead = EventBuilder.textNote("clock ahead").customCreatedAt(Timestamp.fromSecs((now + 900).toULong())).signWithKeys(author)
        val overflow = EventBuilder.textNote("overflow").customCreatedAt(Timestamp.fromSecs(9223372036854775808uL)).signWithKeys(author)
        val nextYear = EventBuilder.textNote("next year").customCreatedAt(Timestamp.fromSecs((now + 400 * 86_400).toULong())).signWithKeys(author)
        listOf(good, ahead, overflow, nextYear).forEach { e.save(it) }
        val filter = Filter().kind(Kind(1u))
        assertEquals(4, e.query(filter).size)
        assertEquals(setOf("good", "clock ahead"), e.queryDecoded(filter).map { it.content }.toSet())
        assertEquals(setOf("good", "clock ahead"), e.queryBoth(filter).map { it.second.content }.toSet())
        // the delete runs beside the read
        val end = System.nanoTime() + 5_000_000_000
        while (e.query(filter).size != 2 && System.nanoTime() < end) Thread.sleep(20)
        assertEquals(setOf("good", "clock ahead"), e.query(filter).map { it.content() }.toSet())
        e.close()
    }
}
