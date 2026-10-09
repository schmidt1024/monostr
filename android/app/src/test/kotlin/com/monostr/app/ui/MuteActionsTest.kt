package com.monostr.app.ui

import com.monostr.app.R
import com.monostr.app.ui.common.MuteActions
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.ProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MuteActionsTest {
    private val alice = "a".repeat(64)
    private fun TestScope.eager(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]) + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `the mute message names the account without waiting for a relay fetch of its profile`() = runTest {
        // the stored profile is there; a relay fetch would never end
        val profiles = object : ProfileRepository by FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", null, null, null))) {
            override suspend fun get(pubkey: String, maxAgeSeconds: Long): Profile = awaitCancellation()
        }
        val mute = FakeMute()
        var result: Pair<UiText, Boolean>? = null
        MuteActions(mute, eager(), profiles).mute(alice) { m, ok -> result = m to ok }
        runCurrent()
        assertEquals(uiText(R.string.mute_done, "Alice") to true, result)
    }

    @Test
    fun `a mute survives the screen that started it`() = runTest {
        val profiles = FakeProfiles(mapOf(alice to Profile(alice, "alice", "Alice", null, null, null)))
        val mute = FakeMute().apply { gate = CompletableDeferred() }
        val screen = eager()
        var result: Pair<UiText, Boolean>? = null
        MuteActions(mute, screen, profiles, background = eager()).mute(alice) { m, ok -> result = m to ok }
        runCurrent()
        screen.cancel() // the user left before the relays answered
        mute.gate!!.complete(Unit); runCurrent()
        assertEquals(setOf(alice), mute.muted.value)
        assertEquals(uiText(R.string.mute_done, "Alice") to true, result)
    }

    @Test
    fun `without a stored profile the message shows the short pubkey`() = runTest {
        var result: UiText? = null
        MuteActions(FakeMute(), eager(), FakeProfiles()).mute(alice) { m, _ -> result = m }
        runCurrent()
        assertEquals(uiText(R.string.mute_done, Profile.shortPubkey(alice)), result)
    }
}
