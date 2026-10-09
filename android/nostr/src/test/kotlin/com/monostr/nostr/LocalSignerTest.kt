package com.monostr.nostr

import com.monostr.tips.event.EventId
import com.monostr.tips.event.UnsignedEvent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys

class LocalSignerTest {
    private val sk1 = "0000000000000000000000000000000000000000000000000000000000000001"

    @Test
    fun `derives the well-known pubkey for secret key 1`() {
        assertEquals("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", LocalSigner(sk1).pubkey)
        assertEquals(sk1, LocalSigner(sk1).secretHex)
    }

    @Test
    fun `signed event has nip01 id, valid signature and untouched fields`() = runTest {
        val signer = LocalSigner(sk1)
        val unsigned = UnsignedEvent(
            kind = 9738, content = "Tip für dich & mehr", createdAt = 1700000000,
            tags = listOf(listOf("e", "b".repeat(64)), listOf("p", "c".repeat(64)), listOf("amount", "5000000000"),
                listOf("pid", "0123456789abcdef"), listOf("type", "like"), listOf("expiration", "1700086400")),
        )
        val event = signer.sign(unsigned)
        assertEquals(signer.pubkey, event.pubkey)
        assertEquals(EventId.compute(signer.pubkey, unsigned), event.id)
        assertEquals(unsigned.tags, event.tags)
        assertEquals(unsigned.content, event.content)
        assertEquals(1700000000, event.createdAt)
        assertEquals(128, event.sig.length)
        assertTrue(RustConvert.toRust(event).verify())
    }

    @Test
    fun `accepts nsec and rejects garbage`() {
        val fromHex = LocalSigner(sk1)
        val nsec = rust.nostr.sdk.Keys.parse(sk1).secretKey().toBech32()
        assertEquals(fromHex.pubkey, LocalSigner(nsec).pubkey)
        assertNotNull(LocalSigner.parseOrNull(" $nsec "))
        assertNull(LocalSigner.parseOrNull("nsec1garbage"))
        assertNull(LocalSigner.parseOrNull(""))
        assertNull(LocalSigner.parseOrNull("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179"))
    }

    @Test
    fun `nip44 round trip between two local signers and to oneself`() = runTest {
        val a = LocalSigner(Keys.generate().secretKey().toHex())
        val b = LocalSigner(Keys.generate().secretKey().toHex())
        val payload = a.nip44Encrypt(b.pubkey, "hello #monero")
        assertNotEquals("hello #monero", payload)
        assertEquals("hello #monero", b.nip44Decrypt(a.pubkey, payload))
        val own = a.nip44Encrypt(a.pubkey, "[[\"e\",\"abc\"]]")
        assertEquals("[[\"e\",\"abc\"]]", a.nip44Decrypt(a.pubkey, own))
        val wrong = runCatching { b.nip44Decrypt(b.pubkey, payload) }
        assertTrue(wrong.isFailure)
        assertTrue(wrong.exceptionOrNull() is rust.nostr.sdk.NostrSdkException, "got ${wrong.exceptionOrNull()}")
    }

    @Test
    fun `silent nip44 decrypt round-trips like the interactive one`() = runTest {
        val a = LocalSigner(sk1)
        val own = a.nip44Encrypt(a.pubkey, "[[\"e\",\"abc\"]]")
        assertEquals("[[\"e\",\"abc\"]]", a.nip44DecryptSilent(a.pubkey, own))
    }
}
