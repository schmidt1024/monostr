package com.monostr.nostr.model

import com.monostr.tips.RelayHosts
import java.net.URI

/** The newest version of an addressable event, kind-agnostic; the UI parses what it shows. */
data class AddressableEvent(
    val id: String,
    val kind: Int,
    val pubkey: String,
    val identifier: String,
    val createdAt: Long,
    val tags: List<List<String>>,
    val content: String,
) {
    fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)?.takeIf { it.isNotBlank() }
}

enum class LiveStatus { PLANNED, LIVE, ENDED }

/** NIP-53 live event (kind 30311), spec 2026-10-29 naddr (ported from feat/naddr-live). Every URL in it is named by someone else. */
data class LiveStream(
    val title: String?,
    val summary: String?,
    val image: String?,
    val thumb: String?,
    val status: LiveStatus,
    val starts: Long?,
    val ends: Long?,
    val currentParticipants: Int?,
    val host: String,
    val contentWarning: String?,
    val createdAt: Long,
) {
    /** NIP-53: a live event without an update for an hour may be considered ended. */
    fun effectiveStatus(now: Long): LiveStatus =
        if (status == LiveStatus.LIVE && createdAt < now - STALE_SECONDS) LiveStatus.ENDED else status

    companion object {
        const val KIND = 30311
        const val STALE_SECONDS = 3600L
        private const val MAX_PARTICIPANTS = 10_000_000
        private val HEX_KEY = Regex("^[0-9a-fA-F]{64}$") // regex

        fun parse(e: AddressableEvent): LiveStream? {
            if (e.kind != KIND) return null
            val status = when (e.tag("status")?.lowercase()) {
                "planned" -> LiveStatus.PLANNED
                "live" -> LiveStatus.LIVE
                else -> LiveStatus.ENDED
            }
            // a hex key only (final review), lower case so mute and profile lookups match
            val host = e.tags.firstOrNull { it.size >= 4 && it[0] == "p" && it[3].equals("host", ignoreCase = true) && HEX_KEY.matches(it[1]) }?.get(1)?.lowercase()
                ?: e.pubkey
            return LiveStream(
                title = e.tag("title"),
                summary = e.tag("summary"),
                image = pictureUrl(e.tag("image")),
                thumb = pictureUrl(e.tag("thumb")),
                status = status,
                starts = e.tag("starts")?.toLongOrNull(),
                ends = e.tag("ends")?.toLongOrNull(),
                currentParticipants = e.tag("current_participants")?.toIntOrNull()?.takeIf { it in 0..MAX_PARTICIPANTS },
                host = host,
                contentWarning = e.tags.firstOrNull { it.isNotEmpty() && it[0] == "content-warning" }?.let { it.getOrNull(1) ?: "" },
                createdAt = e.createdAt,
            )
        }

        /** Pictures named in someone else's event: https on a public host, else none. */
        fun pictureUrl(url: String?, allowed: (String) -> Boolean = RelayHosts::isPublic): String? {
            val u = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val scheme = runCatching { URI(u).scheme }.getOrNull()
            return u.takeIf { scheme.equals("https", ignoreCase = true) && allowed(it) }
        }
    }
}
