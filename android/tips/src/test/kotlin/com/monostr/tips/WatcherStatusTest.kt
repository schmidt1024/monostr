package com.monostr.tips

import com.monostr.monero.MoneroKeys
import com.monostr.monero.Network
import com.monostr.tips.event.UnsignedEvent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatcherStatusTest {
    private val wallet = MoneroKeys.generate()
    private val address = wallet.address(Network.MAINNET)
    private val recipient = "b".repeat(64)
    private val signer = FakeSigner("a".repeat(64))

    private suspend fun status(tags: List<List<String>>, kind: Int = Kinds.WATCHER_STATUS, at: Long = 1_000) =
        signer.sign(UnsignedEvent(kind, "", tags, at))

    private fun tags(network: String = "mainnet", height: String? = "3512345") = listOfNotNull(
        listOf("p", recipient), listOf("address", address.encode()), listOf("network", network),
        height?.let { listOf("height", it) }, listOf("version", "0.4.0"),
    )

    @Test
    fun `parses a status`() = runTest {
        val e = status(tags())
        assertEquals(WatcherStatus(e.pubkey, recipient, address, Network.MAINNET, 3512345, "0.4.0", 1_000), WatcherStatus.parse(e))
    }

    @Test
    fun `missing or bad height is unknown`() = runTest {
        assertEquals(0L, WatcherStatus.parse(status(tags(height = null)))!!.height)
        assertEquals(0L, WatcherStatus.parse(status(tags(height = "-5")))!!.height)
        assertEquals(0L, WatcherStatus.parse(status(tags(height = "x")))!!.height)
    }

    @Test
    fun `unknown or mismatching network is kept for the caller`() = runTest {
        assertNull(WatcherStatus.parse(status(tags(network = "testnet")))!!.network)
        assertEquals(Network.STAGENET, WatcherStatus.parse(status(tags(network = "stagenet")))!!.network)
    }

    @Test
    fun `malformed status is null`() = runTest {
        assertNull(WatcherStatus.parse(status(tags(), kind = Kinds.PAYMENT_INFO)))
        assertNull(WatcherStatus.parse(status(tags().filterNot { it[0] == "p" })))
        assertNull(WatcherStatus.parse(status(tags().map { if (it[0] == "p") listOf("p", "B".repeat(64)) else it })))
        assertNull(WatcherStatus.parse(status(tags().filterNot { it[0] == "address" })))
        assertNull(WatcherStatus.parse(status(tags().map { if (it[0] == "address") listOf("address", "4abc") else it })))
    }

    @Test
    fun `online for 15 minutes`() = runTest {
        val s = WatcherStatus.parse(status(tags(), at = 1_000))!!
        assertTrue(s.isOnline(1_900))
        assertFalse(s.isOnline(1_901))
    }

    @Test
    fun `protocol tag is read`() = runTest {
        assertEquals("0.5", WatcherStatus.parse(status(tags() + listOf(listOf("protocol", "0.5"))))!!.protocol)
        assertNull(WatcherStatus.parse(status(tags()))!!.protocol)
    }
}
