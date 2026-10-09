package com.monostr.app.ui

import com.monostr.app.ui.compose.MentionQuery
import com.monostr.nostr.Npub
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys

/** Spec 8: an edit that cuts into a mention shown as @name removes the whole token. */
class MentionTransformationTest {
    private val alina = Keys.parse("0000000000000000000000000000000000000000000000000000000000000002").publicKey().toHex()
    private val token = "nostr:" + Npub.encode(alina)
    private val names = mapOf(alina to "Alina")
    private val old = "hi $token there"
    private val tokenEnd = 3 + token.length

    @Test
    fun `backspace at the end of a shown mention removes the whole token`() {
        val new = old.removeRange(tokenEnd - 1, tokenEnd)
        assertEquals(MentionQuery.Edit("hi  there", 3), MentionQuery.cutToken(old, new, names))
    }

    @Test
    fun `delete at the start of a shown mention removes the whole token`() {
        val new = old.removeRange(3, 4)
        assertEquals(MentionQuery.Edit("hi  there", 3), MentionQuery.cutToken(old, new, names))
    }

    @Test
    fun `a selection that reaches into the token removes the text and the token`() {
        val new = old.removeRange(1, 6)
        assertEquals(MentionQuery.Edit("h there", 1), MentionQuery.cutToken(old, new, names))
    }

    @Test
    fun `plain deletions, whole tokens, raw tokens and typing are left alone`() {
        assertNull(MentionQuery.cutToken(old, old.dropLast(1), names))
        assertNull(MentionQuery.cutToken(old, "hi  there", names))
        assertNull(MentionQuery.cutToken(old, old.removeRange(tokenEnd - 1, tokenEnd), emptyMap()))
        assertNull(MentionQuery.cutToken(old, old + "x", names))
    }
}
