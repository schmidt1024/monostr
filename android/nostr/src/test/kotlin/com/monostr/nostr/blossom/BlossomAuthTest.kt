package com.monostr.nostr.blossom

import com.monostr.tips.event.Event
import com.monostr.tips.event.EventJson
import com.monostr.tips.event.EventSigner
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class BlossomAuthTest {
    private val hash = "ab".repeat(32)
    private val pubkey = "c".repeat(64)

    /** Signs nothing: fills in a fixed id, pubkey and signature, so the test needs no key. */
    private val signer = EventSigner { e ->
        Event(id = "1".repeat(64), pubkey = pubkey, createdAt = e.createdAt, kind = e.kind, tags = e.tags, content = e.content, sig = "2".repeat(128))
    }

    @Test
    fun `an upload authorization names the action, the hash, the expiry and the bare host`() {
        val e = BlossomAuth.unsigned(BlossomAuth.UPLOAD, hash, "https://Media.Monostr.com/", now = 1000)
        assertEquals(24242, e.kind)
        assertEquals("Upload", e.content)
        assertEquals(1000, e.createdAt)
        assertEquals(
            listOf(listOf("t", "upload"), listOf("x", hash), listOf("expiration", "1300"), listOf("server", "media.monostr.com")),
            e.tags,
        )
    }

    @Test
    fun `a delete authorization says so`() {
        val e = BlossomAuth.unsigned(BlossomAuth.DELETE, hash, "https://media.monostr.com", now = 50)
        assertEquals("Delete", e.content)
        assertEquals(listOf("t", "delete"), e.tags[0])
        assertEquals(listOf("expiration", "350"), e.tags[2])
    }

    @Test
    fun `the server tag is the host without scheme, port or path`() {
        assertEquals("127.0.0.1", BlossomAuth.host("http://127.0.0.1:8080"))
        assertEquals("media.example", BlossomAuth.host(" https://MEDIA.example:8443/x "))
        assertNull(BlossomAuth.host("not a url"))
        assertNull(BlossomAuth.host(""))
        // without a host there is no server tag, the rest stays
        val e = BlossomAuth.unsigned(BlossomAuth.UPLOAD, hash, "not a url", now = 1)
        assertEquals(listOf("t", "x", "expiration"), e.tags.map { it[0] })
    }

    @Test
    fun `another action or a malformed hash is refused`() {
        assertThrows(IllegalArgumentException::class.java) { BlossomAuth.unsigned("list", hash, "https://m.example", 1) }
        assertThrows(IllegalArgumentException::class.java) { BlossomAuth.unsigned(BlossomAuth.UPLOAD, hash.uppercase(), "https://m.example", 1) }
        assertThrows(IllegalArgumentException::class.java) { BlossomAuth.unsigned(BlossomAuth.UPLOAD, "abc", "https://m.example", 1) }
    }

    @Test
    fun `the header is the signed event in standard padded base64`() = runTest {
        val header = BlossomAuth.authorization(signer, BlossomAuth.UPLOAD, hash, "https://media.monostr.com", now = 1000)
        assertTrue(header.startsWith("Nostr "))
        val token = header.removePrefix("Nostr ")
        assertEquals(0, token.length % 4) // padded
        assertFalse(token.contains('-') || token.contains('_')) // the standard alphabet, not base64url
        val event = EventJson.decode(String(Base64.getDecoder().decode(token), Charsets.UTF_8))
        assertEquals(24242, event.kind)
        assertEquals(pubkey, event.pubkey)
        assertEquals(hash, event.firstTagValue("x"))
        assertEquals("upload", event.firstTagValue("t"))
        assertEquals("media.monostr.com", event.firstTagValue("server"))
    }

    @Test
    fun `an event signed elsewhere becomes the same header`() = runTest {
        // the delete is signed silently by the caller and only encoded here
        val signed = signer.sign(BlossomAuth.unsigned(BlossomAuth.DELETE, hash, "https://media.monostr.com", now = 1000))
        assertEquals(BlossomAuth.authorization(signer, BlossomAuth.DELETE, hash, "https://media.monostr.com", now = 1000), BlossomAuth.header(signed))
        assertEquals(signed, EventJson.decode(String(Base64.getDecoder().decode(BlossomAuth.header(signed).removePrefix("Nostr ")), Charsets.UTF_8)))
    }
}
