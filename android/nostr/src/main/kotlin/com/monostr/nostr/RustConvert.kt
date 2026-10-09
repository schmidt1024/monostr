package com.monostr.nostr

import com.monostr.tips.event.Event
import com.monostr.tips.event.EventJson
import com.monostr.tips.event.UnsignedEvent
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Kind
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp

/** Conversions between the `:tips` event model and rust-nostr objects. */
internal object RustConvert {
    /** An [EventBuilder] carrying kind, content, tags and created_at of a `:tips` unsigned event. */
    fun toBuilder(event: UnsignedEvent): EventBuilder =
        EventBuilder(Kind(event.kind.toUShort()), event.content)
            .tags(event.tags.map { Tag.parse(it) })
            .customCreatedAt(Timestamp.fromSecs(event.createdAt.toULong()))

    fun toRustUnsigned(event: UnsignedEvent, pubkeyHex: String): rust.nostr.sdk.UnsignedEvent =
        toBuilder(event).build(PublicKey.parse(pubkeyHex))

    fun toTipsUnsigned(unsigned: rust.nostr.sdk.UnsignedEvent): UnsignedEvent = UnsignedEvent(
        kind = unsigned.kind().asU16().toInt(),
        content = unsigned.content(),
        tags = unsigned.tags().toVec().map { it.asVec() },
        createdAt = unsigned.createdAt().asSecs().toLong(),
    )

    fun toTips(event: rust.nostr.sdk.Event): Event = EventJson.decode(event.asJson())

    fun toRust(event: Event): rust.nostr.sdk.Event = rust.nostr.sdk.Event.fromJson(EventJson.encode(event))
}
