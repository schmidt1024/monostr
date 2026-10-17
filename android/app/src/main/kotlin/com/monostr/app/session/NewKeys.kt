package com.monostr.app.session

import com.monostr.app.data.SessionInfo
import com.monostr.app.data.SignerType
import kotlinx.coroutines.CancellationException
import rust.nostr.sdk.Keys

/** A key made or read for the create-account flow and "Show key" (spec 2026-10-17 §3, §6). Never log it. */
class NewKey(val secretHex: String, val nsec: String, val npub: String, val pubkey: String) {
    override fun toString() = "NewKey($npub)" // no secret in logs or crash reports
}

object NewKeys {
    fun generate(): NewKey = of(Keys.generate())
    fun fromSecretHex(hex: String): NewKey = of(Keys.parse(hex))
    /**
     * Spec 2026-10-17 §6: the key behind "Show key" — only for a key login ([SignerType.LOCAL]); null for Amber
     * (the secret is not read), without a session, or when the stored key is missing or unreadable. A parse
     * error is dropped unseen: its message may echo the key.
     */
    fun ofSession(info: SessionInfo?, secretHex: () -> String?): NewKey? {
        if (info?.signerType != SignerType.LOCAL) return null
        val hex = secretHex() ?: return null
        return try {
            fromSecretHex(hex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun of(k: Keys) = NewKey(k.secretKey().toHex(), k.secretKey().toBech32(), k.publicKey().toBech32(), k.publicKey().toHex())
}
