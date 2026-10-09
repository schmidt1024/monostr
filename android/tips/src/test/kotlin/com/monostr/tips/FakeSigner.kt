package com.monostr.tips

import com.monostr.tips.event.Event
import com.monostr.tips.event.EventId
import com.monostr.tips.event.EventSigner
import com.monostr.tips.event.UnsignedEvent

/** Test signer: computes the real NIP-01 id, uses a dummy signature. */
class FakeSigner(val pubkey: String) : EventSigner {
    override suspend fun sign(event: UnsignedEvent): Event = Event(
        id = EventId.compute(pubkey, event),
        pubkey = pubkey,
        createdAt = event.createdAt,
        kind = event.kind,
        tags = event.tags,
        content = event.content,
        sig = "0".repeat(128),
    )
}
