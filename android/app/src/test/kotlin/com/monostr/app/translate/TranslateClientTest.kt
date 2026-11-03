package com.monostr.app.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TranslateClientTest {
    private lateinit var web: MockWebServer
    private lateinit var server: TranslateServer
    private val client = TranslateClient(OkHttpClient())

    @BeforeEach fun up() { web = MockWebServer(); web.start(); server = TranslateServer(web.url("/").toString().trimEnd('/')) }
    @AfterEach fun down() { web.shutdown() }

    @Test
    fun `request carries q array, format html and no key`() = runTest {
        web.enqueue(MockResponse().setBody("""{"translatedText":["a","b"]}"""))
        val r = client.translate(server, listOf("x", "y"), "fr", "de")
        assertEquals(TranslateResult.Ok(listOf("a", "b")), r)
        val req = web.takeRequest()
        assertEquals("POST", req.method); assertEquals("/translate", req.path)
        val raw = req.body.readUtf8()
        val o = Json.parseToJsonElement(raw).jsonObject
        assertEquals(listOf("x", "y"), o["q"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("fr", o["source"]!!.jsonPrimitive.content)
        assertEquals("de", o["target"]!!.jsonPrimitive.content)
        assertEquals("html", o["format"]!!.jsonPrimitive.content)
        assertFalse(raw.contains("api_key"))
    }

    @Test
    fun `429 is rate limited`() = runTest {
        web.enqueue(MockResponse().setResponseCode(429))
        assertEquals(TranslateResult.RateLimited, client.translate(server, listOf("x"), "fr", "de"))
    }

    @Test
    fun `other errors fail`() = runTest {
        for (code in listOf(401, 500)) {
            web.enqueue(MockResponse().setResponseCode(code))
            assertEquals(TranslateResult.Failed, client.translate(server, listOf("x"), "fr", "de"))
        }
    }

    @Test
    fun `wrong array size fails`() = runTest {
        web.enqueue(MockResponse().setBody("""{"translatedText":["a"]}"""))
        assertEquals(TranslateResult.Failed, client.translate(server, listOf("x", "y"), "fr", "de"))
    }

    @Test
    fun `garbage body fails`() = runTest {
        web.enqueue(MockResponse().setBody("not json"))
        assertEquals(TranslateResult.Failed, client.translate(server, listOf("x"), "fr", "de"))
    }

    @Test
    fun `silent server fails after the call timeout`() = runTest {
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t = System.currentTimeMillis()
        assertEquals(TranslateResult.Failed, TranslateClient(OkHttpClient(), callTimeoutSeconds = 1).translate(server, listOf("x"), "fr", "de"))
        assertTrue(System.currentTimeMillis() - t < 5_000)
    }

    @Test
    fun `a per-call timeout replaces the client's`() = runTest {
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t = System.currentTimeMillis()
        // the client's own timeout is 15 s: the call's 1 s wins
        assertEquals(TranslateResult.Failed, TranslateClient(OkHttpClient()).translate(server, listOf("x"), "fr", "de", timeoutMs = 1_000))
        assertTrue(System.currentTimeMillis() - t < 5_000)
    }

    // LibreTranslate sends its headers only when the whole answer is ready: the shared client's read timeout must not cut it
    @Test
    fun `a slow answer is bounded by the call timeout, not the shared read timeout`() = runTest {
        val base = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        val ok = MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("""{"translatedText":["y"]}""")
        web.enqueue(ok)
        assertEquals(TranslateResult.Ok(listOf("y")), TranslateClient(base).translate(server, listOf("x"), "fr", "de", timeoutMs = 5_000))
        web.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("""{"translatedText":["y"]}"""))
        assertEquals(TranslateResult.Failed, TranslateClient(base).translate(server, listOf("x"), "fr", "de", timeoutMs = 1_000))
    }

    @Test
    fun `host of a server`() {
        assertEquals("translate.monostr.com", TranslateServer.MONOSTR.host)
        assertEquals("example.org", TranslateServer("https://example.org:8443").host)
    }

    // M2: leaving the reader cancels the HTTP call, not just the coroutine

    @Test
    fun `a cancelled caller cancels the http call`(): Unit = runBlocking {
        val cancelled = CountDownLatch(1)
        val listening = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) = cancelled.countDown()
        }).build()
        web.enqueue(MockResponse().setBody("""{"translatedText":["a"]}""").setHeadersDelay(3, TimeUnit.SECONDS))
        val job = launch(Dispatchers.Default) { TranslateClient(listening).translate(server, listOf("x"), "fr", "de") }
        assertTrue(web.takeRequest(5, TimeUnit.SECONDS) != null, "request sent")
        val t = System.currentTimeMillis()
        job.cancelAndJoin()
        assertTrue(cancelled.await(1, TimeUnit.SECONDS), "OkHttp call cancelled")
        assertTrue(System.currentTimeMillis() - t < 1_500, "the caller did not wait for the server")
    }

    // M3: no https -> http redirect for a custom server; same-scheme redirects still follow

    @Test
    fun `redirects never downgrade https to http`() {
        val http = TranslateClient(OkHttpClient()).http
        assertFalse(http.followSslRedirects)
        assertTrue(http.followRedirects)
    }

    @Test
    fun `a same scheme redirect is followed`() = runTest {
        web.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/v2/translate"))
        web.enqueue(MockResponse().setBody("""{"translatedText":["a"]}"""))
        assertEquals(TranslateResult.Ok(listOf("a")), client.translate(server, listOf("x"), "fr", "de"))
        web.takeRequest()
        assertEquals("/v2/translate", web.takeRequest().path)
    }
}
