package com.monostr.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.ui.common.LocalCanSend
import com.monostr.app.ui.common.NoteCard
import com.monostr.app.ui.feed.NoteUi
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 2026-11-06 5.3: with Tor not connected, what sends by itself is disabled; reply and tip open a screen that keeps the draft or says why. */
@RunWith(AndroidJUnit4::class)
class ActionRowBlockedTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun likeRepostBookmarkFollowTheGateReplyAndTipStayEnabled() {
        val id = "d".repeat(64)
        val note = Note(id, "a".repeat(64), "blocked", 1000, 1, null, null, null, emptyList())
        val ui = NoteUi(note, Profile.empty(note.author), Profile.empty(note.author))
        val canSend = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalCanSend provides canSend.value) {
                MaterialTheme { NoteCard(ui, onOpen = {}, onOpenProfile = {}, onReply = {}, onLike = {}, onRepost = {}, onBookmark = {}) }
            }
        }
        val id8 = id.take(8)
        listOf("like", "repost", "bookmark").forEach { rule.onNodeWithTag("note-$it-$id8").assertIsNotEnabled() }
        rule.onNodeWithTag("note-reply-$id8").assertIsEnabled()
        rule.onNodeWithTag("note-tip-$id8").assertIsEnabled()
        canSend.value = true
        rule.waitForIdle()
        listOf("like", "repost", "bookmark").forEach { rule.onNodeWithTag("note-$it-$id8").assertIsEnabled() }
    }
}
