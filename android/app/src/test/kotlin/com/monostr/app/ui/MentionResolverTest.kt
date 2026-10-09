package com.monostr.app.ui

import com.monostr.app.ui.common.MentionResolver
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MentionResolverTest {
    private val carol = "c".repeat(64)
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `each pubkey is resolved once and names merge into one map`() = runTest {
        val profiles = FakeProfiles(mapOf(carol to Profile(carol, "carol", null, null, null, null)))
        val r = MentionResolver(profiles, eager())
        r.track(listOf(carol, "b".repeat(64)))
        advanceUntilIdle()
        r.track(listOf(carol))
        advanceUntilIdle()
        assertEquals(mapOf(carol to "carol"), r.names.value)
        assertEquals(1, profiles.prefetched.size)
    }

    private fun mentioning(id: Char, pubkey: String) = Note(id.toString().repeat(64), "a".repeat(64), "hi", 1000, 1, null, null, null, listOf(pubkey))

    @Test
    fun `follow scans only notes it has not seen and resolves each pubkey once`() = runTest {
        val alice = "e".repeat(64)
        val profiles = FakeProfiles(mapOf(carol to Profile(carol, "carol", null, null, null, null), alice to Profile(alice, "alice", null, null, null, null)))
        val r = MentionResolver(profiles, eager(), UnconfinedTestDispatcher(testScheduler))
        val notes = MutableStateFlow(listOf(mentioning('1', carol)))
        r.follow(notes)
        advanceUntilIdle()
        assertEquals(mapOf(carol to "carol"), r.names.value)
        notes.value = listOf(mentioning('1', carol), mentioning('2', alice))
        advanceUntilIdle()
        assertEquals(mapOf(carol to "carol", alice to "alice"), r.names.value)
        assertEquals(listOf(listOf(carol), listOf(alice)), profiles.prefetched.map { it.toList() })
        assertEquals(2, r.seenCount())
    }

    @Test
    fun `the set of seen note ids is capped`() = runTest {
        val r = MentionResolver(FakeProfiles(), eager(), UnconfinedTestDispatcher(testScheduler))
        r.follow(MutableStateFlow((0 until 2_100).map { Note(it.toString().padStart(64, '0'), "a".repeat(64), "n", 1000, 1, null, null, null, emptyList()) }))
        advanceUntilIdle()
        assertEquals(MentionResolver.SEEN_CAP, r.seenCount())
    }

    @Test
    fun `a failed lookup is scanned again on the next emission`() = runTest {
        val known = FakeProfiles(mapOf(carol to Profile(carol, "carol", null, null, null, null)))
        var offline = true
        val profiles = object : ProfileRepository by known {
            override suspend fun prefetch(pubkeys: Collection<String>) {
                if (offline) error("offline")
                known.prefetch(pubkeys)
            }
        }
        val r = MentionResolver(profiles, eager(), UnconfinedTestDispatcher(testScheduler))
        val notes = MutableSharedFlow<List<Note>>(replay = 1) // re-emits an equal list, unlike a StateFlow
        r.follow(notes)
        notes.emit(listOf(mentioning('1', carol)))
        advanceUntilIdle()
        assertEquals(emptyMap<String, String>(), r.names.value)
        assertEquals(0, r.seenCount()) // forgotten again after the failure
        offline = false
        notes.emit(listOf(mentioning('1', carol)))
        advanceUntilIdle()
        assertEquals(mapOf(carol to "carol"), r.names.value)
        assertEquals(1, r.seenCount())
    }
}
