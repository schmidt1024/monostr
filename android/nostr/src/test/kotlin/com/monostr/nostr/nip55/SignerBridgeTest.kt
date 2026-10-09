package com.monostr.nostr.nip55

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignerBridgeTest {
    @Test
    fun `completes only the request whose id matches, ignores later completions`() = runTest {
        val bridge = SignerBridge()
        val job = launch { bridge.request(Nip55.getPublicKey()) }
        advanceUntilIdle()
        val pending = bridge.pending.value
        assertNotNull(pending)

        // a completion for a stale/unrelated id must not resolve the pending request
        bridge.complete("other", true, Nip55.Response("x", null, "pkg", false))
        advanceUntilIdle()
        assertTrue(job.isActive)
        assertNotNull(bridge.pending.value)

        // the matching id resolves it
        bridge.complete(pending!!.id, true, Nip55.Response("x", null, "pkg", false))
        job.join()
        assertNull(bridge.pending.value)

        // a second completion after the request is already done is ignored (no exception)
        bridge.complete(pending.id, true, Nip55.Response("y", null, "pkg", false))
    }
}
