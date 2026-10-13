package com.monostr.app.ui.tips

import com.monostr.app.data.MoneroSetup
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.data.WatcherGateway
import com.monostr.monero.Address
import com.monostr.nostr.repo.TipsRepository
import com.monostr.tips.ProtocolVersion
import com.monostr.tips.Watcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Hidden tip amounts (protocol 0.5) for an existing setup: whether its watcher supports them and
 * switching them on or off. Shared by Settings and the one-time question so both behave the same.
 */
class AmountsVisibility(
    private val tips: TipsRepository,
    private val gateway: WatcherGateway,
    private val settings: TipSettingsStore,
) {
    /** Whether the setup's watcher speaks protocol 0.5; null when it cannot be asked right now. */
    suspend fun supported(setup: MoneroSetup): Boolean? {
        val protocol = try {
            if (setup.isPersonal) {
                (tips.watcherStatus(setup.watcherPubkey, setup.watcherRelays) ?: return null).protocol
            } else {
                gateway.info(setup.watcherUrl).protocol
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return ProtocolVersion.supportsHiddenAmounts(protocol)
    }

    /**
     * Republishes the payment info of the setup stored now (not a snapshot a caller may hold) with
     * [hidden]; only once a relay took it is the flag stored. False when there is no setup, its
     * address does not parse, or publishing failed or reached no relay: then nothing is stored.
     */
    suspend fun setHidden(hidden: Boolean): Boolean {
        val setup = settings.setup.first() ?: return false
        try {
            if (!tips.publishPaymentInfo(Address.parse(setup.address), setup.watcher(), hidden).sentToAny) return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        settings.setSetup(setup.copy(amountsHidden = hidden))
        return true
    }
}

/** The watcher a setup announces: HTTP by URL, personal by key and relays (spec 0.4 §4.3). */
fun MoneroSetup.watcher(): Watcher =
    if (isPersonal) Watcher.Personal(watcherPubkey, watcherRelays) else Watcher.Http(watcherUrl, watcherPubkey)
