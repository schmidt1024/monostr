package com.monostr.app.ui.common

import com.monostr.app.R
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.ListOutcome
import com.monostr.nostr.repo.MuteRepository
import com.monostr.nostr.repo.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Mute/unmute with optimistic effect (the repository flips [MuteRepository.muted] at once) and the
 * usual failure texts (spec 9.4); `onResult` gets the text to show.
 */
class MuteActions(
    private val mute: MuteRepository,
    private val scope: CoroutineScope,
    private val profiles: ProfileRepository,
    /**
     * The scope the list writes run on, so they survive the screen that started them (mute and leave is the
     * natural gesture); null (tests) = [scope]. The result callback may then run after the screen is gone: it
     * only updates the screen's state flow.
     */
    private val background: CoroutineScope? = null,
) {
    private val writes: CoroutineScope get() = background ?: scope

    /** [onResult] gets "Name muted" and true on success (the screen offers "Undo"), a failure text and false otherwise. */
    fun mute(pubkey: String, onResult: (UiText, Boolean) -> Unit) {
        writes.launch {
            // read before the write, so the confirmation (and its "Undo") shows as soon as the write returns
            val name = name(pubkey)
            val outcome = try {
                mute.mute(pubkey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(e.userMessage(), false)
                return@launch
            }
            if (outcome == ListOutcome.Ok) onResult(uiText(R.string.mute_done, name), true)
            else onResult(failure(outcome), false)
        }
    }

    /** The account's shown name from the stored profile (never a relay fetch); the short pubkey without one. */
    private suspend fun name(pubkey: String): String = try {
        profiles.local(listOf(pubkey)).firstOrNull()?.shownName ?: Profile.shortPubkey(pubkey)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Profile.shortPubkey(pubkey)
    }

    /** Success is silent ([onResult] gets null). */
    fun unmute(pubkey: String, onResult: (UiText?) -> Unit) {
        writes.launch {
            val message = try {
                mute.unmute(pubkey).let { if (it == ListOutcome.Ok) null else failure(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.userMessage()
            }
            onResult(message)
        }
    }

    private fun failure(outcome: ListOutcome): UiText = when (outcome) {
        ListOutcome.Unsupported -> uiText(R.string.mute_read_only)
        ListOutcome.Rejected -> uiText(R.string.error_signing_rejected)
        // NotLoaded: the list could not be read first, so nothing was written
        ListOutcome.Ok, ListOutcome.PublishFailed, ListOutcome.NotLoaded -> uiText(R.string.error_send_failed)
    }
}
