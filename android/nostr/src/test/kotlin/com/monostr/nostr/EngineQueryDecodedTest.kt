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
}
