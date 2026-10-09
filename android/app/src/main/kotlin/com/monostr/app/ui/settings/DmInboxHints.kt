package com.monostr.app.ui.settings

import com.monostr.app.data.DmSettingsStore
import com.monostr.nostr.PublishResult
import com.monostr.nostr.repo.DmRelaysRepository

/** Spec 6: the two cards above the DM relay list; pure, the list is the effective one (adopted or local). */
object DmInboxHints {
    const val MONOSTR = "wss://relay.monostr.com"
    const val HINT_MISSING = "dm_missing"
    const val HINT_SECOND = "dm_second"

    /**
     * DM-capable inbox relays: NIP-11 lists NIP-17 and `limitation.auth_required` is true (only the
     * recipient reads its wraps). Verified 2026-09-30; unverified candidates stay out.
     */
    val DM_RELAY_SUGGESTIONS: List<String> = listOf("wss://auth.nostr1.com")

    fun missing(relays: List<String>): Boolean = MONOSTR !in relays
    fun needsSecond(relays: List<String>): Boolean = relays.size == 1
    fun suggestions(relays: List<String>): List<String> = DM_RELAY_SUGGESTIONS.filter { it !in relays }
    fun full(relays: List<String>): Boolean = relays.size >= DmRelaysRepository.MAX
}

/**
 * Spec 11: a new own inbox list is published (kind 10050) first; only after a relay accepted it is it
 * stored and the DM sync restarted on it ([changed]). A refused or failed publish throws and leaves
 * the stored list as it was, so [SettingsController] shows the old list (and the hint card) again.
 */
suspend fun applyDmRelays(list: List<String>, publish: suspend (List<String>) -> PublishResult, settings: DmSettingsStore, changed: suspend () -> Unit) {
    if (!publish(list).sentToAny) error("dm relay list not accepted by any relay")
    settings.setRelays(list)
    changed()
}
