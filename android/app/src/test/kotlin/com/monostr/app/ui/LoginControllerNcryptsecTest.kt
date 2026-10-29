package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.login.LoginActions
import com.monostr.app.ui.login.LoginController
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.nip55.SignerBridge
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Login with an ncryptsec (NIP-49), spec 2026-10-29 ncryptsec. */
class LoginControllerNcryptsecTest {
    private val vector = "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"
    /** The spec vector re-encoded with log_n 30 (valid checksum), would need 1 TiB of memory. */
    private fun tooStrong() = "ncryptsec1qg09947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wc9kscn0"
    private val secret = "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683"

    private class Recorder : LoginActions {
        val logins = mutableListOf<String>()
        override suspend fun loginLocal(signer: LocalSigner) { logins += signer.secretHex }
        override suspend fun loginAmber(pubkey: String, packageName: String) {}
    }

    private fun controller(a: Recorder) = LoginController(a, SignerBridge(), amberAvailable = false)

    @Test
    fun `right password logs in with the decrypted key`() = runTest {
        val a = Recorder()
        val c = controller(a)
        c.loginWithSecret(vector, "nostr")
        assertEquals(listOf(secret), a.logins)
        assertNull(c.state.value.error)
        assertNull(c.state.value.passwordError)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `wrong password sets the password error only`() = runTest {
        val a = Recorder()
        val c = controller(a)
        c.loginWithSecret(vector, "wrong")
        assertEquals(emptyList<String>(), a.logins)
        assertEquals(uiText(R.string.login_error_wrong_password), c.state.value.passwordError)
        assertNull(c.state.value.error)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `a too strong ncryptsec shows the too strong error under the key field`() = runTest {
        val a = Recorder()
        val c = controller(a)
        c.loginWithSecret(tooStrong(), "nostr")
        assertEquals(uiText(R.string.login_error_too_strong), c.state.value.error)
        assertNull(c.state.value.passwordError)
        assertEquals(emptyList<String>(), a.logins)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `an unparsable ncryptsec shows the invalid key error`() = runTest {
        val a = Recorder()
        val c = controller(a)
        c.loginWithSecret("ncryptsec1xyz", "nostr")
        assertEquals(uiText(R.string.login_error_invalid_key), c.state.value.error)
        assertNull(c.state.value.passwordError)
        assertEquals(emptyList<String>(), a.logins)
    }

    @Test
    fun `a new attempt clears the password error`() = runTest {
        val a = Recorder()
        val c = controller(a)
        c.loginWithSecret(vector, "wrong")
        c.loginWithSecret(vector, "nostr")
        assertNull(c.state.value.passwordError)
        assertEquals(listOf(secret), a.logins)
    }
}
