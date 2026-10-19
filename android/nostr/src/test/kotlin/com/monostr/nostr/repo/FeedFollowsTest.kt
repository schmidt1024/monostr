package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path

/** The follow list read in Kotlin from the contact list's JSON (a call per tag into rust cost ~0.5 s per start). */
class FeedFollowsTest {
    @TempDir lateinit var dir: Path

    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.generate().publicKey().toHex()
    private val bob = Keys.generate().publicKey().toHex()

    private suspend fun engine(): NostrEngine = NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), emptyList())

    private fun contacts(at: Long, vararg tags: List<String>) =
        EventBuilder(Kind(3u), "").tags(tags.map { Tag.parse(it) }).customCreatedAt(Timestamp.fromSecs(at.toULong())).signWithKeys(me)

    @Test
    fun `p tags with a valid key in order, duplicates and junk left out, the user last`() = runTest {
        val e = engine()
        e.save(contacts(1000, listOf("p", alice), listOf("p", "zz"), listOf("p", bob, "wss://r.example", "bob"), listOf("t", "x"), listOf("p", alice), listOf("p", alice.uppercase())))
        val repo = NostrFeedRepository(e)
        assertEquals(listOf(alice, bob, me.publicKey().toHex()), repo.follows())
        e.close()
    }

    @Test
    fun `a followed key that is not on the curve does not break the feed`() = runTest {
        val e = engine()
        val aliceKeys = Keys.generate()
        // 64 hex characters above the field prime: no point, so rust refuses it as a public key
        e.save(contacts(1000, listOf("p", "f".repeat(64)), listOf("p", aliceKeys.publicKey().toHex())))
        e.save(EventBuilder.textNote("hello").customCreatedAt(Timestamp.fromSecs(1500u)).signWithKeys(aliceKeys))
        val repo = NostrFeedRepository(e)
        assertEquals(listOf("hello"), repo.notes().map { it.content })
        e.close()
    }

    @Test
    fun `a newer contact list is read again`() = runTest {
        val e = engine()
        e.save(contacts(1000, listOf("p", alice)))
        val repo = NostrFeedRepository(e)
        assertEquals(listOf(alice, me.publicKey().toHex()), repo.follows())
        e.save(contacts(2000, listOf("p", bob)))
        assertEquals(listOf(bob, me.publicKey().toHex()), repo.follows())
        e.close()
    }
}
