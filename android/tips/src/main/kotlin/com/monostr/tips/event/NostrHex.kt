package com.monostr.tips.event

/** True for a 64-character lowercase hex string (Nostr pubkeys and event ids). */
fun isHex64(s: String): Boolean = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }
