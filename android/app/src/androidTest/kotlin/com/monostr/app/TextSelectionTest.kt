package com.monostr.app

import android.content.ClipboardManager
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.ui.common.NoteCard
import com.monostr.app.ui.feed.NoteUi
import com.monostr.nostr.model.Note
import com.monostr.nostr.model.Profile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 2026-10-16 §4: the opened note's text selects and copies; links still open; the feed card is unchanged. */
@RunWith(AndroidJUnit4::class)
class TextSelectionTest {
    @get:Rule val rule = createComposeRule()
    private val id = "e".repeat(64)
    private val id8 = id.take(8)
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun ui(text: String) = Note(id, "a".repeat(64), text, 1000, 1, null, null, null, emptyList(), displayContent = text)
        .let { NoteUi(it, Profile.empty(it.author), Profile.empty(it.author)) }

    /** Stands in for the system's floating toolbar, which is no Compose node. */
    private class RecordingToolbar : TextToolbar {
        @Volatile var copy: (() -> Unit)? = null
        override val status: TextToolbarStatus get() = if (copy != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            copy = onCopyRequested
        }
        override fun hide() { copy = null }
    }

    private var newMenu = false

    // the new context menu does not go through LocalTextToolbar; the old one does, and is what the app shows on older setups too
    @OptIn(ExperimentalFoundationApi::class)
    @Before fun oldMenu() {
        newMenu = ComposeFoundationFlags.isNewContextMenuEnabled
        ComposeFoundationFlags.isNewContextMenuEnabled = false
    }

    @OptIn(ExperimentalFoundationApi::class)
    @After fun restoreMenu() {
        ComposeFoundationFlags.isNewContextMenuEnabled = newMenu
    }

    private fun clipText(): String? {
        var text: String? = null
        rule.runOnUiThread { text = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
        return text
    }

    @Test
    fun longPressInTheOpenedNoteSelectsAndCopies() {
        val toolbar = RecordingToolbar()
        var opened = 0
        rule.runOnUiThread { ctx.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
        rule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                MaterialTheme { NoteCard(ui("selectable words here"), onOpen = { opened++ }, onOpenProfile = {}, onReply = {}, onLike = {}, onRepost = {}, detail = true) }
            }
        }
        rule.onNodeWithTag("note-text-$id8").performTouchInput { longClick(Offset(40f, centerY)) }
        rule.waitUntil(5_000) { toolbar.copy != null }
        rule.runOnIdle { toolbar.copy!!.invoke() }
        rule.waitUntil(5_000) { !clipText().isNullOrBlank() }
        val copied = clipText()!!
        assertTrue(copied, "selectable words here".contains(copied))
        assertEquals(0, opened)
    }

    @Test
    fun aHashtagInTheOpenedNoteStillOpens() {
        var tag: String? = null
        rule.setContent {
            MaterialTheme { NoteCard(ui("#monero"), onOpen = {}, onOpenProfile = {}, onReply = {}, onLike = {}, onRepost = {}, onHashtag = { tag = it }, detail = true) }
        }
        // the text node spans the width; the link is the first glyphs
        rule.onNodeWithTag("note-text-$id8").performTouchInput { click(Offset(20f, centerY)) }
        rule.runOnIdle { assertEquals("monero", tag) }
    }

    @Test
    fun theFeedCardTextStillOpensTheNote() {
        var opened = 0
        rule.setContent {
            MaterialTheme { NoteCard(ui("tap me"), onOpen = { opened++ }, onOpenProfile = {}, onReply = {}, onLike = {}, onRepost = {}) }
        }
        rule.onNodeWithTag("note-text-$id8").performClick()
        rule.runOnIdle { assertEquals(1, opened) }
    }
}
