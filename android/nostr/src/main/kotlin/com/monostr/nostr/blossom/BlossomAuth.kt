package com.monostr.nostr.blossom

import com.monostr.tips.event.Event
import com.monostr.tips.event.EventJson
import com.monostr.tips.event.EventSigner
import com.monostr.tips.event.UnsignedEvent
import com.monostr.tips.event.isHex64
import java.net.URI
import java.util.Base64

/**
 * Blossom authorization (BUD-11): a signed kind 24242 event in the Authorization header. It names
 * one action, one blob and the server it is meant for, and lives for five minutes. This is not
 * NIP-98 (kind 27235), which the tip watcher uses.
 */
object BlossomAuth {
    const val KIND = 24242
    const val UPLOAD = "upload"
    const val DELETE = "delete"
    /** How long an authorization is valid; the server needs it for one request. */
    const val LIFETIME_SECONDS = 300L
    private const val SCHEME = "Nostr "

    fun unsigned(action: String, sha256: String, serverUrl: String, now: Long): UnsignedEvent {
        require(action == UPLOAD || action == DELETE) { "unknown blossom action" }
        require(isHex64(sha256)) { "sha256 must be 64 lowercase hex characters" }
        val tags = mutableListOf(listOf("t", action), listOf("x", sha256), listOf("expiration", (now + LIFETIME_SECONDS).toString()))
        host(serverUrl)?.let { tags += listOf("server", it) }
        return UnsignedEvent(kind = KIND, content = if (action == UPLOAD) "Upload" else "Delete", tags = tags, createdAt = now)
    }

    suspend fun authorization(signer: EventSigner, action: String, sha256: String, serverUrl: String, now: Long): String =
        header(signer.sign(unsigned(action, sha256, serverUrl, now)))

    /** The header value for an authorization event signed elsewhere (a delete is signed without asking the user). */
    fun header(event: Event): String =
        // standard padded base64: the specification asks for base64url, deployed servers and clients use this (spec 2)
        SCHEME + Base64.getEncoder().encodeToString(EventJson.encode(event).toByteArray(Charsets.UTF_8))

    /** The bare lowercase host of a server URL, as a `server` tag names it; null when the URL has none. */
    fun host(serverUrl: String): String? =
        runCatching { URI(serverUrl.trim()).host }.getOrNull()?.lowercase()?.takeIf { it.isNotEmpty() }
}
