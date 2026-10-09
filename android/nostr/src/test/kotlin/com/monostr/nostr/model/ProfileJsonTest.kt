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
}
