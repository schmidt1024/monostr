package com.monostr.nostr.repo

import com.monostr.nostr.model.FollowSet
import com.monostr.nostr.model.ListAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Spec perf 3.7: the persisted summary of the own lists. */
class OwnListsJsonTest {
    private val me = "e".repeat(64)
    private val set = FollowSet(
        ListAddress(30000, me, "abc"), "Friends", "close ones", listOf("b".repeat(64)), private = true, author = me,
        createdAt = 1000, locked = false, extra = listOf(listOf("image", "https://x/y.png")), eventId = "1".repeat(64),
    )

    @Test
    fun `a list goes through the summary unchanged, private members included`() {
        val back = OwnListsJson.decode(OwnListsJson.encode(listOf(CachedList.of(set))))!!
        assertEquals(listOf(set), back.map { it.toSet() })
    }

    @Test
    fun `an unreadable summary or one of another version is none`() {
        assertNull(OwnListsJson.decode("not json"))
        assertNull(OwnListsJson.decode("""{"version":99,"lists":[]}"""))
    }

    @Test
    fun `a stored address that is not a follow set is dropped`() {
        assertNull(CachedList.of(set).copy(address = "1:x:y").toSet())
    }
}
