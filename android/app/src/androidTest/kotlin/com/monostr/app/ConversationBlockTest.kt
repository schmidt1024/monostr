package com.monostr.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.ui.common.NoteCard
import com.monostr.app.ui.common.ShowRepliesRow
import com.monostr.app.ui.common.UnavailableRow
import com.monostr.app.ui.feed.NoteUi
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 2026-10-16 §3: a reply in the feed stands under the notes above it, joined by the thread line (X's conversation block). */
@RunWith(AndroidJUnit4::class)
class ConversationBlockTest {
    @get:Rule val rule = createComposeRule()
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val root = Note("1".repeat(64), alice, "the root", 900, 1, null, null, null, emptyList(), displayContent = "the root")
    // a reply naming alice: on its own it would carry "Replying to"
    private val reply = Note("2".repeat(64), bob, "the reply", 1000, 1, root.id, root.id, null, listOf(alice), displayContent = "the reply")
    private fun ui(n: Note) = NoteUi(n, Profile.empty(n.author), Profile.empty(n.author))

    @Test
    fun blockShowsLinesGapAndUnavailableRow() {
        var shown = 0
        var line = Color.Unspecified
        rule.setContent {
            MaterialTheme {
                line = MaterialTheme.colorScheme.outlineVariant
                Column {
                    NoteCard(ui(root), onOpen = {}, onOpenProfile = {}, onReply = {}, onLike = {}, onRepost = {}, replyContext = false, lineBelow = true)
                    ShowRepliesRow(onClick = { shown++ })
                    UnavailableRow(lineAbove = true, lineBelow = true)
                    NoteCard(ui(reply), onOpen = {}, onOpenProfile = {}, onReply = {}, onLike = {}, onRepost = {}, replyContext = false, lineAbove = true)
                }
            }
        }
        rule.onNodeWithTag("show-replies").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, shown) }
        rule.onNodeWithTag("note-unavailable").assertIsDisplayed()
        rule.onNodeWithText("This note is unavailable").assertExists()
        rule.onNodeWithTag("thread-line-${root.id.take(8)}").assertExists()
        rule.onNodeWithTag("thread-line-${reply.id.take(8)}").assertExists()
        rule.onAllNodesWithText("Replying to", substring = true).assertCountEquals(0)
        // the line runs down the middle of the avatar column: below the root's avatar to its bottom edge, from the reply's top edge to its avatar
        val x = with(rule.density) { (16.dp + 22.dp).roundToPx() }
        val rootPx = rule.onNodeWithTag("thread-line-${root.id.take(8)}").captureToImage().toPixelMap()
        assertEquals(line, rootPx[x, rootPx.height - 2])
        assertTrue("no line above the root", rootPx[x, 1] != line)
        val replyPx = rule.onNodeWithTag("thread-line-${reply.id.take(8)}").captureToImage().toPixelMap()
        assertEquals(line, replyPx[x, 1])
        assertTrue("no line below the reply", replyPx[x, replyPx.height - 2] != line)
    }
}
