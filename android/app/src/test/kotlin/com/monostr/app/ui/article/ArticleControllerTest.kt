package com.monostr.app.ui.article

import com.monostr.app.ui.FakeCounts
import com.monostr.app.ui.FakePendingTips
import com.monostr.app.ui.FakeProfiles
import com.monostr.app.ui.FakePublish
import com.monostr.app.ui.FakeTips
import com.monostr.app.ui.tips.TipDecorations
import com.monostr.app.ui.withPerfLines
import com.monostr.nostr.model.AddressRef
import com.monostr.nostr.model.AddressableEvent
import com.monostr.nostr.repo.AddressResult
import com.monostr.nostr.repo.AddressableRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleControllerTest {
    private val author = "a".repeat(64)
    private val ref = AddressRef(30023, author, "my-post")
    private val event = AddressableEvent(
        "e".repeat(64), 30023, author, "my-post", 1000,
        listOf(listOf("d", "my-post"), listOf("title", "My post"), listOf("image", "https://example.com/c.png")),
        "# Hello\n\nSome **text** here.\n\n![pic](https://example.com/p.png)",
    )

    private class FakeAddresses(var cached: AddressResult? = null, var loaded: AddressResult = AddressResult.Missing) : AddressableRepository {
        var gets = 0
        val foreignAsked = ArrayList<Boolean>()
        override fun cached(ref: AddressRef, allowForeign: Boolean): AddressResult? { foreignAsked += allowForeign; return cached }
        override suspend fun get(ref: AddressRef, allowForeign: Boolean): AddressResult { gets++; foreignAsked += allowForeign; return loaded }
    }

    /** Counts how often the parse is dispatched, then runs it on the caller's thread. */
    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatched = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) { dispatched++; block.run() }
    }

    private fun TestScope.eager(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.controller(
        addresses: AddressableRepository, parse: CoroutineContext, counts: FakeCounts = FakeCounts(), cache: ArticleBlockCache = ArticleBlockCache(),
        muted: Set<String> = emptySet(), ref: AddressRef = this@ArticleControllerTest.ref, allowForeign: Boolean = true, clock: () -> Long = { 0L },
    ): ArticleController {
        val scope = eager()
        val profiles = FakeProfiles()
        val publish = FakePublish()
        return ArticleController(ref, addresses, profiles, publish, TipDecorations(FakeTips(), FakePendingTips(), profiles, publish, scope), counts, scope, muted = MutableStateFlow(muted), parseContext = parse, cache = cache, clockMs = clock, allowForeign = allowForeign)
    }

    @Test
    fun `a cached event shows without a load, parsed off the caller, with counts requested by coordinate`() = runTest {
        val addresses = FakeAddresses(cached = AddressResult.Found(event))
        val d = CountingDispatcher()
        val counts = FakeCounts()
        val c = controller(addresses, d, counts)
        c.start()
        advanceUntilIdle()
        val shown = assertInstanceOf(ArticleUi.Shown::class.java, c.state.value)
        assertEquals(0, addresses.gets)
        assertEquals(1, d.dispatched)
        assertEquals("My post", shown.article.title)
        assertTrue(shown.blocks.first() is MdBlock.Heading)
        assertEquals(listOf("https://example.com/c.png", "https://example.com/p.png"), shown.pictures)
        assertEquals(false, shown.note.bookmarkEnabled)
        assertEquals(listOf(event.id to "30023:$author:my-post"), counts.articlesRequested)
    }

    @Test
    fun `an uncached event is loaded once and a reopened article is not parsed again`() = runTest {
        val addresses = FakeAddresses(loaded = AddressResult.Found(event))
        val d = CountingDispatcher()
        val cache = ArticleBlockCache()
        val first = controller(addresses, d, cache = cache)
        assertEquals(ArticleUi.Loading, first.state.value)
        first.start()
        advanceUntilIdle()
        assertInstanceOf(ArticleUi.Shown::class.java, first.state.value)
        assertEquals(1, addresses.gets)
        val second = controller(addresses, d, cache = cache)
        second.start()
        advanceUntilIdle()
        assertInstanceOf(ArticleUi.Shown::class.java, second.state.value)
        assertEquals(1, d.dispatched)
    }

    @Test
    fun `missing and deleted are their own states`() = runTest {
        val missing = controller(FakeAddresses(loaded = AddressResult.Missing), CountingDispatcher())
        missing.start(); advanceUntilIdle()
        assertEquals(ArticleUi.Missing, missing.state.value)
        val deleted = controller(FakeAddresses(loaded = AddressResult.Deleted), CountingDispatcher())
        deleted.start(); advanceUntilIdle()
        assertEquals(ArticleUi.Deleted, deleted.state.value)
    }

    @Test
    fun `a draft or another kind is not shown`() = runTest {
        val draft = AddressableEvent("d".repeat(64), 30024, author, "my-post", 1, emptyList(), "x")
        val c = controller(FakeAddresses(loaded = AddressResult.Found(draft)), CountingDispatcher())
        c.start(); advanceUntilIdle()
        assertEquals(ArticleUi.Missing, c.state.value)
        val live = controller(FakeAddresses(), CountingDispatcher(), ref = AddressRef(30311, author, "x"))
        live.start()
        assertEquals(ArticleUi.Missing, live.state.value)
    }

    @Test
    fun `a muted author gives the Muted state`() = runTest {
        val muted = controller(FakeAddresses(loaded = AddressResult.Found(event)), CountingDispatcher(), muted = setOf(author))
        muted.start(); advanceUntilIdle()
        assertEquals(ArticleUi.Muted, muted.state.value)
    }

    @Test
    fun `allowForeign false (a link from a direct message) reaches the repository`() = runTest {
        val addresses = FakeAddresses(loaded = AddressResult.Found(event))
        val c = controller(addresses, CountingDispatcher(), allowForeign = false)
        c.start(); advanceUntilIdle()
        assertEquals(listOf(false, false), addresses.foreignAsked)
        val open = FakeAddresses(loaded = AddressResult.Found(event))
        controller(open, CountingDispatcher()).start()
        assertEquals(listOf(true, true), open.foreignAsked)
    }

    @Test
    fun `the shown mark counts from the open time passed to start`() = runTest {
        var now = 1_000L
        val c = controller(FakeAddresses(cached = AddressResult.Found(event)), CountingDispatcher(), clock = { now })
        val lines = withPerfLines { now = 1_250L; c.start(openedAtMs = 1_000L); advanceUntilIdle() }
        assertTrue(lines.any { it.endsWith("article shown 250ms") }, lines.toString())
    }

    @Test
    fun `the block cache evicts the least recently used entry at the 21st put`() {
        val cache = ArticleBlockCache(20)
        val p = ArticleBlockCache.Parsed(emptyList(), 1)
        repeat(20) { cache.put("id$it", p) }
        cache.get("id0") // id0 is now the most recently used, id1 the oldest
        cache.put("id20", p)
        assertEquals(null, cache.get("id1"))
        assertTrue(cache.get("id0") != null && cache.get("id2") != null && cache.get("id20") != null)
    }

    @Test
    fun `perf marks for parsed and shown`() = runTest {
        val c = controller(FakeAddresses(cached = AddressResult.Found(event)), CountingDispatcher())
        val lines = withPerfLines { c.start(); advanceUntilIdle() }
        assertTrue(lines.any { Regex("article parsed \\d+ms ${event.content.length}$").containsMatchIn(it) }, lines.toString())
        assertTrue(lines.any { Regex("article shown \\d+ms$").containsMatchIn(it) }, lines.toString())
    }

    @Test
    fun `like marks the article and publishes through the repository`() = runTest {
        val c = controller(FakeAddresses(cached = AddressResult.Found(event)), CountingDispatcher())
        c.start(); advanceUntilIdle()
        val shown = c.state.value as ArticleUi.Shown
        c.like(shown.note.note)
        advanceUntilIdle()
        assertTrue((c.state.value as ArticleUi.Shown).note.liked)
    }
}
