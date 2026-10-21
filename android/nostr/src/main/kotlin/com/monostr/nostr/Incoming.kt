package com.monostr.nostr

import com.monostr.tips.event.Event as JsonEvent
import rust.nostr.sdk.Event

/**
 * Spec wave 2 B2: one event as it came in from a relay, read across the FFI once ([json], decoded in Kotlin).
 * [event] stays for rust-only work (open a gift wrap, adopt a list); [relay] is normalised (no trailing slash);
 * [subscriptionId] is the REQ it answered (a live subscription or a fetch).
 */
class Incoming(val event: Event, val json: JsonEvent, val relay: String, val subscriptionId: String)
