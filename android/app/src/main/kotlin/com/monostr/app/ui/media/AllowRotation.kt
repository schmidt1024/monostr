package com.monostr.app.ui.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The app is portrait-only on phones (manifest); while this is composed the screen may rotate,
 * following the system auto-rotate setting. Leaving restores portrait.
 */
@Composable
fun AllowRotation() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
}
