package com.monostr.app.work

import com.monostr.app.data.AmountUnit
import kotlinx.coroutines.flow.first
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.monostr.app.MainActivity
import com.monostr.app.R
import com.monostr.app.data.Presets
import com.monostr.app.data.TipSettingsStore
import com.monostr.app.session.NostrSession
import com.monostr.app.session.Ready
import com.monostr.app.session.SessionState
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.NotificationItem
import com.monostr.nostr.repo.NotificationKind
import com.monostr.nostr.repo.NotificationsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/**
 * Pure part of the background check: which tips are new since the watermark. The first run only sets the
 * watermark. Receipts in the same second as the watermark are fetched again (`since - 1`) and kept out of
 * the result by [Outcome.notified], the ids already reported (capped at [NOTIFIED_CAP], oldest dropped).
 */
object TipChecker {
    const val NOTIFIED_CAP = 200

    data class Outcome(val fresh: List<NotificationItem>, val watermark: Long, val notified: Set<String>)

    suspend fun check(notifications: NotificationsRepository, since: Long, now: Long, notified: Set<String>): Outcome {
        if (since == 0L) return Outcome(emptyList(), now, notified)
        notifications.refresh(since = since - 1)
        val fresh = notifications.list()
            .filter { it.kind == NotificationKind.TIP && it.createdAt >= since && it.id !in notified }
            .sortedBy { it.createdAt }
        val updated = LinkedHashSet(notified).apply { fresh.forEach { add(it.id) } }
        while (updated.size > NOTIFIED_CAP) updated.remove(updated.first())
        return Outcome(fresh, fresh.lastOrNull()?.createdAt ?: since, updated)
    }
}

/** Local notifications for received tips (spec 5.7: no push server). */
class TipNotifier(private val context: Context) {
    fun show(fromName: String, item: NotificationItem, unit: AmountUnit = AmountUnit.XMR) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_tip_title))
            .setContentText(item.amount?.let { context.getString(R.string.notif_tip_text, fromName, Presets.format(it, unit), unit.label) } ?: context.getString(R.string.notif_tip_text_plain, fromName))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(item.id.hashCode(), notification)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Idempotent; re-creating updates the channel name to the current app language.
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CHANNEL = "tips"
    }
}

/**
 * Runs every 15 minutes (spec 5.7): restores the session, fetches receipts since the watermark
 * and shows one notification per new tip. Uses a Hilt entry point instead of hilt-work.
 */
class TipCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun session(): NostrSession
        fun settings(): TipSettingsStore
    }

    /** Replaced by [BackgroundCheckWorker] (2026-11-02); kept so a run WorkManager still has queued under this name ends at once. */
    override suspend fun doWork(): Result = Result.success()

    companion object {
        /** The unique work name it ran under; [BackgroundCheckWorker.schedule] cancels it. */
        internal const val WORK_NAME = "tip-check"
    }
}

/** The tip part of [BackgroundCheckWorker]: new receipts since the watermark become notifications. False: try again later. */
internal suspend fun checkTips(context: Context, session: NostrSession, ready: Ready, settings: TipSettingsStore): Boolean {
    // logged out while this ran (the lookups suspend): nothing of that account may be posted or marked any more
    fun gone() = (session.state.value as? SessionState.Active)?.ready !== ready
    val outcome = try {
        TipChecker.check(ready.notifications, settings.lastTipNotifiedAt(), System.currentTimeMillis() / 1000, notified = settings.notifiedReceiptIds())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return false
    }
    if (gone()) return true
    if (outcome.fresh.isNotEmpty()) {
        val notifier = TipNotifier(context)
        for (item in outcome.fresh) {
            val sender = item.from
            val name = if (sender == null) {
                context.getString(R.string.tip_anonymous)
            } else {
                try {
                    ready.profiles.fetched(sender).shownName
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Profile.shortPubkey(sender)
                }
            }
            val unit = settings.amountUnit.first()
            if (gone()) return true
            notifier.show(name, item, unit)
        }
    }
    // receipts the check stored show in the badge and an open screen without waiting for their next reload (spec perf 3.6)
    ready.notificationsStore.reloadLocal()
    if (gone()) return true
    try {
        settings.setLastTipNotifiedAt(outcome.watermark)
        settings.setNotifiedReceiptIds(outcome.notified)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return false
    }
    return true
}
