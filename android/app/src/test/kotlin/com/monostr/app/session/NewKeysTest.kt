package com.monostr.app.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import com.monostr.app.data.SessionInfo
import com.monostr.app.data.SignerType
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys

class NewKeysTest {
    @Test
    fun `a generated key has matching nsec, npub and hex`() {
        val k = NewKeys.generate()
        assertTrue(k.nsec.startsWith("nsec1"))
        assertTrue(k.npub.startsWith("npub1"))
        val parsed = Keys.parse(k.nsec)
        assertEquals(k.secretHex, parsed.secretKey().toHex())
        assertEquals(k.pubkey, parsed.publicKey().toHex())
        assertEquals(k.npub, parsed.publicKey().toBech32())
    }

    @Test
    fun `two keys differ`() = assertNotEquals(NewKeys.generate().secretHex, NewKeys.generate().secretHex)

    @Test
    fun `fromSecretHex gives the stored key back as nsec`() {
        val k = NewKeys.generate()
        assertEquals(k.nsec, NewKeys.fromSecretHex(k.secretHex).nsec)
    }

    @Test
    fun `show key gives the key of a key login only`() {
        val k = NewKeys.generate()
        var read = false
        assertEquals(k.nsec, NewKeys.ofSession(SessionInfo(k.pubkey, SignerType.LOCAL, null)) { k.secretHex }?.nsec)
        assertNull(NewKeys.ofSession(SessionInfo(k.pubkey, SignerType.AMBER, "com.greenart7c3.nostrsigner")) { read = true; k.secretHex })
        assertTrue(!read) // Amber: the stored secret is not even read
        assertNull(NewKeys.ofSession(null) { k.secretHex })
        assertNull(NewKeys.ofSession(SessionInfo(k.pubkey, SignerType.LOCAL, null)) { null })
        assertNull(NewKeys.ofSession(SessionInfo(k.pubkey, SignerType.LOCAL, null)) { "not a key" })
    }
}
