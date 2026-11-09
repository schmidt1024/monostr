package com.monostr.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The classes that send have an open [SendGate] by default for their tests. A construction in the app that forgets the real one
 * compiles and would send while Tor is down, so it is caught here.
 */
class SendGateWiringTest {
    private val senders = listOf(
        "TipDecorations", "TipSheetController", "ComposeController", "NoteActions", "DmSync", "PendingSetupPublisher", "ChatController",
        "FeedController", "ThreadController", "ArticleController", "BookmarksController", "SearchController", "ProfileController",
        "BookmarkActions", "MuteActions", "MutedController", "AddToListController", "ListController", "ListEditController", "ProfileListsController",
        "HomeTabsController", "PinActions", "ProfileEditController", "AmountsVisibility", "MoneroSettingsController", "MoneroSetupController",
        "DiscoverController", "ProfileTipWatch", "FollowsController", "BlossomClient",
    )

    /** The text between the parentheses opened at [open]. */
    private fun arguments(source: String, open: Int): String {
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return source.substring(open, i)
            }
        }
        return source.substring(open)
    }

    @Test
    fun `every sender built in the app is given the gate`() {
        var cwd = File("").absoluteFile
        while (!File(cwd, "app/src/main/kotlin").isDirectory) cwd = cwd.parentFile
        val main = File(cwd, "app/src/main/kotlin")
        val call = Regex("""(?<![A-Za-z.])(?<!class )(${senders.joinToString("|")})\(""")
        val missing = ArrayList<String>()
        val found = HashSet<String>()
        main.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val source = file.readText()
            call.findAll(source).forEach { m ->
                found += m.groupValues[1]
                val args = arguments(source, m.range.last)
                if (!args.contains("sendGate")) missing += "${file.name}: ${m.groupValues[1]}"
            }
        }
        assertEquals(emptyList<String>(), missing)
        assertTrue(found.containsAll(senders), "not found any more: ${senders - found}")
    }
}
