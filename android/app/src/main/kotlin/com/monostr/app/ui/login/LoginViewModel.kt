package com.monostr.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.nip55.Nip55
import com.monostr.nostr.nip55.SignerBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(val amberAvailable: Boolean = false, val busy: Boolean = false, val error: UiText? = null, val notice: UiText? = null)

/** What the ViewModel needs from the session; kept small so tests can fake it. */
interface LoginActions {
    suspend fun loginLocal(signer: LocalSigner)
    suspend fun loginAmber(pubkey: String, packageName: String)
}

/** Login logic without Android types: nsec validation and the NIP-55 get_public_key round trip. */
class LoginController(
    private val actions: LoginActions,
    private val bridge: SignerBridge,
    amberAvailable: Boolean,
    restoreMessage: UiText? = null,
) {
    private val _state = MutableStateFlow(LoginUiState(amberAvailable = amberAvailable, notice = restoreMessage))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    suspend fun loginWithSecret(input: String) {
        val signer = LocalSigner.parseOrNull(input)
        if (signer == null) {
            _state.update { it.copy(error = uiText(R.string.login_error_invalid_key)) }
            return
        }
        run(_state) { actions.loginLocal(signer) }
    }

    suspend fun loginWithAmber() {
        run(_state) {
            val (ok, response) = bridge.request(Nip55.getPublicKey())
            when (val outcome = Nip55.parsePubkeyResponse(ok, response)) {
                is Nip55.Outcome.Pubkey -> actions.loginAmber(outcome.pubkey, outcome.packageName)
                Nip55.Outcome.Rejected -> throw LoginFailure(uiText(R.string.login_error_rejected))
                is Nip55.Outcome.Failed, is Nip55.Outcome.Signed, is Nip55.Outcome.Result -> throw LoginFailure(uiText(R.string.login_error_signer))
            }
        }
    }

    private suspend fun run(state: MutableStateFlow<LoginUiState>, block: suspend () -> Unit) {
        state.update { it.copy(busy = true, error = null, notice = null) }
        try {
            block()
            state.update { it.copy(busy = false) }
        } catch (e: CancellationException) {
            state.update { it.copy(busy = false) }
            throw e
        } catch (e: LoginFailure) {
            state.update { it.copy(busy = false, error = e.userText) }
        } catch (e: Exception) {
            // engine or Keystore failure: never show the exception text
            state.update { it.copy(busy = false, error = uiText(R.string.login_error_failed)) }
        }
    }

    /** A login failure with a fixed text for the UI. */
    private class LoginFailure(val userText: UiText) : Exception()
}
