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
 * following the system auto-rotate setting. Leaving restores what was asked for before.
 *
 * Counted per activity (backlog 2026-11-11): the video player opens from the media viewer, so the new screen composes
 * before the old one is disposed; without the count the viewer's dispose locked the player to portrait.
 */
@Composable
fun AllowRotation() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val target = activity?.let(::ActivityOrientation)
        target?.let(RotationHolds::acquire)
        onDispose { target?.let(RotationHolds::release) }
    }
}

/** What [RotationHolds] turns: an activity's requested orientation; tests use their own. */
internal interface OrientationTarget {
    /** The same activity gives an equal target. */
    val key: Any
    var orientation: Int
}

private class ActivityOrientation(private val activity: Activity) : OrientationTarget {
    override val key: Any get() = activity
    override var orientation: Int
        get() = activity.requestedOrientation
        set(value) { activity.requestedOrientation = value }
}

/** Main thread only, like composition. The orientation before the first hold comes back after the last one. */
internal object RotationHolds {
    private class Hold(val before: Int, var count: Int)

    private val holds = java.util.WeakHashMap<Any, Hold>()

    fun acquire(target: OrientationTarget) {
        val hold = holds.getOrPut(target.key) { Hold(target.orientation, 0) }
        hold.count++
        target.orientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }

    fun release(target: OrientationTarget) {
        val hold = holds[target.key] ?: return
        if (--hold.count > 0) return
        holds.remove(target.key)
        target.orientation = hold.before
    }
}
