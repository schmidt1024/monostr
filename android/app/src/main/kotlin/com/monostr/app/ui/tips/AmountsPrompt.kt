package com.monostr.app.ui.tips

import com.monostr.app.data.HintStore
import com.monostr.app.data.TipSettingsStore
import com.monostr.nostr.repo.TipsRepository
import com.monostr.tips.PaymentInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The one-time question to recipients who set up before protocol 0.5: hide tip amounts? Asked only
 * when it can be acted on (watcher speaks 0.5, the announced info still shows amounts) and until
 * answered; a failed "yes" is not an answer.
 */
class AmountsPrompt(
    private val settings: TipSettingsStore,
    private val tips: TipsRepository,
    private val hints: HintStore,
    private val visibility: AmountsVisibility,
    private val pubkey: String,
) {
    suspend fun shouldAsk(): Boolean {
        try {
            val setup = settings.setup.first() ?: return false
            if (setup.amountsHidden) return false
            if (hints.dismissed(pubkey, HINT).first()) return false
            val announced = tips.paymentInfo(pubkey, maxAgeSeconds = 0) as? PaymentInfo.Enabled ?: return false
            if (announced.amountsHidden) return false
            return visibility.supported(setup) == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
    }

    /** True when the question is settled; false when hiding failed (nothing stored, asked again next start). */
    suspend fun answer(hide: Boolean): Boolean {
        if (hide && !visibility.setHidden(true)) return false
        try {
            hints.dismiss(pubkey, HINT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // hiding already took effect; shouldAsk is false from the stored flag
        }
        return true
    }

    companion object {
        const val HINT = "amounts_hidden_prompt"
    }
}

/** What the dialog shows: [busy] while a "yes" is being published, [failed] when it reached no relay. */
data class AmountsPromptState(val shown: Boolean = false, val busy: Boolean = false, val failed: Boolean = false)

/** The dialog around [AmountsPrompt]: a "yes" keeps it open until a relay took it; a failed one stays open and says so. */
class AmountsQuestion(private val prompt: AmountsPrompt, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(AmountsPromptState())
    val state: StateFlow<AmountsPromptState> = _state.asStateFlow()

    suspend fun ask() {
        if (prompt.shouldAsk()) _state.value = AmountsPromptState(shown = true)
    }

    /** Back or a tap outside: no answer, nothing stored; asked again next start. Ignored while a "yes" is being published. */
    fun defer() {
        if (!_state.value.busy) _state.value = AmountsPromptState()
    }

    fun answer(hide: Boolean) {
        if (_state.value.busy) return
        if (!hide) {
            _state.value = AmountsPromptState()
            scope.launch { settle(false) }
            return
        }
        _state.value = AmountsPromptState(shown = true, busy = true)
        scope.launch {
            _state.value = if (settle(true)) AmountsPromptState() else AmountsPromptState(shown = true, failed = true)
        }
    }

    private suspend fun settle(hide: Boolean): Boolean = try {
        prompt.answer(hide)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
