package com.monostr.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.ui.common.NoteCard
import com.monostr.app.ui.feed.NoteUi
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Like X: the free space below the avatar opens the note too; the avatar itself still opens the profile. */
@RunWith(AndroidJUnit4::class)
class NoteCardTapTest {
    @get:Rule val rule = createComposeRule()

    private val author = "a".repeat(64)
    private val text = (1..12).joinToString("\n") { "line $it" }
    private val note = Note("1".repeat(64), author, text, 1000, 1, null, null, null, emptyList(), displayContent = text)

    @Test
    fun theSpaceBelowTheAvatarOpensTheNote() {
        var opened = 0
        var profiles = 0
        rule.setContent {
            MaterialTheme {
                NoteCard(NoteUi(note, Profile.empty(author), Profile.empty(author)), onOpen = { opened++ }, onOpenProfile = { profiles++ }, onReply = {}, onLike = {}, onRepost = {})
            }
        }
        val id8 = note.id.take(8)
        // left edge, near the bottom: under the avatar, beside the long text
        rule.onNodeWithTag("note-row-$id8").performTouchInput { click(Offset(8.dp.toPx(), height - 8.dp.toPx())) }
        rule.runOnIdle { assertEquals(1, opened); assertEquals(0, profiles) }
        rule.onNodeWithTag("note-avatar-$id8").performClick()
        rule.runOnIdle { assertEquals(1, opened); assertEquals(1, profiles) }
    }
}
