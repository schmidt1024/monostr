package com.monostr.nostr

import com.monostr.nostr.repo.NostrAuthorRelaysRepository
import com.monostr.nostr.repo.NostrDmRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.giftWrap
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spec 2026-11-19, end to end: relays that send a NIP-42 challenge right after connecting (as strfry and most paid relays
 * do) learn the user's pubkey only when they are the user's own. A stranger reached through sendTo, a temporary attach,
 * an author's outbox or a relay the user removed never receives a kind 22242; the own DM inbox outside the list does.
 */
class EngineAuthAllowlistTest {
    @TempDir lateinit var dir: Path
    private val alice = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    private val bob = Keys.generate()
    private val strfryRefusal = "ERROR: auth-required: requested filter requires authentication"

    private suspend fun wrap() =
        giftWrap(NostrSigner.keys(bob), alice.publicKey(), EventBuilder.privateMsgRumor(alice.publicKey(), "hi").build(bob.publicKey()), emptyList())

    /** The stranger was reached and had the time to receive an AUTH answer to its challenge, yet got none. */
    private suspend fun assertNoAuth(stranger: SilentWsRelay) {
        assertTrue(awaitTrue(10_000) { stranger.connections > 0 }, "the stranger's relay was never reached")
        Thread.sleep(1_500)
        assertTrue(stranger.auths.isEmpty(), "AUTH sent: ${stranger.auths}")
    }

    @Test
    fun `sendTo a stranger inbox delivers the wrap without an AUTH`() = runTest {
        SilentWsRelay().use { own ->
            SilentWsRelay(authChallenge = "c", acceptEvents = true).use { stranger ->
                val e = connectedEngine(dir, own, alice.secretKey().toHex())
                try {
                    val w = wrap()
                    e.save(w)
                    val id = w.id().toHex()
                    val result = e.sendTo(listOf(stranger.url), id)
                    assertTrue(result.sentToAny, "not delivered: ${result.failedRelays}")
                    assertTrue(stranger.events.any { id in it }, "the stranger never got the wrap")
                    assertNoAuth(stranger)
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a temporary relay gets no AUTH`() = runTest {
        SilentWsRelay().use { own ->
            SilentWsRelay(authChallenge = "c").use { stranger ->
                val e = connectedEngine(dir, own, alice.secretKey().toHex())
                try {
                    e.attachTemporary(listOf(stranger.url))
                    assertNoAuth(stranger)
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `an author's outbox gets no AUTH`() = runTest {
        SilentWsRelay().use { own ->
            SilentWsRelay(authChallenge = "c", answerEose = true).use { stranger ->
                val e = connectedEngine(dir, own, alice.secretKey().toHex())
                try {
                    NostrAuthorRelaysRepository(e).fetchNotes(listOf(stranger.url), bob.publicKey().toHex(), 5)
                    assertTrue(stranger.requests.isNotEmpty(), "the outbox was never asked")
                    assertNoAuth(stranger)
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `the own DM inbox outside the relay list is authenticated and read`() = runTest {
        SilentWsRelay().use { own ->
            SilentWsRelay(authChallenge = "c", refuseUntilAuth = strfryRefusal, serve = listOf(wrap().asJson())).use { inbox ->
                val e = connectedEngine(dir, own, alice.secretKey().toHex())
                try {
                    e.authPolicy.ownExtra = { setOfNotNull(RelayKeys.of(inbox.url)) }
                    e.attachTemporary(listOf(inbox.url))
                    val fetch = NostrDmRepository(e, fetchTimeout = Duration.ofSeconds(3), authWait = Duration.ofSeconds(5))
                        .fetchWraps(listOf(inbox.url), since = 0)
                    assertEquals(1, fetch.wraps.size)
                    assertTrue(fetch.completed)
                    assertEquals(1, inbox.auths.size, "AUTHs: ${inbox.auths}")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `a relay removed from the normal set gets no AUTH on its next connection`() = runTest {
        SilentWsRelay().use { own ->
            SilentWsRelay(authChallenge = "c").use { r ->
                val e = connectedEngine(dir, listOf(own, r), alice.secretKey().toHex())
                try {
                    assertTrue(awaitTrue(10_000) { r.auths.size == 1 }, "the own relay was never authenticated: ${r.auths}")
                    e.removeRelays(listOf(r.url))
                    e.attachTemporary(listOf(r.url))
                    assertTrue(awaitTrue(10_000) { r.connections >= 2 }, "no second connection: ${r.connections}")
                    Thread.sleep(1_500)
                    assertEquals(1, r.auths.size, "AUTH sent after the removal: ${r.auths}")
                } finally {
                    e.close()
                }
            }
        }
    }

    @Test
    fun `an own relay is authenticated again after a reconnect`() = runTest {
        SilentWsRelay(authChallenge = "c").use { r ->
            val e = connectedEngine(dir, r, alice.secretKey().toHex())
            try {
                assertTrue(awaitTrue(10_000) { r.auths.size == 1 }, "the own relay was never authenticated: ${r.auths}")
                e.reconnectAll()
                assertTrue(awaitTrue(10_000) { r.connections >= 2 && r.auths.size == 2 }, "connections ${r.connections}, AUTHs ${r.auths.size}")
            } finally {
                e.close()
            }
        }
    }

    @Test
    fun `a relay added mid-session is authenticated on its first connection`() = runTest {
        SilentWsRelay().use { own ->
            SilentWsRelay(authChallenge = "c").use { r2 ->
                val e = connectedEngine(dir, own, alice.secretKey().toHex())
                try {
                    // a live subscription: addRelays connects the new relay before its trailing refresh lists it
                    e.subscribe(Filter().kind(Kind(1u)))
                    val armed = AtomicBoolean(false)
                    // the refresh waits until the challenge was answered: the relay is in the pool, not yet in the normal set
                    e.poolProbe = { at ->
                        if (at == "added") armed.set(true)
                        if (at == "read" && armed.compareAndSet(true, false)) withTimeoutOrNull(3_000) { while (r2.auths.isEmpty()) delay(20) }
                    }
                    e.addRelays(listOf(r2.url))
                    e.poolProbe = null
                    awaitTrue(2_000) { r2.auths.isNotEmpty() }
                    assertEquals(1, r2.auths.size, "AUTHs: ${r2.auths}")
                    assertEquals(1, r2.connections)
                } finally {
                    e.close()
                }
            }
        }
    }
}
