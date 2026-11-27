package com.monostr.app.ui.accounts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monostr.app.R
import com.monostr.app.data.SessionInfo
import com.monostr.app.data.SignerType
import com.monostr.app.session.AccountBusy
import com.monostr.app.session.NostrSession
import com.monostr.app.session.SessionState
import com.monostr.app.session.SwitchResult
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.login.LoginActions
import com.monostr.app.ui.login.LoginController
import com.monostr.app.ui.login.LoginUiState
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.Npub
import com.monostr.nostr.model.Profile
import com.monostr.nostr.nip55.AmberSigner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One account in the switcher; [name] and [picture] come from the registry, never from an inactive account's own store. */
data class AccountRow(
    val pubkey: String,
    val name: String?,
    val picture: String?,
    val npubShort: String,
    val active: Boolean,
    /** Its key is unusable for good: the row offers «Sign in again» instead of a switch. */
    val needsSignIn: Boolean = false,
    val amber: Boolean = false,
)

/** [busy]: a send of the active account runs, no switch now. */
data class AccountsUi(val accounts: List<AccountRow> = emptyList(), val busy: Boolean = false)

/** The rows of [infos], [active] marked; [needsSignIn] asked per account. */
internal suspend fun accountRows(infos: List<SessionInfo>, active: String?, needsSignIn: suspend (SessionInfo) -> Boolean): List<AccountRow> =
    infos.map { info ->
        AccountRow(
            pubkey = info.pubkey,
            name = info.name?.takeIf { it.isNotBlank() },
            picture = info.picture?.takeIf { it.isNotBlank() },
            npubShort = runCatching { Npub.short(info.pubkey) }.getOrDefault(Profile.shortPubkey(info.pubkey)),
            active = info.pubkey == active,
            needsSignIn = info.pubkey != active && needsSignIn(info),
            amber = info.signerType == SignerType.AMBER,
        )
    }

/**
 * Spec «Switcher UI» / «Adding an account»: the accounts on this device for the switcher sheet, the switch itself and the Amber
 * row of the add sheet. A finished switch or add recreates the activity (MainActivity collects [NostrSession.switched]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AccountsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: NostrSession,
    busy: AccountBusy,
) : ViewModel() {
    // while a switch runs the state is Loading: the last active account stays marked
    private val active = session.state.filterNot { it is SessionState.Loading }
        .map { (it as? SessionState.Active)?.ready?.pubkey }.distinctUntilChanged()

    // the key checks (a Keystore read and parse per inactive key account) run once per change of the list or the active account,
    // not on every busy toggle of a publish
    private val rows = combine(session.accounts, active) { infos, pk -> infos to pk }
        .mapLatest { (infos, pk) -> accountRows(infos, pk, session::needsSignIn) }

    val state: StateFlow<AccountsUi> = combine(rows, busy.busy) { r, b -> AccountsUi(r, b) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUi())

    private val _message = MutableStateFlow<UiText?>(null)
    /** Why the last switch did not happen; shown in the sheet until the next try. */
    val message: StateFlow<UiText?> = _message.asStateFlow()

    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    /**
     * Switches to [pubkey], then [onDone]; a refused or failed switch leaves a [message] instead. A failed one is also told by
     * [AccountNotices]: the sheet leaves composition while the switch runs (final review M5).
     */
    fun switchTo(pubkey: String, onDone: () -> Unit = {}) {
        if (_switching.value) return
        _switching.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                when (session.switchTo(pubkey)) {
                    SwitchResult.Done, SwitchResult.AlreadyActive -> onDone()
                    SwitchResult.Busy -> _message.value = uiText(R.string.accounts_busy)
                    SwitchResult.Failed -> {
                        _message.value = uiText(R.string.accounts_switch_failed)
                        AccountNotices.post(AccountNotice.SwitchFailed)
                    }
                }
            } finally {
                _switching.value = false
            }
        }
    }

    /**
     * Spec «Removing an account» from the switcher (final review I2): an account that is not shown, also one whose key is gone, or
     * the stale active one from the login screen. Runs to the end once begun (the session's own rule); a refusal is told.
     */
    fun remove(pubkey: String, keepSettings: Boolean = false) {
        if (_switching.value) return
        _switching.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                session.removeAccount(pubkey, keepSettings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.monostr.app.data.KeepSettingsFailedException) {
                // nothing was removed
                _message.value = uiText(R.string.accounts_keep_failed)
            } catch (e: Exception) {
                _message.value = uiText(R.string.accounts_remove_failed)
                AccountNotices.post(AccountNotice.RemoveFailed)
            } finally {
                _switching.value = false
            }
        }
    }

    fun clearMessage() { _message.value = null }

    /** Whether Amber (a NIP-55 signer) is installed now; asked each time the add sheet opens. */
    fun amberInstalled(): Boolean = AmberSigner.isInstalled(context)

    /** The add sheet's Amber row: the first login's round trip, without its key or create parts. */
    val amber = LoginController(
        actions = object : LoginActions {
            override suspend fun loginLocal(signer: LocalSigner) = session.loginLocal(signer)
            override suspend fun loginAmber(pubkey: String, packageName: String) = session.loginAmber(pubkey, packageName)
        },
        bridge = session.bridge,
        amberAvailable = true,
        onAlreadyActive = { session.activePubkey()?.let { AccountNotices.post(AccountNotice.AlreadyUsing(it)) } },
    )
    val amberState: StateFlow<LoginUiState> get() = amber.state

    fun loginWithAmber() { viewModelScope.launch { amber.loginWithAmber() } }
}
