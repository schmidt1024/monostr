package com.monostr.nostr.nip55

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Nip55Test {
    @Test
    fun `get_public_key request carries permissions and no package`() {
        val r = Nip55.getPublicKey()
        assertEquals("nostrsigner:", r.uri)
        assertNull(r.packageName)
        assertEquals("get_public_key", r.extras["type"])
        assertTrue(r.extras["permissions"]!!.contains("{\"type\":\"sign_event\",\"kind\":9738}"))
    }

    @Test
    fun `sign_event request puts the event json in the uri`() {
        val r = Nip55.signEvent("""{"kind":1}""", "c".repeat(64), "com.greenart7c3.nostrsigner", "req-1")
        assertEquals("""nostrsigner:{"kind":1}""", r.uri)
        assertEquals("com.greenart7c3.nostrsigner", r.packageName)
        assertEquals(mapOf("type" to "sign_event", "id" to "req-1", "current_user" to "c".repeat(64)), r.extras)
    }

    @Test
    fun `content resolver query shape`() {
        val q = Nip55.signEventQuery("com.greenart7c3.nostrsigner", """{"kind":1}""", "c".repeat(64))
        assertEquals("content://com.greenart7c3.nostrsigner.SIGN_EVENT", q.uri)
        assertEquals(listOf("""{"kind":1}""", "", "c".repeat(64)), q.selectionArgs)
    }

    @Test
    fun `responses are classified`() {
        // A dismissed prompt (Back on the signer: RESULT_CANCELED) is the user's decision, as for NIP-44.
        assertEquals(Nip55.Outcome.Rejected, Nip55.parseSignResponse(false, null))
        assertEquals(Nip55.Outcome.Rejected, Nip55.parseSignResponse(false, Nip55.Response(null, null, null, true)))
        assertEquals(Nip55.Outcome.Failed("empty result"), Nip55.parseSignResponse(true, null))
        assertEquals(Nip55.Outcome.Rejected, Nip55.parseSignResponse(true, Nip55.Response(null, null, null, true)))
        assertEquals(Nip55.Outcome.Signed("{\"id\":\"x\"}"), Nip55.parseSignResponse(true, Nip55.Response("sig", "{\"id\":\"x\"}", null, false)))
        assertEquals(Nip55.Outcome.Failed("no event in result"), Nip55.parseSignResponse(true, Nip55.Response("sig", "", null, false)))
        val npub = rust.nostr.sdk.Keys.parse("0000000000000000000000000000000000000000000000000000000000000001").publicKey().toBech32()
        assertEquals(
            Nip55.Outcome.Pubkey("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", "com.x"),
            Nip55.parsePubkeyResponse(true, Nip55.Response(npub, null, "com.x", false)),
        )
        assertEquals(
            Nip55.Outcome.Pubkey("a".repeat(64), "com.x"),
            Nip55.parsePubkeyResponse(true, Nip55.Response("A".repeat(64), null, "com.x", false)),
        )
        assertEquals(Nip55.Outcome.Failed("no pubkey in result"), Nip55.parsePubkeyResponse(true, Nip55.Response("x", null, null, false)))
    }

    @Test
    fun `content resolver outcome - rejected column wins even without rows`() {
        assertEquals(Nip55.ResolverOutcome.Rejected, Nip55.resolverOutcome(hasRejectedColumn = true, hasRow = false, eventJson = null))
        assertEquals(Nip55.ResolverOutcome.Rejected, Nip55.resolverOutcome(hasRejectedColumn = true, hasRow = true, eventJson = "{}"))
        assertEquals(Nip55.ResolverOutcome.Unavailable, Nip55.resolverOutcome(hasRejectedColumn = false, hasRow = false, eventJson = null))
        assertEquals(Nip55.ResolverOutcome.Unavailable, Nip55.resolverOutcome(hasRejectedColumn = false, hasRow = true, eventJson = ""))
        assertEquals(Nip55.ResolverOutcome.Signed("{\"id\":\"x\"}"), Nip55.resolverOutcome(hasRejectedColumn = false, hasRow = true, eventJson = "{\"id\":\"x\"}"))
    }

    @Test
    fun `nip44 resolver query, intent request and permissions`() {
        val q = Nip55.nip44Query("com.greenart7c3.nostrsigner", Nip55.Nip44Op.ENCRYPT, "plain", "b".repeat(64), "c".repeat(64))
        assertEquals("content://com.greenart7c3.nostrsigner.NIP44_ENCRYPT", q.uri)
        assertEquals(listOf("plain", "b".repeat(64), "c".repeat(64)), q.selectionArgs)
        val d = Nip55.nip44Query("pkg", Nip55.Nip44Op.DECRYPT, "cipher", "b".repeat(64), "c".repeat(64))
        assertEquals("content://pkg.NIP44_DECRYPT", d.uri)
        val r = Nip55.nip44Request(Nip55.Nip44Op.DECRYPT, "cipher", "b".repeat(64), "c".repeat(64), "pkg", "id-1")
        assertEquals("nostrsigner:cipher", r.uri)
        assertEquals("pkg", r.packageName)
        assertEquals(mapOf("type" to "nip44_decrypt", "id" to "id-1", "current_user" to "c".repeat(64), "pubkey" to "b".repeat(64)), r.extras)
        val perms = Nip55.permissionsJson()
        assertTrue(perms.contains("{\"type\":\"nip44_encrypt\"}"))
        assertTrue(perms.contains("{\"type\":\"nip44_decrypt\"}"))
        assertTrue(perms.contains("{\"type\":\"sign_event\",\"kind\":10003}"))
    }

    @Test
    fun `nip44 responses map to result, rejected and failed`() {
        assertEquals(Nip55.Outcome.Result("out"), Nip55.parseNip44Response(true, Nip55.Response("out", null, null, false)))
        assertEquals(Nip55.Outcome.Rejected, Nip55.parseNip44Response(true, Nip55.Response(null, null, null, true)))
        // A dismissed prompt (cancelled result, explicit rejection, or both) is always a rejection,
        // never a missing-capability signal.
        assertEquals(Nip55.Outcome.Rejected, Nip55.parseNip44Response(false, null))
        assertEquals(Nip55.Outcome.Rejected, Nip55.parseNip44Response(false, Nip55.Response(null, null, null, true)))
        assertTrue(Nip55.parseNip44Response(true, Nip55.Response("", null, null, false)) is Nip55.Outcome.Failed)
        assertTrue(Nip55.parseNip44Response(true, null) is Nip55.Outcome.Failed)
    }

    @Test
    fun `default permissions cover seals, auth and dm relay lists`() {
        val kinds = Nip55.defaultPermissions.filter { it.type == "sign_event" }.mapNotNull { it.kind }
        assertTrue(kinds.containsAll(listOf(13, 22242, 10050)))
    }

    @Test
    fun `the blossom authorization is among the default permissions`() {
        assertTrue(Nip55.defaultPermissions.any { it.type == "sign_event" && it.kind == 24242 })
    }

    @Test
    fun `deletion requests are among the default permissions`() {
        assertTrue(Nip55.defaultPermissions.any { it.type == "sign_event" && it.kind == 5 })
    }

    @Test
    fun `the mute list is among the default permissions`() {
        assertTrue(Nip55.defaultPermissions.any { it.type == "sign_event" && it.kind == 10000 })
    }

    @Test
    fun `default permissions include profile metadata`() {
        assertTrue(Nip55.defaultPermissions.any { it.type == "sign_event" && it.kind == 0 })
    }
}
