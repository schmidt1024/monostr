package com.monostr.app.translate

import androidx.annotation.WorkerThread
import com.optimaize.langdetect.LanguageDetectorBuilder
import com.optimaize.langdetect.ngram.NgramExtractors
import com.optimaize.langdetect.profiles.LanguageProfile
import com.optimaize.langdetect.profiles.LanguageProfileReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import com.monostr.app.ui.common.NoteLinks
import com.monostr.app.ui.common.NoteSpan

/**
 * The language of a note or article (spec 2026-11-03 «Language detection»): its NIP-32 label when
 * that is one of the app languages, else detected on the device among those eight only. Nothing
 * leaves the phone. Thread-safe; the models load on the first [detect], so never call it on the
 * main thread: callers run it on [LANE]. A model that fails to load gives null from then on, without a second try.
 */
class LanguageDetector internal constructor(private val profiles: () -> List<LanguageProfile>) {
    constructor() : this({ LanguageProfileReader().read(Languages.SUPPORTED) })

    /** The models failed to load once: no detection for the rest of the process (no retry per note card). */
    @Volatile private var broken = false

    // optimaize language-detector (Apache 2.0), the n-gram profiles of the eight languages only (the APK leaves the rest
    // out), fixed seed so a note always gets the same answer. Lingua (the first pick) needed 18.9 ms per note on the Pixel
    // and ~7x the time of this one on the emulator, see the commit.
    private val detector by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LanguageDetectorBuilder.create(NgramExtractors.standard())
            .withProfiles(profiles())
            .minimalConfidence(MIN_CONFIDENCE)
            .seed(SEED)
            .build()
    }

    /**
     * The ISO 639-1 code of [content]: [label] when it is one of [Languages.SUPPORTED], else the
     * detected one, or null when the text is too short (fewer than [MIN_LETTERS] letters once links,
     * mentions, hashtags, cashtags, invoices and emoji are gone) or the detection is not confident.
     */
    @WorkerThread
    fun detect(content: String, label: String?): String? {
        label?.lowercase()?.takeIf { it in Languages.SUPPORTED }?.let { return it }
        val text = prose(content)
        if (letters(text) < MIN_LETTERS || broken) return null
        val model = try {
            detector
        } catch (e: Exception) { // IOException from the profiles, IllegalStateException/IllegalArgumentException from the builder
            broken = true
            return null
        } catch (e: LinkageError) { // a class R8 or the device lacks
            broken = true
            return null
        }
        val found = model.detect(text)
        return if (found.isPresent) found.get().language.takeIf { it in Languages.SUPPORTED } else null
    }

    companion object {
        /**
         * The one thread every detection runs on (final review I2): never a thread of [kotlinx.coroutines.Dispatchers.Default],
         * which the feed's decoding shares; while the models load (~120 ms on the emulator) the other detections wait in
         * line on this lane instead of each blocking a pool thread on the lazy's lock.
         */
        val LANE: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "monostr-langdetect").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }.asCoroutineDispatcher()

        const val MIN_LETTERS = 20
        // below this probability of the top language: no answer, so no translate button
        private const val MIN_CONFIDENCE = 0.9
        private const val SEED = 41L

        // emoji, symbols and their joiners/selectors: no language in them
        private val SYMBOLS = Regex("[\\p{So}\\p{Sk}\\p{Sm}\\p{Cs}\\p{Co}\\u200D\\uFE0F]+") // regex

        /** Only the plain text runs of [content], the way the note renders them, without emoji. */
        internal fun prose(content: String): String =
            NoteLinks.annotate(content).filterIsInstance<NoteSpan.Plain>()
                .joinToString(" ") { it.text }
                .replace(SYMBOLS, " ")

        private fun letters(text: String): Int {
            var n = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (Character.isLetter(cp)) n++
                i += Character.charCount(cp)
            }
            return n
        }
    }
}
