package com.monostr.app.data.linkpreview

import com.monostr.app.net.TorNotReadyException
import com.monostr.tips.RelayHosts
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
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Spec 3.3: reads the head of a linked page with hard limits; every failure is a plain null. */
class LinkPreviewFetcher(
    client: OkHttpClient,
    private val userAgent: String,
    private val addresses: AddressPolicy = AddressPolicy.PUBLIC_ONLY,
    /** Name resolution, the gated one of [client]; tests swap it to map a host to a private address. */
    private val dns: Dns = client.dns,
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

    /**
     * Cancelling the caller interrupts the blocking call, so a preview nobody waits for stops loading. Null is a miss, which the
     * caller remembers. Thrown instead, as it says nothing about the page: [TorNotReadyException], and in Tor mode any [IOException]
     * that ended the fetch without an answer (proxy stopped or refusing, a timeout, the cancel of a switch). Decided by the route at
     * the start as well as now, since switching Tor off cancels its calls after the gate has moved to Direct.
     */
    suspend fun fetch(url: String): LinkPreview? {
        var viaTor = false
        return try {
            runInterruptible(Dispatchers.IO) {
                viaTor = onTorRoute(url)
                fetchBlocking(url)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TorNotReadyException) {
            throw e
        } catch (e: IOException) {
            if (viaTor || onTorRoute(url)) throw e
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun onTorRoute(url: String): Boolean = url.toHttpUrlOrNull()?.let(::proxyResolves) ?: false

    private fun fetchBlocking(start: String): LinkPreview? {
        var url = start.toHttpUrlOrNull() ?: return null
        val deadline = System.nanoTime() + totalMs * 1_000_000
        for (hop in 0..MAX_REDIRECTS) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) throw InterruptedIOException("preview budget used up") // like a timeout within a hop
            // checked here as well as in the Dns: OkHttp skips the Dns for IP literals, and behind an HTTP proxy
            // the proxy resolves the host itself (a rebinding name could still answer the proxy differently)
            if (!hostAllowed(url)) return null
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

    private fun hostAllowed(url: HttpUrl): Boolean {
        if (proxyResolves(url)) return proxiedHostAllowed(url.host, addresses)
        val host = url.host
        if (':' in host) return addresses.allows(InetAddress.getByName(host)) // parsed, never resolved
        if (IPV4.matches(host)) return RelayHosts.ipv4(host)?.let(addresses::allows) ?: false // 999.1.1.1 is no address: no lookup either
        return http.dns.lookup(host).isNotEmpty() // throws when none is allowed
    }

    /**
     * SOCKS = Tor: the name goes to the proxy, no lookup here. Also asked for a URI without a host (127.1, foo_bar.example): OkHttp
     * itself would not ask and takes the direct route, which refuses in Tor mode. The JDK's own selector throws on those.
     */
    private fun proxyResolves(url: HttpUrl): Boolean {
        val proxy = http.proxy ?: try {
            http.proxySelector.select(url.toUri()).firstOrNull()
        } catch (_: IllegalArgumentException) {
            null
        }
        return proxy?.type() == Proxy.Type.SOCKS
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
        val parsed = image.toHttpUrlOrNull() ?: return false
        return runCatching { hostAllowed(parsed) }.getOrDefault(false)
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
