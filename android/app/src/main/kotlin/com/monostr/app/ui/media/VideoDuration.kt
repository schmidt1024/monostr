package com.monostr.app.ui.media

/** A video's length on its card (owner 2026-11-19: people see what they are in for), like a player's clock. */
object VideoDuration {
    /** m:ss below an hour, h:mm:ss from there; digits are the same in every language the app speaks. */
    fun label(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s) else "%d:%02d".format(java.util.Locale.ROOT, m, s)
    }
}
