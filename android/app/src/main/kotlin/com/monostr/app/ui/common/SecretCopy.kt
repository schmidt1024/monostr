package com.monostr.app.ui.common

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import com.monostr.app.ui.monero.AndroidClipboardPort
import com.monostr.app.ui.monero.SeedClipboardClear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Copies a secret as a sensitive clip (hidden in the clipboard preview on Android 13+) and clears it again
 * after [SeedClipboardClear.CLEAR_AFTER_MS] if it is still ours (spec 2026-10-17 §6/§11). Never log [text].
 */
@SuppressLint("InlinedApi") // EXTRA_IS_SENSITIVE is a compile-time constant; older Androids ignore the flag
fun copySecret(context: Context, text: String, label: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    // the clear must not die with the screen: the user leaves it (or the app) to paste the key
    SeedClipboardClear.schedule(clipboardScope, AndroidClipboardPort(context, label))
}

/** Process-wide scope for the clipboard timer; the main dispatcher because the clipboard manager is a main-thread service. */
private val clipboardScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
