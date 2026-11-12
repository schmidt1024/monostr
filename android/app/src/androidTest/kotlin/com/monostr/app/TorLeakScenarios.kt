@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.monostr.app

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.monostr.app.data.PendingTip
import com.monostr.app.data.PrefsPendingTipStore
import com.monostr.app.data.Presets.DEFAULT_WATCHER
import com.monostr.app.data.linkpreview.LinkPreviewFetcher
import com.monostr.app.data.linkpreview.PreviewImages
import com.monostr.app.data.monostrPrefs
import com.monostr.app.media.ImageTarget
import com.monostr.app.net.TorState
import com.monostr.app.session.NostrSessionEntryPoint
import com.monostr.app.session.Ready
import com.monostr.app.session.SessionState
import com.monostr.app.translate.TranslateClient
import com.monostr.app.translate.TranslateResult
import com.monostr.app.translate.TranslateServer
import com.monostr.app.ui.media.VideoPlayerScreen
import com.monostr.app.work.BackgroundCheckWorker
import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.nostr.LocalSigner
import com.monostr.nostr.net.NetworkMode
import com.monostr.tips.PaymentInfo
import com.monostr.tips.TipRequest
import com.monostr.tips.TipType
import com.monostr.tips.Watcher
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction

/**
 * Tor plan Task 11: what runs while `scripts/tor-capture.sh` records the wire. Not part of the suite: every test is skipped without
 * `-e leak true`, as each needs a capture around it, real relays and Tor, and is judged by the capture rather than by an assertion
 * here. See `docs/testing/tor-leak-test.md`. Keys are made here for the run and never printed.
 */
@RunWith(AndroidJUnit4::class)
class TorLeakScenarios {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val args get() = InstrumentationRegistry.getArguments()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val deps get() = EntryPointAccessors.fromApplication(app, NostrSessionEntryPoint::class.java)
    private val session get() = deps.session()
    private val switch get() = deps.torSwitchControl()
    private val gate get() = deps.networkGate()
    private val tor get() = deps.torController().get()
    private val http: OkHttpClient get() = deps.http()

    /** The throwaway account of this run; never printed. */
    private var me: Keys = Keys.generate()

    private fun log(text: String) { Log.i("TorLeak", text) }

    /** A UDP packet to TEST-NET, which `tor_capture_analyse.py` cuts the capture at. Direct on purpose; it carries the name of a step only. */
    private fun mark(name: String) {
        log("mark $name")
        thread {
            runCatching {
                DatagramSocket().use { s ->
                    val body = "TORLEAK:$name".toByteArray()
                    s.send(DatagramPacket(body, body.size, InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)), 9))
                }
            }
        }.join()
    }

    private suspend fun step(label: String, name: String, limitMs: Long = 60_000, block: suspend () -> String) {
        mark("$label-$name")
        val began = System.currentTimeMillis()
        val out = try {
            withTimeoutOrNull(limitMs) { withContext(Dispatchers.IO) { block() } } ?: "TIMEOUT"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "FAIL ${e.javaClass.simpleName}"
        }
        log("result $label $name ${System.currentTimeMillis() - began}ms $out")
    }

    private fun testPicture(): File = File(app.cacheDir, "leak-test.png").apply {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.setPixel(3, 5, (System.currentTimeMillis() and 0xFFFFFF).toInt() or (0xFF shl 24))
        outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A login resets the list to the defaults: then the throwaway account reads from foreign relays, and relay.monostr.com is not in the pool. */
    private suspend fun login(): Ready {
        me = Keys.generate()
        session.loginLocal(LocalSigner(me.secretKey().toHex()))
        session.applyRelays(listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net"))
        return session.requireReady()
    }

    private suspend fun picture(url: String, loader: coil3.ImageLoader): String {
        val r = loader.execute(ImageRequest.Builder(app).data(url).memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build())
        return when (r) {
            is SuccessResult -> "ok"
            is ErrorResult -> "FAIL ${r.throwable.javaClass.simpleName} ${r.throwable.message?.take(60)}"
        }
    }

    private fun get(url: String): String = http.newCall(Request.Builder().url(url).header("Range", "bytes=0-1023").build()).execute().use { "http ${it.code}" }

    /** Everything of the app that opens a connection, once. [publish]: also the steps that leave a test event on relay.monostr.com. */
    private suspend fun exercise(ready: Ready, label: String, viaTor: Boolean, publish: Boolean = true, upload: Boolean = true) {
        val recipient = Keys.generate().publicKey().toHex()
        val watcherKey = Keys.generate().publicKey().toHex()
        val foreign = listOf("wss://relay.snort.social", "wss://offchain.pub", "wss://nostr.mom")
        step(label, "relays", 30_000) {
            ready.engine.connect()
            val until = System.currentTimeMillis() + 25_000
            while (ready.engine.connectedRelayUrls().isEmpty() && System.currentTimeMillis() < until) delay(500)
            "connected ${ready.engine.connectedRelayUrls().size}"
        }
        step(label, "fetch", 30_000) { "notes ${ready.engine.fetch(Filter().kind(Kind(1u)).limit(5u), Duration.ofSeconds(15)).size}" }
        step(label, "foreign-fetch", 40_000) { "notes ${ready.engine.fetchFrom(foreign, Filter().kind(Kind(1u)).limit(3u), Duration.ofSeconds(20)).size}" }
        step(label, "foreign-attach", 40_000) {
            val held = ready.engine.attachTemporary(listOf("wss://relay.snort.social"))
            val notes = ready.engine.fetchFrom(listOf("wss://relay.snort.social"), Filter().kind(Kind(0u)).limit(1u), Duration.ofSeconds(15), attach = false).size
            ready.engine.detachTemporary(held)
            "held ${held.size} notes $notes"
        }
        step(label, "profile", 20_000) { "name ${ready.profiles.fetched(ready.pubkey, Duration.ofSeconds(10)).shownName.isNotEmpty()}" }
        step(label, "picture") { picture("https://monostr.com/monostr-icon.png", SingletonImageLoader.get(app)) }
        step(label, "picture-preview-loader") {
            picture("https://upload.wikimedia.org/wikipedia/commons/4/47/PNG_transparency_demonstration_1.png", PreviewImages.create(app, http).loader)
        }
        step(label, "link-preview") {
            val preview = LinkPreviewFetcher(http, "monostr/leak-test").fetch("https://en.wikipedia.org/wiki/Nostr")
            if (preview == null) "none" else "ok"
        }
        // the real screen, which has its own source and client
        val video = "https://download.samplelib.com/mp4/sample-5s.mp4"
        step(label, "video-screen") {
            val screen = ActivityScenario.launch(ComponentActivity::class.java)
            screen.onActivity { it.setContent { VideoPlayerScreen(video, onBack = {}, http = http) } }
            delay(15_000)
            screen.close()
            "shown 15 s"
        }
        // is the host reachable from here at all, which the screen does not say: a few tries, each on the shared pool
        step(label, "video-head", 90_000) {
            var out = ""
            for (i in 1..3) {
                out = runCatching { get(video) }.getOrElse { "FAIL ${it.javaClass.simpleName}" }
                log("result $label video-head-try $i $out")
                if (out.startsWith("http 2")) break
            }
            out
        }
        if (upload) {
            step(label, "upload", 90_000) {
                val done = ready.media.upload(testPicture().toURI().toString(), ImageTarget.NOTE)
                ready.media.discardNow(done)
                "ok ${done.size} bytes, discarded"
            }
        }
        step(label, "translate", 40_000) {
            when (val r = TranslateClient(http).translate(TranslateServer.MONOSTR, listOf("Guten Morgen"), "de", "en")) {
                is TranslateResult.Ok -> "ok"
                else -> "not ok ${r.javaClass.simpleName}"
            }
        }
        step(label, "watcher-info", 40_000) { "ok ${ready.watchers.info(DEFAULT_WATCHER).toString().length > 0}" }
        step(label, "http-monostr", 30_000) { get("https://relay.monostr.com/") }
        if (publish) {
            step(label, "anon-tip-intent", 60_000) {
                val address = MoneroKeys.generate().address(Network.MAINNET)
                val info = PaymentInfo.Enabled(address, Watcher.Http(DEFAULT_WATCHER, watcherKey))
                val prepared = TipRequest.prepare(info, recipient, null, 1_000_000_000L, TipType.TIP, "monostr Tor test, please ignore", System.currentTimeMillis() / 1000, anonymous = true)
                val sent = ready.tips.sendAnonymousIntent(prepared, listOf("wss://relay.monostr.com"), torOnly = viaTor)
                "accepted ${sent.result.successRelays.size} failed ${sent.result.failedRelays.keys.size}"
            }
            step(label, "sendTo", 60_000) {
                // not in the pool: the path that attaches a relay for this one send
                val signed = EventBuilder.textNote("monostr Tor test, please ignore").signWithKeys(me)
                ready.engine.save(signed)
                val out = ready.engine.sendTo(listOf("wss://relay.monostr.com"), signed.id().toHex())
                "accepted ${out.successRelays.size} failed ${out.failedRelays.keys}"
            }
            step(label, "publish", 60_000) {
                // our relay alone: a test event goes nowhere else
                session.applyRelays(listOf("wss://relay.monostr.com"))
                val out = ready.engine.signAndPublish(EventBuilder.textNote("monostr Tor test, please ignore"))
                "accepted ${out.successRelays} failed ${out.failedRelays.keys}"
            }
        }
    }

    private class Squatter(port: Int) : AutoCloseable {
        private val listener = ServerSocket(port, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val accepted = AtomicInteger(0)
        init {
            thread(isDaemon = true) {
                while (!listener.isClosed) {
                    val s = runCatching { listener.accept() }.getOrNull() ?: break
                    accepted.incrementAndGet()
                    runCatching { s.close() }
                }
            }
        }
        override fun close() = listener.close()
    }

    private fun torPids(): List<Int> = File("/proc").listFiles().orEmpty().filter { it.name.all(Char::isDigit) }
        .filter { runCatching { File(it, "cmdline").readText().trim('\u0000').endsWith(":tor") }.getOrDefault(false) }
        .map { it.name.toInt() }

    private suspend fun awaitTor(limitMs: Long = 150_000): Long {
        val began = System.currentTimeMillis()
        withTimeout(limitMs) { tor.state.first { it == TorState.Connected } }
        return System.currentTimeMillis() - began
    }

    @Before fun only() {
        assumeTrue("needs a capture around it, see docs/testing/tor-leak-test.md", args.getString("leak") == "true")
    }

    @After fun end() {
        // a skipped scenario did nothing, so there is nothing to end: in the suite this would send a mark, log out and cancel the worker of whatever runs next
        if (args.getString("leak") != "true") return
        // before the switch goes off: from there on the app may go direct, as asked
        mark("end")
        if (args.getString("keep") == "true") return
        runBlocking {
            val direct = markDirect()
            runCatching { switch.turnOff() }
            // a turnOff that failed leaves the gate on Tor: no mark, and this must not wait for one
            direct.cancel()
            runCatching { session.logout() }
            BackgroundCheckWorker.cancel(app)
        }
    }

    /** The positive control: the same steps with the switch off, which the analysis must flag. */
    @Test fun control_off() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        assertEquals(NetworkMode.Direct, gate.mode.value)
        mark("start")
        val ready = login()
        exercise(ready, "off", viaTor = false)
    }

    @Test fun connected() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        mark("start")
        switch.onForeground()
        switch.turnOn()
        log("result connected bootstrap ${awaitTor()}ms")
        val ready = login()
        withTimeout(10_000) { ready.engine.routeOpen.first { it } }
        exercise(ready, "connected", viaTor = true)
    }

    /** Both ports held: Tor cannot start and the state goes Failed. Nothing may leave, and nobody may talk to the holder. */
    @Test fun failed() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        val ports = gate.torMode()
        mark("start")
        Squatter(ports.sessionPort).use { a ->
            Squatter(ports.isolatedPort).use { b ->
                switch.onForeground()
                switch.turnOn()
                withTimeout(60_000) { tor.state.first { it == TorState.Failed } }
                log("result failed state ${tor.state.value}")
                val ready = login()
                exercise(ready, "failed", viaTor = true)
                assertEquals(TorState.Failed, tor.state.value)
                log("result failed squatter-heard ${a.accepted.get() + b.accepted.get()}")
                assertEquals(0, a.accepted.get() + b.accepted.get())
            }
        }
    }

    /** A direct session is up; from the mark on nothing of it may stay, and nothing new may go direct while Tor is connecting. */
    @Test fun just_switched_on() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        mark("start")
        switch.onForeground()
        val ready = login()
        ready.engine.connect()
        withTimeoutOrNull(30_000) { while (ready.engine.connectedRelayUrls().isEmpty()) delay(200) }
        log("result switched direct-relays ${ready.engine.connectedRelayUrls().size}")
        http.newCall(Request.Builder().url("https://monostr.com/monostr-icon.png").build()).execute().close()
        mark("flip")
        switch.turnOn()
        val during = session.requireReady()
        log("result switched state-after-flip ${tor.state.value}")
        // while connecting: nothing is sent, so nothing of this can leave an event behind
        exercise(during, "connecting", viaTor = true)
        log("result switched bootstrap-left ${awaitTor()}ms")
        withTimeout(10_000) { during.engine.routeOpen.first { it } }
        exercise(during, "after-connect", viaTor = true, publish = false, upload = false)
    }

    /** First login on a fresh install: the row on the real login screen, then the key. Run after `pm clear`. */
    @Test fun fresh_login_row() {
        fun has(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        runBlocking { mark("start") }
        rule.waitUntil(30_000) { has("login-secret") }
        rule.onNodeWithTag("login-tor").performScrollTo()
        rule.onNodeWithTag("login-tor").onChildAt(0).performClick()
        rule.waitUntil(5_000) { has("tor-explain-confirm") }
        rule.onNodeWithTag("tor-explain-confirm").performClick()
        rule.waitUntil(60_000) { runBlocking { tor.state.value } is TorState.Connecting || runBlocking { tor.state.value } == TorState.Connected }
        log("result fresh state ${tor.state.value}")
        // at once: this first login must wait or go through Tor, connected or not
        rule.onNodeWithTag("login-secret").performScrollTo().performTextInput(Keys.generate().secretKey().toHex())
        rule.onNodeWithTag("login-secret-button").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(60_000) { has("fab-compose") || has("setup-skip") }
        log("result fresh login-passed state ${tor.state.value}")
        runBlocking {
            log("result fresh bootstrap-left ${awaitTor()}ms")
            val ready = session.requireReady()
            withTimeout(10_000) { ready.engine.routeOpen.first { it } }
            // the default relay list reads and writes: no publish in this one
            exercise(ready, "fresh", viaTor = true, publish = false, upload = false)
        }
    }

    /** Spec fail closed: Tor killed under a running session; every step meanwhile fails, and it comes back. Also measures the window. */
    @Test fun kill_tor() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        mark("start")
        switch.onForeground()
        switch.turnOn()
        awaitTor()
        val ready = login()
        withTimeout(10_000) { ready.engine.routeOpen.first { it } }
        ready.engine.connect()
        withTimeoutOrNull(60_000) { while (ready.engine.connectedRelayUrls().isEmpty()) delay(500) }
        val seen = java.util.Collections.synchronizedList(ArrayList<Pair<Long, TorState>>())
        repeat(3) { round ->
            awaitTor()
            delay(3_000)
            val pids = torPids()
            assertEquals(1, pids.size)
            seen.clear()
            val began = System.nanoTime()
            val watch = thread(isDaemon = true) {
                var last: TorState? = null
                while (System.nanoTime() - began < 20_000_000_000L) {
                    val s = tor.state.value
                    if (s != last) { seen += ((System.nanoTime() - began) / 1_000_000) to s; last = s }
                    Thread.sleep(1)
                }
            }
            mark("kill-$round")
            android.os.Process.killProcess(pids[0])
            if (round == 0) {
                // as soon as the state says so: nothing may go out
                exercise(ready, "killed", viaTor = true, publish = false, upload = false)
            } else {
                watch.join()
            }
            log("result kill round $round states ${seen.toList()}")
        }
        log("result kill back ${awaitTor()}ms")
        withTimeout(30_000) { ready.engine.routeOpen.first { it } }
        val back = withTimeoutOrNull(120_000) { while (ready.engine.connectedRelayUrls().isEmpty()) delay(500); true }
        log("result kill relays-back ${back == true} ${ready.engine.connectedRelayUrls().size}")
        step("resumed", "translate", 40_000) {
            when (val r = TranslateClient(http).translate(TranslateServer.MONOSTR, listOf("Guten Morgen"), "de", "en")) {
                is TranslateResult.Ok -> "ok"
                else -> "not ok ${r.javaClass.simpleName}"
            }
        }
    }

    /**
     * Mark `flip` from inside `gate.set(tor)`: unconfined, so it is on the wire before the switch goes on, and the direct session's last writes
     * before the switch stopped it stay in front of it. A mark before `turnOn` cannot tell those from a leak: it comes up to 40 ms before the session stops.
     */
    private fun CoroutineScope.markTheFlip() {
        launch(Dispatchers.Unconfined) {
            gate.mode.first { it is NetworkMode.Tor }
            mark("flip")
        }
    }

    /** Mark `direct` from inside `gate.set(Direct)`, the same way: unconfined, so it is on the wire before anything can use the direct route. */
    private fun CoroutineScope.markDirect() = launch(Dispatchers.Unconfined) {
        gate.mode.first { it == NetworkMode.Direct }
        mark("direct")
    }

    /** Spec 7.4: an onion entry with the switch off is not dialed and asks no DNS. */
    @Test fun onion_stored_switch_off() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        assertEquals(NetworkMode.Direct, gate.mode.value)
        val onion = "wss://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion"
        mark("start")
        session.loginLocal(LocalSigner(Keys.generate().secretKey().toHex()))
        session.applyRelays(listOf(onion, "wss://relay.damus.io"))
        val ready = session.requireReady()
        ready.engine.connect()
        delay(20_000)
        log("result onion connected ${ready.engine.connectedRelayUrls().size} onion-connected ${ready.engine.connectedRelayUrls().any { it.contains(".onion") }}")
        step("onion", "fetchFrom", 20_000) { "notes ${ready.engine.fetchFrom(listOf(onion), Filter().kind(Kind(1u)).limit(1u), Duration.ofSeconds(8)).size}" }
    }

    /**
     * Leaves a logged-in throwaway account, with the switch and a stored undelivered anonymous intent under Tor as asked:
     * `-e off true`: through the deliberate off (the intent must leave the store). `-e crash true`: marker off with the entry still there,
     * as a crash between the two steps would. The relay is then asked for the id, by hand; the app must never send it.
     */
    @Test fun pending_tip_then_off() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        switch.onForeground()
        switch.turnOn()
        awaitTor()
        val ready = login()
        val address = MoneroKeys.generate().address(Network.MAINNET)
        val info = PaymentInfo.Enabled(address, Watcher.Http(DEFAULT_WATCHER, Keys.generate().publicKey().toHex()))
        val recipient = Keys.generate().publicKey().toHex()
        val now = System.currentTimeMillis() / 1000
        val prepared = TipRequest.prepare(info, recipient, null, 1_000_000_000L, TipType.TIP, "monostr Tor test, must never arrive", now, anonymous = true)
        val signed = EventBuilder(Kind(prepared.unsignedEvent.kind.toUShort()), prepared.unsignedEvent.content).tags(prepared.unsignedEvent.tags.map { rust.nostr.sdk.Tag.parse(it) }).signWithKeys(Keys.generate())
        val tips = PrefsPendingTipStore(app.monostrPrefs)
        tips.add(
            PendingTip(
                intentId = signed.id().toHex(), noteId = null, amount = 1_000_000_000L, createdAt = now, recipient = recipient,
                anonEvent = signed.asJson(), anonRelays = listOf("wss://relay.monostr.com"), underTor = args.getString("undertor") != "false",
            ),
        )
        log("result pending intent-id ${signed.id().toHex()}")
        if (args.getString("crash") == "true") {
            // what remains after a crash right after the marker went: the entry is still stored
            deps.torSwitchStore().set(false)
        } else {
            val count = switch.pendingUnderTor()
            val dropped = switch.turnOff()
            log("result pending counted $count dropped $dropped left ${tips.pending.first().size}")
            assertEquals(0, tips.pending.first().size)
        }
    }

    /** Throwaway account for the background run: its relay is ours alone, its payment info names the throwaway watcher [-e watcher]. Stays logged in. */
    @Test fun background_prepare() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        session.loginLocal(LocalSigner(Keys.generate().secretKey().toHex()))
        session.applyRelays(listOf("wss://relay.monostr.com"))
        val ready = session.requireReady()
        ready.engine.connect()
        withTimeout(30_000) { while (ready.engine.connectedRelayUrls().isEmpty()) delay(200) }
        val address = MoneroKeys.generate().address(Network.MAINNET)
        val published = ready.tips.publishPaymentInfo(address, Watcher.Http(DEFAULT_WATCHER, args.getString("watcher")!!), false)
        log("result background payment-info ${published.sentToAny}")
        log("result background pubkey ${ready.pubkey}")
        switch.turnOn()
    }

    /** What rust-nostr says of each relay of an engine's pool, read through its private client: only for the leak hunt. */
    private suspend fun pool(engine: com.monostr.nostr.NostrEngine): String = runCatching {
        val client = com.monostr.nostr.NostrEngine::class.java.getDeclaredField("client").apply { isAccessible = true }.get(engine) as rust.nostr.sdk.Client
        client.relays().entries.joinToString { (url, relay) -> "$url=${relay.status().name}/${relay.connectionMode()}" }
    }.getOrElse { "error ${it.javaClass.simpleName} ${it.message}" }

    /**
     * The finding of the just-switched run, cut down: login, the list changes at once (`-e remove true`) or not, relays up, then the mark and
     * the switch (`-e flip true`) or nothing. Then only waiting: `-e wait` seconds. What the capture shows after the mark is the old session's.
     */
    @Test fun switch_min() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        log("result pid ${android.os.Process.myPid()}")
        mark("start")
        switch.onForeground()
        me = Keys.generate()
        session.loginLocal(LocalSigner(me.secretKey().toHex()))
        if (args.getString("remove") == "true") session.applyRelays(listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net"))
        // a relay that never answers, as a dead entry in a real list: it is connecting or waiting for its retry whenever the switch comes
        if (args.getString("dead") == "true") session.applyRelays(listOf("wss://relay.damus.io", "wss://203.0.113.5"))
        val ready = session.requireReady()
        ready.engine.connect()
        withTimeoutOrNull(30_000) { while (ready.engine.connectedRelayUrls().isEmpty()) delay(200) }
        (args.getString("settle"))?.let { delay(it.toLong() * 1000) }
        log("result min direct ${ready.engine.connectedRelayUrls().size} temporary ${ready.engine.temporaryRelayUrls().size}")
        mark("asked")
        if (args.getString("flip") == "true") {
            markTheFlip()
            switch.turnOn()
            val until = System.currentTimeMillis() + 150_000
            while (tor.state.value != TorState.Connected && System.currentTimeMillis() < until) delay(100)
            log("result min tor ${tor.state.value}")
        }
        delay((args.getString("wait") ?: "40").toLong() * 1000)
        val now = session.requireReady()
        log("result min end connected ${now.engine.connectedRelayUrls()} temporary ${now.engine.temporaryRelayUrls()}")
        log("result min same-engine ${now === ready}")
        log("result min pool-old ${pool(ready.engine)}")
        log("result min pool-new ${pool(now.engine)}")
    }

    /** Logged in with a throwaway account, on a stored list, nothing switched; for what the host then does in the real app. Run with `-e keep true`. */
    @Test fun ui_prepare() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        login()
        deps.uiSettings().setLinkAlwaysOpen(false)
        // reads the stored relays through a session that stops here
        session.requireReady().engine.connect()
        log("result prepare ${session.requireReady().pubkey}")
    }

    /**
     * On a fresh install, with `-e realEnd true`: the key typed, then the row on the login screen. No direct route has run, so no sentence about a restart,
     * no end (it would stop this test), and the key is still in the field.
     */
    @Test fun login_row_keeps_key() {
        fun has(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        val key = Keys.generate().secretKey().toHex()
        val pid = android.os.Process.myPid()
        mark("start")
        rule.waitUntil(30_000) { has("login-secret") }
        rule.onNodeWithTag("login-secret").performScrollTo().performTextInput(key)
        rule.onNodeWithTag("login-tor").performScrollTo()
        rule.onNodeWithTag("login-tor").onChildAt(0).performClick()
        rule.waitUntil(5_000) { has("tor-explain-confirm") }
        assertTrue("no restart to announce", !has("tor-explain-restart"))
        mark("flip")
        rule.onNodeWithTag("tor-explain-confirm").performClick()
        rule.waitUntil(60_000) { gate.mode.value is NetworkMode.Tor && !has("tor-explain-confirm") }
        rule.onNodeWithTag("login-secret").performScrollTo().assert(hasText(key))
        log("result row key-stays pid-same ${android.os.Process.myPid() == pid}")
        rule.onNodeWithTag("login-secret-button").performScrollTo().performClick()
        rule.waitUntil(60_000) { has("fab-compose") || has("setup-skip") }
        runBlocking {
            log("result row bootstrap-left ${awaitTor()}ms")
            val ready = session.requireReady()
            withTimeout(10_000) { ready.engine.routeOpen.first { it } }
            log("result row direct-route-used ${com.monostr.nostr.net.DirectRelayRoute.wasUsed}")
            assertEquals(false, com.monostr.nostr.net.DirectRelayRoute.wasUsed)
        }
        log("result row end pid-same ${android.os.Process.myPid() == pid}")
    }

    /** Tor without a direct route: in place. Then off, a direct session, and on again in the same process: this one ends it. With `-e realEnd true`. */
    @Test fun off_then_on() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        log("result pid ${android.os.Process.myPid()}")
        mark("start")
        switch.onForeground()
        switch.turnOn()
        log("result offon first on in place, direct used ${com.monostr.nostr.net.DirectRelayRoute.wasUsed}")
        awaitTor()
        val ready = login()
        withTimeout(10_000) { ready.engine.routeOpen.first { it } }
        log("result offon tor session, direct used ${com.monostr.nostr.net.DirectRelayRoute.wasUsed}")
        switch.turnOff()
        val direct = session.requireReady()
        direct.engine.connect()
        withTimeoutOrNull(30_000) { while (direct.engine.connectedRelayUrls().isEmpty()) delay(200) }
        log("result offon direct ${direct.engine.connectedRelayUrls().size}, direct used ${com.monostr.nostr.net.DirectRelayRoute.wasUsed}")
        markTheFlip()
        switch.turnOn()
    }

    /** A direct background run in progress in this process when the switch comes; the work is what the next process runs again. With `-e realEnd true`. */
    @Test fun worker_then_on() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        log("result pid ${android.os.Process.myPid()}")
        mark("start")
        switch.onForeground()
        login()
        // a run that begins while the activity is visible does nothing
        MainActivity.inForeground.set(false)
        val work = WorkManager.getInstance(app)
        val request = androidx.work.OneTimeWorkRequestBuilder<BackgroundCheckWorker>().build()
        work.enqueue(request)
        withTimeout(30_000) { while (work.getWorkInfoById(request.id).get()?.state != WorkInfo.State.RUNNING) delay(10) }
        log("result worker running ${request.id}")
        MainActivity.inForeground.set(true)
        markTheFlip()
        switch.turnOn()
    }

    /** One anonymous intent stored as sent direct and undelivered, then the switch. Whether the next process sends it is read from the relay, by id. With `-e realEnd true`. */
    @Test fun pending_direct_then_on() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        log("result pid ${android.os.Process.myPid()}")
        mark("start")
        switch.onForeground()
        val ready = login()
        ready.engine.connect()
        withTimeoutOrNull(30_000) { while (ready.engine.connectedRelayUrls().isEmpty()) delay(200) }
        val address = MoneroKeys.generate().address(Network.MAINNET)
        val info = PaymentInfo.Enabled(address, Watcher.Http(DEFAULT_WATCHER, Keys.generate().publicKey().toHex()))
        val recipient = Keys.generate().publicKey().toHex()
        val now = System.currentTimeMillis() / 1000
        val prepared = TipRequest.prepare(info, recipient, null, 1_000_000_000L, TipType.TIP, "monostr Tor test, please ignore", now, anonymous = true)
        val signed = EventBuilder(Kind(prepared.unsignedEvent.kind.toUShort()), prepared.unsignedEvent.content).tags(prepared.unsignedEvent.tags.map { rust.nostr.sdk.Tag.parse(it) }).signWithKeys(Keys.generate())
        PrefsPendingTipStore(app.monostrPrefs).add(
            PendingTip(
                intentId = signed.id().toHex(), noteId = null, amount = 1_000_000_000L, createdAt = now, recipient = recipient,
                anonEvent = signed.asJson(), anonRelays = listOf("wss://relay.monostr.com"), underTor = false,
            ),
        )
        log("result pending intent-id ${signed.id().toHex()}")
        markTheFlip()
        switch.turnOn()
    }

    /** The player as the screen builds it, on hosts of different kinds: does a video really play through Tor, and how fast. */
    @Test fun video_plays() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        mark("start")
        switch.onForeground()
        switch.turnOn()
        awaitTor()
        val hosts = listOf(
            "https://download.samplelib.com/mp4/sample-5s.mp4",
            "https://www.w3schools.com/html/mov_bbb.mp4",
            "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4",
        )
        for (url in hosts) {
            val state = withContext(Dispatchers.Main) {
                val done = kotlinx.coroutines.CompletableDeferred<String>()
                val source = OkHttpDataSource.Factory(http.newBuilder().followSslRedirects(false).build())
                val player = ExoPlayer.Builder(app).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build()
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) done.complete("ready") }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { done.complete("error ${error.errorCodeName}") }
                })
                player.setMediaItem(MediaItem.fromUri(url))
                val began = System.currentTimeMillis()
                player.prepare()
                val out = withTimeoutOrNull(60_000) { done.await() } ?: "timeout"
                player.release()
                "$out ${System.currentTimeMillis() - began}ms"
            }
            log("result video $url $state")
        }
    }

    /** Item 3: both ports held by someone else, the real banner's Try again: new ports, Tor connects, the holder heard nothing. */
    @Test fun port_conflict_try_again() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        val ports = gate.torMode()
        Squatter(ports.sessionPort).use { a ->
            Squatter(ports.isolatedPort).use { b ->
                switch.onForeground()
                switch.turnOn()
                withTimeout(60_000) { tor.state.first { it == TorState.Failed } }
                login()
                log("result conflict failed portConflict=${tor.portConflict}")
                rule.waitUntil(30_000) { rule.onAllNodes(hasTestTag("tor-retry")).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("tor-retry").performClick()
                val began = System.currentTimeMillis()
                awaitTor()
                val now = gate.mode.value as NetworkMode.Tor
                log("result conflict connected after ${System.currentTimeMillis() - began}ms ports-changed ${now.sessionPort != ports.sessionPort && now.isolatedPort != ports.isolatedPort} heard ${a.accepted.get() + b.accepted.get()}")
                assertTrue(now.sessionPort != ports.sessionPort)
                assertEquals(0, a.accepted.get() + b.accepted.get())
                val ready = session.requireReady()
                withTimeout(30_000) { ready.engine.routeOpen.first { it } }
                withTimeoutOrNull(60_000) { while (ready.engine.connectedRelayUrls().isEmpty()) delay(500) }
                log("result conflict relays ${ready.engine.connectedRelayUrls().size}")
            }
        }
    }

    /** Spec 5.9: what refuses Tor exits. Each round on whatever circuits Tor has then; one watcher registration of a throwaway address, taken back. */
    @Test fun limits() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        mark("start")
        switch.onForeground()
        switch.turnOn()
        awaitTor()
        val ready = login()
        withTimeout(10_000) { ready.engine.routeOpen.first { it } }
        val relays = listOf(
            "wss://relay.monostr.com", "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://search.monostr.com", "wss://search.nos.today",
            "wss://purplepag.es", "wss://indexer.coracle.social", "wss://user.kindpag.es", "wss://nostr.xmr.rocks", "wss://nerostr.xmr.rocks", "wss://xmr.usenostr.org",
        )
        repeat(3) { round ->
            for (r in relays) {
                val reached = java.util.Collections.synchronizedSet(HashSet<String>())
                val began = System.currentTimeMillis()
                val n = runCatching { ready.engine.fetchFrom(listOf(r), Filter().kind(Kind(0u)).limit(1u), Duration.ofSeconds(20), reached = reached).size }.getOrDefault(-1)
                log("result limits relay $round $r ${System.currentTimeMillis() - began}ms events=$n reached=${reached.size}")
            }
            for (i in 1..5) step("limits", "translate-$round-$i", 25_000) {
                when (val t = TranslateClient(http).translate(TranslateServer.MONOSTR, listOf("Guten Morgen $i"), "de", "en")) {
                    is TranslateResult.Ok -> "ok"
                    else -> "not ok ${t.javaClass.simpleName}"
                }
            }
            step("limits", "media-head-$round", 30_000) { get("https://media.monostr.com/") }
            step("limits", "blossom-primal-head-$round", 30_000) { get("https://blossom.primal.net/") }
            step("limits", "watcher-info-$round", 30_000) { "ok ${ready.watchers.info(DEFAULT_WATCHER).toString().isNotEmpty()}" }
        }
        step("limits", "upload", 90_000) {
            val done = ready.media.upload(testPicture().toURI().toString(), ImageTarget.NOTE)
            ready.media.discardNow(done)
            "ok ${done.size} bytes, discarded"
        }
        step("limits", "watcher-register", 60_000) {
            val keys = MoneroKeys.generate()
            val pub = ready.watchers.register(DEFAULT_WATCHER, keys.address(Network.MAINNET), keys.viewSecret)
            ready.watchers.unregister(DEFAULT_WATCHER)
            "ok ${pub.length}"
        }
    }

    /** Check 15: the schedule changes while the run it scheduled is in progress. */
    @Test fun update_during_run() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        MainActivity.inForeground.set(false)
        val work = WorkManager.getInstance(app)
        login()
        session.release()
        mark("flip")
        switch.turnOn()
        BackgroundCheckWorker.cancel(app)
        delay(1_000)
        BackgroundCheckWorker.schedule(app, torOn = true)
        val running = withTimeout(60_000) {
            var info: WorkInfo? = null
            while (info?.state != WorkInfo.State.RUNNING) {
                info = work.getWorkInfosForUniqueWork("background-check").get().firstOrNull()
                delay(100)
            }
            info
        }
        log("result update running id ${running.id} gen ${running.generation} period ${running.periodicityInfo?.repeatIntervalMillis}")
        val torSeen = torPids().size
        BackgroundCheckWorker.schedule(app, torOn = false)
        val log = ArrayList<String>()
        val until = System.currentTimeMillis() + 120_000
        var last = ""
        while (System.currentTimeMillis() < until) {
            val now = work.getWorkInfosForUniqueWork("background-check").get().firstOrNull()
            val line = "${now?.state} gen ${now?.generation} period ${now?.periodicityInfo?.repeatIntervalMillis} state=${tor.state.value} torProc=${torPids().size}"
            if (line != last) { log += "${(System.currentTimeMillis() - until + 120_000)}ms $line"; last = line }
            if (now?.state == WorkInfo.State.ENQUEUED && now.generation > running.generation) break
            delay(200)
        }
        log("result update torSeenAtUpdate $torSeen after $log")
        MainActivity.inForeground.set(true)
    }

    /** Item 9: a redirect to a LAN literal and a name that resolves to the LAN, through the real loader on the real Tor. The listener is on the host, 10.0.2.2:[-e port]. */
    @Test fun lan_redirect() = runBlocking<Unit> {
        withTimeout(10_000) { gate.awaitKnown() }
        val port = args.getString("port") ?: "18443"
        val local = ServerSocket(0, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val heard = AtomicInteger(0)
        thread(isDaemon = true) {
            while (!local.isClosed) {
                val s = runCatching { local.accept() }.getOrNull() ?: break
                heard.incrementAndGet()
                runCatching { s.close() }
            }
        }
        switch.onForeground()
        switch.turnOn()
        awaitTor()
        mark("start")
        val images = PreviewImages.create(app, http).loader
        val enc = { u: String -> java.net.URLEncoder.encode(u, "UTF-8") }
        // which redirector answers through Tor: most refuse exits, and a refusal says nothing about the redirect
        val noFollow = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val candidates = listOf("https://httpbin.org/redirect-to?url=", "https://nghttp2.org/httpbin/redirect-to?url=", "https://httpbun.com/redirect-to?url=", "https://postman-echo.com/redirect-to?url=", "https://httpbingo.org/redirect-to?url=")
        var redirector: String? = null
        for (c in candidates) {
            repeat(3) {
                if (redirector != null) return@repeat
                val out = withContext(Dispatchers.IO) { runCatching { noFollow.newCall(Request.Builder().url(c + enc("http://10.0.2.2:$port/x.png")).build()).execute().use { "${it.code} ${it.header("Location")}" } }.getOrElse { "FAIL ${it.javaClass.simpleName}" } }
                log("result lan redirector $c $out")
                if (out.startsWith("30")) redirector = c
            }
        }
        log("result lan redirector-chosen $redirector")
        assumeTrue("no redirector answers through Tor", redirector != null)
        val targets = listOf(
            "literal-host" to "$redirector${enc("http://10.0.2.2:$port/x.png")}",
            "literal-https-host" to "$redirector${enc("https://10.0.2.2:$port/x.png")}",
            "literal-loopback" to "$redirector${enc("https://127.0.0.1:${local.localPort}/x.png")}",
            "name-host" to "$redirector${enc("https://10.0.2.2.nip.io:$port/x.png")}",
            "name-direct" to "https://10.0.2.2.nip.io:$port/x.png",
        )
        for ((name, url) in targets) {
            step("lan", name, 40_000) { picture(url, images) }
            step("lan", "$name-preview", 40_000) { "preview ${LinkPreviewFetcher(http, "monostr/leak-test").fetch(url) != null}" }
        }
        log("result lan loopback-heard ${heard.get()}")
        assertTrue(heard.get() == 0)
        local.close()
    }
}
