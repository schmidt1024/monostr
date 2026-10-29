package com.monostr.app.ui.common

import androidx.annotation.StringRes
import com.monostr.app.R
import com.monostr.nostr.model.AddressableEvent
import com.monostr.nostr.model.LiveStatus
import com.monostr.nostr.model.LiveStream

/**
 * Spec 2026-10-29 naddr: what an address card shows, decided from the event alone. Every value comes
 * from someone else's event: the picture is https on a public host or none, the cover decision
 * (content-warning, `t` nsfw, `#nsfw` in the title) is made here so the UI only obeys it.
 */
data class AddressCardModel(
    @StringRes val typeLabel: Int,
    val title: String,
    val summary: String?,
    val image: String?,
    val badge: Badge?,
    val startsAt: Long?,
    val participants: Int?,
    /** Whose avatar and name the card shows: the host of a stream, else the author. */
    val personPubkey: String,
    val covered: Boolean,
    val live: Boolean,
) {
    enum class Badge { LIVE, PLANNED, ENDED }

    companion object {
        private val NSFW = Regex("(?i)#nsfw(?![\\p{L}\\p{N}_])") // regex
        private const val SHORT_ID = 12

        fun from(e: AddressableEvent, now: Long): AddressCardModel {
            val stream = LiveStream.parse(e)
            val title = e.tag("title") ?: e.tag("name") ?: e.identifier.let { if (it.length > SHORT_ID) it.take(SHORT_ID) + "…" else it }
            val covered = e.tags.any { it.firstOrNull() == "content-warning" } ||
                e.tags.any { it.size >= 2 && it[0] == "t" && it[1].equals("nsfw", ignoreCase = true) } ||
                NSFW.containsMatchIn(e.tag("title") ?: "")
            val label = when (e.kind) {
                30023 -> R.string.address_type_article
                LiveStream.KIND -> R.string.address_type_live
                30402 -> R.string.address_type_listing
                30000, 30001, 30003, 30030 -> R.string.address_type_list
                31922, 31923 -> R.string.address_type_calendar
                else -> R.string.address_type_event
            }
            val image = LiveStream.pictureUrl(e.tag("image")) ?: LiveStream.pictureUrl(e.tag("thumb"))
            val badge = stream?.let {
                when (it.effectiveStatus(now)) {
                    LiveStatus.LIVE -> Badge.LIVE
                    LiveStatus.PLANNED -> Badge.PLANNED
                    LiveStatus.ENDED -> Badge.ENDED
                }
            }
            return AddressCardModel(
                typeLabel = label,
                title = title,
                summary = e.tag("summary"),
                image = image,
                badge = badge,
                startsAt = stream?.starts,
                participants = if (badge == Badge.LIVE) stream?.currentParticipants else null,
                personPubkey = stream?.host ?: e.pubkey,
                covered = covered,
                live = stream != null,
            )
        }
    }
}
