package com.monostr.app.translate

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceTranslatorTest {
    private class Call(val text: String, val from: String, val to: String)

    @Test
    fun translates_eachPieceInOrder() = runTest {
        val calls = ArrayList<Call>()
        val t = DeviceTranslator(DeviceChannel { text, from, to ->
            calls += Call(text, from, to)
            DeviceReply.Text("DE($text)")
        })
        val span = "<span translate=\"no\">⟦0⟧</span>"
        val r = t.translate(listOf("Hello $span", "a &amp; b"), "en", "de", null)
        assertEquals(TranslateResult.Ok(listOf("DE(Hello $span)", "DE(a &amp; b)")), r)
        assertEquals(listOf("Hello [0]", "a & b"), calls.map { it.text })
        assertTrue(calls.all { it.from == "en" && it.to == "de" })
    }

    @Test
    fun appState_missing_whenNoPackage() {
        val t = DeviceTranslator(DeviceChannel { text, _, _ -> DeviceReply.Text(text) }, { DeviceAppState.MISSING })
        assertEquals(DeviceAppState.MISSING, t.appState())
        assertFalse(t.isInstalled())
    }

    @Test
    fun appState_tooOld_isNotInstalled() {
        val t = DeviceTranslator(DeviceChannel { text, _, _ -> DeviceReply.Text(text) }, { DeviceAppState.TOO_OLD })
        assertEquals(DeviceAppState.TOO_OLD, t.appState())
        assertFalse(t.isInstalled())
    }

    @Test
    fun appState_ready_isInstalled() {
        val t = DeviceTranslator(DeviceChannel { text, _, _ -> DeviceReply.Text(text) }, { DeviceAppState.READY })
        assertEquals(DeviceAppState.READY, t.appState())
        assertTrue(t.isInstalled())
    }

    @Test
    fun packMissing_mapsDetectedButUnavailable() = runTest {
        val t = DeviceTranslator(DeviceChannel { _, _, _ -> DeviceReply.PackMissing })
        assertEquals(TranslateResult.PackMissing, t.translate(listOf("hi"), "en", "de", null))
    }

    @Test
    fun appMissing_whenBindFails() = runTest {
        val t = DeviceTranslator(DeviceChannel { _, _, _ -> DeviceReply.Unbound })
        assertEquals(TranslateResult.AppMissing, t.translate(listOf("hi"), "en", "de", null))
    }

    @Test
    fun error_isFailed() = runTest {
        val t = DeviceTranslator(DeviceChannel { _, _, _ -> DeviceReply.Error("UNEXPECTED") })
        assertEquals(TranslateResult.Failed, t.translate(listOf("hi"), "en", "de", null))
    }

    @Test
    fun timeout_isFailed() = runTest {
        val t = DeviceTranslator(DeviceChannel { _, _, _ -> awaitCancellation() })
        assertEquals(TranslateResult.Failed, t.translate(listOf("hi"), "en", "de", 1_000))
    }

    @Test
    fun defaultTimeout_followsLength() = runTest {
        // no timeout given: ArticleTranslation.timeoutMs(chars) = 10 s + 12 ms per char; 2 chars → 10 024 ms
        val slow = DeviceTranslator(DeviceChannel { text, _, _ -> delay(10_000); DeviceReply.Text(text) })
        assertEquals(TranslateResult.Ok(listOf("hi")), slow.translate(listOf("hi"), "en", "de", null))
        val tooSlow = DeviceTranslator(DeviceChannel { text, _, _ -> delay(10_100); DeviceReply.Text(text) })
        assertEquals(TranslateResult.Failed, tooSlow.translate(listOf("hi"), "en", "de", null))
    }

    @Test
    fun brokenMarker_isFailed() = runTest {
        val span = "<span translate=\"no\">⟦0⟧</span>"
        // the translator dropped the marker
        val dropped = DeviceTranslator(DeviceChannel { _, _, _ -> DeviceReply.Text("Hallo") })
        assertEquals(TranslateResult.Failed, dropped.translate(listOf("Hello $span"), "en", "de", null))
        // a tag the plain bridge cannot carry: never sent
        var sent = false
        val unknown = DeviceTranslator(DeviceChannel { text, _, _ -> sent = true; DeviceReply.Text(text) })
        assertEquals(TranslateResult.Failed, unknown.translate(listOf("<u>x</u>"), "en", "de", null))
        assertFalse(sent)
    }

    @Test
    fun failedPiece_stopsTheRest() = runTest {
        var n = 0
        val t = DeviceTranslator(DeviceChannel { text, _, _ -> n++; if (n == 1) DeviceReply.Error(null) else DeviceReply.Text(text) })
        assertEquals(TranslateResult.Failed, t.translate(listOf("a", "b", "c"), "en", "de", null))
        assertEquals(1, n)
    }

    @Test
    fun blankSource_isFailedWithoutCall() = runTest {
        var sent = false
        val t = DeviceTranslator(DeviceChannel { text, _, _ -> sent = true; DeviceReply.Text(text) })
        assertEquals(TranslateResult.Failed, t.translate(listOf("hi"), "", "de", null))
        assertFalse(sent)
    }

    @Test
    fun callsAreSequential() = runTest {
        var inside = 0
        var maxInside = 0
        val t = DeviceTranslator(DeviceChannel { text, _, _ ->
            inside++
            maxInside = maxOf(maxInside, inside)
            delay(100)
            inside--
            DeviceReply.Text(text)
        })
        val a = async { t.translate(listOf("a1", "a2"), "en", "de", null) }
        val b = async { t.translate(listOf("b1", "b2"), "en", "de", null) }
        assertEquals(TranslateResult.Ok(listOf("a1", "a2")), a.await())
        assertEquals(TranslateResult.Ok(listOf("b1", "b2")), b.await())
        assertEquals(1, maxInside)
    }

    @Test
    fun serviceDeath_failsCallThenRebinds() = runTest {
        var n = 0
        val t = DeviceTranslator(DeviceChannel { text, _, _ -> if (n++ == 0) DeviceReply.Error("binder died") else DeviceReply.Text(text) })
        assertEquals(TranslateResult.Failed, t.translate(listOf("hi"), "en", "de", null))
        assertEquals(TranslateResult.Ok(listOf("hi")), t.translate(listOf("hi"), "en", "de", null))
    }

    @Test
    fun release_reachesTheChannel() = runTest {
        var released = 0
        val t = DeviceTranslator(object : DeviceChannel {
            override suspend fun call(text: String, from: String, to: String) = DeviceReply.Text(text)
            override fun release() { released++ }
        })
        assertEquals(TranslateResult.Ok(listOf("hi")), t.translate(listOf("hi"), "en", "de", null))
        t.release()
        assertEquals(1, released)
        // a later call still works (the real channel binds again)
        assertEquals(TranslateResult.Ok(listOf("hi")), t.translate(listOf("hi"), "en", "de", null))
    }

    @Test
    fun release_defaultIsNoOp() {
        DeviceTranslator(DeviceChannel { text, _, _ -> DeviceReply.Text(text) }).release()
    }

    @Test
    fun idleTimer_firesSixtySecondsAfterLastTouch() = runTest {
        var fired = 0
        val idle = IdleTimer(backgroundScope, 60_000) { fired++ }
        idle.touch()
        advanceTimeBy(59_000); runCurrent()
        assertEquals(0, fired)
        idle.touch() // a new call restarts the wait
        advanceTimeBy(59_000); runCurrent()
        assertEquals(0, fired)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(1, fired)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, fired) // once per idle period
    }

    @Test
    fun idleTimer_cancelStopsIt() = runTest {
        var fired = 0
        val idle = IdleTimer(backgroundScope, 60_000) { fired++ }
        idle.touch()
        idle.cancel() // a call is running
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, fired)
    }

    @Test
    fun backends_labels() {
        assertEquals("Offline Translator", DeviceBackend.label)
        assertEquals("translate.monostr.com", TranslateServer.MONOSTR.label)
        val b: TranslateBackend = TranslateServer("https://lt.example.org")
        assertEquals("lt.example.org", b.label)
    }

    @Test
    fun router_devicePath_neverTouchesNetwork() = runTest {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { requests++; throw java.io.IOException("no network on the device path") }).build()
        val router = TranslateRouter(TranslateClient(http), DeviceTranslator(DeviceChannel { text, _, _ -> DeviceReply.Text("D:$text") }))
        assertEquals(TranslateResult.Ok(listOf("D:hi")), router.translate(DeviceBackend, listOf("hi"), "en", "de"))
        assertEquals(TranslateResult.AppMissing, TranslateRouter(TranslateClient(http), DeviceTranslator(DeviceChannel { _, _, _ -> DeviceReply.Unbound }))
            .translate(DeviceBackend, listOf("hi"), "en", "de"))
        assertEquals(0, requests)
    }

    @Test
    fun router_serverPath_usesClient() = runTest {
        var sent = false
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { throw java.io.IOException("offline") }).build()
        val router = TranslateRouter(TranslateClient(http), DeviceTranslator(DeviceChannel { text, _, _ -> sent = true; DeviceReply.Text(text) }))
        assertEquals(TranslateResult.Failed, router.translate(TranslateServer("https://lt.example.org"), listOf("hi"), "en", "de"))
        assertFalse(sent)
    }
}
