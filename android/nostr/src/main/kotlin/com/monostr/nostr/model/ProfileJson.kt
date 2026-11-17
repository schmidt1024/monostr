package com.monostr.nostr.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Spec 5: the six kind 0 keys the app edits; every other key of the stored JSON is kept verbatim (spec 7: invalid JSON counts as `{}`). */
object ProfileJson {
    val EDITABLE = listOf("banner", "picture", "display_name", "name", "about", "nip05", "website")

    fun fields(raw: String?): Map<String, String> {
        val o = obj(raw)
        return EDITABLE.associateWith { k -> (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty() }
    }

    fun merge(raw: String?, values: Map<String, String>): String {
        val out = LinkedHashMap(obj(raw))
        for ((k, v) in values) {
            if (k !in EDITABLE) continue
            val t = v.trim()
            if (t.isEmpty()) out.remove(k) else out[k] = JsonPrimitive(t)
        }
        return JsonObject(out).toString()
    }

    /**
     * A kind 0 [content] read in Kotlin (spec perf 3.2: no rust Metadata per profile); null when it is no JSON
     * object. Only string values count; `displayName` is read when `display_name` is missing.
     */
    fun profile(pubkey: String, content: String): Profile? {
        val o = runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull() ?: return null
        fun s(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content }
        return Profile(pubkey, s("name")?.let(::plainName), s("display_name", "displayName")?.let(::plainName), s("picture"), s("about"), s("nip05"), banner = s("banner"), website = s("website"))
    }

    /** Direction overrides and isolates (U+202A-202E, U+2066-2069) let a name read backwards or pose as someone else's; joiners stay (emoji). */
    private val DIRECTION_CONTROLS = Regex("[\\u202A-\\u202E\\u2066-\\u2069]") // regex

    private fun plainName(raw: String): String = raw.replace(DIRECTION_CONTROLS, "")

    private fun obj(raw: String?): Map<String, JsonElement> =
        raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: emptyMap()
}
