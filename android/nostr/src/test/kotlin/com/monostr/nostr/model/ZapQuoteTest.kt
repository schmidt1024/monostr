package com.monostr.nostr.model

import com.monostr.tips.event.EventJson

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag

/** Owner 2026-11-12: quoted zap receipts (kind 9735). */
class ZapQuoteTest {
    private val juraj = Keys.generate()
    private val zapper = Keys.generate()
    private val bob = "b".repeat(64)
    private val zapped = "e".repeat(64)
    /** 210u = 21'000 sats (the real invoice from Bolt11Test). */
    private val invoice = "lnbc210u1p4wwjrypp5kgphh5lvzpptnsxay3rclz803akay574hxz64qv2cpe5qyx66p9qhp54k6ularuecp7avwy2fy0sywvse0gjrxf34ws4tcuyalpukl87pzqcqzysxqrrssrzjqv3dpepm8kfdxrk3sl6wzqdf49s9c0h9ljtjrek6c08r6aejlwcnur0dwyqqvusqqqqqqqlgqqqq86qqjqsp5vxyxnphtncx2scesrfrhmygqz2s4etxsjvzyz8nqwhh0qz75eg8q9qxpqysgqf6uk2y0rqql8re7pyrp9d8cthrlzhdnftx4dahu6jeusvvn0emj88q4280k6f8pmpkpczhvnwx4tm5p6098yuvzxky928ktmyfw7mmgp33s5vq"

    private fun request(content: String = "", amountMsat: Long? = 5_000_000, kind: UShort = 9734u) =
        EventBuilder(Kind(kind), content)
            .tags(listOfNotNull(Tag.parse(listOf("p", bob)), amountMsat?.let { Tag.parse(listOf("amount", it.toString())) }))
            .signWithKeys(juraj).asJson()

    private fun receipt(description: String?, bolt11: String? = invoice, e: String? = zapped, p: String? = bob) =
        EventBuilder(Kind(9735u), "").tags(
            listOfNotNull(
                p?.let { Tag.parse(listOf("p", it)) }, e?.let { Tag.parse(listOf("e", it)) },
                bolt11?.let { Tag.parse(listOf("bolt11", it)) }, description?.let { Tag.parse(listOf("description", it)) },
            ),
        ).signWithKeys(zapper)

    @Test
    fun `a receipt whose invoice hash names another request shows the amount but no sender`() {
        val ev = receipt(request("  great post  ", amountMsat = 21_000_000))
        val z = ZapReceipts.parse(ev)!!
        assertEquals(21_000L, z.amountSat)
        // the fixture invoice's description hash belongs to another request: no sender, no comment (the real receipt below binds)
        assertNull(z.sender)
        assertEquals("", z.comment)
        assertEquals(bob, z.recipient)
        assertEquals(zapped, z.zappedNote)
        assertEquals(ev.id().toHex(), z.id)
        assertEquals(ev.createdAt().asSecs().toLong(), z.createdAt)
    }

    @Test
    fun `without a bolt11 amount the request's amount in millisats counts`() {
        assertEquals(5_000L, ZapReceipts.parse(receipt(request(), bolt11 = "lnbc1garbage"))!!.amountSat)
        assertEquals(5_000L, ZapReceipts.parse(receipt(request(), bolt11 = null))!!.amountSat)
    }

    @Test
    fun `an empty comment stays empty and no e tag means no zapped note`() {
        val z = ZapReceipts.parse(receipt(request(""), e = null))!!
        assertEquals("", z.comment)
        assertNull(z.zappedNote)
    }

    @Test
    fun `a bad description drops the sender but keeps the bolt11 amount`() {
        listOf("not json", "{}", request(kind = 1u), null).forEach { d ->
            val z = ZapReceipts.parse(receipt(d))!!
            assertNull(z.sender, "$d")
            assertEquals("", z.comment)
            assertEquals(21_000L, z.amountSat)
        }
        // a tampered request (content changed after signing) fails the signature check
        val tampered = request("hi").replace("\"content\":\"hi\"", "\"content\":\"bye\"")
        assertEquals(true, tampered.contains("bye"))
        assertNull(ZapReceipts.parse(receipt(tampered))!!.sender)
    }

    @Test
    fun `malformed receipts are not zaps`() {
        assertNull(ZapReceipts.parse(receipt(request(amountMsat = null), bolt11 = null))) // no amount anywhere
        assertNull(ZapReceipts.parse(receipt(request(), p = null))) // no recipient
        assertNull(ZapReceipts.parse(receipt(request(), p = "npub-not-hex")))
        assertNull(ZapReceipts.parse(EventBuilder.textNote("hello").signWithKeys(zapper)))
    }

    @Test
    fun `a signed request for someone else lends no sender or comment`() {
        val other = Keys.generate().publicKey().toHex()
        val z = ZapReceipts.parse(receipt(request("copied"), p = other))!!
        assertNull(z.sender)
        assertEquals("", z.comment)
        // an amount that is not the invoice's: the request belongs to another zap
        assertNull(ZapReceipts.parse(receipt(request("other zap", amountMsat = 1_000)))!!.sender)
    }

    /** Juraj's real receipt (relay.primal.net, 2026-11-10): its invoice's description hash binds the request. */
    private val realReceipt = "{\"kind\":9735,\"id\":\"1a1494038b92a6de83747dabc039dfab4c6d6bd5af3f3d169bcd364610ebceec\",\"pubkey\":\"b866ce76be5b826695980248322b8df4c381608ffa5a5b47c4f3abe0d8f767a5\",\"created_at\":1794295732,\"tags\":[[\"p\",\"b866ce76be5b826695980248322b8df4c381608ffa5a5b47c4f3abe0d8f767a5\"],[\"P\",\"dab6c6065c439b9bafb0b0f1ff5a0c68273bce5c1959a4158ad6a70851f507b6\"],[\"description\",\"{\\\"id\\\":\\\"cb8d4c7355188adac9fb8a07bf94a678d8b6107f09f6603fa956487a47b9c2b6\\\",\\\"pubkey\\\":\\\"dab6c6065c439b9bafb0b0f1ff5a0c68273bce5c1959a4158ad6a70851f507b6\\\",\\\"created_at\\\":1794295729,\\\"kind\\\":9734,\\\"content\\\":\\\"\\\",\\\"tags\\\":[[\\\"relays\\\",\\\"wss://relay.fountain.fm\\\",\\\"wss://relay.primal.net\\\"],[\\\"amount\\\",\\\"13370000\\\"],[\\\"p\\\",\\\"b866ce76be5b826695980248322b8df4c381608ffa5a5b47c4f3abe0d8f767a5\\\"],[\\\"k\\\",\\\"podcast:item:guid\\\"],[\\\"i\\\",\\\"podcast:item:guid:a961bb32-5ebc-4d1e-ba68-30242271fa1b\\\",\\\"https://fountain.fm/episode/1Q17yntZitfbLF4Fm2vx\\\"],[\\\"k\\\",\\\"podcast:guid\\\"],[\\\"i\\\",\\\"podcast:guid:254ceab2-c561-5479-bf9f-6980f7015689\\\",\\\"https://fountain.fm/show/DGn4FIswSZciwkqcow5d\\\"]],\\\"sig\\\":\\\"903d27b156d2a9b6b73e071459b1847b83438d18c075d745d6fc46aec892ca691b9b1694f469930bb5170e143c16fc1632728e72405392e4ac3ecb1f6ea02b8c\\\"}\"],[\"bolt11\",\"lnbc133700n1p4093anpp5ksr2eqkmdm60jr7vw4xfm5rh283ytq6a5camduy0q3w7j3sx8lksdql2p85gs6p2d297kjp2p05jnjkfay5x3gcqzysxqzpusp5kw93n0etan4l2lsuhqv3r72h5mkyfzqv70740u0h248h7yevu6vq9qxpqysgqtvgkz2vam2auc9smqlyymsxjwknhfcl9r0gckju232qd6ju6tzwxq740cydxdwqdgle80zfe5rxadr4cyev42h03l6gy47n6zf46hrsqxw2qn4\"],[\"preimage\",\"abe8766cd6bc8a1be0809ab45614c90d3d1f486ca37c5d97d63379f3fa8fb05c\"],[\"i\",\"podcast:item:guid:a961bb32-5ebc-4d1e-ba68-30242271fa1b\",\"https://fountain.fm/episode/1Q17yntZitfbLF4Fm2vx\"],[\"i\",\"podcast:guid:254ceab2-c561-5479-bf9f-6980f7015689\",\"https://fountain.fm/show/DGn4FIswSZciwkqcow5d\"],[\"k\",\"podcast:item:guid\"],[\"k\",\"podcast:guid\"]],\"content\":\"\",\"sig\":\"d2c993c2f619eead4b6d313a103d24780954e95fc9c81709291c3e5047887bfb0edeed048e8323c486817cdfc66e167e626a4f54964688cc1bf9d82569d3df0d\"}"

    @Test
    fun `a real receipt binds its request through the invoice's description hash`() {
        val z = ZapReceipts.parse(EventJson.decode(realReceipt))!!
        assertEquals(13_370L, z.amountSat)
        assertEquals("dab6c6065c439b9bafb0b0f1ff5a0c68273bce5c1959a4158ad6a70851f507b6", z.sender)
    }
}
