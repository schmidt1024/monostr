package com.monostr.tips

/** Watcher protocol versions (`major.minor[.patch]`); hiding amounts needs 0.5 or newer. */
object ProtocolVersion {
    fun supportsHiddenAmounts(protocol: String?): Boolean {
        val parts = protocol?.split('.') ?: return false
        if (parts.size < 2) return false
        val major = parts[0].toIntOrNull() ?: return false
        val minor = parts[1].toIntOrNull() ?: return false
        return major > 0 || minor >= 5
    }
}
