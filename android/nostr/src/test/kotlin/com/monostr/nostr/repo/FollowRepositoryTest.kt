package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.SilentWsRelay
import com.monostr.nostr.SigningRejectedException
import com.monostr.nostr.awaitTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.nio.file.Path
import java.time.Duration

class FollowRepositoryTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val pk = me.publicKey().toHex()
    private val bob = Keys.generate().publicKey().toHex()
    private val carol = Keys.generate().publicKey().toHex()
    private val relayJson = """{"wss://old.example":{"read":true,"write":true}}"""
    private var sends = 0

    private suspend fun engine(relays: List<String> = emptyList()) =
        NostrEngine.create(dir.resolve("lmdb").toString(), LocalSigner(me.secretKey().toHex()), relays)

    private suspend fun connected(relay: SilentWsRelay): NostrEngine {
        val e = engine(listOf(relay.url))
        e.connect()
        assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() }, "loopback relay never connected")
        return e
    }

    /** A relay that accepts: the event is signed with the test key and stored, as signAndPublish does after an OK. */
    private fun okSend(e: NostrEngine): suspend (EventBuilder) -> PublishResult = { b ->
        sends++
        val ev = b.signWithKeys(me); e.save(ev); PublishResult(ev.id().toHex(), 3, listOf("wss://r"), emptyMap())
    }

    private fun kind3(tags: List<List<String>>, content: String = relayJson, at: ULong = 1_000u): Event =
        EventBuilder(Kind(3u), content).tags(tags.map { Tag.parse(it) }).customCreatedAt(Timestamp.fromSecs(at)).signWithKeys(me)

    private suspend fun newest(e: NostrEngine): Event? = e.query(Filter().kind(Kind(3u)).author(me.publicKey()).limit(1u)).firstOrNull()
    private fun Event.tagList() = tags().toVec().map { it.asVec() }
    private suspend fun failure(block: suspend () -> Unit): Throwable? = try { block(); null } catch (e: Exception) { e }

    /** A feed whose kind 3 was fetched from a relay [ago] seconds before [now]. */
    private fun feed(e: NostrEngine, now: Long, ago: Long?) = NostrFeedRepository(e).also { f -> if (ago != null) f.markContactsFetched(now - ago) }

    @Test
    fun `a foreign kind 3 keeps its content and every other tag byte for byte`() = runTest {
        val e = engine()
        val foreign = listOf(
            listOf("p", bob, "wss://bob.example", "bob"),
            listOf("t", "nostr"),
            listOf("alt", "Follow list"),
        )
        e.save(kind3(foreign))
        val repo = NostrFollowRepository(e, feed(e, 2_000, ago = 10), now = { 2_000 }, send = okSend(e))

        repo.follow(carol)
        val followed = newest(e)!!
        assertEquals(foreign + listOf(listOf("p", carol)), followed.tagList())
        assertEquals(relayJson, followed.content())
        assertEquals(2_000L, followed.createdAt().asSecs().toLong())

        repo.unfollow(carol)
        val back = newest(e)!!
        assertEquals(foreign, back.tagList())
        assertEquals(relayJson, back.content())
        assertEquals(2_001L, back.createdAt().asSecs().toLong()) // strictly newer than the list it replaces
        e.close()
    }

    @Test
    fun `no double p, unfollowing someone not followed is a no-op without publish, never the own key`() = runTest {
        val e = engine()
        e.save(kind3(listOf(listOf("p", bob))))
        val repo = NostrFollowRepository(e, feed(e, 2_000, ago = 10), now = { 2_000 }, send = okSend(e))
        repo.follow(bob)
        repo.unfollow(carol)
        assertEquals(0, sends)
        assertEquals(listOf(listOf("p", bob)), newest(e)!!.tagList())
        assertTrue(failure { repo.follow(pk) } is IllegalArgumentException)
        e.close()
    }

    @Test
    fun `a stale local list without a relay answer is NoList and nothing is published`() = runTest {
        val e = engine() // no relays: the fetch finds nothing
        e.save(kind3(listOf(listOf("p", bob))))
        val stale = NostrFollowRepository(e, feed(e, 2_000, ago = 301), now = { 2_000 }, send = okSend(e))
        assertTrue(failure { stale.follow(carol) } is FollowError.NoList)
        val never = NostrFollowRepository(e, feed(e, 2_000, ago = null), now = { 2_000 }, send = okSend(e))
        assertTrue(failure { never.follow(carol) } is FollowError.NoList)
        assertEquals(0, sends)
        assertEquals(listOf(listOf("p", bob)), newest(e)!!.tagList())
        e.close()
    }

    @Test
    fun `a fetch that ran into the timeout without any list publishes nothing`() = runTest {
        SilentWsRelay().use { relay -> // connected, never answers
            val e = connected(relay)
            val repo = NostrFollowRepository(e, NostrFeedRepository(e), send = okSend(e), fetchTimeout = Duration.ofMillis(500))
            assertTrue(failure { repo.follow(bob) } is FollowError.NoList)
            assertEquals(0, sends)
            assertNull(newest(e))
            e.close()
        }
    }

    @Test
    fun `a new account gets a list with exactly one p after a completed empty fetch`() = runTest {
        SilentWsRelay(answerEose = true, acceptEvents = true).use { relay ->
            val e = connected(relay)
            val repo = NostrFollowRepository(e, NostrFeedRepository(e), fetchTimeout = Duration.ofSeconds(3))
            assertEquals(FollowState.NotFollowing, repo.state(bob).first()) // proven absent, not Unknown
            repo.follow(bob)
            val list = newest(e)!!
            assertEquals(listOf(listOf("p", bob)), list.tagList())
            assertEquals("", list.content())
            assertEquals(1, relay.events.size)
            assertEquals(FollowState.Following, repo.state(bob).first())
            e.close()
        }
    }

    @Test
    fun `an empty fetch while a configured relay is not connected is NoList and nothing is published`() = runTest {
        // a port nothing listens on: configured, never connected
        val dead = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { "ws://127.0.0.1:${it.localPort}" }
        SilentWsRelay(answerEose = true, acceptEvents = true).use { relay ->
            val e = engine(listOf(relay.url, dead))
            e.connect()
            assertTrue(awaitTrue { relay.url in e.connectedNormalRelayUrls() }, "loopback relay never connected")
            val repo = NostrFollowRepository(e, NostrFeedRepository(e), fetchTimeout = Duration.ofSeconds(3))
            assertTrue(failure { repo.follow(bob) } is FollowError.NoList)
            assertTrue(relay.events.isEmpty())
            assertNull(newest(e))
            e.close()
        }
    }

    @Test
    fun `an empty fetch a relay answered with CLOSED is NoList and nothing is published`() = runTest {
        SilentWsRelay(answerEose = true, closeWith = "restricted: members only", acceptEvents = true).use { relay ->
            val e = connected(relay)
            val repo = NostrFollowRepository(e, NostrFeedRepository(e), fetchTimeout = Duration.ofSeconds(3))
            assertTrue(failure { repo.follow(bob) } is FollowError.NoList)
            assertTrue(relay.events.isEmpty())
            assertNull(newest(e))
            e.close()
        }
    }

    @Test
    fun `follow without a relay OK leaves the database unchanged`() = runTest {
        val old = kind3(listOf(listOf("p", bob)), at = 1_000u)
        SilentWsRelay(serve = listOf(old.asJson()), rejectEvents = true).use { relay ->
            val e = connected(relay)
            val repo = NostrFollowRepository(e, NostrFeedRepository(e), fetchTimeout = Duration.ofSeconds(3))
            assertTrue(failure { repo.follow(carol) } is FollowError.NotAccepted)
            assertEquals(old.id().toHex(), newest(e)!!.id().toHex())
            assertEquals(FollowState.NotFollowing, repo.state(carol).first())
            e.close()
        }
    }

    @Test
    fun `a relay list is fresh after the fetch and the state follows a write`() = runTest {
        val old = kind3(listOf(listOf("p", bob)), at = 1_000u)
        SilentWsRelay(serve = listOf(old.asJson()), acceptEvents = true).use { relay ->
            val e = connected(relay)
            val feed = NostrFeedRepository(e)
            val repo = NostrFollowRepository(e, feed, fetchTimeout = Duration.ofSeconds(3))
            assertEquals(FollowState.Following, repo.state(bob).first())
            assertEquals(FollowState.NotFollowing, repo.state(carol).first())
            repo.follow(carol)
            assertTrue(feed.contactsFetchedAt != null)
            assertEquals(FollowState.Following, repo.state(carol).first())
            assertEquals(listOf(listOf("p", bob), listOf("p", carol)), newest(e)!!.tagList())
            e.close()
        }
    }

    @Test
    fun `a signer refusal publishes nothing and reaches the caller`() = runTest {
        val e = engine()
        e.save(kind3(listOf(listOf("p", bob))))
        val repo = NostrFollowRepository(e, feed(e, 2_000, ago = 10), now = { 2_000 }, send = { throw SigningRejectedException() })
        assertTrue(failure { repo.follow(carol) } is SigningRejectedException)
        assertEquals(listOf(listOf("p", bob)), newest(e)!!.tagList())
        e.close()
    }
}
