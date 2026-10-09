package com.monostr.app.ui.tips

import com.monostr.app.data.PendingTipStore
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.session.NostrSession
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/** Builds the per-screen tip helpers from the active session; keeps the ViewModels free of wiring. */
class TipSheetFactory @Inject constructor(
    private val session: NostrSession,
    private val settings: TipSettingsStore,
    private val pending: PendingTipStore,
) {
    fun decorations(scope: CoroutineScope): TipDecorations {
        val r = session.requireReady()
        return TipDecorations(r.tips, pending, r.profiles, r.publish, scope, selfPubkey = r.pubkey)
    }

    fun tipSheet(scope: CoroutineScope): TipSheetController {
        val r = session.requireReady()
        return TipSheetController(r.tips, r.watchers, settings, pending, scope)
    }

    fun profileTipWatch(pubkey: String, scope: CoroutineScope): ProfileTipWatch =
        ProfileTipWatch(pubkey, session.requireReady().tips, pending, scope)
}
