package com.monostr.tips.watcher

import com.monostr.tips.FakeSigner
import com.monostr.tips.Kinds
import com.monostr.tips.event.EventId
import com.monostr.tips.event.UnsignedEvent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Nip98Test {
    private val signer = FakeSigner("a".repeat(64))
    private val url = "https://watcher.monostr.com/v1/accounts"
    private val body = """{"address":"42nsXK8WbVGTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQdF28r3","view_key":"cea3c5dfea43f31197bd4b7166da59f90af44b4afac2b0732d9fcbe2b7fa0c06"}"""

    @Test
    fun `header carries a kind 27235 event with url, method and payload hash`() = runTest {
        val header = Nip98.authorization(signer, url, "post", body.toByteArray(), now = 1700000000)
        assertTrue(header.startsWith("Nostr "), header)

        val event = Nip98.decode(header)!!
        assertEquals(Kinds.HTTP_AUTH, event.kind)
        assertEquals(signer.pubkey, event.pubkey)
        assertEquals(1700000000, event.createdAt)
        assertEquals("", event.content)
        assertEquals(url, event.firstTagValue("u"))
        assertEquals("POST", event.firstTagValue("method"))
        assertEquals("e5e1b6d819fd87f06222a6e782914bac359d6eb46da3d7dc06495a349bf428b4", event.firstTagValue("payload"))
        assertEquals(EventId.compute(signer.pubkey, UnsignedEvent(event.kind, event.content, event.tags, event.createdAt)), event.id)
    }

    @Test
    fun `no payload tag without body`() = runTest {
        val event = Nip98.decode(Nip98.authorization(signer, url, "DELETE", null, now = 1))!!
        assertEquals("DELETE", event.firstTagValue("method"))
        assertNull(event.firstTagValue("payload"))
        assertEquals(2, event.tags.size)
    }

    @Test
    fun `decode rejects other schemes and garbage`() {
        assertNull(Nip98.decode("Bearer abc"))
        assertNull(Nip98.decode("Nostr !!!"))
        assertNull(Nip98.decode("Nostr " + java.util.Base64.getEncoder().encodeToString("{}".toByteArray())))
    }
}
