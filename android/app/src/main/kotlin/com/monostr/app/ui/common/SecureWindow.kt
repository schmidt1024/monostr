package com.monostr.app.ui.common

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** Blocks screenshots and recents thumbnails while a secret is on screen (seed: spec 6.2; nsec: spec 2026-10-17 §6). */
@Composable
internal fun SecureWindow() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
