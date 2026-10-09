package com.monostr.nostr.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import rust.nostr.sdk.Metadata

class NoteMapperProfileTest {
    @Test
    fun `banner comes from kind 0 and is absent without metadata`() {
        val p = NoteMapper.profile("a".repeat(64), Metadata.fromJson("""{"name":"a","banner":"https://x.example/b.jpg"}"""))
        assertEquals("https://x.example/b.jpg", p.banner)
        assertNull(NoteMapper.profile("a".repeat(64), null).banner)
    }
}
