package com.monostr.app.data.linkpreview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Spec 3.3: reads the head of a linked page with hard limits; every failure is a plain null. */
class LinkPreviewFetcher(
    client: OkHttpClient,
    private val userAgent: String,
    private val addresses: AddressPolicy = AddressPolicy.PUBLIC_ONLY,
    /** Name resolution; tests swap it to map a host to a private address. */
    private val dns: Dns = Dns.SYSTEM,
    /** The budget for the whole fetch, all redirects included (spec 3.3: 8 s). */
    private val totalMs: Long = 8_000,
) {
    private val http = client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(totalMs, TimeUnit.MILLISECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val ok = dns.lookup(hostname).filter(addresses::allows)
                if (ok.isEmpty()) throw UnknownHostException("no allowed address for $hostname")
                return ok
            }
        })
        .build()

    /** Cancelling the caller interrupts the blocking call, so a preview nobody waits for stops loading. */
    suspend fun fetch(url: String): LinkPreview? = try {
        runInterruptible(Dispatchers.IO) { fetchBlocking(url) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun fetchBlocking(start: String): LinkPreview? {
        var url = start.toHttpUrlOrNull() ?: return null
        val deadline = System.nanoTime() + totalMs * 1_000_000
        for (hop in 0..MAX_REDIRECTS) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return null
            // checked here as well as in the Dns: OkHttp skips the Dns for IP literals, and behind an HTTP proxy
            // the proxy resolves the host itself (a rebinding name could still answer the proxy differently)
            if (!hostAllowed(url.host)) return null
            val request = Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en")
                .build()
            val call = http.newCall(request)
            call.timeout().timeout(left, TimeUnit.MILLISECONDS)
            val next = call.execute().use { resp ->
                if (!resp.isRedirect) return read(resp, url)
                resp.header("Location")?.let { url.resolve(it) }
            }
            url = next ?: return null
        }
        return null
    }

    private fun hostAllowed(host: String): Boolean {
        val literal = ':' in host || IPV4.matches(host)
        return if (literal) addresses.allows(InetAddress.getByName(host)) else http.dns.lookup(host).isNotEmpty() // throws when none is allowed
    }

    private fun read(resp: Response, url: HttpUrl): LinkPreview? {
        if (resp.code != 200) return null
        val body = resp.body ?: return null
        val type = body.contentType() ?: return null
        val mime = "${type.type}/${type.subtype}".lowercase()
        if (mime != "text/html" && mime != "application/xhtml+xml") return null
        val bytes = readHead(body.source())
        val charset = browserCharset(type.charset() ?: sniffCharset(bytes) ?: Charsets.UTF_8)
        val preview = OpenGraphParser.parse(String(bytes, charset), url.toString()) ?: return null
        // the image loader has no address policy of its own: keep only a picture this fetcher could contact too
        return preview.copy(imageUrl = preview.imageUrl?.takeIf { imageAllowed(it) })
    }

    private fun imageAllowed(image: String): Boolean {
        val host = image.toHttpUrlOrNull()?.host ?: return false
        return runCatching { hostAllowed(host) }.getOrDefault(false)
    }

    companion object {
        const val MAX_BYTES = 256 * 1024
        const val MAX_REDIRECTS = 5
        private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}") // regex
        private val META_CHARSET = Regex("<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]+)", RegexOption.IGNORE_CASE) // regex
        private val HEAD_END = "</head>".encodeUtf8()
        private val HEAD_END_UPPER = "</HEAD>".encodeUtf8()

        /** At most [max] bytes, stopping early once the head is closed; a timeout keeps what was read. */
        internal fun readHead(source: BufferedSource, max: Int = MAX_BYTES): ByteArray {
            val out = Buffer()
            try {
                while (out.size < max) {
                    val n = source.read(out, minOf(8192L, max - out.size))
                    if (n == -1L) break
                    if (out.indexOf(HEAD_END) != -1L || out.indexOf(HEAD_END_UPPER) != -1L) break
                }
            } catch (_: IOException) {
                // the budget ran out mid-body: parse what arrived
            }
            return out.readByteArray()
        }

        /** Browsers read pages labelled Latin-1 or ASCII as windows-1252 (smart quotes, the euro sign). */
        private fun browserCharset(c: Charset): Charset =
            if (c == Charsets.ISO_8859_1 || c == Charsets.US_ASCII) runCatching { Charset.forName("windows-1252") }.getOrDefault(c) else c

        private fun sniffCharset(bytes: ByteArray): Charset? {
            val probe = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            val name = META_CHARSET.find(probe)?.groupValues?.get(1) ?: return null
            return runCatching { Charset.forName(name) }.getOrNull()
        }
    }
}
