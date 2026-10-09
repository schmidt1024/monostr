package com.monostr.app.ui
import com.monostr.app.ui.common.uiText
import com.monostr.app.R

import com.monostr.nostr.model.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.monostr.app.ui.login.LoginActions
import com.monostr.app.ui.login.LoginController
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.nip55.Nip55
import com.monostr.nostr.nip55.SignerBridge
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LoginControllerTest {
    @Test
    fun `login validates the secret and runs the amber round trip`() = runTest {
        val actions = object : LoginActions {
            var local: String? = null
            var amber: String? = null
            override suspend fun loginLocal(signer: LocalSigner) { local = signer.pubkey }
            override suspend fun loginAmber(pubkey: String, packageName: String) { amber = "$pubkey@$packageName" }
        }
        val bridge = SignerBridge()
        val c = LoginController(actions, bridge, amberAvailable = true)
        c.loginWithSecret("nope")
        assertEquals(uiText(R.string.login_error_invalid_key), c.state.value.error)
        c.loginWithSecret("0000000000000000000000000000000000000000000000000000000000000001")
        assertEquals("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", actions.local)
        assertNull(c.state.value.error)
        assertFalse(c.state.value.busy)
        // amber: the bridge exposes the request; the "activity" answers
        val job = launch { c.loginWithAmber() }
        advanceUntilIdle()
        val pending = bridge.pending.value
        assertNotNull(pending)
        assertEquals("get_public_key", pending!!.request.extras["type"])
        bridge.complete(pending.id, true, Nip55.Response("c".repeat(64), null, "com.greenart7c3.nostrsigner", false))
        job.join()
        assertEquals("${"c".repeat(64)}@com.greenart7c3.nostrsigner", actions.amber)
        val rejected = launch { c.loginWithAmber() }
        advanceUntilIdle()
        val rejectedPending = bridge.pending.value
        assertNotNull(rejectedPending)
        bridge.complete(rejectedPending!!.id, true, Nip55.Response(null, null, null, true))
        rejected.join()
        assertEquals(uiText(R.string.login_error_rejected), c.state.value.error)
        val failed = launch { c.loginWithAmber() }
        advanceUntilIdle()
        bridge.complete(bridge.pending.value!!.id, false, null)
        failed.join()
        assertEquals(uiText(R.string.login_error_signer), c.state.value.error)
    }

    @Test
    fun `engine or keystore failures show a fixed message, never the exception text`() = runTest {
        val actions = object : LoginActions {
            override suspend fun loginLocal(signer: LocalSigner) = throw IllegalStateException("keystore failure: raw key material")
            override suspend fun loginAmber(pubkey: String, packageName: String) {}
        }
        val c = LoginController(actions, SignerBridge(), amberAvailable = false)
        c.loginWithSecret("0000000000000000000000000000000000000000000000000000000000000001")
        assertEquals(uiText(R.string.login_error_failed), c.state.value.error)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `a failed session restore is shown on the login screen`() = runTest {
        val actions = object : LoginActions {
            override suspend fun loginLocal(signer: LocalSigner) {}
            override suspend fun loginAmber(pubkey: String, packageName: String) {}
        }
        val c = LoginController(actions, SignerBridge(), amberAvailable = false, restoreMessage = uiText(R.string.session_restore_failed))
        assertEquals(uiText(R.string.session_restore_failed), c.state.value.notice)
        assertNull(LoginController(actions, SignerBridge(), amberAvailable = false).state.value.notice)
    }
}
