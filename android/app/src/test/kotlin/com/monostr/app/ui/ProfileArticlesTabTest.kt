package com.monostr.app.ui

import com.monostr.app.ui.common.BookmarkActions
import com.monostr.app.ui.profile.ProfileController
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.nostr.model.AddressableEvent
import com.monostr.nostr.model.Article
import com.monostr.nostr.repo.ProfileSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Profile "Articles" tab (article reader task 6): visibility, the probe after the header, lazy list. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileArticlesTabTest {
    private val bob = "b".repeat(64)

    private fun article(d: String, at: Long, published: Long? = null): Article = Article.parse(
        AddressableEvent(d.padEnd(64, '0'), 30023, bob, d, at, listOfNotNull(listOf("d", d), listOf("title", "T $d"), published?.let { listOf("published_at", "$it") }), "text"),
    )!!

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
    private fun TestScope.controller(feed: FakeFeed, profiles: FakeProfiles = FakeProfiles(), relays: FakeAuthorRelays = FakeAuthorRelays(), authorRelaysOn: Boolean = false): ProfileController {
        val scope = eager()
        val decorations = TipDecorations(FakeTips(), FakePendingTips(), FakeProfiles(), FakePublish(), scope) { 5_000 }
        return ProfileController(bob, "a".repeat(64), feed, profiles, FakePublish(), decorations, BookmarkActions(FakeBookmarks(), scope), FakeCounts(), relays, { authorRelaysOn }, scope, refreshMs = 1_000)
    }

    @Test
    fun `tab hidden without articles`() = runTest {
        val feed = FakeFeed()
        val c = controller(feed)
        c.start(); runCurrent()
        assertFalse(c.state.value.hasArticles)
        assertFalse(ProfileSection.ARTICLES in c.state.value.tabs)
        c.close()
    }

    @Test
    fun `a stored article shows the tab at once without a probe`() = runTest {
        val feed = FakeFeed().apply { storedArticles = mapOf(bob to listOf(article("a", 10))) }
        val c = controller(feed)
        c.start(); runCurrent()
        assertTrue(c.state.value.hasArticles)
        assertEquals(ProfileSection.ARTICLES, c.state.value.tabs.last())
        assertFalse("probe" in feed.articleCalls)
        // the list itself waits for the tab
        assertNull(c.state.value.articles)
        c.close()
    }

    @Test
    fun `the probe runs once and only after the header loaded`() = runTest {
        val feed = FakeFeed().apply { fetchedArticles = mapOf(bob to listOf(article("a", 10))) }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val profiles = FakeProfiles().apply { getGate = gate }
        val c = controller(feed, profiles)
        c.start(); runCurrent()
        assertFalse("probe" in feed.articleCalls)
        assertFalse(c.state.value.hasArticles)
        gate.complete(Unit); runCurrent()
        assertEquals(1, feed.articleCalls.count { it == "probe" })
        assertTrue(c.state.value.hasArticles)
        c.close()
    }

    @Test
    fun `the list loads when the tab opens, newest published first, once`() = runTest {
        val feed = FakeFeed().apply {
            storedArticles = mapOf(bob to listOf(article("old", 500, published = 100), article("new", 50, published = 900)))
            fetchedArticles = mapOf(bob to listOf(article("mid", 400)))
        }
        val c = controller(feed)
        c.start(); runCurrent()
        assertEquals(0, feed.articleCalls.count { it.startsWith("list") })
        c.selectTab(ProfileSection.ARTICLES); runCurrent()
        assertEquals(listOf("new", "mid", "old"), c.state.value.articles!!.map { it.event.identifier })
        c.selectTab(ProfileSection.POSTS); c.selectTab(ProfileSection.ARTICLES); runCurrent()
        assertEquals(listOf("list:false", "list:true"), feed.articleCalls.filter { it.startsWith("list") })
        c.close()
    }

    @Test
    fun `the author's write relays are asked once when the own relays found nothing`() = runTest {
        val feed = FakeFeed()
        val relays = FakeAuthorRelays(mapOf(bob to listOf("wss://bob.example"))).apply {
            onArticles = { feed.storedArticles = mapOf(bob to listOf(article("a", 10))); true }
        }
        val c = controller(feed, relays = relays, authorRelaysOn = true)
        c.start(); runCurrent()
        assertEquals(listOf(listOf("wss://bob.example") to 1), relays.articleFetches)
        assertTrue(c.state.value.hasArticles)
        c.selectTab(ProfileSection.ARTICLES); runCurrent()
        assertEquals(2, relays.articleFetches.size)
        assertEquals(listOf("a"), c.state.value.articles!!.map { it.event.identifier })
        c.close()
    }

    @Test
    fun `no author relays are asked with the setting off`() = runTest {
        val feed = FakeFeed()
        val relays = FakeAuthorRelays(mapOf(bob to listOf("wss://bob.example")))
        val c = controller(feed, relays = relays)
        c.start(); runCurrent()
        c.selectTab(ProfileSection.ARTICLES); runCurrent()
        assertEquals(emptyList<Pair<List<String>, Int>>(), relays.articleFetches)
        c.close()
    }
}
