package com.monostr.nostr.model

/** NIP-32 language label: `["l", "<code>", "ISO-639-1"]`, usually next to `["L", "ISO-639-1"]`. Tags come from someone else. */
object LanguageLabel {
    private const val NAMESPACE = "ISO-639-1"
    private val CODE = Regex("^[a-z]{2}$") // regex

    /** The first ISO-639-1 code (lowercased, two letters) among the `l` tags, null without a valid one. */
    fun of(tags: List<List<String>>): String? =
        tags.firstNotNullOfOrNull { t ->
            if (t.size >= 3 && t[0] == "l" && t[2] == NAMESPACE) t[1].lowercase().takeIf { CODE.matches(it) } else null
        }
}
