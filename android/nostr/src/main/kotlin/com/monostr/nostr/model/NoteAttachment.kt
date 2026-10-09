package com.monostr.nostr.model

/**
 * A picture uploaded for a note about to be published (spec 5.2): it becomes a URL at the end of
 * the text and a NIP-92 `imeta` tag, which is how [NoteMedia.parse] and other clients find it.
 */
data class NoteAttachment(
    val url: String,
    val mime: String,
    val sha256: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val blurhash: String? = null,
) {
    companion object {
        /** [text] with the attachment URLs appended in order, each on its own line, after a blank line. */
        fun content(text: String, attachments: List<NoteAttachment>): String {
            val body = text.trim()
            val urls = attachments.joinToString("\n") { it.url }
            return when {
                urls.isEmpty() -> body
                body.isEmpty() -> urls
                else -> "$body\n\n$urls"
            }
        }

        /** One `imeta` tag per attachment and, for a [sensitive] note with pictures, a NIP-36 `content-warning` tag. */
        fun tags(attachments: List<NoteAttachment>, sensitive: Boolean): List<List<String>> {
            val imeta = attachments.map { a ->
                buildList {
                    add("imeta")
                    add("url ${a.url}")
                    add("m ${a.mime}")
                    add("x ${a.sha256}")
                    add("size ${a.size}")
                    add("dim ${a.width}x${a.height}")
                    a.blurhash?.let { add("blurhash $it") }
                }
            }
            return if (sensitive && attachments.isNotEmpty()) imeta + listOf(listOf("content-warning", "")) else imeta
        }
    }
}
