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
        return Profile(pubkey, s("name")?.let(::plainName), s("display_name", "displayName")?.let(::plainName), s("picture"), s("about"), s("nip05")?.let(::plainNip05), banner = s("banner"), website = s("website"))
    }

    /** A name longer than this is no name; it is cut. */
    const val MAX_NAME = 100

    /**
     * A name as shown: one line, without the characters that let it read backwards or pose as someone else's. Out go the
     * direction overrides, isolates and marks (U+202A-202E, U+2066-2069, U+200E/200F, U+061C), the invisible fillers
     * (U+200B, U+2060, U+FEFF) and control characters, a line break becoming a space. The joiners stay: emoji (U+200D)
     * and Persian (U+200C) need them. Names go into notification titles and mention labels (bug bounty 2026-11-26).
     */
    private fun plainName(raw: String): String {
        val out = StringBuilder(minOf(raw.length, MAX_NAME))
        for (c in raw) {
            when {
                c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\u200E' || c == '\u200F' || c == '\u061C' -> {}
                c == '\u200B' || c == '\u2060' || c == '\uFEFF' -> {}
                c == '\n' || c == '\r' || c == '\t' -> out.append(' ')
                c.isISOControl() -> {}
                else -> out.append(c)
            }
        }
        // never half of a surrogate pair at the cut
        val cut = if (out.length > MAX_NAME) out.substring(0, if (Character.isHighSurrogate(out[MAX_NAME - 1])) MAX_NAME - 1 else MAX_NAME) else out.toString()
        return cut.trim()
    }

    /** A NIP-05 address is `local@domain`, well below this. */
    const val MAX_NIP05 = 100

    /**
     * The NIP-05 claim of a kind 0 as shown on the profile: one line of visible characters, at most [MAX_NIP05]; null when
     * nothing is left. It is a claim nobody checked, so no list shows it in place of the npub (bug bounty 2026-11-26).
     */
    fun plainNip05(raw: String): String? =
        raw.filterNot { it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.take(MAX_NIP05).ifEmpty { null }

    private fun obj(raw: String?): Map<String, JsonElement> =
        raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: emptyMap()
}
