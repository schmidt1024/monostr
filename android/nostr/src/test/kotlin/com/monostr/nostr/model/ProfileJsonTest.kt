package com.monostr.nostr.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ProfileJsonTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `merge replaces only the edited keys and keeps foreign ones including lightning`() {
        val raw = """{"name":"old","about":"bio","lud16":"me@wallet.example","website":"https://w.example","custom":{"x":1}}"""
        val out = obj(ProfileJson.merge(raw, mapOf("name" to " new ", "about" to "", "picture" to "https://x.example/p.jpg", "lud16" to "ignored")))
        assertEquals("new", out["name"]!!.jsonPrimitive.content)
        assertFalse("about" in out)
        assertEquals("https://x.example/p.jpg", out["picture"]!!.jsonPrimitive.content)
        assertEquals("me@wallet.example", out["lud16"]!!.jsonPrimitive.content)
        assertEquals("https://w.example", out["website"]!!.jsonPrimitive.content)
        assertEquals(obj("""{"x":1}"""), out["custom"])
    }

    @Test
    fun `invalid or non-object json counts as empty, non-string values read as empty`() {
        val empty = ProfileJson.EDITABLE.associateWith { "" }
        assertEquals(empty, ProfileJson.fields("not json"))
        assertEquals(empty, ProfileJson.fields("[1,2]"))
        assertEquals(empty, ProfileJson.fields(null))
        assertEquals("""{"name":"x"}""", ProfileJson.merge("not json", mapOf("name" to "x")))
        val mixed = ProfileJson.fields("""{"name":"alice","nip05":5}""")
        assertEquals("alice", mixed["name"])
        assertEquals("", mixed["nip05"])
    }

    @Test
    fun `a kind 0 is read into a profile in Kotlin, string values only`() {
        val pk = "a".repeat(64)
        val p = ProfileJson.profile(pk, """{"name":"al","display_name":"Al","picture":"https://x/p.png","about":"hi","nip05":"al@x","banner":"https://x/b.png","website":"https://x","lud16":"al@w"}""")!!
        assertEquals(Profile(pk, "al", "Al", "https://x/p.png", "hi", "al@x", banner = "https://x/b.png", website = "https://x"), p)
        assertEquals("Al", ProfileJson.profile(pk, """{"displayName":"Al"}""")!!.displayName)
        assertEquals(null, ProfileJson.profile(pk, """{"name":42}""")!!.name)
        assertEquals(null, ProfileJson.profile(pk, "not json"))
        assertEquals(null, ProfileJson.profile(pk, "[1,2]"))
    }

    /** Bug bounty 2026-11-26: the address is a claim; it is kept to one line of visible characters. */
    @Test
    fun `a nip05 claim loses invisible characters and is capped`() {
        val pk = "a".repeat(64)
        assertEquals("monostr@monostr.com", ProfileJson.profile(pk, """{"nip05":"mono\u202Estr@\u200Bmonostr.com\n "}""")!!.nip05)
        assertEquals(ProfileJson.MAX_NIP05, ProfileJson.profile(pk, """{"nip05":"${"x".repeat(5000)}"}""")!!.nip05!!.length)
        assertEquals(null, ProfileJson.profile(pk, """{"nip05":" \u200E"}""")!!.nip05)
    }

    @Test
    fun `a name is one line without direction marks or fillers, joiners stay`() {
        val pk = "a".repeat(64)
        fun name(json: String) = ProfileJson.profile(pk, """{"name":"$json"}""")!!.name
        assertEquals("Monostr", name("\u202EMono\u200Bstr\u200E\u061C\uFEFF\u2060"))
        assertEquals("two lines", name("two\nlines"))
        assertEquals("\uD83D\uDC68\u200D\uD83D\uDC69", name("\uD83D\uDC68\u200D\uD83D\uDC69"), "an emoji joined with U+200D")
        assertEquals("\u0645\u06CC\u200C\u062E", name("\u0645\u06CC\u200C\u062E"), "Persian needs U+200C")
        assertEquals(ProfileJson.MAX_NAME, name("x".repeat(500))!!.length)
        // the cut does not leave half an emoji
        assertEquals(ProfileJson.MAX_NAME - 1, name("x".repeat(ProfileJson.MAX_NAME - 1) + "\uD83D\uDE00")!!.length)
    }
}
