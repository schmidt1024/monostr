package com.monostr.app.ui.common

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.WeakHashMap

/**
 * Blocks screenshots and recents thumbnails while a secret is on screen (seed: spec 6.2; nsec: spec 2026-10-17 §6; the
 * key field of the login: security review 2026-11-26).
 */
@Composable
internal fun SecureWindow() {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        if (window != null) SecureWindows.enter(window)
        onDispose { if (window != null) SecureWindows.leave(window) }
    }
}

/**
 * Counts the screens that ask for the flag: during a navigation transition the screen that leaves is disposed after the
 * next one entered, and must not take the flag away from it.
 */
internal object SecureWindows {
    private val holders = WeakHashMap<Window, Int>()

    fun enter(window: Window) {
        if (count(window, +1) == 1) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun leave(window: Window) {
        if (count(window, -1) == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** The holders of [window] after [delta], never below zero. Main thread only, as the window calls are. */
    private fun count(window: Window, delta: Int): Int {
        val n = ((holders[window] ?: 0) + delta).coerceAtLeast(0)
        if (n == 0) holders.remove(window) else holders[window] = n
        return n
    }
}
