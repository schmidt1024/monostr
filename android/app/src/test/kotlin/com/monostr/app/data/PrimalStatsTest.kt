package com.monostr.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import com.monostr.nostr.repo.FollowCounts

/** Primal's cache answers `["REQ", id, {"cache": ["user_profile", {"pubkey": …}]}]` with a kind 10000105 event whose content carries the stats. */
class PrimalStatsTest {
    private val pk = "0d7ceca9e000e711e263bc14a2216ab74968a9e903eba214a714300adede5a20"

    /** A cache server that answers every REQ with [frames] (the request id substituted for `ID`), then EOSE. */
    private fun server(vararg frames: String, eose: Boolean = true): Pair<MockWebServer, CopyOnWriteArrayList<String>> {
        val received = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                val id = Regex("\"REQ\",\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return
                frames.forEach { webSocket.send(it.replace("ID", id)) }
                if (eose) webSocket.send("[\"EOSE\",\"$id\"]")
            }
            // answer the client's close so the server's queue can drain
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        return server to received
    }

    private fun stats(server: MockWebServer, timeout: Duration = Duration.ofSeconds(3)) =
        PrimalStats(OkHttpClient(), url = server.url("/v1").toString().replaceFirst("http", "ws"), timeout = timeout)

    @Test
    fun `the stats event gives followers and following, and the request names the pubkey`() = runBlocking {
        val content = """{\"pubkey\":\"$pk\",\"follows_count\":315,\"followers_count\":2282,\"note_count\":900}"""
        val (server, received) = server("""["EVENT","ID",{"kind":10000105,"content":"$content","tags":[],"created_at":1,"id":"x","pubkey":"y","sig":"z"}]""")
        server.use {
            assertEquals(FollowCounts(following = 315, followers = 2282), stats(it).stats(pk))
            assertTrue(received.single().contains("\"cache\":[\"user_profile\",{\"pubkey\":\"$pk\"}]"), received.single())
        }
    }

    @Test
    fun `an EOSE without the stats event, a broken content and a silent server give null`() = runBlocking {
        server().use { (s, _) -> assertNull(stats(s).stats(pk)) }
        server("""["EVENT","ID",{"kind":10000105,"content":"not json","tags":[]}]""").use { (s, _) -> assertNull(stats(s).stats(pk)) }
        server(eose = false).use { (s, _) -> assertNull(stats(s, Duration.ofMillis(300)).stats(pk)) }
    }

    @Test
    fun `a server that is not there gives null`() = runBlocking {
        assertNull(PrimalStats(OkHttpClient(), url = "ws://127.0.0.1:9", timeout = Duration.ofSeconds(1)).stats(pk))
    }

    private inline fun <T> Pair<MockWebServer, T>.use(block: (Pair<MockWebServer, T>) -> Unit) { first.use { block(this) } }

    @Test
    fun `switched off in the settings, Primal is never contacted`() = runBlocking {
        val (server, received) = server()
        server.use {
            val off = PrimalStats(OkHttpClient(), url = it.url("/v1").toString().replaceFirst("http", "ws"), enabled = { false })
            assertNull(off.stats(pk))
            assertEquals(0, it.requestCount)
            assertTrue(received.isEmpty())
        }
    }
}
