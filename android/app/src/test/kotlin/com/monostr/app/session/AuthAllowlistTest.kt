package com.monostr.app.session

import com.monostr.nostr.AuthPolicy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class AuthAllowlistTest {
    @Test fun `an inbox edit counts at the next challenge`() = runTest {
        val dm = MutableStateFlow(listOf("wss://A.example/"))
        val search = MutableStateFlow(emptyList<String>())
        val p = AuthPolicy()
        AuthAllowlist.wire(p, dm, search, borrowed = { false })
        assertTrue(p.allows("wss://a.example"))
        dm.value = listOf("wss://b.example")
        assertFalse(p.allows("wss://a.example"))
        assertTrue(p.allows("wss://b.example"))
    }

    @Test fun `configured search relays count, others do not`() = runTest {
        val p = AuthPolicy()
        AuthAllowlist.wire(p, MutableStateFlow(emptyList()), MutableStateFlow(listOf("wss://search.example")), borrowed = { false })
        assertTrue(p.allows("wss://search.example"))
        assertFalse(p.allows("wss://search.other"))
    }

    @Test fun `a hanging source refuses after the timeout`() = runTest {
        val p = AuthPolicy()
        AuthAllowlist.wire(p, flow { awaitCancellation() }, MutableStateFlow(emptyList()), borrowed = { false })
        assertFalse(p.allows("wss://inbox.example")) // virtual time: returns after AUTH_LOOKUP_TIMEOUT_MS
    }

    @Test fun `the sources are read in parallel, one hanging source does not hide the other`() = runTest {
        val p = AuthPolicy()
        AuthAllowlist.wire(p, flow { awaitCancellation() }, flow { awaitCancellation() }, borrowed = { false })
        val start = currentTime
        assertFalse(p.allows("wss://inbox.example"))
        assertTrue(currentTime - start <= AuthAllowlist.AUTH_LOOKUP_TIMEOUT_MS, "took ${currentTime - start} ms")
        val q = AuthPolicy()
        AuthAllowlist.wire(q, flow { awaitCancellation() }, MutableStateFlow(listOf("wss://search.example")), borrowed = { false })
        assertTrue(q.allows("wss://search.example"))
    }

    @Test fun `a throwing source refuses`() = runTest {
        val p = AuthPolicy()
        AuthAllowlist.wire(p, flow { throw IOException("disk") }, MutableStateFlow(listOf("wss://s.example")), borrowed = { false })
        assertFalse(p.allows("wss://inbox.example"))
        assertFalse(p.allows("wss://s.example"))
    }

    @Test fun `an own extra relay is allowed even while borrowed`() = runTest {
        val p = AuthPolicy()
        AuthAllowlist.wire(p, MutableStateFlow(listOf("wss://inbox.example")), MutableStateFlow(emptyList()), borrowed = { true })
        assertTrue(p.allows("wss://inbox.example"))
    }
}
