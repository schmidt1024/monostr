package com.monostr.tips.event

import com.monostr.monero.toHex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.security.MessageDigest

/** NIP-01 event id: sha256 over the compact JSON of [0, pubkey, created_at, kind, tags, content]. */
object EventId {
    fun compute(pubkey: String, event: UnsignedEvent): String {
        val array = buildJsonArray {
            add(JsonPrimitive(0))
            add(JsonPrimitive(pubkey))
            add(JsonPrimitive(event.createdAt))
            add(JsonPrimitive(event.kind))
            add(JsonArray(event.tags.map { tag -> JsonArray(tag.map { JsonPrimitive(it) }) }))
            add(JsonPrimitive(event.content))
        }
        val serialized = EventJson.json.encodeToString(JsonArray.serializer(), array)
        return sha256(serialized.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}
