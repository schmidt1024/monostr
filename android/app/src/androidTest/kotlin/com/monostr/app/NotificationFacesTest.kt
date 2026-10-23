package com.monostr.app

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monostr.app.ui.notifications.NotificationRow
import com.monostr.app.ui.notifications.NotificationRowItem
import com.monostr.app.ui.notifications.NotificationRowUi
import com.monostr.nostr.repo.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The faces of a grouped like fill one line: up to 7, fewer when the screen is narrow, never a second line. */
@RunWith(AndroidJUnit4::class)
class NotificationFacesTest {
    @get:Rule val rule = createComposeRule()

    private val actors = (1..9).map { "$it".repeat(64) }
    private val row = NotificationRowUi(NotificationRow.Group(NotificationKind.REACTION, "f".repeat(64), actors, 1000), actors.associateWith { "n" }, actors.associateWith { null }, null)

    private fun faces() = rule.onAllNodes(SemanticsMatcher("a face") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("notification-avatar-") == true }).fetchSemanticsNodes()

    private fun show(width: Int) = rule.setContent {
        MaterialTheme { Box(Modifier.width(width.dp)) { NotificationRowItem(row, emptyMap(), onOpenThread = {}, onOpenProfile = {}) } }
    }

    @Test
    fun aWideRowShowsSeven() {
        show(420)
        assertEquals(7, faces().size)
    }

    @Test
    fun aNarrowRowShowsFewerOnOneLine() {
        show(260)
        val shown = faces()
        assertTrue("${shown.size} faces", shown.size in 1..6)
        val tops = shown.map { it.boundsInRoot.top }.toSet()
        assertEquals("one line", 1, tops.size)
        val right = shown.maxOf { it.boundsInRoot.right }
        val rowRight = rule.onNodeWithTag("notification-${row.row.key}").getUnclippedBoundsInRoot().right
        assertTrue("faces stay inside the row", right <= with(rule.density) { rowRight.toPx() } + 1)
    }
}
