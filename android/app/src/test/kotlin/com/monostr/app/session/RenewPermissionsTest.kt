package com.monostr.app.session

import com.monostr.nostr.nip55.Nip55
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RenewPermissionsTest {
    private val me = "a".repeat(64)

    @Test
    fun `same key updates, other key is refused, rejection cancels`() {
        assertEquals(RenewResult.Updated, NostrSession.renewOutcome(me, true, Nip55.Response(result = me, event = null, packageName = "com.greenart7c3.nostrsigner", rejected = false)))
        assertEquals(RenewResult.WrongKey, NostrSession.renewOutcome(me, true, Nip55.Response(result = "b".repeat(64), event = null, packageName = "x", rejected = false)))
        assertEquals(RenewResult.Cancelled, NostrSession.renewOutcome(me, false, null))
        assertEquals(RenewResult.Cancelled, NostrSession.renewOutcome(me, true, Nip55.Response(null, null, "x", rejected = true)))
    }
}
