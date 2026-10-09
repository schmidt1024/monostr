package com.monostr.monero

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AddressTest {
    private val spendPub = "1ed49357e217e79dab3c5503822f2bdb561e302e24476ee6ff33242c7551d4e7".hexToBytes()
    private val viewPub = "8944790c0cfa9998c2f196061be89b2b8387f9d397db20ea8e049899cdc947d1".hexToBytes()
    private val mainnet = "42nsXK8WbVGTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQdF28r3"
    private val stagenet = "52zucA3UF6NTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQf9eTdc"
    private val integratedMainnet =
        "4CVYY7x1CknTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnbxc5xHroTU8U3vykdq"

    @Test
    fun `encode mainnet and stagenet`() {
        assertEquals(mainnet, Address(Network.MAINNET, spendPub, viewPub).encode())
        assertEquals(stagenet, Address(Network.STAGENET, spendPub, viewPub).encode())
    }

    @Test
    fun `parse mainnet`() {
        val a = Address.parse(mainnet)
        assertEquals(Network.MAINNET, a.network)
        assertEquals(spendPub.toHex(), a.spendPublic.toHex())
        assertEquals(viewPub.toHex(), a.viewPublic.toHex())
    }

    @Test
    fun `parse stagenet`() {
        assertEquals(Network.STAGENET, Address.parse(stagenet).network)
    }

    @Test
    fun `parse roundtrip equals`() {
        assertEquals(Address(Network.MAINNET, spendPub, viewPub), Address.parse(mainnet))
    }

    @Test
    fun `parse rejects bad checksum`() {
        val corrupted = mainnet.dropLast(1) + (if (mainnet.last() == '3') '4' else '3')
        assertThrows(AddressException.BadChecksum::class.java) { Address.parse(corrupted) }
    }

    @Test
    fun `parse rejects integrated address with explanation`() {
        assertThrows(AddressException.IntegratedNotAllowed::class.java) { Address.parse(integratedMainnet) }
    }

    @Test
    fun `parse rejects subaddress`() {
        // Build a syntactically valid mainnet subaddress (prefix 42) from the same keys
        val body = byteArrayOf(42) + spendPub + viewPub
        val sub = MoneroBase58.encode(body + Keccak.hash256(body).copyOfRange(0, 4))
        assertThrows(AddressException.SubaddressNotAllowed::class.java) { Address.parse(sub) }
    }

    @Test
    fun `parse rejects unknown prefix and garbage`() {
        val body = byteArrayOf(99) + spendPub + viewPub
        val unknown = MoneroBase58.encode(body + Keccak.hash256(body).copyOfRange(0, 4))
        assertThrows(AddressException.UnknownPrefix::class.java) { Address.parse(unknown) }
        assertThrows(AddressException.BadFormat::class.java) { Address.parse("4abc") }
        assertThrows(AddressException::class.java) { Address.parse("") }
    }

    @Test
    fun `parse trims surrounding whitespace`() {
        assertEquals(Address.parse(mainnet), Address.parse("  $mainnet\n"))
    }

    @Test
    fun `parse reports invalid base58 characters as BadFormat with cause`() {
        val withExcludedLetter = mainnet.substring(0, 10) + "O" + mainnet.substring(11)
        val e = assertThrows(AddressException.BadFormat::class.java) { Address.parse(withExcludedLetter) }
        assertTrue(e.cause is IllegalArgumentException)
    }

    @Test
    fun `integrated address for reference payment id`() {
        val pid = "0123456789abcdef".hexToBytes()
        assertEquals(integratedMainnet, Address(Network.MAINNET, spendPub, viewPub).integrated(pid))
    }

    @Test
    fun `integrated address on stagenet matches reference and has prefix byte 25`() {
        val pid = "0123456789abcdef".hexToBytes()
        val s = Address(Network.STAGENET, spendPub, viewPub).integrated(pid)
        assertEquals(
            "5ChacxrxrMtTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnbxc5xHroTU8TyFqL9U",
            s,
        )
        assertEquals(106, s.length)
        assertEquals(25, MoneroBase58.decode(s)[0].toInt() and 0xff)
    }

    @Test
    fun `integrated rejects wrong payment id length`() {
        assertThrows(IllegalArgumentException::class.java) {
            Address(Network.MAINNET, spendPub, viewPub).integrated(ByteArray(7))
        }
    }
}
