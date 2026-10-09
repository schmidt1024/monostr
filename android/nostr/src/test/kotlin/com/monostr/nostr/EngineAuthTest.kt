package com.monostr.nostr

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import java.nio.file.Path
import java.time.Duration

class EngineAuthTest {
    @TempDir lateinit var dir: Path
    private val me = Keys.parse("0000000000000000000000000000000000000000000000000000000000000001")
    /** One LMDB directory per engine: a closed engine's environment is only released by the native side later. */
    private suspend fun engine(relay: SilentWsRelay, name: String) =
        NostrEngine.create(dir.resolve(name).toString(), LocalSigner(me.secretKey().toHex()), listOf(relay.url))

    @Test
    fun `an AUTH the relay accepted is reported for that relay`() = runTest {
        SilentWsRelay(authChallenge = "challenge-1").use { relay ->
            val e = engine(relay, "lmdb")
            e.connect()
            assertTrue(awaitTrue(10_000) { relay.url in e.authenticatedRelays() }, "the AUTH OK never reached the engine")
            assertEquals(1, relay.auths.size)
            e.close()
        }
    }

    @Test
    fun `an auth-required CLOSED counts as a refusal after the mark, another reason does not`() = runTest {
        SilentWsRelay(closeWith = "auth-required: members only", answerEose = true).use { relay ->
            val e = engine(relay, "lmdb-auth")
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() })
            val mark = e.closedMark()
            e.subscribe(Filter().kind(Kind(1u)))
            assertTrue(awaitTrue { e.authRefusedSince(mark, listOf(relay.url)).isNotEmpty() })
            assertTrue(e.authRefusedSince(e.closedMark(), listOf(relay.url)).isEmpty())
            e.close()
        }
        SilentWsRelay(closeWith = "error: too many subscriptions", answerEose = true).use { relay ->
            val e = engine(relay, "lmdb-other")
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() })
            val mark = e.closedMark()
            e.subscribe(Filter().kind(Kind(1u)))
            assertTrue(awaitTrue { e.closedSince(mark, listOf(relay.url)) })
            assertTrue(e.authRefusedSince(mark, listOf(relay.url)).isEmpty())
            e.close()
        }
    }

    @Test
    fun `strfry's ERROR-prefixed refusals are recognised`() {
        val strfry = "ERROR: auth-required: requested filter requires authentication"
        assertTrue(NostrEngine.isAuthRequired(strfry))
        assertTrue(NostrEngine.isAuthRefusal(strfry))
        assertTrue(NostrEngine.isAuthRequired("  error:AUTH-REQUIRED: x"))
        assertFalse(NostrEngine.isAuthRequired("ERROR: restricted: members only"))
        assertTrue(NostrEngine.isAuthRefusal("ERROR: restricted: members only"))
        assertFalse(NostrEngine.isAuthRequired("ERROR: bad req: too many filters"))
        assertFalse(NostrEngine.isAuthRefusal("ERROR: bad req: too many filters"))
    }

    @Test
    fun `strfry's ERROR auth-required CLOSED counts as an auth refusal`() = runTest {
        SilentWsRelay(closeWith = "ERROR: auth-required: requested filter requires authentication", answerEose = true).use { relay ->
            val e = engine(relay, "lmdb-strfry")
            e.connect()
            assertTrue(awaitTrue { e.connectedNormalRelayUrls().isNotEmpty() })
            val mark = e.closedMark()
            e.subscribe(Filter().kind(Kind(1u)))
            assertTrue(awaitTrue { e.authRefusedSince(mark, listOf(relay.url)).isNotEmpty() })
            e.close()
        }
    }

    @Test
    fun `an AUTH the relay refused is reported with the relay's reason and never counts as authenticated`() = runTest {
        SilentWsRelay(authChallenge = "challenge-1", rejectAuthWith = "error: relay needs serviceUrl to be configured before AUTH can work").use { relay ->
            val e = engine(relay, "lmdb-rejected")
            e.connect()
            assertTrue(awaitTrue(10_000) { e.authRejection(relay.url) != null }, "the AUTH OK false never reached the engine")
            assertEquals("error: relay needs serviceUrl to be configured before AUTH can work", e.authRejection(relay.url)!!.message)
            assertFalse(relay.url in e.authenticatedRelays())
            e.close()
        }
    }

    @Test
    fun `waiting for a healed refusal ends at once when the relay refused the AUTH`() = runTest {
        SilentWsRelay(
            authChallenge = "challenge-1", rejectAuthWith = "error: no", refuseUntilAuth = "auth-required: sign in", answerEose = true,
        ).use { relay ->
            val e = engine(relay, "lmdb-wait")
            e.connect()
            val mark = e.closedMark()
            runCatching { e.fetchFrom(listOf(relay.url), Filter().kind(Kind(1059u)), Duration.ofSeconds(3)) }
            assertTrue(awaitTrue(10_000) { e.authRefusedSince(mark, listOf(relay.url)).isNotEmpty() }, "no auth-required CLOSED arrived")
            assertTrue(awaitTrue(10_000) { e.authRejection(relay.url) != null })
            val started = System.nanoTime()
            assertFalse(e.awaitAuthAfterRefusal(mark, listOf(relay.url), Duration.ofSeconds(20)))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "it waited for an AUTH OK that cannot come")
            e.close()
        }
    }
}
