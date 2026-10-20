package com.monostr.nostr.repo

import com.monostr.nostr.model.Profile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec perf 3.8: who is on the device, searchable in memory. */
class PeopleIndexTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    @Test
    fun `built once, searched in memory, a changed profile replaces the old one`() = runTest {
        var loads = 0
        val index = PeopleIndex { loads++; listOf(Profile(a, "alice", "Alice", null, null, "al@x.org")) }
        assertEquals(listOf(a), index.search("X.ORG", 20).map { it.pubkey })
        assertEquals(listOf(a), index.search("ali", 20).map { it.pubkey })
        assertEquals(1, loads)
        // Review Focus: an own edit or a landed profile is found under its new name, no longer under the old one
        index.add(listOf(Profile(a, "alina", null, null, null, null)))
        assertEquals(emptyList<String>(), index.search("alice", 20).map { it.pubkey })
        assertEquals(listOf(a), index.search("alina", 20).map { it.pubkey })
    }

    @Test
    fun `a profile that arrives while the index is built is not replaced by the stored one`() = runTest {
        lateinit var index: PeopleIndex
        index = PeopleIndex {
            index.add(listOf(Profile(b, "bobby", null, null, null, null)))
            listOf(Profile(b, "bob", null, null, null, null))
        }
        assertEquals(listOf("bobby"), index.search("bob", 20).map { it.name })
    }

    @Test
    fun `a failed build is tried again by the next search`() = runTest {
        var loads = 0
        val index = PeopleIndex { if (loads++ == 0) error("database busy") else listOf(Profile(a, "alice", null, null, null, null)) }
        assertTrue(runCatching { index.search("ali", 20) }.isFailure)
        assertEquals(listOf(a), index.search("ali", 20).map { it.pubkey })
    }

    @Test
    fun `a blank query finds nobody, hits are sorted by the shown name and cut at the limit`() = runTest {
        val index = PeopleIndex {
            listOf(Profile(a, "zed", null, null, null, null), Profile(b, "amy", null, null, null, null), Profile(c, "max", null, null, null, null))
        }
        assertEquals(emptyList<Profile>(), index.search("  ", 20))
        assertEquals(listOf("amy", "max"), index.search("m", 2).map { it.name })
    }
}
