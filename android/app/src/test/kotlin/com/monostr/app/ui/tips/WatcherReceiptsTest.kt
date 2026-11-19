package com.monostr.app.ui.tips

import com.monostr.app.ui.FakeWatcherGateway
import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.tips.PaymentInfo
import com.monostr.tips.RelayHosts
import com.monostr.tips.Watcher
import com.monostr.tips.watcher.WatcherInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WatcherReceiptsTest {
    private var clock = 0L

    private fun info(watcher: Watcher) = PaymentInfo.Enabled(MoneroKeys.generate().address(Network.MAINNET), watcher)

    @Test
    fun `personal relays are public wss, distinct, at most four`() = runTest {
        val w = WatcherReceipts(FakeWatcherGateway(), { true }) { clock }
        val tag = listOf("ws://a.example", "wss://10.0.0.5", "wss://x.onion", "wss://r1.example", "wss://r1.example/", "wss://r2.example", "wss://r3.example", "wss://r4.example", "wss://r5.example")
        // the parser already normalises "wss://r1.example/" to "wss://r1.example"; the helper filters again
        assertEquals(listOf("wss://r1.example", "wss://r2.example", "wss://r3.example", "wss://r4.example"),
            w.relaysOf(info(Watcher.Personal("d".repeat(64), tag.map { Watcher.normalizeRelay(it) ?: it }))))
    }

    @Test
    fun `an http watcher is asked once and cached for 24 hours`() = runTest {
        val gw = FakeWatcherGateway(info = WatcherInfo("d".repeat(64), listOf("wss://relay.monostr.com", "ws://bad.example"), "mainnet", 3_000_000, "t"))
        val w = WatcherReceipts(gw, { true }) { clock }
        val http = info(Watcher.Http("https://w.example", "d".repeat(64)))
        assertEquals(listOf("wss://relay.monostr.com"), w.relaysOf(http))
        clock += WatcherReceipts.HTTP_TTL_MS - 1; w.relaysOf(http)
        assertEquals(1, gw.infoCalls)
        clock += 2; w.relaysOf(http)
        assertEquals(2, gw.infoCalls)
    }

    @Test
    fun `a watcher that does not answer is asked again only after ten minutes`() = runTest {
        val gw = FakeWatcherGateway().apply { infoGate = CompletableDeferred() } // never answers
        val w = WatcherReceipts(gw, { true }) { clock }
        val http = info(Watcher.Http("https://w.example", "d".repeat(64)))
        assertEquals(emptyList<String>(), w.relaysOf(http)) // after INFO_TIMEOUT_MS of virtual time
        clock += WatcherReceipts.FAILED_TTL_MS - 1; assertEquals(emptyList<String>(), w.relaysOf(http))
        assertEquals(1, gw.infoCalls)
        gw.infoGate = null; clock += 2
        assertEquals(listOf("wss://watcher-relay.example"), w.relaysOf(http))
        assertEquals(2, gw.infoCalls)
    }

    @Test
    fun `a LAN watcher url is never asked`() = runTest {
        val gw = FakeWatcherGateway()
        val w = WatcherReceipts(gw, { url -> RelayHosts.isPublic(url) }) { clock }
        assertEquals(emptyList<String>(), w.relaysOf(info(Watcher.Http("https://192.168.1.10", "d".repeat(64)))))
        assertEquals(0, gw.infoCalls)
    }

    @Test
    fun `by default only a public https base url is asked`() = runTest {
        val gw = FakeWatcherGateway()
        val w = WatcherReceipts(gw) { clock }
        for (url in listOf("http://w.example", "https://w.example/v1", "https://w.example/?x=1", "https://w.example#f", "https://u@w.example", "https://127.0.0.1", "https://w.local", "https://x.onion")) {
            assertEquals(emptyList<String>(), w.relaysOf(info(Watcher.Http(url, "d".repeat(64)))), url)
        }
        assertEquals(0, gw.infoCalls)
        assertEquals(listOf("wss://watcher-relay.example"), w.relaysOf(info(Watcher.Http("https://w.example:8443/", "d".repeat(64)))))
        assertEquals(1, gw.infoCalls)
    }

    @Test
    fun `a note is due again only after five minutes`() {
        val w = WatcherReceipts(FakeWatcherGateway(), { true }) { clock }
        w.markAsked(listOf("n1"))
        assertEquals(listOf("n2"), w.due(listOf("n1", "n2")))
        clock += WatcherReceipts.ASK_EVERY_MS - 1; assertEquals(listOf("n2"), w.due(listOf("n1", "n2")))
        clock += 1; assertEquals(listOf("n1", "n2"), w.due(listOf("n1", "n2")))
    }

    @Test
    fun `the asked map is pruned of expired entries once it is full`() {
        val w = WatcherReceipts(FakeWatcherGateway(), { true }) { clock }
        w.markAsked((0..WatcherReceipts.MAX_ASKED).map { "old$it" })
        clock += WatcherReceipts.ASK_EVERY_MS
        w.markAsked(listOf("new"))
        assertEquals(1, w.askedSize)
    }
}
