package com.monostr.app.translate

import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.monostr.nostr.StartupGate
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The server translations go to: monostr's, the own one when it has an address, else none (translation off). */
internal fun translateServer(mode: TranslationMode, customUrl: String): TranslateServer? = when (mode) {
    TranslationMode.MONOSTR -> TranslateServer.MONOSTR
    TranslationMode.CUSTOM -> customUrl.takeIf { it.isNotBlank() }?.let { TranslateServer(it) }
    TranslationMode.OFF -> null
}

/** What the note card shows about translating one note. */
sealed interface TranslationState {
    /** No button: translation off, language unknown or unsupported, or already the reader's language. */
    data object None : TranslationState
    /** «Translate» button; [source] is the detected language. */
    data class Offer(val source: String) : TranslationState
    data object Loading : TranslationState
    /** The note shows [text] (the translation), «Translated by [host]» / «Show original». */
    data class Shown(val text: String, val host: String) : TranslationState
    /** The note shows its original content; [text] keeps the translation so «Show translation» needs no network. */
    data class Original(val text: String, val host: String) : TranslationState
    /** «Translation failed · Retry»; [Translations.translate] retries. */
    data object Failed : TranslationState
}

/**
 * Translation state per note for the session (spec 2026-11-03). One instance for the app, so a note
 * visible in the feed and in its thread at once shares one state and one request. Nothing is
 * persisted; translations live as long as the process (bounded by [MAX_NOTES]).
 *
 * The mode is applied on top of the stored state: with translation off every note reads [TranslationState.None],
 * and turning it back on restores what was there. Detection only runs while a server is configured, one note at a time
 * on [LanguageDetector.LANE], and not before the session's [StartupGate] opens ([useStartupGate]): the feed's first page
 * comes first (final review I2).
 */
@Singleton
class Translations internal constructor(
    settings: TranslationSettingsStore,
    private val detect: (content: String, label: String?) -> String?,
    private val translator: suspend (TranslateServer, List<String>, String, String) -> TranslateResult,
    private val target: () -> String,
    private val scope: CoroutineScope,
    private val detectDispatcher: CoroutineDispatcher,
    private val protect: (String) -> Shielded? = TokenShield::protect,
) {
    @Inject constructor(settings: TranslationSettingsStore, detector: LanguageDetector, client: TranslateClient) : this(
        settings,
        detector::detect,
        client::translate,
        { Languages.target(systemLocales()) },
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        LanguageDetector.LANE,
    )

    private class Note {
        var content: String? = null
        var label: String? = null
        var detectStarted = false
        /** Detection is over: [lang] holds its answer. */
        var detected = false
        /** The detected language when it is one of [Languages.SUPPORTED]; null until detected or when unknown. */
        var lang: String? = null
        /** [lang] when it differs from the current target; null until detected or when there is nothing to offer. */
        var source: String? = null
        /** The state regardless of mode; [out] shows it or None. */
        var raw: TranslationState = TranslationState.None
        val out = MutableStateFlow<TranslationState>(TranslationState.None)
        val shown = HashMap<String, TranslationState.Shown>() // by target language
        var inFlight: Deferred<Unit>? = null
    }

    /** Guards [notes], every [Note] field and [server]. Never held across a suspension. */
    private val lock = Any()
    private val notes = object : LinkedHashMap<String, Note>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Note>?) = size > MAX_NOTES
    }
    /** Null while translation is off (OFF, CUSTOM without address) or the settings are not loaded yet. */
    private var server: TranslateServer? = null
    /** The target language the states were last set for; null before the first read. */
    private var current: String? = null

    /** The logged-in session's startup gate; open (no wait) until a session sets one. */
    private val gate = MutableStateFlow(StartupGate.OPEN)

    /** Detection waits for [g] (the session's gate: the home feed's first fresh page, or its timeout). */
    fun useStartupGate(g: StartupGate) { gate.value = g }

    /** Returns once the current gate is open; a gate whose session ended waits for the next session's. */
    private suspend fun awaitGate() {
        while (true) {
            val g = gate.value
            try {
                g.await()
                return
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive() // our own cancellation: leave
                gate.first { it !== g }
            }
        }
    }

    init {
        scope.launch {
            combine(settings.mode, settings.customUrl, ::translateServer).distinctUntilChanged().collect { s ->
                val pending = synchronized(lock) {
                    server = s
                    notes.values.forEach { publish(it) }
                    if (s == null) emptyList() else notes.values.filter { it.content != null && !it.detectStarted }
                }
                pending.forEach { startDetection(it) }
            }
        }
    }

    fun state(noteId: String): StateFlow<TranslationState> = synchronized(lock) { syncTarget(); note(noteId).out.asStateFlow() }

    /** Records the note and detects its language in the background, once per id; never blocks the caller. */
    fun prepare(noteId: String, content: String, label: String?) {
        val n = synchronized(lock) {
            val n = note(noteId)
            if (n.content != null) return
            n.content = content
            n.label = label
            if (server == null) return // detected when translation is turned on
            n
        }
        startDetection(n)
    }

    /** Translates the note (or shows the session's cached translation). Concurrent calls share one request. */
    suspend fun translate(noteId: String) {
        val job = synchronized(lock) {
            val n = notes[noteId] ?: return
            val s = server ?: return
            val to = syncTarget()
            val source = n.source ?: return
            val content = n.content ?: return
            if (source == to) return
            n.shown[to]?.let { set(n, it); return }
            n.inFlight ?: scope.async(start = CoroutineStart.LAZY) { run(n, s, content, source, to) }.also {
                n.inFlight = it
                set(n, TranslationState.Loading)
            }
        }
        job.start()
        job.await() // a cancelled caller leaves; the request itself belongs to [scope]
    }

    fun showOriginal(noteId: String) {
        synchronized(lock) {
            syncTarget()
            val n = notes[noteId] ?: return
            (n.raw as? TranslationState.Shown)?.let { set(n, TranslationState.Original(it.text, it.host)) }
        }
    }

    fun showTranslation(noteId: String) {
        synchronized(lock) {
            syncTarget()
            val n = notes[noteId] ?: return
            (n.raw as? TranslationState.Original)?.let { set(n, TranslationState.Shown(it.text, it.host)) }
        }
    }

    private suspend fun run(n: Note, s: TranslateServer, content: String, source: String, to: String) {
        val result = try {
            translateOnce(s, content, source, to)
        } catch (e: CancellationException) {
            synchronized(lock) { n.inFlight = null; if (n.raw == TranslationState.Loading) set(n, TranslationState.Failed) }
            throw e
        } catch (e: Exception) {
            TranslationState.Failed
        }
        synchronized(lock) {
            n.inFlight = null
            if (result is TranslationState.Shown) n.shown[to] = result
            val now = current ?: to
            if (now == to) set(n, result) else retarget(n, now, force = true) // the language was switched meanwhile: settle
        }
    }

    private suspend fun translateOnce(s: TranslateServer, content: String, source: String, to: String): TranslationState {
        // never send unshielded content: no shield, no request
        val shielded = protect(content) ?: return TranslationState.Failed
        return when (val r = translator(s, listOf(shielded.html), source, to)) {
            is TranslateResult.Ok -> r.texts.singleOrNull()?.let { TokenShield.restore(it, shielded) }
                ?.let { TranslationState.Shown(it, s.host) } ?: TranslationState.Failed
            TranslateResult.RateLimited, TranslateResult.Failed -> TranslationState.Failed
        }
    }

    private fun startDetection(n: Note) {
        val (content, label) = synchronized(lock) {
            if (n.detectStarted) return
            n.detectStarted = true
            n.content!! to n.label
        }
        scope.launch(detectDispatcher) {
            awaitGate()
            val found = runCatching { detect(content, label) }.getOrNull() // a failing model means no button
            synchronized(lock) {
                val to = syncTarget()
                n.lang = found?.takeIf { it in Languages.SUPPORTED }
                n.detected = true
                n.source = n.lang?.takeIf { it != to }
                if (n.raw == TranslationState.None) set(n, n.source?.let { TranslationState.Offer(it) } ?: TranslationState.None)
            }
        }
    }

    /**
     * Caller holds [lock]. The reader's language now; when it changed since the last read (the app's language was
     * switched), every note follows it: a translation shown for the old language becomes «Translate» again, or nothing
     * when the note is in the new one. Translations stay cached per (note, target).
     */
    private fun syncTarget(): String {
        val to = target()
        val was = current
        if (to != was) {
            current = to
            if (was != null) notes.values.forEach { retarget(it, to) }
        }
        return to
    }

    /**
     * Caller holds [lock]. A request in flight keeps its «Loading» unless [force]: its answer lands for the old target
     * and is retargeted then, with [force].
     */
    private fun retarget(n: Note, to: String, force: Boolean = false) {
        if (!n.detected) return
        n.source = n.lang?.takeIf { it != to }
        if (force || n.raw != TranslationState.Loading) set(n, n.source?.let { TranslationState.Offer(it) } ?: TranslationState.None)
    }

    /** Caller holds [lock]. */
    private fun note(id: String): Note = notes.getOrPut(id) { Note() }

    /** Caller holds [lock]. */
    private fun set(n: Note, s: TranslationState) {
        n.raw = s
        publish(n)
    }

    /** Caller holds [lock]. */
    private fun publish(n: Note) {
        n.out.value = if (server == null) TranslationState.None else n.raw
    }

    companion object {
        const val MAX_NOTES = 500

        internal fun systemLocales(): List<Locale> {
            val l = LocaleListCompat.getAdjustedDefault()
            return (0 until l.size()).mapNotNull { l[it] }
        }
    }
}
