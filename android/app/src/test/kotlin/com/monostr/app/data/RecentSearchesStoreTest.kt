package com.monostr.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RecentSearchesStoreTest {
    @TempDir lateinit var dir: Path
    private fun scope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Test
    fun `newest first, a repeated query moves up instead of duplicating`() = runTest {
        val scope = scope()
        val s = PrefsRecentSearchesStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("recent.preferences_pb").toFile() })
        assertEquals(emptyList<String>(), s.recent.first())
        s.add("monero"); s.add("#nostr"); s.add("Monero")
        assertEquals(listOf("Monero", "#nostr"), s.recent.first()) // the newer spelling wins, once
        s.remove("#NOSTR")
        assertEquals(listOf("Monero"), s.recent.first())
        s.clear()
        assertEquals(emptyList<String>(), s.recent.first())
        scope.cancel()
    }

    @Test
    fun `the list holds ten, the oldest falls out, blanks are ignored`() = runTest {
        val scope = scope()
        val s = PrefsRecentSearchesStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("recent.preferences_pb").toFile() })
        for (i in 1..12) s.add("q$i")
        assertEquals((12 downTo 3).map { "q$it" }, s.recent.first())
        s.add("   "); s.add("")
        assertEquals(10, s.recent.first().size)
        s.add("  spaced  ")
        assertEquals("spaced", s.recent.first().first())
        // full list, one removed, a new search: ten stays the limit and the removed one does not come back
        s.remove("q11")
        s.add("q13")
        assertEquals(10, s.recent.first().size)
        assertTrue("q11" !in s.recent.first())
        // a pasted private key is never kept; a huge paste is cut
        s.add("nsec1" + "x".repeat(58)); s.add("NCRYPTSEC1abc")
        assertTrue(s.recent.first().none { it.lowercase().startsWith("nsec1") || it.lowercase().startsWith("ncryptsec1") })
        s.add("y".repeat(500))
        assertEquals(RecentSearchesStore.MAX_LENGTH, s.recent.first().first().length)
        scope.cancel()
    }
}
