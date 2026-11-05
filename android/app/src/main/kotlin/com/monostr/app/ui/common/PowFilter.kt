package com.monostr.app.ui.common

/** GitHub #4: notes below [minBits] of proof of work are hidden, except from [exempt] (the follows and the user); 0 = off. */
data class PowFilter(val minBits: Int, val exempt: Set<String>) {
    val on: Boolean get() = minBits > 0

    fun hides(author: String?, bits: Int): Boolean = on && bits < minBits && author !in exempt

    companion object {
        val OFF = PowFilter(0, emptySet())
    }
}
