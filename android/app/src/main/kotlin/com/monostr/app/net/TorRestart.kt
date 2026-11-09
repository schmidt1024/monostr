package com.monostr.app.net

import java.io.File
import java.io.FileOutputStream

/**
 * Where the user was when a switch to Tor ended the process, for the next start, one time. A private file and not an Intent extra:
 * the activity is exported, and no other app may choose a route. [arm] comes with the request, [persist] from the end itself.
 */
class TorRestartReturn(private val file: File) {
    @Volatile private var armed: String? = null

    fun arm(route: String?) { armed = route }

    /** Nothing armed also removes what may be left from an end that nobody came back from. */
    fun persist() {
        val route = armed
        if (route == null) {
            file.delete()
            return
        }
        FileOutputStream(file).use {
            it.write(route.toByteArray())
            it.fd.sync()
        }
    }

    /** One stat when there is nothing. An unreadable store is no route, the host starts at the feed. */
    fun take(): String? {
        if (!file.exists()) return null
        val route = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        return route?.takeIf { it.isNotEmpty() }
    }
}

/**
 * The end of the process after a direct relay route, what [TorSwitch.turnOn] calls. In the foreground the activity comes back at once, on a new
 * process, at the stored route; out of it there is nobody to show anything to and no route is kept, the next start reads the marker. Nothing that fails here keeps the process alive.
 */
class TorProcessEnd(
    private val inForeground: () -> Boolean,
    private val persistReturn: () -> Unit,
    private val restartActivity: () -> Unit,
    private val exit: () -> Unit,
) {
    operator fun invoke() {
        try {
            if (inForeground()) {
                runCatching { persistReturn() }
                runCatching { restartActivity() }
            }
        } finally {
            exit()
        }
    }
}
