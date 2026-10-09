package com.monostr.app.ui.settings

import com.monostr.app.R
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.common.userMessage
import com.monostr.nostr.PublishResult
import kotlinx.coroutines.CancellationException

/**
 * Publishes the app's relays as the user's NIP-65 relay list (kind 10002) and returns the message
 * to show. Other clients and the tip watcher look for the user on the relays of that list; the app
 * itself only creates one when none exists, so a list left behind by another client stays until
 * the user replaces it here.
 */
suspend fun publishRelayList(relays: suspend () -> List<String>, publish: suspend (List<String>) -> PublishResult): UiText =
    try {
        if (publish(relays()).sentToAny) uiText(R.string.settings_msg_relay_list_published) else uiText(R.string.settings_msg_relay_list_not_published)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }
