package com.monostr.app.data.linkpreview

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.Proxy

/**
 * Checks the address of every connection a request uses, redirects included, so the address
 * check cannot be skipped by a redirect or a DNS answer that changes between lookup and connect.
 * Behind a proxy the proxy connects, so the target name is resolved and checked instead.
 */
class AddressCheckInterceptor(private val policy: AddressPolicy = AddressPolicy.PUBLIC_ONLY) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val route = chain.connection()?.route() ?: throw IOException("no connection")
        val allowed = if (route.proxy.type() == Proxy.Type.DIRECT) {
            policy.allows(route.socketAddress.address)
        } else {
            Dns.SYSTEM.lookup(chain.request().url.host).let { all -> all.isNotEmpty() && all.all(policy::allows) }
        }
        if (!allowed) throw IOException("address refused for ${chain.request().url.host}")
        return chain.proceed(chain.request())
    }
}

/** Security review: the pictures of link previews load through their own image loader, never the app's shared one. */
class PreviewImages(val loader: ImageLoader) {
    companion object {
        fun create(context: Context, http: OkHttpClient): PreviewImages {
            val client by lazy {
                http.newBuilder().cookieJar(CookieJar.NO_COOKIES).addNetworkInterceptor(AddressCheckInterceptor()).build()
            }
            return PreviewImages(
                ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }.build(),
            )
        }
    }
}
