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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleTranslatorTest {
    private class FakeSettings : TranslationSettingsStore {
        override val mode = MutableStateFlow(TranslationMode.MONOSTR)
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
}
