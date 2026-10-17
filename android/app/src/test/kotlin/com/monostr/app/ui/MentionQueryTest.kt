package com.monostr.app.ui

import com.monostr.app.ui.compose.MentionQuery
import com.monostr.nostr.Npub
import com.monostr.nostr.model.Profile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Keys

class MentionQueryTest {
    private val alina = Keys.parse("0000000000000000000000000000000000000000000000000000000000000002").publicKey().toHex()

    @Test
    fun `an at-sign inside a word never triggers and the replacement uses the cursor`() {
        assertNull(MentionQuery.active("mail me@al", 10))
        assertNull(MentionQuery.active("hi @", 4))
        assertNull(MentionQuery.active("hi @al", 2))
        val text = "hi @al and more"
        val active = MentionQuery.active(text, 6)!!
        assertEquals(MentionQuery.Active(3, "al"), active)
        val token = "nostr:" + Npub.encode(alina)
        val edit = MentionQuery.replace(text, active, 6, alina)
        assertEquals("hi $token and more", edit.text)
        assertEquals(3 + token.length + 1, edit.cursor)
        val end = MentionQuery.replace("@al", MentionQuery.active("@al", 3)!!, 3, alina)
        assertEquals("$token ", end.text)
        assertEquals(end.text.length, end.cursor)
    }

    @Test
    fun `ranking puts prefix hits first, then follows, at most eight`() {
        val followed = Profile("f".repeat(64), "bob", "Albert", null, null, null)
        val prefix = Profile("1".repeat(64), "alfred", null, null, null, null)
        val inner = Profile("2".repeat(64), "cal", null, null, null, null)
        assertEquals(
            listOf(followed, prefix, inner).map { it.pubkey },
            MentionQuery.rank("al", listOf(followed), listOf(inner, prefix, followed)).map { it.pubkey },
        )
        val many = (0 until 12).map { Profile(it.toString().padStart(64, '3'), "al$it", null, null, null, null) }
        assertEquals(8, MentionQuery.rank("al", emptyList(), many).size)
        assertTrue(MentionQuery.rank("zz", listOf(followed), listOf(prefix)).isEmpty())
    }

    @Test
    fun `known npubs show as at-names, unknown ones stay raw, several in one text`() {
        val bob = Keys.parse("0000000000000000000000000000000000000000000000000000000000000003").publicKey().toHex()
        val a = "nostr:" + Npub.encode(alina)
        val b = "nostr:" + Npub.encode(bob)
        val raw = "hi $a and $b there"
        val names = mapOf(alina to "Alina", bob to "Bob")
        assertEquals(listOf((3 until 3 + a.length) to "@Alina", (3 + a.length + 5 until 3 + a.length + 5 + b.length) to "@Bob"), MentionQuery.shownTokens(raw, names))
        assertEquals("hi @Alina and @Bob there", MentionQuery.shown(raw, names))
        assertEquals("hi @Alina and $b there", MentionQuery.shown(raw, mapOf(alina to "Alina")))
        assertEquals(raw, MentionQuery.shown(raw, emptyMap()))
        assertTrue(MentionQuery.shownTokens(raw, emptyMap()).isEmpty())
    }

    @Test
    fun `a pick right before a line break leaves the cursor after the new space, before the break`() {
        val text = "@al\nnext"
        val token = "nostr:" + Npub.encode(alina)
        val edit = MentionQuery.replace(text, MentionQuery.active(text, 3)!!, 3, alina)
        assertEquals("$token \nnext", edit.text)
        assertEquals(token.length + 1, edit.cursor)
        assertEquals('\n', edit.text[edit.cursor])
    }
}
