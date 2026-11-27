package com.monostr.app.ui.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.monostr.app.security.TrustedApps

/** Which installed Tor Browser can open a link; null when none. Asked off the main thread when the prompt opens (torbrowser brief, point 6). */
fun interface TorBrowserFinder {
    fun find(url: String): String?

    /**
     * An installed Tor Browser that takes no link from another app: 16.0 dropped its https filter and closed its own
     * `torbrowser:` scheme (Pixel 2026-11-26). It can still be started as from its icon; the prompt then copies the
     * link first. Null when none is installed. Asked only when [find] found nothing.
     */
    fun launchOnly(): String? = null
}

/** An `ACTION_VIEW` of [url] bound to [pkg]: kept plain so the choice can be tested without Android. */
data class ViewLaunch(val action: String, val url: String, val pkg: String) {
    fun toIntent(): Intent = Intent(action, Uri.parse(url)).setPackage(pkg)
}

/** Torbrowser brief 2026-11-07: under Tor a link can go to Tor Browser, the stable one before the alpha, and to no other app. */
object TorBrowser {
    /** Also in the manifest's `<queries>`, without which Android 11+ hides them. */
    val PACKAGES = listOf(TrustedApps.TOR_BROWSER, TrustedApps.TOR_BROWSER_ALPHA)

    fun choose(resolves: (String) -> Boolean): String? = PACKAGES.firstOrNull(resolves)

    fun launch(url: String, resolves: (String) -> Boolean): ViewLaunch? =
        choose(resolves)?.let { ViewLaunch(Intent.ACTION_VIEW, url, it) }

    /** Asks the package manager whether [pkg] is the Tor Project's build and has an activity for a view of [url]. */
    fun finder(context: Context): TorBrowserFinder {
        val pm = context.applicationContext.packageManager
        return object : TorBrowserFinder {
            override fun find(url: String): String? = choose { pkg ->
                // only the Tor Project's own build: another app under its name would get the link without Tor
                if (!TrustedApps.isTrusted(context, pkg)) return@choose false
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
                    else @Suppress("DEPRECATION") pm.resolveActivity(intent, 0)
                }.getOrNull() != null
            }

            override fun launchOnly(): String? = choose { pkg ->
                TrustedApps.isTrusted(context, pkg) && runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() != null
            }
        }
    }
}
