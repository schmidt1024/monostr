package com.monostr.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.data.Presets
import androidx.compose.foundation.layout.padding
import com.monostr.app.ui.common.NoteActionRow
import com.monostr.app.ui.feed.NoteUi
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.NoteCounts
import com.monostr.nostr.model.Profile
import com.monostr.nostr.repo.TipSummary
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 6: five action icons plus the tip counter fit one row at font scale 1.3 in the action row of a 360 dp wide card (spec 11.5: in a list card the row sits in the column beside the avatar, so it is narrower there and the chip may collapse earlier). */
@RunWith(AndroidJUnit4::class)
class ActionRowScaleTest {
    @get:Rule val rule = createComposeRule()

    private val id = "c".repeat(64)
    private val id8 = id.take(8)
    private val allTags = listOf("note-reply-$id8", "note-repost-$id8", "note-like-$id8", "note-bookmark-$id8", "note-tip-$id8", "note-tips-$id8")
    private val fiveActionTags = listOf("note-repost-$id8", "note-like-$id8", "note-bookmark-$id8", "note-tip-$id8", "note-tips-$id8")

    private fun note() = Note(id, "a".repeat(64), "hello", 1000, 1, null, null, null, emptyList())
    private fun ui(tips: TipSummary, counts: NoteCounts? = null) = NoteUi(note(), Profile.empty(note().author), Profile.empty(note().author), tips = tips, counts = counts)

    /** The row alone at exactly [widthDp] (a list card's column, spec 11.5 §2). */
    private fun setRow(widthDp: Dp, tips: TipSummary, counts: NoteCounts? = null) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1.3f)) {
                MaterialTheme { Box(Modifier.width(widthDp)) { NoteActionRow(ui(tips, counts), note(), onReply = {}, onRepost = {}, onQuote = {}, onLike = {}, onBookmark = {}, onTip = {}, onShowTippers = {}) } }
            }
        }
    }

    private fun setNote(widthDp: Dp, tips: TipSummary, counts: NoteCounts? = null) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1.3f)) {
                // spec 11.5: the row is a composable of its own; a "card" of widthDp has 16 dp of padding on each side
                MaterialTheme { Box(Modifier.width(widthDp).padding(horizontal = 16.dp)) { NoteActionRow(ui(tips, counts), note(), onReply = {}, onRepost = {}, onQuote = {}, onLike = {}, onBookmark = {}, onTip = {}, onShowTippers = {}) } }
            }
        }
    }

    @Test
    fun allActionsVisibleInsideTheCardAtLargeFont() {
        // 2.5 XMR from 3 tippers: a short chip ("2.5 · 3") that comfortably fits the ~88 dp left
        // over after five 48 dp touch-target icons at 360 dp, so it must NOT collapse here.
        val total = 2_500_000_000_000L
        val count = 3
        setNote(360.dp, TipSummary(total, count, emptyList()))
        for (tag in fiveActionTags) {
            rule.onNodeWithTag(tag).assertIsDisplayed()
            val b = rule.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue("$tag right edge ${b.right} exceeds 360 dp", b.right <= 360.dp)
        }
        val tip = rule.onNodeWithTag("note-tip-$id8").getBoundsInRoot()
        val chip = rule.onNodeWithTag("note-tips-$id8").getBoundsInRoot()
        assertTrue("counter must sit right of the tip icon in the same row", chip.left >= tip.right - 1.dp && chip.top < tip.bottom)

        // The card is wide enough that the chip must NOT have collapsed to the count-only fallback:
        // it stays on a single line (well under the 48 dp touch target, and under 2x a one-line
        // labelSmall's height at 1.3x), showing the full "<amount> · <count>" text.
        val chipHeight = chip.bottom - chip.top
        assertTrue("chip height $chipHeight must fit the 48 dp touch target", chipHeight <= 48.dp)
        assertTrue("chip height $chipHeight must be a single line (< 40 dp)", chipHeight < 40.dp)
        val expectedFull = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.note_tips_chip, Presets.format(total), count)
        rule.onNodeWithTag("note-tips-$id8").assertTextEquals(expectedFull)
    }

    @Test
    fun counterCollapsesToCountWhenThereIsNoRoom() {
        // 12.345678900 XMR from 12 tippers formats to the long mono string "0.0123456789 · 12"
        // (measured ~153 dp unconstrained at 1.3x), which cannot fit in the ~88 dp left over after
        // five 48 dp touch-target icons even at 360 dp -- let alone a narrower 300 dp card -- so the
        // chip must collapse to the bare count.
        val total = 12_345_678_900L
        val count = 12
        val width = 300.dp
        setNote(width, TipSummary(total, count, emptyList()))
        rule.waitUntil(5_000) {
            try {
                rule.onNodeWithTag("note-tips-$id8").assertTextEquals(count.toString())
                true
            } catch (e: AssertionError) {
                false
            }
        }
        for (tag in allTags) {
            rule.onNodeWithTag(tag).assertIsDisplayed()
            val b = rule.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue("$tag right edge ${b.right} exceeds $width", b.right <= width)
        }
        val tip = rule.onNodeWithTag("note-tip-$id8").getBoundsInRoot()
        val chip = rule.onNodeWithTag("note-tips-$id8").getBoundsInRoot()
        assertTrue("counter must sit right of the tip icon in the same row", chip.left >= tip.right - 1.dp && chip.top < tip.bottom)
        rule.onNodeWithTag("note-tips-$id8").assertTextEquals(count.toString())
    }

    @Test
    fun countsSitRightOfTheIconsAndEverythingStaysInsideAt360() {
        // 0.4.2: like/repost/reply counts sit right of their icon (X-style). With three counts the
        // tip chip may collapse to its bare count, but every icon, every count and the chip stay inside 360 dp.
        val total = 2_500_000_000_000L
        val count = 3
        setNote(360.dp, TipSummary(total, count, emptyList()), NoteCounts(1234, 56, 999_999))
        for (tag in allTags) {
            rule.onNodeWithTag(tag).assertIsDisplayed()
            val b = rule.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue("$tag right edge ${b.right} exceeds 360 dp", b.right <= 360.dp)
        }
        for (action in listOf("note-like-$id8", "note-repost-$id8", "note-reply-$id8")) {
            rule.onNodeWithTag("$action-count", useUnmergedTree = true).assertIsDisplayed()
            val icon = rule.onNodeWithTag(action).getBoundsInRoot()
            val label = rule.onNodeWithTag("$action-count", useUnmergedTree = true).getBoundsInRoot()
            assertTrue("$action count must sit right of the 22 dp icon", label.left >= icon.left + 20.dp)
            assertTrue("$action count must be on the icon's row", label.top < icon.bottom && label.bottom > icon.top)
        }
    }

    @Test
    fun theGlyphsKeepTheirPlaceWhenCountsArrive() {
        // 0.11.8: a count widens its target to the right only — the reply glyph (and so the row's left edge) stays put
        var counts by mutableStateOf<NoteCounts?>(null)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1.3f)) {
                MaterialTheme { Box(Modifier.width(360.dp)) { NoteActionRow(ui(TipSummary(0, 0, emptyList()), counts), note(), onReply = {}, onRepost = {}, onQuote = {}, onLike = {}, onBookmark = {}, onTip = {}, onShowTippers = {}) } }
            }
        }
        val reply = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.note_reply)
        val glyph = { rule.onNode(hasContentDescription(reply) and hasAnyAncestor(hasTestTag("note-reply-$id8")), useUnmergedTree = true).getBoundsInRoot().left }
        val before = glyph()
        counts = NoteCounts(1234, 56, 999_999)
        rule.waitForIdle()
        rule.onNodeWithTag("note-reply-$id8-count", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("the reply glyph moved: $before -> ${glyph()}", before.value, glyph().value, 0.5f)
    }

    @Test
    fun theListCardColumnNeverOverflowsWithBigCounts() {
        // spec 11.5 §2: on a 360 dp phone the list card's column is 272 dp wide; three four-character counts at 1.3x
        // do not fit beside the other icons there, so the counts go and the five icons plus the chip stay inside
        setRow(272.dp, TipSummary(2_500_000_000_000L, 3, emptyList()), NoteCounts(999_000, 999_000, 999_000))
        for (tag in allTags) {
            rule.onNodeWithTag(tag).assertIsDisplayed()
            val b = rule.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue("$tag right edge ${b.right} exceeds 272 dp", b.right <= 272.dp)
        }
        val tip = rule.onNodeWithTag("note-tip-$id8").getBoundsInRoot()
        assertTrue("the tip target keeps 48 dp: ${tip.right - tip.left}", tip.right - tip.left >= 48.dp)
        rule.onNodeWithTag("note-like-$id8-count", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun countsAreDroppedOnNarrowCards() {
        // spec 11.5 §2: counts go below 300 dp of row width (a 320 dp card has 288 dp inside its padding)
        setNote(320.dp, TipSummary(2_500_000_000_000L, 3, emptyList()), NoteCounts(1234, 56, 999_999))
        rule.onNodeWithTag("note-like-$id8").assertIsDisplayed()
        rule.onNodeWithTag("note-like-$id8-count", useUnmergedTree = true).assertDoesNotExist()
    }
}
