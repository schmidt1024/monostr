package com.monostr.app.ui.lists

import com.monostr.app.R
import com.monostr.app.ui.common.Message
import com.monostr.app.ui.common.asMessage
import com.monostr.app.ui.common.attempt
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.model.ListAddress
import com.monostr.nostr.model.SaveResult
import com.monostr.nostr.repo.FeedTabsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Pins or unpins a list as a home tab (spec 2026-10-18 §2.2, §3.3): a sixth pin says the limit, a failed
 * save offers Retry. [scope] should outlive the screen (the engine's background scope), so leaving at once
 * does not cancel the write.
 */
class PinActions(private val pins: FeedTabsRepository, private val scope: CoroutineScope, private val onMessage: (Message) -> Unit) {
    fun toggle(address: ListAddress, pinned: Boolean) {
        scope.launch {
            when (attempt { if (pinned) pins.unpin(address) else pins.pin(address) } ?: SaveResult.Failed) {
                SaveResult.Ok -> Unit
                SaveResult.TooMany -> onMessage(uiText(R.string.list_pin_limit).asMessage())
                SaveResult.Failed, SaveResult.Locked -> onMessage(Message(uiText(R.string.list_save_failed), retry = { toggle(address, pinned) }))
            }
        }
    }
}
