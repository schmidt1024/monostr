package com.monostr.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Bug bounty 2026-11-26: an id or pubkey from a stranger's tag with a slash in it found no destination and closed the app. */
class RoutesHexTest {
    private val id = "ab".repeat(32)
    private val none = "0".repeat(64)

    @Test
    fun `ids and pubkeys pass, anything else names nothing`() {
        assertEquals("thread/$id", Routes.thread(id))
        assertEquals("profile/$id", Routes.profile(id))
        assertEquals("dm/$id", Routes.dm(id))
        assertEquals("follows/$id?followers=true", Routes.follows(id, true))
        assertEquals("thread/$none", Routes.thread("a/b"))
        assertEquals("profile/$none", Routes.profile("x/y?z=1"))
        assertEquals("dm/$none", Routes.dm("jack"))
        assertEquals("follows/$none?followers=false", Routes.follows("../x", false))
    }
}
