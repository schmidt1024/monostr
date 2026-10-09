package com.monostr.nostr

import com.monostr.tips.event.Event
import com.monostr.tips.event.EventSigner
import com.monostr.tips.event.UnsignedEvent

/**
 * Signs events for the logged-in user (spec 5.3). Extends the `:tips`
 * [EventSigner] so the tip flow can use the same instance.
 */
interface Signer : EventSigner {
    val pubkey: String
    /** NIP-44 v2 to [peerPubkey] (hex); for own data the peer is the user's own pubkey (spec 5). */
    suspend fun nip44Encrypt(peerPubkey: String, plaintext: String): String
    suspend fun nip44Decrypt(peerPubkey: String, payload: String): String
    /**
     * Like [nip44Decrypt], but never asks the user: null when decrypting would need an
     * interaction (an external signer without a remembered permission). Used by implicit loads,
     * e.g. the bookmark list on feed open (spec 4.2). A remembered "reject" still throws
     * [SigningRejectedException].
     */
    suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String?

    /**
     * Like [sign], but never asks the user: throws [SilentSignUnavailable] when signing would need
     * an interaction. Used for signatures nobody asked for, e.g. NIP-42 AUTH answering a relay's
     * challenge. A local key signs as [sign] does.
     */
    suspend fun signEventSilent(event: UnsignedEvent): Event = sign(event)
}

/** Thrown by [Signer.signEventSilent] when signing needs the user (an external signer without a remembered permission). */
class SilentSignUnavailable : Exception("no silent signature")

/** Thrown when the user declines a signing request in an external signer. */
class SigningRejectedException(message: String = "signing rejected") : Exception(message)

/** The signer cannot do NIP-44 (old Amber build, missing permission): bookmarks and DMs stay read-only. */
class Nip44UnsupportedException(message: String = "signer has no NIP-44") : Exception(message)
