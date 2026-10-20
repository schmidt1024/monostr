package com.monostr.app.ui

import com.monostr.app.ui.profile.ProfileCache
import com.monostr.nostr.model.Profile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProfileCacheTest {
    private fun entry(pk: String) = ProfileCache.Entry(Profile.empty(pk), emptyList(), null, null, null)

    @Test
    fun `keeps the most recently used profiles up to its capacity`() {
        val cache = ProfileCache(capacity = 2)
        cache.put("a", entry("a")); cache.put("b", entry("b"))
        cache.get("a") // used again: b is now the oldest
        cache.put("c", entry("c"))
        assertNotNull(cache.get("a")); assertNull(cache.get("b")); assertNotNull(cache.get("c"))
        assertEquals(2, cache.size())
    }

    @Test
    fun `lists are kept per profile with the same capacity`() {
        val cache = ProfileCache(capacity = 1)
        cache.putLists("a", emptyList()); cache.putLists("b", emptyList())
        assertNull(cache.lists("a")); assertNotNull(cache.lists("b"))
    }
}
