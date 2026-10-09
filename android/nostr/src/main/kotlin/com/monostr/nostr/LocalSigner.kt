package com.monostr.nostr

import com.monostr.tips.event.Event
import com.monostr.tips.event.UnsignedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Nip44Version
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.PublicKey

/**
 * Signs with a locally held secret key (hex or nsec). The app stores the key
 * encrypted with the Android Keystore; this class never persists it. Every rust-nostr call runs on
 * [Dispatchers.IO], never on the caller's thread (see [NostrEngine.ffiDispatcher]).
 */
class LocalSigner(secretKey: String) : Signer {
    private val keys: Keys = Keys.parse(secretKey.trim())
    private val rustSigner: NostrSigner = NostrSigner.keys(keys)

    override val pubkey: String = keys.publicKey().toHex()

    /** The secret key as hex, for the app's encrypted store. */
    val secretHex: String get() = keys.secretKey().toHex()

    override suspend fun sign(event: UnsignedEvent): Event = withContext(Dispatchers.IO) {
        val signed = rustSigner.signEvent(RustConvert.toRustUnsigned(event, pubkey))
        RustConvert.toTips(signed)
    }

    override suspend fun nip44Encrypt(peerPubkey: String, plaintext: String): String = withContext(Dispatchers.IO) {
        rust.nostr.sdk.nip44Encrypt(keys.secretKey(), PublicKey.parse(peerPubkey), plaintext, Nip44Version.V2)
    }

    override suspend fun nip44Decrypt(peerPubkey: String, payload: String): String = withContext(Dispatchers.IO) {
        rust.nostr.sdk.nip44Decrypt(keys.secretKey(), PublicKey.parse(peerPubkey), payload)
    }

    /** A local key never needs the user. */
    override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? = nip44Decrypt(peerPubkey, payload)

    companion object {
        /** Accepts hex or nsec; returns null when the input is not a valid secret key. */
        fun parseOrNull(input: String): LocalSigner? = try {
            LocalSigner(input)
        } catch (e: Exception) {
            null
        }
    }
}
