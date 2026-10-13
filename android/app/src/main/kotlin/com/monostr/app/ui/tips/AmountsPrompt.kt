package com.monostr.app.ui.tips

import com.monostr.app.data.HintStore
import com.monostr.app.data.TipSettingsStore
import com.monostr.nostr.repo.TipsRepository
import com.monostr.tips.PaymentInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

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
