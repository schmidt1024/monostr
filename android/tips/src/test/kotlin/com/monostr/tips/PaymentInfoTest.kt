package com.monostr.tips

import com.monostr.monero.Address
import com.monostr.monero.Network
import com.monostr.tips.event.Event
import com.monostr.tips.event.UnsignedEvent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PaymentInfoTest {
    private val mainnet = "42nsXK8WbVGTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQdF28r3"
    private val stagenet = "52zucA3UF6NTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQf9eTdc"
    private val watcherPubkey = "c".repeat(64)
    private val watcherUrl = "https://watcher.monostr.com"
    private val signer = FakeSigner("a".repeat(64))

    private fun event(kind: Int = Kinds.PAYMENT_INFO, tags: List<List<String>>) = Event(
        id = "1".repeat(64), pubkey = "a".repeat(64), createdAt = 1, kind = kind, tags = tags, content = "", sig = "0".repeat(128),
    )

    private val validTags = listOf(
        listOf("address", mainnet),
        listOf("watcher", watcherUrl, watcherPubkey),
        listOf("network", "mainnet"),
    )

    @Test
    fun `build produces the protocol tags`() {
        val unsigned = PaymentInfo.build(Address.parse(mainnet), Watcher.Http(watcherUrl, watcherPubkey), createdAt = 1700000000)
        assertEquals(UnsignedEvent(Kinds.PAYMENT_INFO, "", validTags, 1700000000), unsigned)
    }

    @Test
    fun `build rejects testnet, bad pubkey and bad url`() {
        val stage = Address.parse(stagenet)
        val testnet = Address(Network.TESTNET, stage.spendPublic, stage.viewPublic)
        assertThrows(IllegalArgumentException::class.java) { PaymentInfo.build(testnet, Watcher.Http(watcherUrl, watcherPubkey), 1) }
        assertThrows(IllegalArgumentException::class.java) { PaymentInfo.build(stage, Watcher.Http(watcherUrl, "C".repeat(64)), 1) }
        assertThrows(IllegalArgumentException::class.java) { PaymentInfo.build(stage, Watcher.Http("watcher.monostr.com", watcherPubkey), 1) }
    }

    @Test
    fun `build then parse roundtrip on stagenet`() = runTest {
        val address = Address.parse(stagenet)
        val signed = signer.sign(PaymentInfo.build(address, Watcher.Http("$watcherUrl/", watcherPubkey), 1))
        assertEquals(PaymentInfo.Enabled(address, Watcher.Http(watcherUrl, watcherPubkey)), PaymentInfo.parse(signed))
    }

    @Test
    fun `parse valid mainnet`() {
        val info = PaymentInfo.parse(event(tags = validTags)) as PaymentInfo.Enabled
        assertEquals(Address.parse(mainnet), info.address)
        assertEquals(Network.MAINNET, info.network)
        assertEquals(Watcher.Http(watcherUrl, watcherPubkey), info.watcher)
        assertEquals(watcherPubkey, info.watcherPubkey)
    }

    @Test
    fun `parse accepts http url for local watchers`() {
        val tags = validTags.map { if (it[0] == "watcher") listOf("watcher", "http://10.0.2.2:8080", watcherPubkey) else it }
        assertEquals("http://10.0.2.2:8080", (PaymentInfo.parse(event(tags = tags)) as PaymentInfo.Enabled).watcher.let { (it as Watcher.Http).url })
    }

    @Test
    fun `parse rejects foreign or malformed watcher urls as Disabled`() {
        fun disabledWithWatcher(url: String) {
            val tags = validTags.map { if (it[0] == "watcher") listOf("watcher", url, watcherPubkey) else it }
            assertEquals(PaymentInfo.Disabled, PaymentInfo.parse(event(tags = tags)), url)
        }
        disabledWithWatcher("https://exa mple.com")
        disabledWithWatcher("https://w.example/?x=1")
        disabledWithWatcher("https://w.example/#f")
        disabledWithWatcher("https://u:p@w.example")
        disabledWithWatcher("ftp://w.example")
    }

    @Test
    fun `parse canonicalizes watcher url casing and trailing slash`() {
        val tags = validTags.map { if (it[0] == "watcher") listOf("watcher", "https://Watcher.Example/", watcherPubkey) else it }
        assertEquals("https://watcher.example", (PaymentInfo.parse(event(tags = tags)) as PaymentInfo.Enabled).watcher.let { (it as Watcher.Http).url })
    }

    @Test
    fun `disabled event parses as Disabled`() = runTest {
        val signed = signer.sign(PaymentInfo.buildDisabled(1))
        assertEquals(PaymentInfo.Disabled, PaymentInfo.parse(signed))
    }

    @Test
    fun `malformed info is Disabled`() {
        fun disabled(tags: List<List<String>>, kind: Int = Kinds.PAYMENT_INFO) =
            assertEquals(PaymentInfo.Disabled, PaymentInfo.parse(event(kind, tags)), tags.toString())

        disabled(validTags.filter { it[0] != "address" })
        disabled(validTags.filter { it[0] != "watcher" })
        disabled(validTags.filter { it[0] != "network" })
        disabled(validTags.map { if (it[0] == "network") listOf("network", "testnet") else it })
        disabled(validTags.map { if (it[0] == "network") listOf("network", "stagenet") else it })      // address is mainnet
        disabled(validTags.map { if (it[0] == "address") listOf("address", stagenet) else it })       // network says mainnet
        disabled(validTags.map { if (it[0] == "address") listOf("address", "4abc") else it })
        disabled(validTags.map { if (it[0] == "watcher") listOf("watcher", watcherUrl) else it })       // pubkey missing
        disabled(validTags.map { if (it[0] == "watcher") listOf("watcher", watcherUrl, "zz") else it })
        disabled(validTags.map { if (it[0] == "watcher") listOf("watcher", "ftp://x", watcherPubkey) else it })
        disabled(validTags, kind = 1)
    }

    @Test
    fun `tip type and network tag helpers`() {
        assertEquals(TipType.LIKE, TipType.fromTag("like"))
        assertEquals(TipType.BOOST, TipType.fromTag("boost"))
        assertEquals(TipType.TIP, TipType.fromTag("tip"))
        assertNull(TipType.fromTag("zap"))
        assertEquals("mainnet", NetworkTag.toTag(Network.MAINNET))
        assertEquals("stagenet", NetworkTag.toTag(Network.STAGENET))
        assertThrows(IllegalArgumentException::class.java) { NetworkTag.toTag(Network.TESTNET) }
        assertEquals(Network.STAGENET, NetworkTag.parse("stagenet"))
        assertNull(NetworkTag.parse("testnet"))
        assertNull(NetworkTag.parse("Mainnet"))
    }

    private val relay = "wss://relay.example.com"

    @Test
    fun `build and parse the pubkey form`() = runTest {
        val unsigned = PaymentInfo.build(Address.parse(mainnet), Watcher.Personal(watcherPubkey, listOf(relay, "wss://nos.lol")), 100)
        assertEquals(listOf("watcher", watcherPubkey, relay, "wss://nos.lol"), unsigned.tags[1])
        val parsed = PaymentInfo.parse(signer.sign(unsigned))
        assertEquals(PaymentInfo.Enabled(Address.parse(mainnet), Watcher.Personal(watcherPubkey, listOf(relay, "wss://nos.lol"))), parsed)
        assertEquals(watcherPubkey, (parsed as PaymentInfo.Enabled).watcherPubkey)
    }

    @Test
    fun `build rejects a personal watcher without relays, with five, or with ws`() {
        val a = Address.parse(mainnet)
        assertThrows(IllegalArgumentException::class.java) { PaymentInfo.build(a, Watcher.Personal(watcherPubkey, emptyList()), 1) }
        assertThrows(IllegalArgumentException::class.java) { PaymentInfo.build(a, Watcher.Personal(watcherPubkey, List(5) { "wss://r$it.example" }), 1) }
        assertThrows(IllegalArgumentException::class.java) { PaymentInfo.build(a, Watcher.Personal(watcherPubkey, listOf("ws://r.example")), 1) }
        assertThrows(IllegalArgumentException::class.java) { PaymentInfo.build(a, Watcher.Personal(watcherPubkey, listOf("wss://192.168.1.5")), 1) }
    }

    @Test
    fun `pubkey form with bad relays is Disabled`() = runTest {
        val bad = listOf(
            listOf("watcher", watcherPubkey),
            listOf("watcher", watcherPubkey) + List(5) { "wss://r$it.example" },
            listOf("watcher", watcherPubkey, "ws://r.example"),
            listOf("watcher", watcherPubkey, "https://r.example"),
            listOf("watcher", watcherPubkey, "wss://user@r.example"),
            listOf("watcher", watcherPubkey, "wss://r.example?x=1"),
            listOf("watcher", watcherPubkey, "wss://r.example#f"),
            listOf("watcher", watcherPubkey, "wss://"),
            listOf("watcher", watcherPubkey, "wss://10.0.0.2"),
            listOf("watcher", watcherPubkey, "wss://umbrel.local"),
            listOf("watcher", watcherPubkey, relay, "wss://[::1]"),
            listOf("watcher", watcherPubkey.uppercase(), relay),
        )
        for (w in bad) {
            val tags = listOf(listOf("address", mainnet), w, listOf("network", "mainnet"))
            assertEquals(PaymentInfo.Disabled, PaymentInfo.parse(event(Kinds.PAYMENT_INFO, tags)), "tag $w")
        }
    }

    @Test
    fun `old url form still parses next to the pubkey form`() = runTest {
        assertEquals(PaymentInfo.Enabled(Address.parse(mainnet), Watcher.Http(watcherUrl, watcherPubkey)), PaymentInfo.parse(event(Kinds.PAYMENT_INFO, validTags)))
    }

    @Test
    fun `relay urls are canonicalized`() {
        assertEquals("wss://relay.example.com", Watcher.normalizeRelay(" WSS://Relay.Example.com/ "))
        assertEquals("wss://stagenet.watcher.monostr.com/relay", Watcher.normalizeRelay("wss://stagenet.watcher.monostr.com/relay"))
        assertNull(Watcher.normalizeRelay("ws://relay.example.com"))
    }

    @Test
    fun `hidden tag round trip`() = runTest {
        val address = Address.parse(mainnet)
        val unsigned = PaymentInfo.build(address, Watcher.Http(watcherUrl, watcherPubkey), 1, amountsHidden = true)
        assertTrue(unsigned.tags.contains(listOf("amounts", "hidden")))
        val parsed = PaymentInfo.parse(signer.sign(unsigned)) as PaymentInfo.Enabled
        assertTrue(parsed.amountsHidden)
    }

    @Test
    fun `unknown amounts value is public`() {
        val parsed = PaymentInfo.parse(event(tags = validTags + listOf(listOf("amounts", "shown")))) as PaymentInfo.Enabled
        assertFalse(parsed.amountsHidden)
    }

    @Test
    fun `missing tag is public`() {
        assertFalse((PaymentInfo.parse(event(tags = validTags)) as PaymentInfo.Enabled).amountsHidden)
        assertTrue(PaymentInfo.build(Address.parse(mainnet), Watcher.Http(watcherUrl, watcherPubkey), 1).tags.none { it[0] == "amounts" })
    }
}
