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
import com.monostr.app.data.AmountUnit
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

/** Spec 6: five action icons plus the tip counter fit one row at font scale 1.3 in the action row of a 360 dp wide card (spec 11.5: in a list card the row sits in the column beside the avatar, 272 dp wide at 360 dp, so the tips text shows the bare count there; the spread detail row has room for the full chip). */
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
    private fun setRow(widthDp: Dp, tips: TipSummary, counts: NoteCounts? = null, spread: Boolean = false) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1.3f)) {
                MaterialTheme { Box(Modifier.width(widthDp)) { NoteActionRow(ui(tips, counts), note(), onReply = {}, onRepost = {}, onQuote = {}, onLike = {}, onBookmark = {}, onTip = {}, onShowTippers = {}, spread = spread) } }
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

    /** The five actions right of the reply glyph all lie inside [limit] and the counter follows the Monero glyph on its row. */
    private fun assertFiveActionsInside(limit: Dp) {
        for (tag in fiveActionTags) {
            rule.onNodeWithTag(tag).assertIsDisplayed()
            val b = rule.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue("$tag right edge ${b.right} exceeds $limit", b.right <= limit)
        }
        val tip = rule.onNodeWithTag("note-tip-$id8").getBoundsInRoot()
        val chip = rule.onNodeWithTag("note-tips-$id8").getBoundsInRoot()
        assertTrue("counter must start after the Monero glyph in the same row: tip=$tip chip=$chip", chip.left > tip.left + 13.dp && chip.top < tip.bottom)
    }

    @Test
    fun allActionsVisibleInsideTheListCardAtLargeFont() {
        // The list card puts the row in the column beside the avatar: 360 dp card - 2 x 16 dp padding
        // - 44 dp avatar (LIST_AVATAR) - 12 dp gap (LIST_GAP), both private in ui/common/Common.kt = 272 dp.
        // At font 1.3 only ~20 dp remain for the tips text, so it shows the bare count "3".
        val total = 2_500_000_000_000L
        val count = 3
        val rowWidth = 360.dp - 32.dp - 44.dp - 12.dp
        setRow(rowWidth, TipSummary(total, count, emptyList()))
        assertFiveActionsInside(rowWidth)
        rule.onNodeWithTag("note-tips-$id8").assertTextEquals(count.toString())
    }

    @Test
    fun allActionsVisibleInsideTheDetailCardAtLargeFont() {
        // The detail card spreads the row over the full inner width. At 412 dp (a common phone width, 380 dp inside the
        // padding) ~140 dp are left over after five 48 dp icons, enough for "2.5 XMR · 3" at 1.3x, which shows in full
        // on a single line. (At 360 dp only ~88 dp remain: the chip then drops the unit label, see detailCardAt360KeepsTheAmountWithoutUnit.)
        val total = 2_500_000_000_000L
        val count = 3
        val rowWidth = 412.dp - 32.dp
        setRow(rowWidth, TipSummary(total, count, emptyList()), spread = true)
        assertFiveActionsInside(rowWidth)
        val chip = rule.onNodeWithTag("note-tips-$id8").getBoundsInRoot()
        assertTrue("the full chip ends right of the Monero glyph", chip.right > rule.onNodeWithTag("note-tip-$id8").getBoundsInRoot().right)
        val chipHeight = chip.bottom - chip.top
        assertTrue("chip height $chipHeight must fit the 48 dp touch target", chipHeight <= 48.dp)
        assertTrue("chip height $chipHeight must be a single line (< 40 dp)", chipHeight < 40.dp)
        // built the way the app builds it (NoteActionRow): amount with unit, then the count
        val expectedFull = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.note_tips_chip, Presets.withUnit(total, AmountUnit.XMR), count)
        rule.onNodeWithTag("note-tips-$id8").assertTextEquals(expectedFull)
    }

    @Test
    fun detailCardAt360KeepsTheAmountWithoutUnit() {
        // At 360 dp the spread row has ~88 dp for the tips text: "2.5 XMR · 3" does not fit at 1.3x, "2.5 · 3" does,
        // so the chip drops the unit label before it drops the amount.
        val total = 2_500_000_000_000L
        val count = 3
        val rowWidth = 360.dp - 32.dp
        setRow(rowWidth, TipSummary(total, count, emptyList()), spread = true)
        assertFiveActionsInside(rowWidth)
        val expected = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.note_tips_chip, Presets.format(total, AmountUnit.XMR), count)
        rule.waitUntil(5_000) {
            try {
                rule.onNodeWithTag("note-tips-$id8").assertTextEquals(expected)
                true
            } catch (e: AssertionError) {
                false
            }
        }
    }

    @Test
    fun counterCollapsesToCountWhenThereIsNoRoom() {
        // 12.345678900 XMR from 12 tippers formats to the long mono string "0.0123456789 · 12"
        // (measured ~153 dp unconstrained at 1.3x), which cannot fit in the room left over after
        // five 48 dp touch-target icons even in a 360 dp card -- let alone a narrower 300 dp one -- so the
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
        assertTrue("counter must start after the Monero glyph in the same row", chip.left > tip.left + 13.dp && chip.right > tip.right && chip.top < tip.bottom)
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
