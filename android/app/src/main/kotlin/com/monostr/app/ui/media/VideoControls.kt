package com.monostr.app.ui.media

import android.content.Context

/** The player's small rules beyond media3's controls (owner 2026-11-19, like X): loop short clips, double-tap seeks, mute. */
object VideoControls {
    /** Clips up to this length play in a loop. */
    const val LOOP_UP_TO_MS = 60_000L
    /** One double tap jumps this far. */
    const val SEEK_STEP_MS = 10_000L

    /** A known length up to a minute loops; an unknown one (media3's TIME_UNSET is negative) does not. */
    fun loops(durationMs: Long): Boolean = durationMs in 1..LOOP_UP_TO_MS

    /** Where a double tap lands: ten seconds back or ahead, never before the start or past a known end. */
    fun seekTarget(positionMs: Long, durationMs: Long, forward: Boolean): Long {
        val target = positionMs + if (forward) SEEK_STEP_MS else -SEEK_STEP_MS
        val end = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return target.coerceIn(0, end)
    }

    private const val PREFS = "video_player"
    private const val MUTED = "muted"

    /** The mute choice is remembered on the device only (no account data, no sync). */
    fun muted(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MUTED, false)

    fun setMuted(context: Context, muted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(MUTED, muted).apply()
    }
}
