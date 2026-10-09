package com.monostr.nostr.nip55

import android.content.ContextWrapper
import com.monostr.nostr.SigningRejectedException
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
}
