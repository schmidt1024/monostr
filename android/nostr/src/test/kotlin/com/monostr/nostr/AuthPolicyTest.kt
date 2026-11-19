package com.monostr.nostr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AuthPolicyTest {
    private fun policy(own: Set<String> = emptySet(), extra: suspend () -> Set<String> = { emptySet() }, borrowed: Set<String> = emptySet()) =
        AuthPolicy().apply { ownList = { it in own }; ownExtra = extra; this.borrowed = { it in borrowed } }

    @Test fun `refuses unknown, null and unparsable relays`() = runTest {
        val p = policy(own = setOf("wss://own.example"))
        assertFalse(p.allows("wss://stranger.example"))
        assertFalse(p.allows(null))
        assertFalse(p.allows("not a url"))
    }

    @Test fun `allows a normal relay, the own DM inbox and the configured search relay`() = runTest {
        val p = policy(own = setOf("wss://own.example"), extra = { setOf("wss://inbox.example", "wss://search.example") })
        assertTrue(p.allows("wss://own.example"))
        assertTrue(p.allows("wss://inbox.example/"))
        assertTrue(p.allows("wss://search.example"))
    }

    @Test fun `url tricks never widen the allowlist`() = runTest {
        val p = policy(own = setOf("wss://own.example"))
        assertTrue(p.allows("WSS://Own.EXAMPLE:443/"))
        for (t in listOf("ws://own.example", "wss://own.example.", "wss://own.example@evil.example", "wss://own.example/x", "wss://own.example:444", "wss://оwn.example"))
            assertFalse(p.allows(t), t)
    }

    @Test fun `a borrowed relay is refused unless it is an own extra relay`() = runTest {
        assertFalse(policy(own = setOf("wss://a.example"), borrowed = setOf("wss://a.example")).allows("wss://a.example"))
        assertTrue(policy(own = setOf("wss://a.example"), extra = { setOf("wss://a.example") }, borrowed = setOf("wss://a.example")).allows("wss://a.example"))
    }

    @Test fun `a throwing rule refuses, a normal relay does not depend on the extras`() = runTest {
        assertFalse(AuthPolicy().apply { ownList = { error("boom") } }.allows("wss://own.example"))
        val p = policy(own = setOf("wss://own.example"), extra = { error("datastore") })
        assertTrue(p.allows("wss://own.example"))
        assertFalse(p.allows("wss://inbox.example"))
    }

    @Test fun `cancellation is not swallowed`() = runTest {
        val p = policy(extra = { throw CancellationException("gone") })
        assertThrows<CancellationException> { p.allows("wss://inbox.example") }
    }
}
