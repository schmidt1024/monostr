package com.monostr.nostr.repo

import com.monostr.nostr.NostrEngine
import com.monostr.nostr.PublishResult
import com.monostr.nostr.Signer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rust.nostr.sdk.EventBuilder
import java.time.Duration

/**
 * NIP-51 mute list (kind 10000, spec 9): only `p` entries are read (public and private), new entries
 * are written privately, everything else in the list is preserved. [muted] is what the screens filter
 * against. Read-only when the private part cannot be read ([ListState.writable] false).
 */
interface MuteRepository {
    /** Pubkeys (64 lower-case hex) of the muted accounts, public and private entries together; never the user's own. */
    val muted: StateFlow<Set<String>>
    val state: StateFlow<ListState>
    suspend fun ensureLoaded(interactive: Boolean = true)
    suspend fun mute(pubkey: String): ListOutcome
    suspend fun unmute(pubkey: String): ListOutcome
    fun clear()
}

class NostrMuteRepository(
    engine: NostrEngine,
    signer: Signer,
    now: () -> Long = { System.currentTimeMillis() / 1000 },
    send: suspend (EventBuilder) -> PublishResult = { engine.signAndPublish(it) },
    fetchTimeout: Duration = Duration.ofSeconds(8),
    refreshTimeout: Duration = Duration.ofSeconds(3),
    watch: CoroutineScope? = null,
) : MuteRepository {
    private val _muted = MutableStateFlow<Set<String>>(emptySet())
    override val muted: StateFlow<Set<String>> = _muted.asStateFlow()

    // The set follows every state change synchronously through the list's hook (no collector, so it
    // also works without a watcher scope).
    private val list = NostrPrivateList(
        engine, signer, kind = 10000, tag = "p", now = now, send = send,
        fetchTimeout = fetchTimeout, refreshTimeout = refreshTimeout, watch = watch,
        // another app may have written anything: only foreign 64-hex pubkeys count (a malformed entry
        // would break PublicKey.parse downstream, the own key would hide the user from themselves)
        onState = { s -> _muted.value = s.ids.filter { it.matches(HEX64) && it != signer.pubkey }.toSet() },
    )
    override val state: StateFlow<ListState> get() = list.state

    override suspend fun ensureLoaded(interactive: Boolean) = list.ensureLoaded(interactive)
    override suspend fun mute(pubkey: String): ListOutcome = list.set(pubkey, present = true)
    override suspend fun unmute(pubkey: String): ListOutcome = list.set(pubkey, present = false)
    override fun clear() = list.clear()

    private companion object {
        val HEX64 = Regex("[0-9a-f]{64}")
    }
}
