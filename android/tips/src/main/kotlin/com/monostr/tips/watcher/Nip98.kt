package com.monostr.tips.watcher

import com.monostr.monero.toHex
import com.monostr.tips.Kinds
import com.monostr.tips.event.Event
import com.monostr.tips.event.EventId
import com.monostr.tips.event.EventJson
import com.monostr.tips.event.EventSigner
import com.monostr.tips.event.UnsignedEvent
import kotlinx.serialization.SerializationException
import java.util.Base64

/** NIP-98 HTTP auth: a signed kind 27235 event carried in the Authorization header. */
object Nip98 {
    private const val SCHEME = "Nostr "

    suspend fun authorization(signer: EventSigner, url: String, method: String, body: ByteArray?, now: Long): String {
        val tags = mutableListOf(listOf("u", url), listOf("method", method.uppercase()))
        if (body != null && body.isNotEmpty()) {
            tags += listOf("payload", EventId.sha256(body).toHex())
        }
        val event = signer.sign(UnsignedEvent(kind = Kinds.HTTP_AUTH, content = "", tags = tags, createdAt = now))
        val encoded = Base64.getEncoder().encodeToString(EventJson.encode(event).toByteArray(Charsets.UTF_8))
        return SCHEME + encoded
    }

    /** Decodes the event from an Authorization header value; null if it is not a well-formed NIP-98 header. */
    fun decode(header: String): Event? {
        if (!header.startsWith(SCHEME)) return null
        val json = try {
            String(Base64.getDecoder().decode(header.removePrefix(SCHEME)), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            EventJson.decode(json)
        } catch (e: SerializationException) {
            null
        }
    }
}
