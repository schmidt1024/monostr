package com.monostr.app.ui.tips

import com.monostr.app.net.SendGate
import com.monostr.app.data.HintStore
import com.monostr.app.data.PendingTipStore
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.data.UiSettingsStore
import com.monostr.app.session.NostrSession
import com.monostr.app.session.SessionState
import com.monostr.nostr.model.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Builds the per-screen tip helpers from the active session; keeps the ViewModels free of wiring. */
class TipSheetFactory @Inject constructor(
    private val session: NostrSession,
    private val settings: TipSettingsStore,
    private val pending: PendingTipStore,
    private val uiSettings: UiSettingsStore,
    private val sendGate: SendGate,
    private val hints: HintStore,
) {
    fun decorations(scope: CoroutineScope): TipDecorations {
        val r = session.requireReady()
        return TipDecorations(r.tips, pending, r.profiles, r.publish, scope, selfPubkey = r.pubkey, gate = r.startupGate, sendGate = r.sendGate)
    }

    fun tipSheet(scope: CoroutineScope): TipSheetController {
        val r = session.requireReady()
        return TipSheetController(r.tips, r.watchers, settings, pending, scope, r.authorRelays, { uiSettings.authorRelays.first() }, sendGate = r.sendGate, takeWalletNote = walletNoteOnce({ r.sendGate.viaTor }, hints, r.pubkey), recipientName = { pk -> storedName(r.profiles.local(listOf(pk)).firstOrNull()) })
    }

    fun profileTipWatch(pubkey: String, scope: CoroutineScope): ProfileTipWatch =
        // follows the session: nothing at logout, the new repository after a Tor switch (the view model may outlive both)
        ProfileTipWatch(pubkey, session.state.map { (it as? SessionState.Active)?.ready?.tips }.distinctUntilChanged(), pending, scope, sendGate = sendGate)
}

/** Display name, then name; null without one (the payto title then has no name, not a shortened key). */
internal fun storedName(profile: Profile?): String? =
    profile?.displayName?.takeIf { it.isNotBlank() } ?: profile?.name?.takeIf { it.isNotBlank() }

private const val HINT_TOR_WALLET = "tor-wallet"

/** Spec 2026-11-06 5.6: true the first time under Tor for this account, and then never again. */
internal fun walletNoteOnce(viaTor: () -> Boolean, hints: HintStore, pubkey: String): suspend () -> Boolean = {
    viaTor() && !hints.dismissed(pubkey, HINT_TOR_WALLET).first() && run { hints.dismiss(pubkey, HINT_TOR_WALLET); true }
}
