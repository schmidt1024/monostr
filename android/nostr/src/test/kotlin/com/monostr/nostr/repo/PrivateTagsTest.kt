package com.monostr.nostr.repo

import com.monostr.nostr.LocalSigner
import com.monostr.nostr.Signer
import com.monostr.nostr.SigningRejectedException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys

class PrivateTagsTest {
    private val me = Keys.generate()
    private val signer = LocalSigner(me.secretKey().toHex())

    @Test
    fun `tags round-trip through NIP-44 to self`() = runTest {
        val p = PrivateTags(signer)
        val tags = listOf(listOf("title", "Friends"), listOf("p", "a".repeat(64)))
        val content = p.encrypt(tags)
        assertFalse(content.contains("Friends"))
        assertEquals(Decrypted.Ok(tags), p.decrypt(content, interactive = false))
        assertEquals(tags, PrivateTags.parse(signer.nip44Decrypt(signer.pubkey, content)))
    }

    @Test
    fun `blank content is an empty private part`() = runTest {
        assertEquals(Decrypted.Ok(emptyList<List<String>>()), PrivateTags(signer).decrypt("", interactive = false))
    }

    @Test
    fun `no silent answer, a refusal and garbage are told apart`() = runTest {
        val content = PrivateTags(signer).encrypt(listOf(listOf("p", "a".repeat(64))))
        val silentNull = object : Signer by signer {
            override suspend fun nip44DecryptSilent(peerPubkey: String, payload: String): String? = null
        }
        val refusing = object : Signer by signer {
            override suspend fun nip44Decrypt(peerPubkey: String, payload: String): String = throw SigningRejectedException()
        }
        assertEquals(Decrypted.NoSilentAnswer, PrivateTags(silentNull).decrypt(content, interactive = false))
        assertEquals(Decrypted.Rejected, PrivateTags(refusing).decrypt(content, interactive = true))
        assertEquals(Decrypted.Unreadable, PrivateTags(signer).decrypt("not a payload", interactive = true))
        val notTags = signer.nip44Encrypt(signer.pubkey, """{"v":1}""")
        assertEquals(Decrypted.Unreadable, PrivateTags(signer).decrypt(notTags, interactive = true))
        assertEquals(Decrypted.Ok("""{"v":1}"""), PrivateTags(signer).decryptText(notTags, interactive = true))
    }
}
