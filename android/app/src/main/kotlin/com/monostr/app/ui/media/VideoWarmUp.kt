package com.monostr.app.ui.media

import android.content.Context
import androidx.core.content.res.ResourcesCompat
import androidx.media3.exoplayer.ExoPlayer
import com.monostr.nostr.PerfTrace
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The first video of a process froze the app for 1.6 s when it opened (Pixel watchdog 2026-11-26, backlog 116): the
 * player's classes initialise on the main thread, among them the platform's media-route ones, and the controls load
 * their font from the resources. Both are done here once, on a background thread, as soon as a video tile is on
 * screen; opening the player then finds the classes loaded and the typeface in androidx's cache. Nothing is kept: the
 * builder is never built, no player and no codec exist until a video is opened.
 */
object VideoWarmUp {
    private val started = AtomicBoolean()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            val t0 = System.nanoTime()
            // the constructor alone runs the class initialisers the player's start waited for. Never `.build()` here: a
            // player without the gated source would reach the network on its own (NoBareClientsTest allows this file only)
            runCatching { ExoPlayer.Builder(app) }
            runCatching { ResourcesCompat.getFont(app, androidx.media3.ui.R.font.roboto_medium_numbers) }
            if (PerfTrace.enabled) PerfTrace.mark("video warm-up ${(System.nanoTime() - t0) / 1_000_000}")
        }, "video-warm-up").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}
