package com.monostr.app.data

import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.nostr.SigningRejectedException
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Final review M5: the background watcher client of backlog 97 can look a stranger's watcher up, never sign for it. */
class WatcherClientsInfoOnlyTest {
    private val requests = CopyOnWriteArrayList<String>()
    private val http = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> requests += chain.request().url.toString(); throw IOException("no network in tests") })
        .build()

    @Test
    fun `register and unregister are refused before anything leaves`() = runTest {
        val clients = WatcherClients.infoOnly(http)
        assertThrows<SigningRejectedException> { clients.unregister("https://w.example") }
        val keys = MoneroKeys.generate()
        assertThrows<SigningRejectedException> { clients.register("https://w.example", keys.address(Network.MAINNET), keys.viewSecret) }
        assertTrue(requests.isEmpty(), "sent: $requests")
    }
}
