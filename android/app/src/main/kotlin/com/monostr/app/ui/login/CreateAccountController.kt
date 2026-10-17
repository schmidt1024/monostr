package com.monostr.app.ui.login

import com.monostr.app.R
import com.monostr.app.session.NewKey
import com.monostr.app.session.NewKeys
import com.monostr.app.session.PendingSetup
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class CreateStep { Welcome, Backup, Name, Follow }

/** UI state of the create-account flow (spec 2026-10-17 §3). [nsec] is for display only. */
data class CreateAccountState(
    val step: CreateStep = CreateStep.Welcome,
    val nsec: String = "",
    val npub: String = "",
    val saved: Boolean = false,
    val name: String = "",
    val unticked: Set<String> = emptySet(),
    val confirmCancel: Boolean = false,
    val busy: Boolean = false,
    val error: UiText? = null,
) {
    val nameValid: Boolean
        get() = name.trim().let { it.isNotEmpty() && it.length <= CreateAccountController.MAX_NAME }
    val canNext: Boolean
        get() = when (step) {
            CreateStep.Welcome -> true
            CreateStep.Backup -> saved
            CreateStep.Name -> nameValid
            CreateStep.Follow -> false
        }

    /** Never prints the nsec (logs, crash reports); spec 2026-10-17 §6. */
    override fun toString(): String =
        "CreateAccountState(step=$step, npub=$npub, saved=$saved, name=$name, unticked=$unticked, " +
            "confirmCancel=$confirmCancel, busy=$busy, error=$error)"
}

/** What "Let's go" does with the new key and the pending setup (spec 2026-10-17 §4). */
interface CreateAccountActions {
    suspend fun create(key: NewKey, setup: PendingSetup)
}

/**
 * The four steps of the create-account flow (spec 2026-10-17 §3/§4), plain and JVM-testable.
 * The key is made once in the constructor and stays in memory only; the state carries its
 * text for display. Nothing is stored or published before [finish].
 */
class CreateAccountController(
    private val actions: CreateAccountActions,
    private val relays: suspend () -> List<String>,
    private val dmRelays: List<String>,
    keyGen: () -> NewKey = NewKeys::generate,
) {
    private val key: NewKey = keyGen()
    private val _state = MutableStateFlow(CreateAccountState(nsec = key.nsec, npub = key.npub))
    val state: StateFlow<CreateAccountState> = _state.asStateFlow()

    fun next() = _state.update { s ->
        if (!s.canNext) return@update s
        val to = when (s.step) {
            CreateStep.Welcome -> CreateStep.Backup
            CreateStep.Backup -> CreateStep.Name
            CreateStep.Name -> CreateStep.Follow
            CreateStep.Follow -> return@update s
        }
        s.copy(step = to, error = null)
    }

    /** False means the caller leaves the flow; on the first step the first call asks to cancel. */
    fun back(): Boolean {
        val s = _state.value
        val to = when (s.step) {
            CreateStep.Welcome -> {
                if (s.confirmCancel) return false
                _state.update { it.copy(confirmCancel = true) }
                return true
            }
            CreateStep.Backup -> CreateStep.Welcome
            CreateStep.Name -> CreateStep.Backup
            CreateStep.Follow -> CreateStep.Name
        }
        _state.update { it.copy(step = to, error = null) }
        return true
    }

    fun setSaved(saved: Boolean) = _state.update { it.copy(saved = saved) }

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun toggle(pubkey: String) = _state.update {
        it.copy(unticked = if (pubkey in it.unticked) it.unticked - pubkey else it.unticked + pubkey)
    }

    fun confirmCancel() = _state.update { it.copy(confirmCancel = true) }

    fun dismissCancel() = _state.update { it.copy(confirmCancel = false) }

    /** The nsec for the copy button; never logged. */
    fun copyText(): String = key.nsec

    suspend fun finish() {
        val s = _state.value
        if (s.busy || s.step != CreateStep.Follow) return
        _state.update { it.copy(busy = true, error = null) }
        try {
            val follows = SuggestedFollows.all.map { it.pubkey }.filter { it !in s.unticked }
            val setup = PendingSetup(
                name = s.name.trim(),
                relays = relays(),
                dmRelays = dmRelays,
                follows = follows,
                followsDone = follows.isEmpty(),
            )
            actions.create(key, setup)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = uiText(R.string.login_error_failed)) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    companion object {
        const val MAX_NAME = 64
    }
}
