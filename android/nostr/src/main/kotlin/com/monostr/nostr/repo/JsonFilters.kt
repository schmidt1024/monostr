package com.monostr.nostr.repo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import rust.nostr.sdk.EventId
import com.monostr.tips.Kinds
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey

/**
 * Events by [ids] built from JSON in one call (an [EventId.parse] per id is the slow part for long lists). A set
 * the JSON route refuses (a malformed id) falls back to parsing each and leaving the bad ones out.
 */
internal fun idsFilter(ids: Collection<String>): Filter = try {
    Filter.fromJson(buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }.toString())
} catch (e: Exception) {
    Filter().ids(ids.mapNotNull { runCatching { EventId.parse(it) }.getOrNull() })
}

/** [kinds] by [authors] built from JSON in one call (a [PublicKey.parse] per key is the slow part); a refused key set falls back to parsing each. */
internal fun kindsByAuthorsFilter(kinds: List<Int>, authors: Collection<String>): Filter = try {
    Filter.fromJson(
        buildJsonObject {
            put("kinds", JsonArray(kinds.map { JsonPrimitive(it) }))
            put("authors", JsonArray(authors.map { JsonPrimitive(it) }))
        }.toString(),
    )
} catch (e: Exception) {
    Filter().kinds(kinds.map { Kind(it.toUShort()) }).authors(authors.mapNotNull { runCatching { PublicKey.parse(it) }.getOrNull() })
}

/**
 * [kinds] whose `e` tags name any of [ids] (spec perf2 decision 2: a thread asks `#e = [root, focused]`), from
 * [since] when set. Built from JSON: `Filter.customTag` takes one value only.
 */
/**
 * NIP-22 comments whose root scope (`E`) is any of [ids], from [since] when set: a comment on a comment names the
 * thread root only as `E`, its lowercase `e` is the parent comment (backlog 31, Mostr's Fediverse replies).
 */
internal fun rootCommentsFilter(ids: Collection<String>, since: Long? = null): Filter = Filter.fromJson(
    buildJsonObject {
        put("kinds", JsonArray(listOf(JsonPrimitive(Kinds.COMMENT))))
        put("#E", JsonArray(ids.map { JsonPrimitive(it) }))
        if (since != null) put("since", JsonPrimitive(since.coerceAtLeast(0)))
    }.toString(),
)

internal fun taggedFilter(kinds: List<Int>, ids: Collection<String>, since: Long? = null): Filter = Filter.fromJson(
    buildJsonObject {
        put("kinds", JsonArray(kinds.map { JsonPrimitive(it) }))
        put("#e", JsonArray(ids.map { JsonPrimitive(it) }))
        if (since != null) put("since", JsonPrimitive(since.coerceAtLeast(0)))
    }.toString(),
)
