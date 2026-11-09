package com.monostr.app.ui.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/** Which installed Tor Browser can open a link; null when none. Asked off the main thread when the prompt opens (torbrowser brief, point 6). */
fun interface TorBrowserFinder {
    fun find(url: String): String?
}

/** An `ACTION_VIEW` of [url] bound to [pkg]: kept plain so the choice can be tested without Android. */
data class ViewLaunch(val action: String, val url: String, val pkg: String) {
    fun toIntent(): Intent = Intent(action, Uri.parse(url)).setPackage(pkg)
}

/** Torbrowser brief 2026-11-07: under Tor a link can go to Tor Browser, the stable one before the alpha, and to no other app. */
object TorBrowser {
    /** Also in the manifest's `<queries>`, without which Android 11+ hides them. */
    val PACKAGES = listOf("org.torproject.torbrowser", "org.torproject.torbrowser_alpha")

    fun choose(resolves: (String) -> Boolean): String? = PACKAGES.firstOrNull(resolves)

    fun launch(url: String, resolves: (String) -> Boolean): ViewLaunch? =
        choose(resolves)?.let { ViewLaunch(Intent.ACTION_VIEW, url, it) }

    /** Asks the package manager whether [pkg] has an activity for a view of [url]. */
    fun finder(context: Context): TorBrowserFinder {
        val pm = context.applicationContext.packageManager
        return TorBrowserFinder { url ->
            choose { pkg ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
                    else @Suppress("DEPRECATION") pm.resolveActivity(intent, 0)
                }.getOrNull() != null
            }
        }
    }
}
