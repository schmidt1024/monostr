package com.monostr.app.translate

import com.monostr.app.ui.article.MdBlock
import com.monostr.app.ui.article.MdText
import com.monostr.nostr.model.AddressableEvent
import com.monostr.nostr.model.Article
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleTranslatorTest {
    private class FakeSettings(mode: TranslationMode = TranslationMode.MONOSTR) : TranslationSettingsStore {
        override val mode = MutableStateFlow(mode)
        override val customUrl = MutableStateFlow("")
        override val consentGiven = MutableStateFlow(true)
        override suspend fun setMode(m: TranslationMode) { mode.value = m }
        override suspend fun setCustomUrl(u: String): Boolean { customUrl.value = u; return true }
        override suspend fun setConsentGiven() { consentGiven.value = true }
    }

    private val article = Article(
        AddressableEvent("e".repeat(64), Article.KIND, "a".repeat(64), "d", 1, emptyList(), "Bonjour"), "Le titre", null, null, 1, null,
    )
    private val blocks = (1..12).map { MdBlock.Paragraph(MdText("paragraphe $it", emptyList())) }

    @Test
    fun `a finished run never stays on loading, a failed one offers retry`() = runTest {
        val settings = FakeSettings()
        var fail = true
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val t = ArticleTranslator(
            settings, { _, _ -> "fr" },
            { _, texts, _, _, _ -> if (fail) TranslateResult.Failed else TranslateResult.Ok(texts.map { "DE $it" }) },
            { "de" }, scope, StandardTestDispatcher(testScheduler),
        )
        t.prepare(article, blocks); advanceUntilIdle()
        assertEquals(TranslationState.Offer("fr"), t.state.value.row)
        t.translate(); advanceUntilIdle()
        assertEquals(TranslationState.Failed, t.state.value.row)
        fail = false
        t.translate(); advanceUntilIdle()
        val s = t.state.value
        assertTrue(s.row is TranslationState.Shown, "row $s")
        assertEquals("DE Le titre", s.title)
        assertEquals("DE paragraphe 12", (s.blocks!!.last() as MdBlock.Paragraph).text.text)
        t.showOriginal(); advanceUntilIdle()
        assertTrue(t.state.value.row is TranslationState.Original)
        t.showTranslation(); advanceUntilIdle()
        assertTrue(t.state.value.row is TranslationState.Shown)
        scope.cancel()
    }

    @Test
    fun `a switched app language drops the translation for the old one`() = runTest {
        val settings = FakeSettings()
        var to = "en"
        val targets = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val t = ArticleTranslator(
            settings, { _, _ -> "fr" },
            { _, texts, _, target, _ -> targets += target; TranslateResult.Ok(texts.map { "$target $it" }) },
            { to }, scope, StandardTestDispatcher(testScheduler),
        )
        t.prepare(article, blocks); advanceUntilIdle()
        t.translate(); advanceUntilIdle()
        assertEquals("en Le titre", t.state.value.title)
        to = "de"
        t.checkTarget(); advanceUntilIdle()
        assertEquals(ArticleTranslationUi(TranslationState.Offer("fr")), t.state.value, "the English translation is gone")
        targets.clear()
        t.translate(); advanceUntilIdle()
        assertEquals("de Le titre", t.state.value.title)
        assertTrue(targets.isNotEmpty() && targets.all { it == "de" })
        to = "fr"
        t.checkTarget(); advanceUntilIdle()
        assertEquals(ArticleTranslationUi(), t.state.value, "the article is in the reader's language now: no button")
        scope.cancel()
    }

    @Test
    fun packMissing_setsRow() = runTest {
        val backends = mutableListOf<TranslateBackend>()
        var answer: (List<String>) -> TranslateResult = { TranslateResult.PackMissing }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val t = ArticleTranslator(
            FakeSettings(TranslationMode.DEVICE), { _, _ -> "fr" },
            { b, texts, _, _, _ -> backends += b; answer(texts) },
            { "de" }, scope, StandardTestDispatcher(testScheduler),
        )
        t.prepare(article, blocks); advanceUntilIdle()
        assertEquals(TranslationState.Offer("fr"), t.state.value.row)
        t.translate(); advanceUntilIdle()
        assertEquals(TranslationState.PackMissing, t.state.value.row)
        assertEquals(1, backends.size, "the run stopped")
        answer = { TranslateResult.AppMissing }
        t.translate(); advanceUntilIdle()
        assertEquals(TranslationState.AppMissing, t.state.value.row)
        answer = { texts -> TranslateResult.Ok(texts.map { "DE $it" }) }
        t.translate(); advanceUntilIdle()
        assertEquals(TranslationState.Shown("", "Offline Translator"), t.state.value.row)
        assertEquals("DE paragraphe 12", (t.state.value.blocks!!.last() as MdBlock.Paragraph).text.text)
        assertTrue(backends.all { it == DeviceBackend }, "never a server")
        scope.cancel()
    }

    @Test
    fun `a retry after switching to device mode never resumes on the server`() = runTest {
        val settings = FakeSettings()
        val backends = mutableListOf<TranslateBackend>()
        var calls = 0
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val t = ArticleTranslator(
            settings, { _, _ -> "fr" },
            { b, texts, _, _, _ -> backends += b; if (++calls == 3) TranslateResult.RateLimited else TranslateResult.Ok(texts.map { "DE $it" }) },
            { "de" }, scope, StandardTestDispatcher(testScheduler),
        )
        t.prepare(article, blocks); advanceUntilIdle()
        t.translate(); advanceUntilIdle()
        assertTrue(t.state.value.canRetry, "stopped part-way on the server")
        assertTrue(backends.all { it == TranslateServer.MONOSTR })
        val before = backends.size
        settings.mode.value = TranslationMode.DEVICE; advanceUntilIdle()
        t.translate(); advanceUntilIdle()
        val after = backends.drop(before)
        assertTrue(after.isNotEmpty() && after.all { it == DeviceBackend }, "after the switch: $after")
        assertEquals(TranslationState.Shown("", "Offline Translator"), t.state.value.row)
        assertEquals("DE paragraphe 12", (t.state.value.blocks!!.last() as MdBlock.Paragraph).text.text)
        scope.cancel()
    }

    @Test
    fun `switching to device mode cancels a run still sending to the server`() = runTest {
        val settings = FakeSettings()
        val backends = mutableListOf<TranslateBackend>()
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val t = ArticleTranslator(
            settings, { _, _ -> "fr" },
            { b, texts, _, _, _ -> backends += b; if (backends.size == 2) hold.await(); TranslateResult.Ok(texts.map { "DE $it" }) },
            { "de" }, scope, StandardTestDispatcher(testScheduler),
        )
        t.prepare(article, blocks); advanceUntilIdle()
        t.translate(); advanceUntilIdle()
        assertEquals(TranslationState.Loading, t.state.value.row, "mid-flight on call 2")
        assertEquals(listOf<TranslateBackend>(TranslateServer.MONOSTR, TranslateServer.MONOSTR), backends)
        settings.mode.value = TranslationMode.DEVICE; advanceUntilIdle()
        assertTrue(hold.isActive, "the held call was never answered")
        assertEquals(2, backends.size, "no further server call")
        assertEquals(ArticleTranslationUi(TranslationState.Offer("fr")), t.state.value)
        hold.complete(Unit); advanceUntilIdle()
        assertEquals(2, backends.size, "the cancelled run sends nothing more")
        assertEquals(ArticleTranslationUi(TranslationState.Offer("fr")), t.state.value)
        t.translate(); advanceUntilIdle()
        assertTrue(backends.drop(2).all { it == DeviceBackend })
        assertEquals(TranslationState.Shown("", "Offline Translator"), t.state.value.row)
        scope.cancel()
    }

    /** The stored mode reaches the reader late: its first subscriber (the reader's collector) sees MONOSTR for [lagMs], every later read DEVICE. */
    private class LaggingSettings(private val lagMs: Long) : TranslationSettingsStore {
        private var reads = 0
        override val mode: Flow<TranslationMode> = flow {
            if (reads++ == 0) { emit(TranslationMode.MONOSTR); delay(lagMs); emit(TranslationMode.DEVICE); awaitCancellation() } else emit(TranslationMode.DEVICE)
        }
        override val customUrl = MutableStateFlow("")
        override val consentGiven = MutableStateFlow(true)
        override suspend fun setMode(m: TranslationMode) = Unit
        override suspend fun setCustomUrl(u: String) = true
        override suspend fun setConsentGiven() = Unit
    }

    private fun TestScope.lagging(lagMs: Long, used: MutableList<TranslateBackend>): Pair<ArticleTranslator, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val t = ArticleTranslator(
            LaggingSettings(lagMs), { _, _ -> "fr" },
            { b, texts, _, _, _ -> used += b; TranslateResult.Ok(texts.map { "DE $it" }) },
            { "de" }, scope, StandardTestDispatcher(testScheduler),
        )
        t.prepare(article, blocks)
        return t to scope
    }

    @Test
    fun `translateWhenApplied waits for the stored device backend, nothing goes to the old server`() = runTest {
        val used = mutableListOf<TranslateBackend>()
        val (t, scope) = lagging(1_000, used)
        runCurrent()
        t.translateWhenApplied()
        advanceTimeBy(500); runCurrent()
        assertTrue(used.isEmpty(), "still on MONOSTR in the reader: nothing sent")
        advanceUntilIdle()
        assertTrue(used.isNotEmpty())
        assertTrue(used.all { it == DeviceBackend }, "only the device: $used")
        assertTrue(t.state.value.row is TranslationState.Shown)
        scope.cancel()
    }

    @Test
    fun `translateWhenApplied drops the run when the backend never arrives in time`() = runTest {
        val used = mutableListOf<TranslateBackend>()
        val (t, scope) = lagging(ArticleTranslator.APPLY_TIMEOUT_MS + 1_000, used)
        runCurrent()
        t.translateWhenApplied()
        advanceUntilIdle()
        assertTrue(used.isEmpty(), "dropped, never sent: $used")
        assertEquals(TranslationState.Offer("fr"), t.state.value.row)
        scope.cancel()
    }
}
