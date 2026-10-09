package com.monostr.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.ui.common.NoteCard
import com.monostr.app.ui.feed.NoteUi
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression: a tap anywhere inside the 48 dp action target must reach the callback, including the
 * outer ring (0.2.0 shrank the clickable node to 40 dp inside a 48 dp layout, so edge taps were lost).
 */
@RunWith(AndroidJUnit4::class)
class ActionIconTapTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun edgeTapInsideTheTargetTogglesLikeAndBookmark() {
        val id = "d".repeat(64)
        val note = Note(id, "a".repeat(64), "tap me", 1000, 1, null, null, null, emptyList())
        val ui = NoteUi(note, Profile.empty(note.author), Profile.empty(note.author))
        var likes = 0
        var bookmarks = 0
        rule.setContent { MaterialTheme { NoteCard(ui, onOpen = {}, onOpenProfile = {}, onReply = {}, onLike = { likes++ }, onRepost = {}, onBookmark = { bookmarks++ }) } }
        val id8 = id.take(8)
        val like = rule.onNodeWithTag("note-like-$id8")
        val bounds = like.getBoundsInRoot()
        assertTrue("target must be at least 48 dp, was ${bounds.right - bounds.left}", bounds.right - bounds.left >= 48.dp)
        // 2 dp from the left edge at mid height: inside the 48 dp circle, outside a 40 dp inner circle
        like.performTouchInput { click(Offset(2.dp.toPx(), center.y)) }
        like.performTouchInput { click(center) }
        rule.onNodeWithTag("note-bookmark-$id8").performTouchInput { click(Offset(2.dp.toPx(), center.y)) }
        rule.waitForIdle()
        assertEquals("edge tap and centre tap must both count", 2, likes)
        assertEquals("edge tap on the bookmark must count", 1, bookmarks)
    }
}
