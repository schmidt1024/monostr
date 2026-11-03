package com.monostr.app.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationsTest {
    private class FakeSettings(mode: TranslationMode = TranslationMode.MONOSTR, url: String = "") : TranslationSettingsStore {
        override val mode = MutableStateFlow(mode)
        override val customUrl = MutableStateFlow(url)
        override val consentGiven = MutableStateFlow(false)
        override suspend fun setMode(m: TranslationMode) { mode.value = m }
        override suspend fun setCustomUrl(u: String): Boolean { customUrl.value = u; return true }
        override suspend fun setConsentGiven() { consentGiven.value = true }
    }

    private class FakeClient {
        val calls = mutableListOf<Pair<TranslateServer, List<String>>>()
        val targets = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var answer: (List<String>) -> TranslateResult = { texts -> TranslateResult.Ok(texts.map { it.replace("Bonjour", "Hallo") }) }
        suspend fun translate(server: TranslateServer, texts: List<String>, source: String, target: String): TranslateResult {
            calls += server to texts
            targets += target
            gate?.await()
            return answer(texts)
        }
    }

    private val french = "Bonjour tout le monde, https://example.org/a?b=1&c=2 #nostr"
    private val german = "Hallo zusammen, wie geht es euch heute allen?"

    private var detectCalls = 0
    private var detectThrows = false
    private fun detect(content: String, label: String?): String? {
        detectCalls++
        if (detectThrows) throw IOException("profiles")
        return when (content) { french -> "fr"; german -> "de"; else -> null }
    }

    // not backgroundScope: advanceUntilIdle skips background work, and the detection must run under it
    private fun TestScope.owned() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    private fun TestScope.translations(settings: TranslationSettingsStore, client: FakeClient, target: () -> String = { "de" }) =
        Translations(settings, ::detect, client::translate, target, owned(), StandardTestDispatcher(testScheduler))
            .also { runCurrent() }

    // Pixel check 2026-11-03: after switching the app language a note kept its translation for the old one

    @Test
    fun `a switched app language turns a shown translation back into an offer for the new target`() = runTest {
        var to = "en"
        val c = FakeClient()
        c.answer = { texts -> TranslateResult.Ok(texts.map { "[${c.targets.last()}] $it" }) }
        val t = translations(FakeSettings(), c) { to }
        t.prepare("a", french, null); advanceUntilIdle()
        t.translate("a")
        assertTrue((t.state("a").value as TranslationState.Shown).text.startsWith("[en]"))
        to = "de"
        assertEquals(TranslationState.Offer("fr"), t.state("a").value, "not Shown(en)")
        t.translate("a")
        assertEquals(listOf("en", "de"), c.targets)
        assertTrue((t.state("a").value as TranslationState.Shown).text.startsWith("[de]"))
        // back to English: offered again, and translating uses the session's cached English text
        to = "en"
        assertEquals(TranslationState.Offer("fr"), t.state("a").value)
        t.translate("a")
        assertEquals(2, c.calls.size, "cached per (note, target)")
        assertTrue((t.state("a").value as TranslationState.Shown).text.startsWith("[en]"))
    }

    @Test
    fun `a switch while the request is in flight settles on the new target's offer`() = runTest {
        var to = "en"
        val c = FakeClient().apply { gate = CompletableDeferred() }
        val t = translations(FakeSettings(), c) { to }
        t.prepare("a", french, null); advanceUntilIdle()
        val job = launch { t.translate("a") }
        runCurrent()
        assertEquals(TranslationState.Loading, t.state("a").value)
        to = "de"
        assertEquals(TranslationState.Loading, t.state("a").value, "still waiting for the answer")
        c.gate!!.complete(Unit); job.join(); advanceUntilIdle()
        assertEquals(TranslationState.Offer("fr"), t.state("a").value, "not stuck on Loading")
    }

    @Test
    fun `a note in the new app language loses its button and original view`() = runTest {
        var to = "en"
        val c = FakeClient()
        val t = translations(FakeSettings(), c) { to }
        t.prepare("a", german, null); advanceUntilIdle()
        assertEquals(TranslationState.Offer("de"), t.state("a").value)
        t.translate("a"); t.showOriginal("a")
        assertTrue(t.state("a").value is TranslationState.Original)
        to = "de"
        assertEquals(TranslationState.None, t.state("a").value)
        t.translate("a")
        assertEquals(1, c.calls.size, "nothing to translate in the reader's language")
    }

    @Test
    fun `mode off gives None and detects nothing`() = runTest {
        val s = FakeSettings(TranslationMode.OFF)
        val t = translations(s, FakeClient())
        t.prepare("a", french, null); advanceUntilIdle()
        assertEquals(TranslationState.None, t.state("a").value)
        assertEquals(0, detectCalls)
        s.mode.value = TranslationMode.MONOSTR; advanceUntilIdle()
        assertEquals(TranslationState.Offer("fr"), t.state("a").value)
    }

    @Test
    fun `custom mode with blank url behaves like off`() = runTest {
        val s = FakeSettings(TranslationMode.CUSTOM, "")
        val c = FakeClient()
        val t = translations(s, c)
        t.prepare("a", french, null); advanceUntilIdle()
        assertEquals(TranslationState.None, t.state("a").value)
        s.customUrl.value = "https://tr.example.org"; advanceUntilIdle()
        assertEquals(TranslationState.Offer("fr"), t.state("a").value)
        t.translate("a")
        assertEquals("tr.example.org", (t.state("a").value as TranslationState.Shown).host)
        assertEquals(TranslateServer("https://tr.example.org"), c.calls.single().first)
    }

    @Test
    fun `same language gives None`() = runTest {
        val t = translations(FakeSettings(), FakeClient())
        t.prepare("a", german, null); advanceUntilIdle()
        assertEquals(TranslationState.None, t.state("a").value)
    }

    @Test
    fun `undetected or throwing detector gives None`() = runTest {
        val t = translations(FakeSettings(), FakeClient())
        t.prepare("a", "short", null); advanceUntilIdle()
        assertEquals(TranslationState.None, t.state("a").value)
        detectThrows = true
        t.prepare("b", french, null); advanceUntilIdle()
        assertEquals(TranslationState.None, t.state("b").value)
    }

    @Test
    fun `prepare detects once per id and does not block`() = runTest {
        val t = translations(FakeSettings(), FakeClient())
        t.prepare("a", french, null)
        assertEquals(0, detectCalls) // runs on the detect dispatcher, not in the caller
        t.prepare("a", french, null); advanceUntilIdle()
        t.prepare("a", french, null); advanceUntilIdle()
        assertEquals(1, detectCalls)
        assertEquals(TranslationState.Offer("fr"), t.state("a").value)
    }

    @Test
    fun `offer then translate goes Loading then Shown with restored tokens`() = runTest {
        val c = FakeClient().apply { gate = CompletableDeferred() }
        val t = translations(FakeSettings(), c)
        t.prepare("a", french, null); advanceUntilIdle()
        val flow = t.state("a")
        assertEquals(TranslationState.Offer("fr"), flow.value)
        val job = launch { t.translate("a") }
        runCurrent()
        assertEquals(TranslationState.Loading, flow.value)
        assertEquals(1, c.calls.size)
        assert(!c.calls.single().second.single().contains("https://")) { "url must be shielded" }
        c.gate!!.complete(Unit); job.join()
        assertEquals(
            TranslationState.Shown("Hallo tout le monde, https://example.org/a?b=1&c=2 #nostr", "translate.monostr.com"),
            flow.value,
        )
    }

    @Test
    fun `two concurrent translate calls make one client call`() = runTest {
        val c = FakeClient().apply { gate = CompletableDeferred() }
        val t = translations(FakeSettings(), c)
        t.prepare("a", french, null); advanceUntilIdle()
        val one = async { t.translate("a") }
        val two = async { t.translate("a") }
        runCurrent()
        c.gate!!.complete(Unit)
        one.await(); two.await()
        assertEquals(1, c.calls.size)
        assert(t.state("a").value is TranslationState.Shown)
    }

    @Test
    fun `cancelling one caller does not cancel the shared request`() = runTest {
        val c = FakeClient().apply { gate = CompletableDeferred() }
        val t = translations(FakeSettings(), c)
        t.prepare("a", french, null); advanceUntilIdle()
        val one = launch { t.translate("a") }
        val two = async { t.translate("a") }
        runCurrent()
        one.cancel(); runCurrent()
        c.gate!!.complete(Unit)
        two.await()
        assertEquals(1, c.calls.size)
        assert(t.state("a").value is TranslationState.Shown)
    }

    @Test
    fun `showOriginal and showTranslation toggle without a new call`() = runTest {
        val c = FakeClient()
        val t = translations(FakeSettings(), c)
        t.prepare("a", french, null); advanceUntilIdle()
        t.translate("a")
        val shown = t.state("a").value as TranslationState.Shown
        t.showOriginal("a")
        assertEquals(TranslationState.Original(shown.text, shown.host), t.state("a").value)
        t.showTranslation("a")
        assertEquals(shown, t.state("a").value)
        t.showOriginal("a")
        t.translate("a") // cached for the session
        assertEquals(shown, t.state("a").value)
        assertEquals(1, c.calls.size)
    }

    @Test
    fun `client failure gives Failed and retry gives Shown`() = runTest {
        val c = FakeClient().apply { answer = { TranslateResult.Failed } }
        val t = translations(FakeSettings(), c)
        t.prepare("a", french, null); advanceUntilIdle()
        t.translate("a")
        assertEquals(TranslationState.Failed, t.state("a").value)
        c.answer = { TranslateResult.RateLimited }
        t.translate("a")
        assertEquals(TranslationState.Failed, t.state("a").value)
        c.answer = { texts -> TranslateResult.Ok(texts.map { it.replace("Bonjour", "Hallo") }) }
        t.translate("a")
        assert(t.state("a").value is TranslationState.Shown)
        assertEquals(3, c.calls.size)
    }

    @Test
    fun `shield mismatch gives Failed`() = runTest {
        val c = FakeClient().apply { answer = { texts -> TranslateResult.Ok(texts.map { it.replace("⟦0⟧", "⟦7⟧") }) } }
        val t = translations(FakeSettings(), c)
        t.prepare("a", french, null); advanceUntilIdle()
        t.translate("a")
        assertEquals(TranslationState.Failed, t.state("a").value)
    }

    @Test
    fun `unshieldable content fails without a client call`() = runTest {
        val c = FakeClient()
        val t = Translations(
            FakeSettings(), ::detect, c::translate, { "de" }, owned(), StandardTestDispatcher(testScheduler),
            protect = { null },
        ).also { runCurrent() }
        t.prepare("a", french, null); advanceUntilIdle()
        t.translate("a")
        assertEquals(TranslationState.Failed, t.state("a").value)
        assertEquals(0, c.calls.size)
    }

    @Test
    fun `mode off while shown gives None and back on shows the translation again`() = runTest {
        val s = FakeSettings()
        val t = translations(s, FakeClient())
        t.prepare("a", french, null); advanceUntilIdle()
        t.translate("a")
        val shown = t.state("a").value
        assert(shown is TranslationState.Shown)
        s.mode.value = TranslationMode.OFF; runCurrent()
        assertEquals(TranslationState.None, t.state("a").value)
        s.mode.value = TranslationMode.MONOSTR; runCurrent()
        assertEquals(shown, t.state("a").value)
    }

    @Test
    fun `translate while off does nothing`() = runTest {
        val s = FakeSettings()
        val c = FakeClient()
        val t = translations(s, c)
        t.prepare("a", french, null); advanceUntilIdle()
        s.mode.value = TranslationMode.OFF; runCurrent()
        t.translate("a")
        assertEquals(0, c.calls.size)
        assertEquals(TranslationState.None, t.state("a").value)
    }

    // I2: no detection before the session's startup gate opens

    private class TestGate : com.monostr.nostr.StartupGate {
        val opened = CompletableDeferred<Unit>()
        override val isOpen get() = opened.isCompleted && !opened.isCancelled
        override suspend fun await() = opened.await()
        override fun open(reason: String) { opened.complete(Unit) }
    }

    @Test
    fun `detection waits for the startup gate`() = runTest {
        val t = translations(FakeSettings(), FakeClient())
        val gate = TestGate()
        t.useStartupGate(gate)
        t.prepare("a", french, null); advanceUntilIdle()
        assertEquals(0, detectCalls)
        assertEquals(TranslationState.None, t.state("a").value)
        gate.open("feed fresh page"); advanceUntilIdle()
        assertEquals(1, detectCalls)
        assertEquals(TranslationState.Offer("fr"), t.state("a").value)
    }

    @Test
    fun `a gate whose session ended hands over to the next session's`() = runTest {
        val t = translations(FakeSettings(), FakeClient())
        val old = TestGate()
        t.useStartupGate(old)
        t.prepare("a", french, null); advanceUntilIdle()
        old.opened.cancel() // logout: the old session's scope closed
        advanceUntilIdle()
        assertEquals(0, detectCalls)
        val next = TestGate().apply { open("worker") }
        t.useStartupGate(next); advanceUntilIdle()
        assertEquals(TranslationState.Offer("fr"), t.state("a").value)
    }
}
