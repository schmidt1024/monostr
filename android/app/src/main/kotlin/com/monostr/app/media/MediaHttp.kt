package com.monostr.app.media

import com.monostr.app.BuildConfig
import com.monostr.app.data.linkpreview.AddressCheckInterceptor
import com.monostr.app.data.linkpreview.AddressPolicy
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The gated client with an address check, for everything a stranger's note can name: pictures, avatars, video poster
 * frames, the video player and the picture export (the last three since the bug bounty of 2026-11-26). A URL in a note
 * must not make the app reach into the user's LAN or loopback (router pages, local services). The own media server is
 * exempt, it may be self-hosted at home; debug builds allow any address for the loopback device tests.
 */
@Singleton
class MediaHttp @Inject constructor(private val http: dagger.Lazy<OkHttpClient>) {
    /** The account's upload server (`https://host`), kept current by the application; null until the settings are read. */
    @Volatile var ownServer: String? = null

    /** Built with the first picture: a client is not for the main thread at start. */
    val client: OkHttpClient by lazy {
        val base = http.get()
        val check = AddressCheckInterceptor(if (BuildConfig.DEBUG) AddressPolicy.ANY else AddressPolicy.PUBLIC_ONLY, base.dns)
        base.newBuilder().addNetworkInterceptor { chain ->
            val own = ownServer?.let { runCatching { java.net.URI(it).host }.getOrNull() }
            if (own != null && chain.request().url.host.equals(own, ignoreCase = true)) chain.proceed(chain.request()) else check.intercept(chain)
        }.build()
    }
}
