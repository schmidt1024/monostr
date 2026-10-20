package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Metadata
import java.nio.file.Path

/** Filters built from JSON in one call; a malformed key or id never breaks the query, it is left out. */
class JsonFiltersTest {
    @TempDir lateinit var dir: Path

    private val alice = Keys.generate()
    private val bob = Keys.generate()

    @Test
    fun `ids and authors filters find the stored events and leave malformed values out`() = runTest {
        val e = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(alice.secretKey().toHex()), emptyList())
        val note = EventBuilder.textNote("hi").signWithKeys(alice)
        val meta = EventBuilder.metadata(Metadata.fromJson("""{"name":"bob"}""")).signWithKeys(bob)
        e.save(note); e.save(meta)
        val id = note.id().toHex()
        assertEquals(listOf(id), e.query(idsFilter(listOf(id))).map { it.id().toHex() })
        assertEquals(listOf(id), e.query(idsFilter(listOf(id, "not-an-id"))).map { it.id().toHex() })
        val bobHex = bob.publicKey().toHex()
        assertEquals(listOf(meta.id().toHex()), e.query(kindsByAuthorsFilter(listOf(0), listOf(bobHex, "not-a-key"))).map { it.id().toHex() })
        assertEquals(emptyList<String>(), e.query(kindsByAuthorsFilter(listOf(0), listOf(alice.publicKey().toHex()))).map { it.id().toHex() })
        e.close()
    }
}
