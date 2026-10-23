package com.monostr.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.ui.common.NoteText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A profile bio folds earlier than a note: more than 7 lines show 5 and "Show more"; 7 lines stay whole. */
@RunWith(AndroidJUnit4::class)
class NoteTextCollapseTest {
    @get:Rule val rule = createComposeRule()

    private fun lines(n: Int) = (1..n).joinToString("\n") { "line $it" }

    private fun show(text: String, key: String) = rule.setContent {
        MaterialTheme {
            NoteText(text, onOpen = {}, onHashtag = {}, onOpenProfile = {}, onOpenNote = {}, collapsible = true, collapseAbove = 7, collapsedLines = 5, stateKey = key)
        }
    }

    @Test
    fun eightLinesFoldWithShowMore() {
        show(lines(8), "bio-long")
        rule.onNodeWithTag("note-more-bio-long").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasTestTag("note-more-bio-long")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun sevenLinesStayWhole() {
        show(lines(7), "bio-fits")
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasTestTag("note-more-bio-fits")).fetchSemanticsNodes().isEmpty())
    }
}
