package com.monostr.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.serviceLoaderEnabled
import com.monostr.app.net.TorNotReadyException
import com.monostr.app.net.gatedOkHttp
import com.monostr.app.ui.media.VideoPlayerScreen
import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Spec 2026-11-06 Tor 7.2 on the Android runtime: its SOCKS client, Coil and media3 are what the JVM tests cannot show. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class GatedHttpDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }

    @After fun stop() = server.shutdown()

    private fun direct() = gatedOkHttp(NetworkGate().apply { set(NetworkMode.Direct) }) { false }
    private fun torNotReady() = gatedOkHttp(NetworkGate(pickPorts = { 1 to 2 }).apply { set(torMode()) }) { false }
    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    private fun loader(http: OkHttpClient) =
        ImageLoader.Builder(ctx).serviceLoaderEnabled(false).components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }.build()

    private fun png(): MockResponse {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(out.toByteArray()))
    }

    private fun mp4(): MockResponse =
        MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(instrumentation.context.assets.open("rotated-gps.mp4").use { it.readBytes() }))

    /** The request of a name over SOCKS as it arrives: address type and name. */
    private fun socksAsk(listener: ServerSocket): CompletableFuture<Pair<Int, String>> {
        val ask = CompletableFuture<Pair<Int, String>>()
        thread(isDaemon = true) {
            runCatching {
                listener.accept().use { s ->
                    val input = DataInputStream(s.getInputStream())
                    input.readUnsignedByte()
                    repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
                    s.getOutputStream().write(byteArrayOf(5, 0))
                    repeat(3) { input.readUnsignedByte() }
                    val atyp = input.readUnsignedByte()
                    val name = if (atyp == 3) ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII) else "atyp $atyp"
                    ask.complete(atyp to name)
                    s.getOutputStream().write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                }
            }.onFailure { ask.completeExceptionally(it) }
        }
        return ask
    }

    @Test
    fun the_android_socks_client_hands_over_the_name_unresolved() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val ask = socksAsk(listener)
            val http = gatedOkHttp(NetworkGate(pickPorts = { listener.localPort to 1 }).apply { set(torMode()) }) { true }
            val failure = runCatching { http.newCall(Request.Builder().url("https://x.example/a").build()).execute().close() }.exceptionOrNull()
            assertTrue("$failure", failure is IOException)
            assertEquals(3 to "x.example", ask.get(5, TimeUnit.SECONDS))
        }
    }

    /** Android's java.net.URI, unlike the JDK's, finds a host in an underscore name, so here it reaches the proxy. */
    @Test
    fun an_underscore_host_goes_to_the_proxy_on_android() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val ask = socksAsk(listener)
            val http = gatedOkHttp(NetworkGate(pickPorts = { listener.localPort to 1 }).apply { set(torMode()) }) { true }
            runCatching { http.newCall(Request.Builder().url("https://foo_bar.example/a").build()).execute().close() }
            assertEquals(3 to "foo_bar.example", ask.get(5, TimeUnit.SECONDS))
        }
    }

    /** A last label that starts with a digit: whatever URI makes of it, the proxy or a typed refusal, never the direct route. */
    @Test
    fun a_numeric_looking_host_stays_off_the_direct_route() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val ask = socksAsk(listener)
            val http = gatedOkHttp(NetworkGate(pickPorts = { listener.localPort to 1 }).apply { set(torMode()) }) { true }
            val failure = runCatching { http.newCall(Request.Builder().url("https://x.1/a").build()).execute().close() }.exceptionOrNull()
            assertTrue("$failure", failure is IOException)
            assertTrue("$failure", failure is TorNotReadyException || ask.get(5, TimeUnit.SECONDS) == (3 to "x.1"))
        }
    }

    @Test
    fun the_apps_own_image_loader_works_with_the_switch_off() = runBlocking {
        server.enqueue(png())
        // MonostrApp's loader on the real graph: gate and switch as at a real start
        val result = SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(url("/app.png")).build())
        assertTrue("$result", result is SuccessResult)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun coil_loads_in_direct_mode() = runBlocking {
        server.enqueue(png())
        val result = loader(direct()).execute(ImageRequest.Builder(ctx).data(url("/a.png")).build())
        assertTrue("$result", result is SuccessResult)
    }

    @Test
    fun coil_sends_nothing_in_tor_not_ready() = runBlocking {
        val result = loader(torNotReady()).execute(ImageRequest.Builder(ctx).data(url("/a.png")).build())
        assertTrue("$result", result is ErrorResult)
        assertTrue("${(result as ErrorResult).throwable}", result.throwable is TorNotReadyException)
        assertEquals(0, server.requestCount)
    }

    /** Ready or the player's error, whichever comes first. */
    private fun play(http: OkHttpClient, path: String): CompletableFuture<PlaybackException?> {
        val outcome = CompletableFuture<PlaybackException?>()
        Handler(Looper.getMainLooper()).post {
            val player = ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(http))).build()
            outcome.whenComplete { _, _ -> Handler(Looper.getMainLooper()).post { player.release() } }
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) outcome.complete(null) }
                override fun onPlayerError(error: PlaybackException) { outcome.complete(error) }
            })
            player.setMediaItem(MediaItem.fromUri(url(path)))
            player.prepare()
        }
        return outcome
    }

    @Test
    fun video_plays_in_direct_mode() {
        repeat(3) { server.enqueue(mp4()) }
        assertEquals(null, play(direct(), "/v.mp4").get(30, TimeUnit.SECONDS))
        assertTrue(server.requestCount >= 1)
    }

    @Test
    fun video_sends_nothing_in_tor_not_ready() {
        val error = play(torNotReady(), "/v.mp4").get(30, TimeUnit.SECONDS)
        assertTrue("$error", error != null)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun the_player_screen_fetches_through_the_client_it_is_given() {
        repeat(3) { server.enqueue(mp4()) }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val http = direct().newBuilder().addInterceptor { chain -> calls.incrementAndGet(); chain.proceed(chain.request()) }.build()
        rule.setContent { VideoPlayerScreen(url = url("/v.mp4"), onBack = {}, http = http) }
        rule.onNodeWithTag("video-player").assertExists()
        rule.waitUntil(15_000) { calls.get() >= 1 }
        rule.waitUntil(15_000) { server.requestCount >= 1 }
    }
}
