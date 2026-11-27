package com.monostr.app.work

import android.app.NotificationManager
import android.content.Context
import com.monostr.app.data.pk16

/** What a tap on a notification does, given the account it was posted for. */
sealed interface OpenAction {
    /** Same account (or an older notification without one): open the target. */
    data class Open(val peer: String?) : OpenAction

    /** Another known account: switch to it first, then open the target. */
    data class SwitchThenOpen(val account: String, val peer: String?) : OpenAction

    /** The account is gone (removed meanwhile): open the app only. */
    data object Ignore : OpenAction
}

/** [active] is the running account, [known] the accounts still on this device; [account] is the notification's extra (null: none). */
fun resolveOpen(active: String, known: Set<String>, account: String?, peer: String?): OpenAction = when {
    account == null || account == active -> OpenAction.Open(peer)
    account !in known -> OpenAction.Ignore
    else -> OpenAction.SwitchThenOpen(account, peer)
}

/** The target of a tapped notification, bound to the account it was posted for until that account's nav host consumes it. */
data class PendingOpen(val account: String, val peer: String?, val messages: Boolean)

/** True only when the target's own account runs: A's chat never opens inside B. */
fun shouldConsume(target: PendingOpen?, readyPubkey: String): Boolean = target != null && target.account == readyPubkey

/** Notification identity per account: the tag carries pk16, so A's and B's notifications never replace each other. */
object AccountNotifications {
    fun dmTag(pubkey: String) = "dm.${pk16(pubkey)}"
    fun tipTag(pubkey: String) = "tip.${pk16(pubkey)}"

    /** Request code of a PendingIntent: distinct per account and target. */
    fun requestCode(pubkey: String, target: String) = "${pk16(pubkey)}:$target".hashCode()

    /** Tag of notifications posted before accounts existed. */
    private const val LEGACY_DM_TAG = "dm"

    fun belongs(tag: String?, pubkey: String) = tag == dmTag(pubkey) || tag == tipTag(pubkey)

    /** Cancels the visible DM and tip notifications of [pubkey] only. */
    fun cancelAccount(context: Context, pubkey: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { belongs(it.tag, pubkey) || it.tag == LEGACY_DM_TAG }.forEach { manager.cancel(it.tag, it.id) }
    }
}
