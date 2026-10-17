package com.monostr.app.session

import com.monostr.app.data.DmSettingsStore
import com.monostr.app.data.PendingSetupStore
import com.monostr.nostr.model.ProfileJson
import com.monostr.nostr.repo.FollowRepository
import com.monostr.nostr.repo.PublishRepository
import kotlinx.coroutines.CancellationException

/**
 * Spec 2026-10-17 §4/§7: publishes the open parts of a new account's [PendingSetupStore] record —
 * kind 0, 10002, 10050, 3 in this order. [exists] tells whether an event of a kind by the account is
 * already stored locally or on the relays; such a part is dropped as done and never overwritten (§7, §11).
 */
class PendingSetupPublisher(
    private val store: PendingSetupStore,
    private val pubkey: String,
    private val publish: PublishRepository,
    private val follows: FollowRepository,
    private val dmSettings: DmSettingsStore,
    private val exists: suspend (kind: Int) -> Boolean,
) {
    /** Publishes what spec 2026-10-17 §4 left open, in §4 order; each part that is done (or already on the relays, §7/§11) is saved as done at once. True = nothing left. */
    suspend fun run(): Boolean {
        var s = store.load(pubkey) ?: return true
        suspend fun part(done: Boolean, kind: Int, mark: (PendingSetup) -> PendingSetup, send: suspend () -> Boolean) {
            if (done) return
            val ok = try {
                exists(kind) || send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (ok) {
                s = mark(s)
                store.save(pubkey, s)
            }
        }
        part(s.profileDone, 0, { it.copy(profileDone = true) }) { publish.profile(ProfileJson.merge(null, mapOf("name" to s.name))).sentToAny }
        part(s.relaysDone, 10002, { it.copy(relaysDone = true) }) { publish.initialRelayList(s.relays).sentToAny }
        // a list found by [exists] is left to DmSync, which adopts it itself
        part(s.dmRelaysDone, 10050, { it.copy(dmRelaysDone = true) }) {
            publish.dmRelayList(s.dmRelays).sentToAny.also {
                if (it) {
                    dmSettings.setRelays(s.dmRelays)
                    dmSettings.setListAdopted(true)
                }
            }
        }
        // an empty list is done as it is: no kind 3 without follows, and the record must not stay forever
        part(s.followsDone || s.follows.isEmpty(), 3, { it.copy(followsDone = true) }) { follows.setInitial(s.follows); true }
        val followsDone = s.followsDone || s.follows.isEmpty()
        val all = s.profileDone && s.relaysDone && s.dmRelaysDone && followsDone
        if (all) store.remove(pubkey)
        return all
    }
}
