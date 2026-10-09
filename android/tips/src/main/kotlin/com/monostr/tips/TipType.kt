package com.monostr.tips

/** Value of the `type` tag on intents and receipts (protocol 3.2/3.3). */
enum class TipType(val tag: String) {
    /** Tip coupled to a like; kept for clients that tie the tip to a reaction. */
    LIKE("like"),
    /** Tip coupled to a repost ("boost"). */
    BOOST("boost"),
    /** Stand-alone tip, no reaction published alongside; what Monostr sends. */
    TIP("tip");

    companion object {
        fun fromTag(tag: String): TipType? = entries.firstOrNull { it.tag == tag }
    }
}
