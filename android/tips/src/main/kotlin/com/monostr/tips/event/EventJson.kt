package com.monostr.tips.event

import kotlinx.serialization.json.Json

object EventJson {
    val json: Json = Json { ignoreUnknownKeys = true }

    fun encode(event: Event): String = json.encodeToString(Event.serializer(), event)

    fun decode(text: String): Event = json.decodeFromString(Event.serializer(), text)
}
