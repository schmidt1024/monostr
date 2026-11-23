package com.monostr.app.ui.common

import com.monostr.app.ui.note
import com.monostr.nostr.model.AddressRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThreadKeyTest {
    @Test
    fun threadKey_replyUsesRoot() = assertEquals("r".repeat(64), note('b', replyTo = "c".repeat(64), root = "R".repeat(64)).threadKey())

    @Test
    fun threadKey_rootUsesId() = assertEquals("a".repeat(64), note('a').threadKey())

    @Test
    fun threadKey_articleUsesOwnId() {
        val n = note('d', root = "r".repeat(64)).copy(articleParent = AddressRef(30023, "e".repeat(64), "x"))
        assertEquals("d".repeat(64), n.threadKey())
    }
}
