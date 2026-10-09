package com.monostr.app.media

import com.monostr.nostr.Signer
import com.monostr.tips.event.Event
import com.monostr.tips.event.UnsignedEvent

/**
 * A [Signer] for the media tests with a fixed id and signature: [onSign] answers the interactive
 * requests, [onSilent] those that must never ask the user. NIP-44 is not used here.
 */
class FakeSigner(
    override val pubkey: String = "c".repeat(64),
    private val onSign: suspend (UnsignedEvent) -> Event = { signed(it, pubkey) },
    private val onSilent: suspend (UnsignedEvent) -> Event = onSign,
) : Signer {
    override suspend fun sign(event: UnsignedEvent): Event = onSign(event)
    override suspend fun signEventSilent(event: UnsignedEvent): Event = onSilent(event)
    override suspend fun nip44Encrypt(peerPubkey: String, plaintext: String): String = error("not used")
    override suspend fun nip44Decrypt(peerPubkey: String, payload: String): String = error("not used")
    override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? = error("not used")

    companion object {
        fun signed(e: UnsignedEvent, pubkey: String = "c".repeat(64)) =
            Event(id = "1".repeat(64), pubkey = pubkey, createdAt = e.createdAt, kind = e.kind, tags = e.tags, content = e.content, sig = "2".repeat(128))
    }
}
