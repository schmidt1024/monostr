package com.monostr.tips.event

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object EventJson {
    val json: Json = Json { ignoreUnknownKeys = true }

    fun encode(event: Event): String = json.encodeToString(Event.serializer(), event)

    /**
     * Never fails on a `created_at` past [Long.MAX_VALUE]: NIP-01 timestamps are unsigned 64 bit, and one such event in the
     * database made every read that met it throw (bug bounty 2026-11-26). It reads as [Long.MAX_VALUE], which
     * [isFarFuture] and every "newer than now" bound then sort out. The usual event takes the fast path unchanged.
     */
    fun decode(text: String): Event = try {
        json.decodeFromString(Event.serializer(), text)
    } catch (e: SerializationException) {
        val raw = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: throw e
        val stamp = (raw["created_at"] as? JsonPrimitive)?.takeIf { !it.isString }?.content ?: throw e
        // digits only and too long for a Long: an unsigned value above it, nothing else is repaired
        if (stamp.isEmpty() || !stamp.all { it in '0'..'9' } || stamp.toLongOrNull() != null) throw e
        json.decodeFromJsonElement(Event.serializer(), JsonObject(raw + ("created_at" to JsonPrimitive(Long.MAX_VALUE))))
    }

    /** Seconds past now from which a timestamp is bogus rather than a wrong clock: such an event is dropped and deleted. */
    const val FAR_FUTURE_SECONDS = 86_400L

    /** Whether [createdAt] lies more than [FAR_FUTURE_SECONDS] after [nowSeconds], without overflow at [Long.MAX_VALUE]. */
    fun isFarFuture(createdAt: Long, nowSeconds: Long): Boolean = createdAt - FAR_FUTURE_SECONDS > nowSeconds
}
