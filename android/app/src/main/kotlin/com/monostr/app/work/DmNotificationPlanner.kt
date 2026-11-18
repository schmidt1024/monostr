package com.monostr.app.work

import com.monostr.app.R
import com.monostr.app.data.dm.DmMessage
import com.monostr.app.data.dm.DmText
import com.monostr.app.data.dm.PendingSince
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.pluralText
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.model.Profile

/** One DM notification; [peer] is empty for the pending-wraps summary (opens the app, no chat). */
data class DmNotification(val peer: String, val title: UiText, val text: UiText, val id: Int)

/** Pure selection step of [DmCheckWorker]: which messages and which pending total to notify, and the next watermark. */
object DmCheckPlan {
    /** [pending] is the total to show in the summary, 0 for no summary. */
    data class Selection(val fresh: List<DmMessage>, val pending: Int, val watermark: Long)

    /** How long before the watermark a message may have been written and still count as new (slow delivery, a skewed clock). */
    const val LATE_GRACE_S = 24 * 60 * 60L

    /**
     * [lastNotified] == 0 is the first run: it only sets the watermark to [now], so the history a new
     * install or login syncs does not burst out as notifications. Later runs take the incoming messages
     * after the watermark and post the summary (showing [totalPending]) only when wraps were parked
     * after it and no summary is standing ([summaryShown], reset by a healed AUTH or an empty parking
     * lot). Their new watermark is the newest `receivedAt` selected (never the clock: a message stored
     * in the same second after the store was read stays above it), unchanged when nothing was new.
     *
     * A message stored after the watermark but written more than [LATE_GRACE_S] before it is old history
     * that arrived late (a new inbox relay refetches 30 days, a relay back from the dead delivers its
     * backlog): it is not notified, one per peer would burst out, but the watermark still passes it.
     */
    fun select(lastNotified: Long, incoming: List<DmMessage>, pending: PendingSince, totalPending: Int, now: Long, summaryShown: Boolean = false): Selection {
        if (lastNotified == 0L) return Selection(emptyList(), 0, now)
        val stored = DmNotificationPlanner.unnotified(incoming, lastNotified)
        val fresh = stored.filter { it.createdAt > lastNotified - LATE_GRACE_S }
        val newest = maxOf(stored.maxOfOrNull { it.receivedAt } ?: 0L, if (pending.count > 0) pending.newestReceivedAt else 0L)
        return Selection(fresh, if (pending.count > 0 && !summaryShown) totalPending else 0, maxOf(lastNotified, newest))
    }
}

/** Pure part of the DM background check: what to notify for the messages a sync round stored. */
object DmNotificationPlanner {
    val PENDING_ID = "dm-pending".hashCode()

    /** The incoming messages of [messages] the worker has not notified yet (received after [lastNotified]). */
    fun unnotified(messages: List<DmMessage>, lastNotified: Long): List<DmMessage> =
        messages.filter { !it.outgoing && it.receivedAt > lastNotified }

    /**
     * One notification per peer for [fresh] (RECEIVED messages stored by this run) showing the peer's
     * newest message, plus one summary when [pending] > 0 wraps wait for an interactive signer.
     * Messages of [muted] peers are left out (spec 9.3). [showName]/[showText] are the preview switches; a peer missing from [names] shows its short pubkey.
     */
    fun plan(fresh: List<DmMessage>, names: Map<String, String>, pending: Int, showName: Boolean, showText: Boolean, muted: Set<String> = emptySet()): List<DmNotification> {
        val perPeer = fresh.filter { !it.outgoing && it.peer !in muted }
            .groupBy { it.peer }
            .map { (peer, messages) ->
                val newest = messages.maxWith(compareBy<DmMessage>({ it.createdAt }, { it.receivedAt }))
                val title = if (showName) uiText(R.string.notif_dm_title, names[peer] ?: Profile.shortPubkey(peer)) else uiText(R.string.notif_dm_title_anonymous)
                val preview = DmText.preview(newest.content)
                // a media-only message has no text line; the tap hint stands in for it
                val text = if (showText && preview.isNotEmpty()) UiText.Plain(preview) else uiText(R.string.notif_dm_tap)
                newest.createdAt to DmNotification(peer, title, text, peer.hashCode())
            }
            .sortedByDescending { it.first }
            .map { it.second }
        val summary = if (pending > 0) listOf(DmNotification("", uiText(R.string.notif_dm_title_anonymous), pluralText(R.plurals.dm_pending_summary, pending, pending), PENDING_ID)) else emptyList()
        return perPeer + summary
    }
}
