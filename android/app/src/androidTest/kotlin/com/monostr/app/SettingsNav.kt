package com.monostr.app

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.monostr.app.ui.settings.SettingsPage

/** Spec 2026-11-11: Settings is an overview with one subpage per category; these open it from the feed's gear. */
fun ComposeTestRule.openSettings(ctx: Context) {
    onNodeWithContentDescription(ctx.getString(R.string.feed_settings)).performClick()
    waitUntil(15_000) { onAllNodesWithTag("settings-cat-privacy").fetchSemanticsNodes().isNotEmpty() }
}

/** Opens Settings and then [page]; a second-level page (DM or search relays, quick reactions) through its row on its parent page. */
fun ComposeTestRule.openSettingsPage(ctx: Context, page: SettingsPage) {
    openSettings(ctx)
    val top = page.parent ?: page
    tapWhenShown(if (top == SettingsPage.ACCOUNT) "settings-account" else "settings-cat-${top.id}")
    when (page) {
        SettingsPage.DM_RELAYS -> tapWhenShown("settings-open-dm-relays")
        SettingsPage.SEARCH_RELAYS -> tapWhenShown("settings-open-search-relays")
        SettingsPage.REACTIONS -> tapWhenShown("settings-open-reactions")
        else -> Unit
    }
}

private fun ComposeTestRule.tapWhenShown(tag: String) {
    waitUntil(15_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag(tag).performScrollTo().performClick()
}
