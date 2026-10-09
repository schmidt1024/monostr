package com.monostr.app.ui.monero

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the auto-clear needs from the system clipboard, small enough to fake on the JVM. */
interface ClipboardPort {
    /** true: our seed clip is on the clipboard; false: something else is; null: unreadable (Android 10+ hides it from a background app). */
    fun ownClipPresent(): Boolean?
    fun clear()
}

/**
 * Clears the copied seed again after [CLEAR_AFTER_MS]. The timer runs in a scope that outlives the Seed step,
 * because the user normally leaves it (or the app, to paste into the wallet) long before the minute is over.
 * When the clip cannot be read the clear happens anyway: wiping something the user copied later is the
 * smaller harm than leaving the spend secret on the clipboard.
 */
object SeedClipboardClear {
    const val CLEAR_AFTER_MS = 60_000L
    private var pending: Job? = null

    fun schedule(scope: CoroutineScope, port: ClipboardPort, delayMs: Long = CLEAR_AFTER_MS): Job {
        pending?.cancel()
        return scope.launch {
            delay(delayMs)
            if (port.ownClipPresent() != false) port.clear()
        }.also { pending = it }
    }
}

internal const val SEED_CLIP_LABEL = "seed"

class AndroidClipboardPort(context: Context) : ClipboardPort {
    private val manager = context.applicationContext.getSystemService(ClipboardManager::class.java)

    override fun ownClipPresent(): Boolean? = manager.primaryClipDescription?.let { it.label == SEED_CLIP_LABEL }

    override fun clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.clearPrimaryClip() else manager.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
