package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.awaitTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Metadata
import java.nio.file.Path
import java.time.Duration

/**
 * A profile (kind 0) that is on none of the user's relays is looked up once on the profile index
 * relays (v0.8.4: the operator's own profile was only on purplepag.es and showed without a name).
 */
class ProfileIndexTest {
    @TempDir lateinit var dir: Path

    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val alice = Keys.generate()
    private val bob = Keys.generate()
    private var clock = 1_000_000L

    private fun profile(keys: Keys, name: String): Event = EventBuilder.metadata(Metadata.fromJson("""{"name":"$name","lud16":"$name@wallet.example"}""")).signWithKeys(keys)

    private suspend fun engine(own: SilentWsRelay): NostrEngine =
        NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), listOf(own.url)).also { it.connect() }

    @Test
    fun `a profile the user's relays do not have is found on the index relay, once`() = runTest {
        SilentWsRelay(answerEose = true).use { own ->
            SilentWsRelay(serve = listOf(profile(alice, "alice").asJson())).use { index ->
                val e = engine(own)
                try {
                    val repo = NostrProfileRepository(e, now = { clock }, indexRelays = listOf(index.url))
                    val aliceHex = alice.publicKey().toHex()
                    assertEquals("alice", repo.get(aliceHex).name)
                    assertEquals(1, index.requests.size)
                    assertTrue(index.requests[0].contains(aliceHex))
                    assertEquals("alice", repo.get(aliceHex).name)
                    assertEquals(1, index.requests.size, "it is in the database now")
                    assertEquals(1, e.relayUrls().size, "the index relay never joins the user's relays")
                    assertTrue(e.temporaryRelayUrls().isEmpty(), "and is let go after the lookup")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `prefetch asks the index relay only for the profiles that are still missing`() = runTest {
        SilentWsRelay(serve = listOf(profile(bob, "bob").asJson())).use { own ->
            SilentWsRelay(serve = listOf(profile(alice, "alice").asJson())).use { index ->
                val e = engine(own)
                try {
                    val repo = NostrProfileRepository(e, now = { clock }, indexRelays = listOf(index.url))
                    val aliceHex = alice.publicKey().toHex()
                    val bobHex = bob.publicKey().toHex()
                    repo.prefetch(listOf(aliceHex, bobHex))
                    assertEquals(listOf("bob"), repo.local(listOf(bobHex)).map { it.name })
                    assertTrue(awaitTrue { repo.observe(aliceHex).first().name == "alice" }, "what the index relay had shows once it is there")
                    assertEquals(listOf("alice", "bob"), repo.local(listOf(aliceHex, bobHex)).map { it.name })
                    assertEquals(1, index.requests.size)
                    assertTrue(index.requests[0].contains(aliceHex))
                    assertFalse(index.requests[0].contains(bobHex), "the user's own relay had this one")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a profile nobody has is asked for once per retry window`() = runTest {
        SilentWsRelay(answerEose = true).use { own ->
            SilentWsRelay(answerEose = true).use { index ->
                val e = engine(own)
                try {
                    val repo = NostrProfileRepository(e, now = { clock }, missingRetrySeconds = 600, indexRelays = listOf(index.url))
                    val aliceHex = alice.publicKey().toHex()
                    assertNull(repo.get(aliceHex).name)
                    assertEquals(1, index.requests.size)
                    clock += 599
                    assertNull(repo.get(aliceHex).name)
                    repo.prefetch(listOf(aliceHex))
                    withContext(Dispatchers.IO) { Thread.sleep(300) } // real time: a lookup in the background would have asked by now
                    assertEquals(1, index.requests.size, "not again within the retry window")
                    clock += 2
                    repo.prefetch(listOf(aliceHex))
                    assertTrue(awaitTrue { index.requests.size == 2 }, "asked again after the retry window: ${index.requests.size}")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a list does not wait for the index relay`() = runTest {
        // prefetch runs before every feed, search and notification list is shown
        SilentWsRelay(answerEose = true).use { own ->
            SilentWsRelay().use { index -> // connects, never answers
                val e = engine(own)
                try {
                    val repo = NostrProfileRepository(e, now = { clock }, indexRelays = listOf(index.url))
                    assertTrue(awaitTrue { e.connectedRelayUrls().isNotEmpty() })
                    val started = System.currentTimeMillis()
                    repo.prefetch(listOf(alice.publicKey().toHex()))
                    val took = System.currentTimeMillis() - started
                    assertTrue(took < 2_000, "prefetch took $took ms")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a lookup leaves an index relay somebody else holds attached`() = runTest {
        SilentWsRelay(answerEose = true).use { own ->
            SilentWsRelay(serve = listOf(profile(alice, "alice").asJson())).use { index ->
                val e = engine(own)
                try {
                    val held = e.attachTemporary(listOf(index.url))
                    val repo = NostrProfileRepository(e, now = { clock }, indexRelays = listOf(index.url))
                    assertEquals("alice", repo.get(alice.publicKey().toHex()).name)
                    assertEquals(held, e.temporaryRelayUrls(), "the other holder keeps its relay")
                    e.detachTemporary(held)
                    assertTrue(e.temporaryRelayUrls().isEmpty())
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `the profile editor finds an own profile that is only on the index relay`() = runTest {
        // without this the editor would start empty and a save would drop the keys other clients wrote (lud16, ...)
        SilentWsRelay(answerEose = true).use { own ->
            SilentWsRelay(serve = listOf(profile(me, "me").asJson())).use { index ->
                val e = engine(own)
                try {
                    val repo = NostrProfileRepository(e, now = { clock }, indexRelays = listOf(index.url))
                    val meHex = me.publicKey().toHex()
                    val fresh = repo.freshMetadata(meHex, Duration.ofSeconds(2))
                    assertTrue(fresh != null && fresh.contains("lud16"), "got: $fresh")
                    assertEquals(fresh, repo.rawMetadata(meHex, Duration.ofSeconds(2)))
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `an index relay that is one of the user's relays is not asked a second time`() = runTest {
        SilentWsRelay(answerEose = true).use { own ->
            val e = engine(own)
            try {
                val repo = NostrProfileRepository(e, now = { clock }, indexRelays = listOf(own.url))
                assertNull(repo.get(alice.publicKey().toHex()).name)
                assertEquals(1, own.requests.size, "one request over the normal connection, none as an index lookup: ${own.requests}")
            } finally {
                e.close()
            }
        }
    }
}
