package com.monostr.monero

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class MoneroKeysTest {
    // Reference from monero-python 1.1.1: Seed("00..01")
    private val spendSecret = "0000000000000000000000000000000000000000000000000000000000000001"
    private val viewSecret = "cea3c5dfea43f31197bd4b7166da59f90af44b4afac2b0732d9fcbe2b7fa0c06"
    private val spendPublic = "1ed49357e217e79dab3c5503822f2bdb561e302e24476ee6ff33242c7551d4e7"
    private val viewPublic = "8944790c0cfa9998c2f196061be89b2b8387f9d397db20ea8e049899cdc947d1"
    private val mainnet = "42nsXK8WbVGTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQdF28r3"

    @Test
    fun `derives view secret and public keys from spend secret`() {
        val k = MoneroKeys.fromSpendSecret(spendSecret.hexToBytes())
        assertEquals(spendSecret, k.spendSecret.toHex())
        assertEquals(viewSecret, k.viewSecret.toHex())
        assertEquals(spendPublic, k.spendPublic.toHex())
        assertEquals(viewPublic, k.viewPublic.toHex())
        assertEquals(mainnet, k.address(Network.MAINNET).encode())
    }

    @Test
    fun `fromSpendSecret reduces non canonical input`() {
        val unreduced = Scalar.fromBigInteger(Scalar.L.add(java.math.BigInteger.ONE))
        val k = MoneroKeys.fromSpendSecret(unreduced)
        assertEquals(Scalar.fromBigInteger(java.math.BigInteger.ONE).toHex(), k.spendSecret.toHex())
    }

    @Test
    fun `generate yields canonical distinct keys`() {
        val a = MoneroKeys.generate(SecureRandom())
        val b = MoneroKeys.generate(SecureRandom())
        assertTrue(Scalar.isCanonical(a.spendSecret))
        assertTrue(Scalar.isCanonical(a.viewSecret))
        assertNotEquals(a.spendSecret.toHex(), b.spendSecret.toHex())
        assertEquals(95, a.address(Network.MAINNET).encode().length)
    }

    @Test
    fun `viewKeyMatches accepts the right key and rejects others`() {
        val address = Address.parse(mainnet)
        assertTrue(MoneroKeys.viewKeyMatches(address, viewSecret.hexToBytes()))
        assertFalse(MoneroKeys.viewKeyMatches(address, spendSecret.hexToBytes()))
        assertFalse(MoneroKeys.viewKeyMatches(address, ByteArray(32)))
        assertFalse(MoneroKeys.viewKeyMatches(address, ByteArray(31)))
        assertFalse(MoneroKeys.viewKeyMatches(address, ByteArray(32) { 0xff.toByte() })) // non-canonical
    }
}
