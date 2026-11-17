package com.monostr.nostr.nip55

import android.content.ContextWrapper
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.SigningRejectedException
import com.monostr.tips.event.EventJson
import com.monostr.tips.event.UnsignedEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmberSignerTest {
    @Test
    fun `a dismissed signer prompt ends in SigningRejectedException`() = runTest {
        val bridge = SignerBridge()
        // No content resolver on the JVM: the resolver attempt finds nothing and the signer activity is asked.
        val signer = AmberSigner(ContextWrapper(null), "com.greenart7c3.nostrsigner", "c".repeat(64), bridge)
        val signing = async { runCatching { signer.sign(UnsignedEvent(24242, "Upload", emptyList(), 1_700_000_000)) } }
        val pending = bridge.pending.filterNotNull().first()
        bridge.complete(pending.id, resultOk = false, response = null)
        val error = signing.await().exceptionOrNull()
        assertTrue(error is SigningRejectedException, "was $error")
    }

    private val skA = "1".repeat(64)
    private val skB = "2".repeat(64)

    private suspend fun answered(asPubkey: String, answer: suspend (UnsignedEvent) -> String): Result<com.monostr.tips.event.Event> = kotlinx.coroutines.coroutineScope {
        val bridge = SignerBridge()
        val signer = AmberSigner(ContextWrapper(null), "com.greenart7c3.nostrsigner", asPubkey, bridge)
        val request = UnsignedEvent(1, "hello", listOf(listOf("t", "x")), 1_700_000_000)
        val signing = async { runCatching { signer.sign(request) } }
        val pending = bridge.pending.filterNotNull().first()
        bridge.complete(pending.id, resultOk = true, response = Nip55.Response(null, answer(request), null, false))
        signing.await()
    }

    @Test
    fun `the event the signer returns is taken when it is the one asked for`() = runTest {
        val a = LocalSigner(skA)
        val got = answered(a.pubkey) { EventJson.encode(a.sign(it)) }
        assertTrue(got.isSuccess, "was ${got.exceptionOrNull()}")
    }

    @Test
    fun `an event of another account is refused`() = runTest {
        val a = LocalSigner(skA)
        val b = LocalSigner(skB)
        assertTrue(answered(a.pubkey) { EventJson.encode(b.sign(it)) }.isFailure)
    }

    @Test
    fun `other content or a broken signature is refused`() = runTest {
        val a = LocalSigner(skA)
        assertTrue(answered(a.pubkey) { EventJson.encode(a.sign(it.copy(content = "something else"))) }.isFailure)
        assertTrue(answered(a.pubkey) { EventJson.encode(a.sign(it).let { e -> e.copy(sig = e.sig.reversed()) }) }.isFailure)
    }
}
