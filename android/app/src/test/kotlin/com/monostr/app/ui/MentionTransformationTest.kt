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

    // editToken: the field's input rule; the keyboard sees "hi @Alina there" and the field maps its edits to the raw text

    @Test
    fun `a one-character delete at the token end still removes the whole token`() {
        assertEquals(MentionQuery.Edit("hi  there", 3), MentionQuery.editToken(old, old.removeRange(tokenEnd - 1, tokenEnd), names))
        assertEquals(MentionQuery.Edit("hi  there", 3), MentionQuery.editToken(old, old.removeRange(3, 4), names))
    }

    @Test
    fun `composing text that shortens the shown name removes the whole token`() {
        assertEquals(MentionQuery.Edit("hi  there", 3), MentionQuery.editToken(old, "hi @Alin there", names))
        assertEquals(MentionQuery.Edit("hi  there", 3), MentionQuery.editToken(old, "hi Alin there", names))
        assertEquals(MentionQuery.Edit("hi  there", 3), MentionQuery.editToken(old, "hi @ there", names))
    }

    @Test
    fun `the shown name committed again keeps the token`() {
        assertEquals(MentionQuery.Edit(old, tokenEnd), MentionQuery.editToken(old, "hi @Alina there", names))
        assertEquals(MentionQuery.Edit(old, tokenEnd), MentionQuery.editToken(old, "hi Alina there", names))
    }

    @Test
    fun `the name committed with a space or punctuation keeps the token and adds what follows`() {
        assertEquals(MentionQuery.Edit("hi $token  there", tokenEnd + 1), MentionQuery.editToken(old, "hi Alina  there", names))
        assertEquals(MentionQuery.Edit("hi $token, there", tokenEnd + 1), MentionQuery.editToken(old, "hi Alina, there", names))
        assertEquals(MentionQuery.Edit("hi $token! there", tokenEnd + 1), MentionQuery.editToken(old, "hi @Alina! there", names))
        assertEquals(MentionQuery.Edit("hi $token's there", tokenEnd + 2), MentionQuery.editToken(old, "hi Alina's there", names))
    }

    @Test
    fun `an autocorrected near-copy of the name removes the whole token and keeps nothing of it`() {
        assertEquals(MentionQuery.Edit("hi  there", 3), MentionQuery.editToken(old, "hi Aline there", names))
        assertEquals(MentionQuery.Edit("hi  there", 3), MentionQuery.editToken(old, "hi Alinas there", names))
        assertEquals(MentionQuery.Edit("hi  there", 3), MentionQuery.editToken(old, "hi @Alba there", names))
    }

    @Test
    fun `an unrelated word in the token's place stays, the token is gone`() {
        assertNull(MentionQuery.editToken(old, "hi hello there", names))
        assertNull(MentionQuery.editToken(old, "hi @hello there", names))
        assertNull(MentionQuery.editToken(old, "hi nostrich there", names))
    }

    @Test
    fun `typing right before or after the token is normal typing`() {
        assertNull(MentionQuery.editToken(old, "hi ${token}x there", names))
        assertNull(MentionQuery.editToken(old, "hi $token, there", names))
        assertNull(MentionQuery.editToken(old, "hi n$token there", names)) // ambiguous with an "n" inside the token
        assertNull(MentionQuery.editToken(old, "hi @$token there", names))
        assertNull(MentionQuery.editToken(old, old + "!", names))
    }

    @Test
    fun `an insertion inside the token takes the token and keeps the typed text`() {
        val inside = old.substring(0, 10) + "#" + old.substring(10)
        assertEquals(MentionQuery.Edit("hi # there", 4), MentionQuery.editToken(old, inside, names))
    }

    @Test
    fun `of two tokens only the edited one goes`() {
        val bob = Keys.parse("0000000000000000000000000000000000000000000000000000000000000003").publicKey().toHex()
        val tokenB = "nostr:" + Npub.encode(bob)
        val two = "hi $token $tokenB there"
        val both = mapOf(alina to "Alina", bob to "Bob")
        assertEquals(MentionQuery.Edit("hi $token  there", tokenEnd + 1), MentionQuery.editToken(two, "hi $token Bo there", both))
        assertEquals(MentionQuery.Edit("hi  $tokenB there", 3), MentionQuery.editToken(two, "hi Ali $tokenB there", both))
        assertEquals(MentionQuery.Edit(two, tokenEnd + 1 + tokenB.length), MentionQuery.editToken(two, "hi $token @Bob there", both))
    }

    @Test
    fun `without names, plain edits and whole deletions it does nothing`() {
        assertNull(MentionQuery.editToken(old, "hi @Alin there", emptyMap()))
        assertNull(MentionQuery.editToken(old, "hi  there", names))
        assertNull(MentionQuery.editToken(old, old.replace("there", "where"), names))
        assertNull(MentionQuery.editToken(old, old, names))
    }
}
