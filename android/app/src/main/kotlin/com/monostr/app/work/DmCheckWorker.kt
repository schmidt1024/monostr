package com.monostr.app.work

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
import com.monostr.app.data.DmSettingsStore
import com.monostr.app.session.NostrSession
import com.monostr.app.session.Ready
import com.monostr.app.session.SessionState
import com.monostr.app.ui.common.resolve
import com.monostr.nostr.model.Profile
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Local notifications for received direct messages, tagged [TAG] so logout can cancel exactly these. */
class DmNotifier(private val context: Context) {
    fun show(n: DmNotification) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return
        ensureChannel()
        val intent = Intent(context, MainActivity::class.java)
        if (n.peer.isNotEmpty()) intent.putExtra(MainActivity.EXTRA_OPEN_DM, n.peer) else intent.putExtra(MainActivity.EXTRA_OPEN_MESSAGES, true)
        // the summary has no peer; its own request code keeps it from sharing a PendingIntent slot
        val requestCode = if (n.peer.isNotEmpty()) n.peer.hashCode() else DmNotificationPlanner.PENDING_ID
        val open = PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n.title.resolve(context))
            .setContentText(n.text.resolve(context))
            .setContentIntent(open)
            .setGroup(GROUP)
            // the pending summary is re-posted every run while wraps wait; only its first post alerts
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        compat.notify(TAG, n.id, notification)
    }

    fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(TAG, id)

    /** Removes every DM notification (logout); tip notifications stay. */
    fun cancelAll() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag == TAG }.forEach { manager.cancel(TAG, it.id) }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Idempotent; re-creating updates the channel name to the current app language.
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notif_dm_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CHANNEL = "dms"
        const val TAG = "dm"
        private const val GROUP = "dms"
    }
}

/**
 * Runs every 15 minutes: restores the session, fetches new gift wraps silently (never prompts the
 * signer; wraps that need one stay parked and show up as the pending summary) and notifies per
 * peer. The foreground app syncs itself, so a run while it is visible does nothing.
 */
class DmCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun session(): NostrSession
        fun dmSettings(): DmSettingsStore
    }

    /** Replaced by [BackgroundCheckWorker] (2026-11-02); kept so a run WorkManager still has queued under this name ends at once. */
    override suspend fun doWork(): Result = Result.success()

    companion object {
        /** The unique work name it ran under; [BackgroundCheckWorker.schedule] cancels it. */
        internal const val WORK_NAME = "dm-check"
    }
}

/**
 * The DM part of [BackgroundCheckWorker]: fetches new gift wraps silently (never prompts the signer; wraps that need one stay
 * parked and show up as the pending summary) and notifies per peer. False: try again later.
 */
internal suspend fun checkDms(context: Context, session: NostrSession, ready: Ready, dmSettings: DmSettingsStore): Boolean {
    val store = ready.dmStore
    val lastNotified = store.lastNotified()
    try {
        ready.dms.sync(interactive = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return false
    }
    // logged out while this run synced: nothing of that account may be posted any more
    if ((session.state.value as? SessionState.Active)?.ready !== ready) return true
    // from the store, not sync's result: the live subscription also stores messages while the app is in the background
    val totalPending = store.pendingCount().first()
    val selection = DmCheckPlan.select(
        lastNotified, store.incomingSince(lastNotified), store.pendingSince(lastNotified), totalPending,
        now = System.currentTimeMillis() / 1000,
        summaryShown = store.pendingSummaryShown(),
    )
    val fresh = selection.fresh
    val names = fresh.map { it.peer }.distinct().associateWith { peer ->
        try {
            ready.profiles.fetched(peer).shownName
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Profile.shortPubkey(peer)
        }
    }
    val settings = dmSettings
    // silent load first: right after a restore the muted set is still empty and a muted peer would alert
    try {
        ready.mute.ensureLoaded(interactive = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // the current set is what we have
    }
    val plan = DmNotificationPlanner.plan(
        fresh, names, pending = selection.pending,
        showName = settings.previewName.first(), showText = settings.previewText.first(),
        muted = ready.muted.value,
    )
    val notifier = DmNotifier(context)
    plan.forEach { notifier.show(it) }
    try {
        if (selection.pending > 0) store.setPendingSummaryShown(true)
        // the wraps were unwrapped meanwhile (chat tab): drop the stale summary; the next parked wrap may post again
        if (totalPending == 0) {
            notifier.cancel(DmNotificationPlanner.PENDING_ID)
            store.setPendingSummaryShown(false)
        }
        store.setLastNotified(selection.watermark)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return false
    }
    return true
}
